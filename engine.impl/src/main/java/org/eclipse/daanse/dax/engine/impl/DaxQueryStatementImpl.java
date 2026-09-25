/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   dbulahov - initial
 */
package org.eclipse.daanse.dax.engine.impl;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.daanse.dax.engine.api.DaxCancelledException;
import org.eclipse.daanse.dax.engine.api.DaxException;
import org.eclipse.daanse.dax.engine.api.DaxExecutionException;
import org.eclipse.daanse.dax.engine.api.DaxQueryStatement;
import org.eclipse.daanse.dax.engine.api.DaxResult;
import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxSyntaxException;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.model.TabularModel;
import org.eclipse.daanse.dax.engine.impl.model.TabularModelBuilder;
import org.eclipse.daanse.dax.engine.impl.plan.Binder;
import org.eclipse.daanse.dax.engine.impl.plan.QueryPlan;
import org.eclipse.daanse.dax.model.api.DaxStatement;
import org.eclipse.daanse.dax.parser.api.DaxParserException;
import org.eclipse.daanse.dax.parser.api.DaxParserProvider;
import org.eclipse.daanse.olap.api.catalog.CatalogReader;
import org.eclipse.daanse.olap.api.connection.Connection;
import org.eclipse.daanse.olap.api.element.Cube;

/**
 * Executes DAX queries on an OLAP connection: parses the query, binds it to
 * the tabular view of the cube as the connection's role sees it, and leaves
 * computing the tables to the {@link DaxResultImpl}.
 */
final class DaxQueryStatementImpl implements DaxQueryStatement {

    private final Connection connection;
    private final Optional<String> cube;
    private final DaxParserProvider parsers;
    private final ScheduledExecutorService timeouts;

    private final Map<String, Object> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private Duration timeout = Duration.ZERO;
    private DaxResultImpl result;
    private volatile QueryControl control;
    private volatile boolean closed;

    DaxQueryStatementImpl(Connection connection, Optional<String> cube, DaxParserProvider parsers,
            ScheduledExecutorService timeouts) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.cube = cube;
        this.parsers = parsers;
        this.timeouts = timeouts;
    }

    @Override
    public void setParameter(String name, Object value) {
        checkOpen();
        Objects.requireNonNull(name, "name");
        if (value != null) {
            DaxType.of(value);
        }
        parameters.put(name, value);
    }

    @Override
    public void setTimeout(Duration timeout) {
        checkOpen();
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("negative timeout: " + timeout);
        }
        this.timeout = timeout;
    }

    @Override
    public DaxResult execute(String daxText) throws DaxException {
        checkOpen();
        Objects.requireNonNull(daxText, "daxText");
        closeResult();
        QueryControl query = new QueryControl();
        control = query;
        query.startTimer(timeouts, timeout);
        try {
            DaxStatement statement = parse(daxText);
            TabularModel model = TabularModelBuilder.build(connection.getCatalogReader(), cube());
            QueryPlan plan = new Binder(model, parameters).bind(statement);
            query.check();
            result = new DaxResultImpl(plan.evaluates(), model.cube(), new MdxRunner(connection, query), query);
            return result;
        } catch (DaxException e) {
            query.close();
            throw e;
        } catch (RuntimeException e) {
            query.close();
            throw new DaxExecutionException("preparing the query failed: " + e.getMessage(), e);
        }
    }

    private DaxStatement parse(String daxText) throws DaxSyntaxException {
        try {
            return parsers.newParser(daxText).parseDaxStatement();
        } catch (DaxParserException e) {
            String message = e.getMessage() == null ? "invalid DAX" : e.getMessage().strip();
            // the position goes into the message, which is all a caller passes on
            String position = e.line() + ":" + e.column();
            if (e.line() != DaxParserException.UNKNOWN_POSITION && !message.contains(position)) {
                message = message + " (line " + e.line() + ", column " + e.column() + ")";
            }
            throw new DaxSyntaxException(message, e);
        }
    }

    private Cube cube() throws DaxSemanticException {
        CatalogReader reader = connection.getCatalogReader();
        List<Cube> cubes = reader.getCubes();
        if (cube.isPresent()) {
            return cubes.stream().filter(c -> c.getName().equalsIgnoreCase(cube.get())).findFirst()
                    .orElseThrow(() -> new DaxSemanticException("the cube '" + cube.get() + "' does not exist"));
        }
        if (cubes.size() != 1) {
            throw new DaxSemanticException("the catalog has " + cubes.size()
                    + " cubes; the cube to query must be named, e.g. by the Cube connection property");
        }
        return cubes.get(0);
    }

    @Override
    public void cancel() {
        QueryControl query = control;
        if (query != null) {
            query.stop(DaxCancelledException.Reason.CANCELLED);
        }
    }

    @Override
    public void close() {
        closed = true;
        closeResult();
    }

    private void closeResult() {
        if (result != null) {
            result.close();
            result = null;
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("the statement is closed");
        }
    }
}
