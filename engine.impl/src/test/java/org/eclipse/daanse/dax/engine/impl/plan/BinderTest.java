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
package org.eclipse.daanse.dax.engine.impl.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.daanse.dax.engine.impl.TestModel.CATEGORY;
import static org.eclipse.daanse.dax.engine.impl.TestModel.SALES_AMOUNT;
import static org.eclipse.daanse.dax.engine.impl.TestModel.SUBCATEGORY;
import static org.eclipse.daanse.dax.engine.impl.TestModel.UNIT_SALES;
import static org.eclipse.daanse.dax.engine.impl.TestModel.YEAR;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.TestModel;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan.SortKey;
import org.eclipse.daanse.dax.parser.ccc.CCCDaxParserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BinderTest {

    private static QueryPlan bind(String dax) throws Exception {
        return bind(dax, Map.of());
    }

    private static QueryPlan bind(String dax, Map<String, Object> parameters) throws Exception {
        return new Binder(TestModel.model(), parameters)
                .bind(new CCCDaxParserProvider().newParser(dax).parseDaxStatement());
    }

    private static TablePlan single(String dax) throws Exception {
        QueryPlan plan = bind(dax);
        assertThat(plan.evaluates()).hasSize(1);
        return plan.evaluates().get(0).table();
    }

    @Test
    void tableReferenceGroupsByAllItsColumns() throws Exception {
        assertThat(single("EVALUATE 'Product'")).isEqualTo(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()));
        assertThat(single("EVALUATE product")).isEqualTo(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()));
    }

    @Test
    void summarizeColumns() throws Exception {
        TablePlan plan = single("EVALUATE SUMMARIZECOLUMNS('Product'[Category], 'Date'[Year], "
                + "\"Sales\", [Sales Amount], \"Units\", 'Measures'[Unit Sales])");
        assertThat(plan).isEqualTo(new Summarize(List.of(CATEGORY, YEAR),
                List.of(new NamedMeasure("Sales", SALES_AMOUNT), new NamedMeasure("Units", UNIT_SALES))));
        assertThat(plan.columns()).extracting(DaxColumn::name)
                .containsExactly("Product[Category]", "Date[Year]", "[Sales]", "[Units]");
    }

    @Test
    void rowOfMeasuresIsSummarizeWithoutGroups() throws Exception {
        assertThat(single("EVALUATE ROW(\"Total\", [Sales Amount])"))
                .isEqualTo(new Summarize(List.of(), List.of(new NamedMeasure("Total", SALES_AMOUNT))));
    }

    @Test
    void tableConstructor() throws Exception {
        ConstantTable table = (ConstantTable) single("EVALUATE {(1, \"a\"), (2.5, BLANK())}");
        assertThat(table.columns()).extracting(DaxColumn::name).containsExactly("[Value1]", "[Value2]");
        assertThat(table.columns()).extracting(DaxColumn::type).containsExactly(DaxType.DOUBLE, DaxType.STRING);
        assertThat(table.rows()).containsExactly(List.of(1.0, "a"), Arrays.asList(2.5, null));
    }

    @Test
    void singleColumnConstructorIsNamedValue() throws Exception {
        ConstantTable table = (ConstantTable) single("EVALUATE {1, 2, 3}");
        assertThat(table.columns()).containsExactly(new DaxColumn("[Value]", java.util.Optional.empty(),
                DaxType.INTEGER));
        assertThat(table.rows()).containsExactly(List.of(1L), List.of(2L), List.of(3L));
    }

    @Test
    void rowOfConstantsWithParameterAndVariable() throws Exception {
        QueryPlan plan = bind("DEFINE VAR v = TRUE EVALUATE ROW(\"p\", @p, \"v\", v)", Map.of("P", "x"));
        ConstantTable table = (ConstantTable) plan.evaluates().get(0).table();
        assertThat(table.rows()).containsExactly(List.of("x", true));
    }

    @Test
    void severalEvaluatesShareTheirDefinitions() throws Exception {
        QueryPlan plan = bind("DEFINE VAR v = 1 EVALUATE {v} EVALUATE 'Date' ORDER BY 'Date'[Year] DESC");
        assertThat(plan.evaluates()).hasSize(2);
        assertThat(((ConstantTable) plan.evaluates().get(0).table()).rows()).containsExactly(List.of(1L));
        assertThat(plan.evaluates().get(1).orderBy()).containsExactly(new SortKey(0, false));
    }

    @Test
    void orderByMeasureColumn() throws Exception {
        QueryPlan plan = bind(
                "EVALUATE SUMMARIZECOLUMNS('Product'[Category], \"Sales\", [Sales Amount]) ORDER BY [Sales] DESC, 'Product'[Category]");
        assertThat(plan.evaluates().get(0).orderBy()).containsExactly(new SortKey(1, false), new SortKey(0, true));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { //
            "EVALUATE 'Nope'|the table 'Nope' does not exist", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Color])|the column 'Product'[Color] does not exist", //
            "EVALUATE ROW(\"x\", [Profit])|the measure [Profit] does not exist", //
            "EVALUATE {@missing}|the parameter @missing has no value", //
            "EVALUATE {1} ORDER BY [Other]|ORDER BY [Other]: no such column in the result", //
            "DEFINE MEASURE 'Product'[M] = 1 EVALUATE {1}|DEFINE MEASURE is not supported yet", //
            "EVALUATE FILTER('Product', TRUE)|the table function FILTER is not supported yet", //
            "EVALUATE 'Store'|columns of several hierarchies of the table 'Store' without a measure is not supported yet", //
            "EVALUATE ROW(\"a\", 1, \"b\", [Sales Amount])|ROW mixing measures and other expressions is not supported yet" })
    void reportsWhatDoesNotFit(String dax, String message) {
        assertThatThrownBy(() -> bind(dax)).isInstanceOf(DaxSemanticException.class).hasMessage(message);
    }

    @Test
    void severalHierarchiesOfATableWithAMeasure() throws Exception {
        assertThat(single("EVALUATE SUMMARIZECOLUMNS('Store'[City], 'Store'[Type], \"S\", [Sales Amount])"))
                .isInstanceOf(Summarize.class);
    }
}
