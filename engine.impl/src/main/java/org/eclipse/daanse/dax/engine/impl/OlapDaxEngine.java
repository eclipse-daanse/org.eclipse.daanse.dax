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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.daanse.dax.engine.api.DaxQueryStatement;
import org.eclipse.daanse.dax.parser.api.DaxParserProvider;
import org.eclipse.daanse.olap.api.connection.Connection;

/**
 * The DAX engine for OLAP connections. It answers a DAX query on the tabular
 * view of a cube of the connection's catalog by translating it into MDX and
 * executing that on the connection.
 * <p>
 * Thread-safe. It holds the thread that times queries out, so it is closed
 * when no longer used.
 * </p>
 */
public final class OlapDaxEngine implements AutoCloseable {

    /** The property naming the cube to query, as the XMLA property {@code Cube} does. */
    public static final String CUBE = "Cube";

    private final DaxParserProvider parsers;
    private final ScheduledExecutorService timeouts;

    public OlapDaxEngine(DaxParserProvider parsers) {
        this.parsers = parsers;
        this.timeouts = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dax-query-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Stops timing queries out; statements created before no longer time out. */
    @Override
    public void close() {
        timeouts.shutdownNow();
    }

    /**
     * Creates a statement that executes DAX queries on the connection, with its
     * catalog, role and locale. It queries the cube the {@value #CUBE} property
     * names, or, without it, the only cube of the connection's catalog; its
     * queries fail if the catalog has several.
     *
     * @param connection the connection; it must stay open while the statement
     *                   is used
     * @param properties properties of the caller's request by name ignoring
     *                   case; those other than {@value #CUBE} are ignored
     * @return a new statement; the caller closes it
     */
    public DaxQueryStatement createStatement(Connection connection, Map<String, String> properties) {
        Optional<String> cube = properties.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(CUBE))
                .map(Map.Entry::getValue).filter(v -> v != null && !v.isBlank()).findFirst();
        return new DaxQueryStatementImpl(connection, cube, parsers, timeouts);
    }
}
