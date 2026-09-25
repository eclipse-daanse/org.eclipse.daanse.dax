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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;

import org.eclipse.daanse.dax.engine.api.DaxCancelledException;
import org.eclipse.daanse.dax.engine.api.DaxExecutionException;
import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxSyntaxException;
import org.eclipse.daanse.dax.parser.ccc.CCCDaxParserProvider;
import org.eclipse.daanse.olap.api.catalog.CatalogReader;
import org.eclipse.daanse.olap.api.connection.Connection;
import org.eclipse.daanse.olap.api.element.Cube;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DaxStatementLanguageTest {

    private final DaxStatementLanguage language = new DaxStatementLanguage(new CCCDaxParserProvider());

    @AfterEach
    void deactivate() {
        language.deactivate();
    }

    private static Connection connection(String... cubeNames) {
        Connection connection = mock(Connection.class);
        CatalogReader reader = mock(CatalogReader.class);
        when(connection.getCatalogReader()).thenReturn(reader);
        List<Cube> cubes = new java.util.ArrayList<>();
        for (String name : cubeNames) {
            Cube cube = mock(Cube.class);
            when(cube.getName()).thenReturn(name);
            cubes.add(cube);
        }
        when(reader.getCubes()).thenReturn(cubes);
        return connection;
    }

    @Test
    void acceptsDaxOnly() {
        assertThat(language.accepts("EVALUATE {1}")).isTrue();
        assertThat(language.accepts("SELECT FROM [Sales]")).isFalse();
    }

    @Test
    void answersOneResultSetPerEvaluate() throws Exception {
        List<ResultSet> resultSets = language.execute(connection("Sales"),
                "EVALUATE {1, 2} EVALUATE ROW(\"Name\", \"x\")", Map.of());
        assertThat(resultSets).hasSize(2);

        ResultSet first = resultSets.get(0);
        assertThat(first.getMetaData().getColumnLabel(1)).isEqualTo("[Value]");
        assertThat(first.next()).isTrue();
        assertThat(first.getLong(1)).isEqualTo(1L);
        assertThat(first.next()).isTrue();
        assertThat(first.getLong(1)).isEqualTo(2L);
        assertThat(first.next()).isFalse();

        ResultSet second = resultSets.get(1);
        assertThat(second.next()).isTrue();
        assertThat(second.getString("[Name]")).isEqualTo("x");
    }

    @Test
    void passesTheCubeProperty() throws Exception {
        Connection connection = connection("Sales", "Budget");
        assertThatThrownBy(() -> language.execute(connection, "EVALUATE {1}", Map.of()))
                .isInstanceOf(SQLSyntaxErrorException.class).hasMessageContaining("2 cubes");
        assertThat(language.execute(connection, "EVALUATE {1}", Map.of("Cube", "Budget"))).hasSize(1);
    }

    @Test
    void failuresCarryTheirSqlState() {
        assertThatThrownBy(() -> language.execute(connection("Sales"), "EVALUATE {", Map.of()))
                .isInstanceOfSatisfying(SQLSyntaxErrorException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo(SqlExceptions.SYNTAX_OR_SEMANTIC);
                    assertThat(e.getCause()).isInstanceOf(DaxSyntaxException.class);
                });

        assertThat(SqlExceptions.of(new DaxSemanticException("x")).getSQLState()).isEqualTo("42000");
        assertThat(SqlExceptions.of(new DaxExecutionException("x")).getSQLState()).isEqualTo("HY000");
        SQLException cancelled = SqlExceptions.of(new DaxCancelledException(DaxCancelledException.Reason.CANCELLED));
        assertThat(cancelled.getSQLState()).isEqualTo("HY008");
        SQLException timedOut = SqlExceptions.of(new DaxCancelledException(DaxCancelledException.Reason.TIMEOUT));
        assertThat(timedOut.getSQLState()).isEqualTo("HYT00");
        assertThat(timedOut instanceof SQLTimeoutException).isTrue();
    }
}
