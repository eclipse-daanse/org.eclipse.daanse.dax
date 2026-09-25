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
package org.eclipse.daanse.dax.engine.impl.language;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.daanse.dax.engine.api.DaxException;
import org.eclipse.daanse.dax.engine.api.DaxQueries;
import org.eclipse.daanse.dax.engine.api.DaxQueryStatement;
import org.eclipse.daanse.dax.engine.api.DaxResult;
import org.eclipse.daanse.dax.engine.api.DaxTable;
import org.eclipse.daanse.dax.engine.impl.OlapDaxEngine;
import org.eclipse.daanse.dax.parser.api.DaxParserProvider;
import org.eclipse.daanse.olap.api.connection.Connection;
import org.eclipse.daanse.olap.api.query.StatementLanguage;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 * DAX as a {@link StatementLanguage} of OLAP connections: the OLAP side hands
 * it the statements it accepts and renders the result sets it answers, without
 * knowing DAX.
 * <p>
 * Each result set is one {@code EVALUATE}'s table, read to its end before it is
 * handed over, so the result sets stay readable independently of each other.
 * Failures are {@link SQLException}s, see {@link SqlExceptions}.
 * </p>
 */
@Component(service = StatementLanguage.class)
public final class DaxStatementLanguage implements StatementLanguage {

    private final OlapDaxEngine engine;

    @Activate
    public DaxStatementLanguage(@Reference DaxParserProvider parsers) {
        this.engine = new OlapDaxEngine(Objects.requireNonNull(parsers, "parsers"));
    }

    @Deactivate
    public void deactivate() {
        engine.close();
    }

    /** @return whether the statement is a DAX query */
    @Override
    public boolean accepts(String statement) {
        return DaxQueries.isDaxQuery(statement);
    }

    /**
     * Executes a DAX query.
     *
     * @param connection the connection to execute it on, with its catalog,
     *                   role and locale
     * @param statement  the query text
     * @param properties properties of the caller's connection, e.g.
     *                   {@code Cube}
     * @return one result set per {@code EVALUATE}, in query order
     * @throws SQLException if the query failed; its SQL state tells how
     */
    @Override
    public List<ResultSet> execute(Connection connection, String statement, Map<String, String> properties)
            throws SQLException {
        try (DaxQueryStatement query = engine.createStatement(connection, properties);
                DaxResult result = query.execute(statement)) {
            List<ResultSet> resultSets = new ArrayList<>();
            for (DaxTable table = result.nextTable(); table != null; table = result.nextTable()) {
                resultSets.add(TableResultSet.of(table));
            }
            return resultSets;
        } catch (DaxException e) {
            throw SqlExceptions.of(e);
        }
    }
}
