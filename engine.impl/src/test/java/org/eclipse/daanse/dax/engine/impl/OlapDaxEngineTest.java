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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.cellSet;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.dimension;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.hierarchy;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.level;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.measure;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.member;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.daanse.dax.engine.api.DaxCancelledException;
import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxQueryStatement;
import org.eclipse.daanse.dax.engine.api.DaxResult;
import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxSyntaxException;
import org.eclipse.daanse.dax.engine.api.DaxTable;
import org.eclipse.daanse.dax.parser.ccc.CCCDaxParserProvider;
import org.eclipse.daanse.olap.api.catalog.CatalogReader;
import org.eclipse.daanse.olap.api.connection.Connection;
import org.eclipse.daanse.olap.api.element.Cube;
import org.eclipse.daanse.olap.api.element.Dimension;
import org.eclipse.daanse.olap.api.element.Hierarchy;
import org.eclipse.daanse.olap.api.element.Level;
import org.eclipse.daanse.olap.api.element.Member;
import org.eclipse.daanse.olap.api.execution.Statement;
import org.eclipse.daanse.olap.api.result.CellSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OlapDaxEngineTest {

    private final OlapDaxEngine engine = new OlapDaxEngine(new CCCDaxParserProvider());
    private final Connection connection = mock(Connection.class);
    private final CatalogReader reader = mock(CatalogReader.class);
    private final Cube cube = mock(Cube.class);
    private final Statement olapStatement = mock(Statement.class);
    private final Level category = level("Category", "[Product].[Category]", 1);
    private final Level subcategory = level("Subcategory", "[Product].[Subcategory]", 2);

    @BeforeEach
    void catalog() {
        when(connection.getCatalogReader()).thenReturn(reader);
        when(connection.createStatement()).thenReturn(olapStatement);
        when(reader.getCubes()).thenReturn(List.of(cube));
        when(cube.getName()).thenReturn("Sales");
        Dimension product = dimension("Product", false);
        Dimension measures = dimension("Measures", true);
        when(reader.getCubeDimensions(cube)).thenReturn(List.of(measures, product));
        Hierarchy hierarchy = hierarchy("Product", "[Product]");
        when(reader.getDimensionHierarchies(product)).thenReturn(List.of(hierarchy));
        when(reader.getHierarchyLevels(hierarchy)).thenReturn(List.of(category, subcategory));
        Member salesAmount = measure("Sales Amount");
        when(cube.getMeasures()).thenReturn(List.of(salesAmount));
    }

    @AfterEach
    void close() {
        engine.close();
    }

    private List<List<Object>> rows(DaxTable table) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        while (table.next()) {
            List<Object> row = new ArrayList<>();
            for (int c = 0; c < table.columns().size(); c++) {
                row.add(table.getObject(c));
            }
            rows.add(row);
        }
        return rows;
    }

    @Test
    void executesSeveralEvaluatesThroughMdx() throws Exception {
        Member bikes = member("Bikes", category, null);
        Member clothes = member("Clothes", category, null);
        List<List<Member>> positions = List.of(List.of(member("Road", subcategory, bikes)),
                List.of(member("Mountain", subcategory, bikes)), List.of(member("Caps", subcategory, clothes)));
        CellSet cellSet = cellSet(positions, new Object[][] { { 10.0 }, { 30 }, { 20.5 } });
        when(olapStatement.executeQuery(
                "SELECT {[Measures].[Sales Amount]} ON 0, NON EMPTY [Product].[Subcategory].Members ON 1 FROM [Sales]"))
                .thenReturn(cellSet);

        try (DaxQueryStatement statement = engine.createStatement(connection, Map.of());
                DaxResult result = statement.execute("""
                        EVALUATE SUMMARIZECOLUMNS('Product'[Category], 'Product'[Subcategory], "Sales", [Sales Amount])
                        ORDER BY [Sales] DESC
                        EVALUATE {1}
                        """)) {
            DaxTable first = result.nextTable();
            assertThat(first.columns()).extracting(DaxColumn::name)
                    .containsExactly("Product[Category]", "Product[Subcategory]", "[Sales]");
            assertThat(rows(first)).containsExactly(List.of("Bikes", "Mountain", 30L), List.of("Clothes", "Caps", 20.5),
                    List.of("Bikes", "Road", 10.0));

            DaxTable second = result.nextTable();
            assertThat(rows(second)).containsExactly(List.of(1L));
            assertThatIllegalStateException().isThrownBy(first::next);

            assertThat(result.nextTable()).isNull();
        }
        verify(olapStatement).close();
    }

    @Test
    void measuresAloneAnswerNoRowWhenBlank() throws Exception {
        CellSet cellSet = cellSet(null, new Object[][] { { null } });
        when(olapStatement.executeQuery(anyString())).thenReturn(cellSet);
        try (DaxQueryStatement statement = engine.createStatement(connection, Map.of());
                DaxResult result = statement.execute("EVALUATE ROW(\"Total\", [Sales Amount])")) {
            assertThat(rows(result.nextTable())).isEmpty();
        }
    }

    @Test
    void syntaxErrorHasItsPosition() {
        DaxQueryStatement statement = engine.createStatement(connection, Map.of());
        assertThatThrownBy(() -> statement.execute("EVALUATE SUMMARIZECOLUMNS(")).isInstanceOfSatisfying(
                DaxSyntaxException.class, e -> assertThat(e.getMessage()).contains("1:"));
    }

    @Test
    void errorInAnyEvaluateFailsBeforeAnythingRuns() {
        DaxQueryStatement statement = engine.createStatement(connection, Map.of());
        assertThatThrownBy(() -> statement.execute("EVALUATE 'Product' EVALUATE 'Nope'"))
                .isInstanceOf(DaxSemanticException.class).hasMessage("the table 'Nope' does not exist");
        verify(connection, never()).createStatement();
    }

    @Test
    void severalCubesNeedTheCubeNamed() throws Exception {
        Cube other = mock(Cube.class);
        when(other.getName()).thenReturn("Other");
        when(reader.getCubes()).thenReturn(List.of(other, cube));
        assertThatThrownBy(() -> engine.createStatement(connection, Map.of()).execute("EVALUATE {1}"))
                .isInstanceOf(DaxSemanticException.class).hasMessageContaining("2 cubes");
        try (DaxResult result = engine.createStatement(connection, Map.of("CUBE", "sales")).execute("EVALUATE {1}")) {
            assertThat(result.nextTable()).isNotNull();
        }
    }

    @Test
    void cancelStopsTheRunningMdx() throws Exception {
        DaxQueryStatement statement = engine.createStatement(connection, Map.of());
        when(olapStatement.executeQuery(anyString())).thenAnswer(invocation -> {
            statement.cancel(); // as another thread would, while the MDX runs
            verify(olapStatement).cancel();
            throw new IllegalStateException("interrupted");
        });
        DaxResult result = statement.execute("EVALUATE 'Product'");
        assertThatThrownBy(result::nextTable).isInstanceOfSatisfying(DaxCancelledException.class,
                e -> assertThat(e.reason()).isEqualTo(DaxCancelledException.Reason.CANCELLED));
    }

    @Test
    void timeoutStopsTheRunningMdx() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        doAnswer(invocation -> {
            cancelled.countDown();
            return null;
        }).when(olapStatement).cancel();
        when(olapStatement.executeQuery(anyString())).thenAnswer(invocation -> {
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("interrupted");
        });
        DaxQueryStatement statement = engine.createStatement(connection, Map.of());
        statement.setTimeout(Duration.ofMillis(50));
        DaxResult result = statement.execute("EVALUATE 'Product'");
        assertThatThrownBy(result::nextTable).isInstanceOfSatisfying(DaxCancelledException.class,
                e -> assertThat(e.reason()).isEqualTo(DaxCancelledException.Reason.TIMEOUT));
    }

    @Test
    void parametersMustBeDaxValues() {
        DaxQueryStatement statement = engine.createStatement(connection, Map.of());
        statement.setParameter("p", 1L);
        statement.setParameter("blank", null);
        assertThatThrownBy(() -> statement.setParameter("p", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
