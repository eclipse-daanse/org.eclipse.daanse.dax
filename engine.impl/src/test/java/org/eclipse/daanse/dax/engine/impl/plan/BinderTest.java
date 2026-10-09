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

import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.daanse.dax.engine.impl.TestModel.CATEGORY;
import static org.eclipse.daanse.dax.engine.impl.TestModel.MARKETS;
import static org.eclipse.daanse.dax.engine.impl.TestModel.SALES;
import static org.eclipse.daanse.dax.engine.impl.TestModel.SALES_AMOUNT;
import static org.eclipse.daanse.dax.engine.impl.TestModel.SUBCATEGORY;
import static org.eclipse.daanse.dax.engine.impl.TestModel.UNIT_SALES;
import static org.eclipse.daanse.dax.engine.impl.TestModel.YEAR;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.TestModel;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan.SortKey;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.ColumnValue;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Comparison;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Constant;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.InList;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Logical;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.MeasureValue;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Not;
import org.eclipse.daanse.dax.model.api.expression.BooleanExpression.BooleanOperator;
import org.eclipse.daanse.dax.model.api.expression.LogicalExpression.LogicalOperator;
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
        return single(dax, Map.of());
    }

    private static TablePlan single(String dax, Map<String, Object> parameters) throws Exception {
        QueryPlan plan = bind(dax, parameters);
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

    @Test
    void columnReferredToByLevelUniqueNameInRowsAndOrderBy() throws Exception {
        QueryPlan plan = bind("""
                EVALUATE TOPN(501, FILTER(KEEPFILTERS(VALUES('Product'[Product.Category])),
                    NOT(ISBLANK('Product'[Product.Category]))), 'Product'[Product.Category], 1)
                ORDER BY 'Product'[Product.Category]""");
        TopN topN = (TopN) plan.evaluates().get(0).table();
        assertThat(topN.keys()).containsExactly(new SortKey(0, true));
        assertThat(topN.columns()).extracting(DaxColumn::name).containsExactly("Product[Category]");
        assertThat(plan.evaluates().get(0).orderBy()).containsExactly(new SortKey(0, true));
    }

    @Test
    void sampleOfConstantTableSpreadsRowsEvenly() throws Exception {
        assertThat(((ConstantTable) single("EVALUATE SAMPLE(3, {5, 1, 4, 2, 3}, [Value])")).rows())
                .containsExactly(List.of(1L), List.of(3L), List.of(5L));
        assertThat(((ConstantTable) single("EVALUATE SAMPLE(10, {2, 1, 3}, [Value])")).rows())
                .containsExactly(List.of(1L), List.of(2L), List.of(3L));
        assertThat(((ConstantTable) single("EVALUATE SAMPLE(2, {2, 1, 3}, [Value], DESC)")).rows())
                .containsExactly(List.of(3L), List.of(1L));
        assertThat(((ConstantTable) single("EVALUATE SAMPLE(1, {2, 1, 3}, [Value])")).rows())
                .containsExactly(List.of(1L));
        assertThat(((ConstantTable) single("EVALUATE SAMPLE(0, {1, 2}, [Value])")).rows()).isEmpty();
    }

    @Test
    void sampleOfValuesFilteredByMeasuresWithAddedMeasures() throws Exception {
        QueryPlan plan = bind("""
                EVALUATE ADDCOLUMNS(KEEPFILTERS(SAMPLE(3502, FILTER(KEEPFILTERS(VALUES('Markets'[Country])),
                        OR(NOT(ISBLANK('Measures'[Sales])), NOT(ISBLANK('Measures'[Unit Sales])))),
                        'Markets'[Country], 1)),
                    "S", 'Measures'[Sales], "U", 'Measures'[Unit Sales])
                ORDER BY 'Markets'[Country]""");
        Sample sample = (Sample) plan.evaluates().get(0).table();
        assertThat(sample.count()).isEqualTo(3502);
        assertThat(sample.keys()).containsExactly(new SortKey(0, true));
        // the measures are added to the grouping the cube computes, below the sample
        Summarize summarize = (Summarize) sample.source();
        assertThat(summarize.groupBy()).containsExactly(MARKETS);
        assertThat(summarize.condition()).contains(new Logical(LogicalOperator.OR,
                new Not(new ScalarPlan.IsBlank(new MeasureValue(SALES))),
                new Not(new ScalarPlan.IsBlank(new MeasureValue(UNIT_SALES)))));
        assertThat(summarize.added()).containsExactly(new NamedMeasure("S", new MeasureValue(SALES)),
                new NamedMeasure("U", new MeasureValue(UNIT_SALES)));
        assertThat(sample.columns()).extracting(DaxColumn::name).containsExactly("Markets[Country]", "[S]", "[U]");
        assertThat(plan.evaluates().get(0).orderBy()).containsExactly(new SortKey(0, true));
    }

    @Test
    void summarizeOfTableGroupsByItsColumns() throws Exception {
        QueryPlan plan = bind("""
                EVALUATE TOPN(501, FILTER(KEEPFILTERS(SUMMARIZE(VALUES('Product'),
                        'Product'[Product.Subcategory], 'Product'[Product.Category])),
                    OR(NOT(ISBLANK('Product'[Product.Subcategory])),
                        NOT(ISBLANK('Product'[Product.Category])))),
                    'Product'[Product.Subcategory], 1, 'Product'[Product.Category], 1)
                ORDER BY 'Product'[Product.Subcategory], 'Product'[Product.Category]""");
        assertThat(plan.evaluates().get(0).table()).isEqualTo(new TopN(new Filter(
                new Summarize(List.of(SUBCATEGORY, CATEGORY), List.of()),
                new Logical(LogicalOperator.OR, new Not(new ScalarPlan.IsBlank(new ColumnValue(0))),
                        new Not(new ScalarPlan.IsBlank(new ColumnValue(1))))),
                501, List.of(new SortKey(0, true), new SortKey(1, true))));
        assertThat(plan.evaluates().get(0).table().columns()).extracting(DaxColumn::name)
                .containsExactly("Product[Subcategory]", "Product[Category]");
        assertThat(single("EVALUATE SUMMARIZE('Product', 'Product'[Category])"))
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of()));
        assertThat(single("EVALUATE SUMMARIZE(DISTINCT(VALUES('Product'[Category])), 'Product'[Category])"))
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of()));
    }

    @Test
    void summarizeAddsItsMeasures() throws Exception {
        TablePlan plan = single("EVALUATE SUMMARIZE('Markets', 'Markets'[Country], \"S\", [Sales], "
                + "\"Empty\", ISBLANK([Unit Sales]))");
        assertThat(plan).isEqualTo(new Summarize(List.of(MARKETS), List.of()).withAdded(List.of(
                new NamedMeasure("S", new MeasureValue(SALES)),
                new NamedMeasure("Empty", new ScalarPlan.IsBlank(new MeasureValue(UNIT_SALES))))));
        assertThat(plan.columns()).extracting(DaxColumn::name).containsExactly("Markets[Country]", "[S]", "[Empty]");
    }

    @Test
    void distinctOfColumnGroupsByIt() throws Exception {
        TablePlan plan = single("EVALUATE DISTINCT('Product'[Subcategory])");
        assertThat(plan).isEqualTo(new Summarize(List.of(SUBCATEGORY), List.of()));
        assertThat(plan.columns()).extracting(DaxColumn::name).containsExactly("Product[Subcategory]");
    }

    @Test
    void distinctOfGroupingIsTheGrouping() throws Exception {
        assertThat(single("EVALUATE DISTINCT('Product')"))
                .isEqualTo(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()));
        assertThat(single("EVALUATE DISTINCT(SUMMARIZECOLUMNS('Product'[Category], \"S\", [Sales Amount]))"))
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of(new NamedMeasure("S", SALES_AMOUNT))));
    }

    @Test
    void distinctOfConstantTableRemovesDuplicateRows() throws Exception {
        ConstantTable table = (ConstantTable) single(
                "EVALUATE DISTINCT({(1, \"a\"), (2, BLANK()), (1, \"a\"), (2, BLANK())})");
        assertThat(table.columns()).extracting(DaxColumn::name).containsExactly("[Value1]", "[Value2]");
        assertThat(table.rows()).containsExactly(List.of(1L, "a"), Arrays.asList(2L, null));
    }

    @Test
    void valuesOfColumnGroupsByIt() throws Exception {
        TablePlan plan = single("EVALUATE VALUES('Product'[Category])");
        assertThat(plan).isEqualTo(new Summarize(List.of(CATEGORY), List.of()));
        assertThat(plan.columns()).extracting(DaxColumn::name).containsExactly("Product[Category]");
    }

    @Test
    void valuesOfTableIsTheTable() throws Exception {
        assertThat(single("EVALUATE VALUES('Product')"))
                .isEqualTo(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()));
        assertThat(single("EVALUATE VALUES(product)"))
                .isEqualTo(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()));
    }

    @Test
    void isBlankOfConstants() throws Exception {
        QueryPlan plan = bind("DEFINE VAR v = BLANK() EVALUATE ROW(\"v\", ISBLANK(v), \"p\", IsBlank(@p), "
                + "\"empty\", ISBLANK(\"\"), \"zero\", ISBLANK(0), \"nested\", ISBLANK(ISBLANK(BLANK())))",
                Map.of("p", 1L));
        ConstantTable table = (ConstantTable) plan.evaluates().get(0).table();
        assertThat(table.columns()).extracting(DaxColumn::type).containsOnly(DaxType.BOOLEAN);
        assertThat(table.rows()).containsExactly(List.of(true, false, false, false, false));
    }

    @Test
    void notOfConstants() throws Exception {
        QueryPlan plan = bind("DEFINE VAR v = FALSE EVALUATE ROW(\"v\", NOT(v), \"true\", not(TRUE()), "
                + "\"blank\", NOT(BLANK()), \"zero\", NOT(0), \"number\", NOT(@p), "
                + "\"isblank\", NOT(ISBLANK(1)), \"nested\", NOT(NOT(TRUE)))", Map.of("p", 2.5));
        ConstantTable table = (ConstantTable) plan.evaluates().get(0).table();
        assertThat(table.columns()).extracting(DaxColumn::type).containsOnly(DaxType.BOOLEAN);
        assertThat(table.rows()).containsExactly(List.of(true, false, true, true, false, true, true));
    }

    @Test
    void currencyOfConstants() throws Exception {
        QueryPlan plan = bind("EVALUATE ROW(\"text\", CURRENCY(\"442\"), \"rounded\", CURRENCY(1.23456), "
                + "\"whole\", CURRENCY(7), \"true\", CURRENCY(TRUE), \"param\", CURRENCY(@p))", Map.of("p", "-0.00005"));
        ConstantTable table = (ConstantTable) plan.evaluates().get(0).table();
        assertThat(table.columns()).extracting(DaxColumn::type).containsOnly(DaxType.DECIMAL);
        assertThat(table.rows()).containsExactly(List.of(new BigDecimal("442.0000"), new BigDecimal("1.2346"),
                new BigDecimal("7.0000"), new BigDecimal("1.0000"), new BigDecimal("-0.0001")));
    }

    @Test
    void currencyOfBlankIsBlankAndOfTextNoNumberAnError() throws Exception {
        ConstantTable table = (ConstantTable) single("EVALUATE ROW(\"blank\", CURRENCY(BLANK()))");
        assertThat(table.rows()).containsExactly(Arrays.asList((Object) null));
        assertThatThrownBy(() -> bind("EVALUATE ROW(\"x\", CURRENCY(\"abc\"))"))
                .isInstanceOf(DaxSemanticException.class).hasMessageContaining("CURRENCY cannot convert");
    }

    @Test
    void filterOfConstantTableIsComputedWhenBinding() throws Exception {
        ConstantTable table = (ConstantTable) single("EVALUATE FILTER({1, 2, BLANK(), 3}, [Value] > 1)");
        assertThat(table.columns()).extracting(DaxColumn::name).containsExactly("[Value]");
        assertThat(table.rows()).containsExactly(List.of(2L), List.of(3L));
    }

    @Test
    void filterOfTableReference() throws Exception {
        assertThat(single("EVALUATE FILTER('Product', 'Product'[Subcategory] <> \"Road\" && NOT('product'[category] = \"Caps\"))"))
                .isEqualTo(new Filter(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()),
                        new Logical(LogicalOperator.AND,
                                new Comparison(BooleanOperator.NOT_EQUAL, new ColumnValue(1), new Constant("Road")),
                                new Not(new Comparison(BooleanOperator.EQUAL, new ColumnValue(0), new Constant("Caps"))))));
    }

    @Test
    void filterOfSummarizeColumnsByItsMeasureColumn() throws Exception {
        QueryPlan plan = bind("DEFINE VAR least = 100 EVALUATE FILTER(SUMMARIZECOLUMNS('Product'[Category], "
                + "\"S\", [Sales Amount]), [S] >= least || 'Product'[Category] IN {\"Bikes\", BLANK()}) ORDER BY [S]",
                Map.of());
        EvaluatePlan evaluate = plan.evaluates().get(0);
        assertThat(evaluate.table()).isEqualTo(new Filter(
                new Summarize(List.of(CATEGORY), List.of(new NamedMeasure("S", SALES_AMOUNT))),
                new Logical(LogicalOperator.OR,
                        new Comparison(BooleanOperator.GREATER_THAN_OR_EQUAL, new ColumnValue(1), new Constant(100L)),
                        new InList(new ColumnValue(0), Arrays.asList("Bikes", null)))));
        assertThat(evaluate.orderBy()).containsExactly(new SortKey(1, true));
    }

    @Test
    void filterByMeasuresIsComputedByTheCube() throws Exception {
        assertThat(single("EVALUATE FILTER(FILTER('Product', 'Product'[Category] = \"Bikes\"), "
                + "[Sales Amount] > 1 && 'Product'[Subcategory] <> \"Road\" && NOT(ISBLANK('Measures'[Unit Sales])))"))
                .isEqualTo(new Filter(
                        new Filter(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of(), Optional.of(new Logical(
                                LogicalOperator.AND,
                                new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(SALES_AMOUNT),
                                        new Constant(1L)),
                                new Not(new ScalarPlan.IsBlank(new MeasureValue(UNIT_SALES)))))),
                                new Comparison(BooleanOperator.EQUAL, new ColumnValue(0), new Constant("Bikes"))),
                        new Comparison(BooleanOperator.NOT_EQUAL, new ColumnValue(1), new Constant("Road"))));
    }

    @Test
    void measureOrColumnGroupedByIsComputedByTheCube() throws Exception {
        assertThat(single("EVALUATE FILTER('Product', [Sales Amount] > 1 || 'Product'[Subcategory] IN {\"Road\"} "
                + "|| \"Bikes\" = 'Product'[Category])"))
                .isEqualTo(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()).filtered(new Logical(
                        LogicalOperator.OR,
                        new Logical(LogicalOperator.OR,
                                new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(SALES_AMOUNT),
                                        new Constant(1L)),
                                new InList(new ColumnValue(1), List.of("Road"))),
                        new Comparison(BooleanOperator.EQUAL, new Constant("Bikes"), new ColumnValue(0)))));
        // not in reportsWhatDoesNotFit: its separator is |
        String message = "FILTER by a condition joining measures with columns other than grouped by and compared "
                + "with text, with IN of other than text, BLANK or dates other than by && is not supported yet";
        assertThatThrownBy(() -> bind("EVALUATE FILTER('Product', [Sales Amount] > 1 || 'Product'[Category] = 1)"))
                .isInstanceOf(DaxSemanticException.class).hasMessage(message);
        assertThatThrownBy(() -> bind("EVALUATE FILTER(ADDCOLUMNS('Product', \"a\", \"x\"), "
                + "[Sales Amount] > 1 || [a] = \"x\")")).isInstanceOf(DaxSemanticException.class).hasMessage(message);
        assertThatThrownBy(() -> bind("EVALUATE FILTER({\"x\"}, [Sales Amount] > 1 || [Value] = \"x\")"))
                .isInstanceOf(DaxSemanticException.class).hasMessage(message);
    }

    @Test
    void keepFiltersOfFilterIteratedByAddColumns() throws Exception {
        assertThat(single("EVALUATE ADDCOLUMNS(KEEPFILTERS(FILTER(VALUES('Markets'[Country]), [Sales] > 100)), "
                + "\"S\", [Sales])"))
                .isEqualTo(new Summarize(List.of(MARKETS), List.of(), Optional.of(
                        new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(SALES), new Constant(100L))),
                        Optional.empty(), List.of(), List.of(new NamedMeasure("S", SALES))));
    }

    @Test
    void measureColumnOfTheRowBeforeTheMeasure() throws Exception {
        TablePlan plan = single("EVALUATE FILTER(SUMMARIZECOLUMNS('Product'[Category], \"Sales Amount\", "
                + "[Unit Sales]), [Sales Amount] > 1)");
        assertThat(plan).isInstanceOfSatisfying(Filter.class, filter -> assertThat(filter.condition())
                .isEqualTo(new Comparison(BooleanOperator.GREATER_THAN, new ColumnValue(1), new Constant(1L))));
    }

    @Test
    void topNOfConstantTableKeepsTies() throws Exception {
        ConstantTable table = (ConstantTable) single(
                "EVALUATE TOPN(2, {(1, \"a\"), (3, \"b\"), (2, \"c\"), (2, \"d\"), (BLANK(), \"e\")}, [Value1])");
        assertThat(table.rows()).containsExactly(List.of(3L, "b"), List.of(2L, "c"), List.of(2L, "d"));
        ConstantTable ascending = (ConstantTable) single(
                "EVALUATE TOPN(2, {(1, \"a\"), (3, \"b\"), (2, \"c\"), (BLANK(), \"e\")}, [Value1], ASC)");
        assertThat(ascending.rows()).containsExactly(Arrays.asList(null, "e"), List.of(1L, "a"));
        ConstantTable twoKeys = (ConstantTable) single(
                "EVALUATE TOPN(1, {(2, \"c\"), (2, \"d\")}, [Value1], 0, [Value2], TRUE)");
        assertThat(twoKeys.rows()).containsExactly(List.of(2L, "c"));
        assertThat(((ConstantTable) single("EVALUATE TOPN(0, {1, 2}, [Value])")).rows()).isEmpty();
    }

    @Test
    void topNByColumnsOfTheCubeIsComputedOnTheRows() throws Exception {
        assertThat(single("EVALUATE TOPN(@n, 'Product', 'Product'[Subcategory], DESC, 'Product'[Category], 1)",
                Map.of("n", 5L))).isEqualTo(new TopN(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()), 5,
                        List.of(new SortKey(1, false), new SortKey(0, true))));
    }

    @Test
    void topNByMeasureIsComputedByTheCube() throws Exception {
        assertThat(single("EVALUATE TOPN(3, FILTER(VALUES('Product'[Category]), [Unit Sales] > 1), "
                + "[Sales Amount], ASC)"))
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of(),
                        Optional.of(new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(UNIT_SALES),
                                new Constant(1L))),
                        Optional.of(new Summarize.Top(3, SALES_AMOUNT, true))));
        // a column of the table named as a measure is the column
        assertThat(single("EVALUATE TOPN(3, SUMMARIZECOLUMNS('Product'[Category], \"Sales Amount\", [Unit Sales]), "
                + "[Sales Amount])")).isInstanceOf(TopN.class);
    }

    @Test
    void isBlankOfMeasureIsComputedByTheCube() throws Exception {
        assertThat(single("EVALUATE SUMMARIZECOLUMNS('Product'[Category], \"Empty\", ISBLANK([Sales Amount]))"))
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of(
                        new NamedMeasure("Empty", new ScalarPlan.IsBlank(new MeasureValue(SALES_AMOUNT))))));
        assertThat(single("EVALUATE ROW(\"Empty\", ISBLANK([Sales Amount]))")).isEqualTo(new Summarize(List.of(),
                List.of(new NamedMeasure("Empty", new ScalarPlan.IsBlank(new MeasureValue(SALES_AMOUNT))))));
    }

    @Test
    void keepFiltersIsItsFilterTable() throws Exception {
        TablePlan plain = single("EVALUATE SUMMARIZECOLUMNS('Product'[Category], "
                + "FILTER(VALUES('Markets'[Country]), 'Markets'[Country] = \"USA\"), \"S\", [Sales Amount])");
        assertThat(single("EVALUATE SUMMARIZECOLUMNS('Product'[Category], KEEPFILTERS(KEEPFILTERS("
                + "FILTER(VALUES('Markets'[Country]), 'Markets'[Country] = \"USA\"))), \"S\", [Sales Amount])"))
                .isEqualTo(plain)
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of(new NamedMeasure("S", SALES_AMOUNT)),
                        Optional.empty(), Optional.empty(), List.of(new Filter(new Summarize(List.of(MARKETS), List.of()),
                                new Comparison(BooleanOperator.EQUAL, new ColumnValue(0), new Constant("USA"))))));
    }

    @Test
    void keepFiltersOfValuesIteratedIsTheValues() throws Exception {
        assertThat(single("EVALUATE FILTER(KEEPFILTERS(VALUES('Markets'[Country])), [Sales] > 100)"))
                .isEqualTo(single("EVALUATE FILTER(VALUES('Markets'[Country]), [Sales] > 100)"))
                .isEqualTo(new Summarize(List.of(MARKETS), List.of(), Optional.of(
                        new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(SALES), new Constant(100L)))));
        assertThat(single("EVALUATE TOPN(3, KEEPFILTERS(VALUES('Markets'[Country])), [Sales])"))
                .isEqualTo(new Summarize(List.of(MARKETS), List.of(), Optional.empty(),
                        Optional.of(new Summarize.Top(3, SALES, false))));
        assertThat(single("EVALUATE TOPN(1, KEEPFILTERS(KEEPFILTERS({1, 2})), [Value])"))
                .isEqualTo(new ConstantTable(List.of(new DaxColumn("[Value]", Optional.empty(), DaxType.INTEGER)),
                        List.of(List.of(2L))));
    }

    @Test
    void keepFiltersOfFilterOfKeepFiltersOfValuesAsFilterTable() throws Exception {
        assertThat(single("EVALUATE SUMMARIZECOLUMNS('Product'[Category], "
                + "KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Markets'[Country])), [Sales] > 1)), \"S\", [Sales Amount])"))
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of(new NamedMeasure("S", SALES_AMOUNT)),
                        Optional.empty(), Optional.empty(), List.of(new Summarize(List.of(MARKETS), List.of(),
                                Optional.of(new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(SALES),
                                        new Constant(1L)))))));
    }

    @Test
    void addColumnsOfConstantTableIsComputedWhenBinding() throws Exception {
        ConstantTable table = (ConstantTable) single("EVALUATE ADDCOLUMNS({1, 2}, \"Big\", [Value] > 1, \"c\", \"x\")");
        assertThat(table.columns()).extracting(DaxColumn::name).containsExactly("[Value]", "[Big]", "[c]");
        assertThat(table.columns()).extracting(DaxColumn::type).containsExactly(DaxType.INTEGER, DaxType.BOOLEAN,
                DaxType.STRING);
        assertThat(table.rows()).containsExactly(List.of(1L, false, "x"), List.of(2L, true, "x"));
    }

    @Test
    void addColumnsOfMeasuresIsComputedByTheCube() throws Exception {
        assertThat(single("EVALUATE ADDCOLUMNS(VALUES('Product'[Category]), \"S\", [Sales Amount], "
                + "\"E\", ISBLANK([Unit Sales]))"))
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of(), Optional.empty(), Optional.empty(), List.of(),
                        List.of(new NamedMeasure("S", SALES_AMOUNT),
                                new NamedMeasure("E", new ScalarPlan.IsBlank(new MeasureValue(UNIT_SALES))))));
        assertThat(single("EVALUATE ADDCOLUMNS(SUMMARIZECOLUMNS('Product'[Category], \"S\", [Sales Amount]), "
                + "\"U\", [Unit Sales])"))
                .isEqualTo(new Summarize(List.of(CATEGORY), List.of(new NamedMeasure("S", SALES_AMOUNT)),
                        Optional.empty(), Optional.empty(), List.of(), List.of(new NamedMeasure("U", UNIT_SALES))));
    }

    @Test
    void addColumnsOfColumnsAndMeasures() throws Exception {
        TablePlan plan = single("EVALUATE ADDCOLUMNS('Product', \"Bikes\", 'Product'[Category] = \"Bikes\", "
                + "\"S\", [Sales Amount])");
        assertThat(plan).isEqualTo(new AddColumns(
                new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of(), Optional.empty(), Optional.empty(),
                        List.of(), List.of(new NamedMeasure("S", SALES_AMOUNT))),
                2, List.of(new AddColumns.Added("Bikes",
                        new Comparison(BooleanOperator.EQUAL, new ColumnValue(0), new Constant("Bikes")), DaxType.BOOLEAN),
                        new AddColumns.Added("S", new ColumnValue(2), DaxType.VARIANT))));
        assertThat(plan.columns()).extracting(DaxColumn::name)
                .containsExactly("Product[Category]", "Product[Subcategory]", "[Bikes]", "[S]");
    }

    @Test
    void filterAndTopNOfAddColumns() throws Exception {
        Summarize withS = new Summarize(List.of(CATEGORY), List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new NamedMeasure("S", SALES_AMOUNT)));
        assertThat(single("EVALUATE FILTER(ADDCOLUMNS(VALUES('Product'[Category]), \"S\", [Sales Amount]), "
                + "[S] > 1 && [Unit Sales] > 2)"))
                .isEqualTo(new Filter(withS.filtered(new Comparison(BooleanOperator.GREATER_THAN,
                        new MeasureValue(UNIT_SALES), new Constant(2L))),
                        new Comparison(BooleanOperator.GREATER_THAN, new ColumnValue(1), new Constant(1L))));
        assertThat(single("EVALUATE TOPN(2, ADDCOLUMNS(VALUES('Product'[Category]), \"S\", [Sales Amount]), [S])"))
                .isEqualTo(new TopN(withS, 2, List.of(new SortKey(1, false))));
    }

    @Test
    void distinctOfFilterIsTheFilter() throws Exception {
        TablePlan filter = single("EVALUATE FILTER('Product', 'Product'[Category] = \"Bikes\")");
        assertThat(single("EVALUATE DISTINCT(FILTER('Product', 'Product'[Category] = \"Bikes\"))")).isEqualTo(filter);
    }

    @Test
    void calculateTableAddsItsFiltersToTheGrouping() throws Exception {
        Filter usa = new Filter(new Summarize(List.of(MARKETS), List.of()),
                new Comparison(BooleanOperator.EQUAL, new ColumnValue(0), new Constant("USA")));
        TablePlan expected = new Summarize(List.of(CATEGORY), List.of(new NamedMeasure("S", SALES_AMOUNT)),
                Optional.empty(), Optional.empty(), List.of(usa));
        assertThat(single("EVALUATE CALCULATETABLE(SUMMARIZECOLUMNS('Product'[Category], \"S\", [Sales Amount]), "
                + "'Markets'[Country] = \"USA\")")).isEqualTo(expected);
        assertThat(single("EVALUATE CALCULATETABLE(SUMMARIZECOLUMNS('Product'[Category], \"S\", [Sales Amount]), "
                + "KEEPFILTERS(FILTER(VALUES('Markets'[Country]), 'Markets'[Country] = \"USA\")), "
                + "VALUES('Date'[Year]))")).isEqualTo(expected);
        // the same as the filter tables of SUMMARIZECOLUMNS
        assertThat(single("EVALUATE SUMMARIZECOLUMNS('Product'[Category], "
                + "FILTER(VALUES('Markets'[Country]), 'Markets'[Country] = \"USA\"), \"S\", [Sales Amount])"))
                .isEqualTo(expected);
    }

    @Test
    void calculateTableWithoutFiltersIsItsTable() throws Exception {
        assertThat(single("EVALUATE CALCULATETABLE('Product')"))
                .isEqualTo(new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()));
        assertThat(single("EVALUATE CALCULATETABLE({1}, 'Product'[Category] = \"Bikes\")"))
                .isEqualTo(single("EVALUATE {1}"));
    }

    @Test
    void calculateTableFiltersTheMeasuresOfIteratorsWithin() throws Exception {
        ScalarPlan bikes = new Comparison(BooleanOperator.EQUAL, new ColumnValue(0), new Constant("Bikes"));
        ScalarPlan notUsa = new Not(new InList(new ColumnValue(0), List.of("USA")));
        Summarize byMeasure = new Summarize(List.of(MARKETS), List.of(), Optional.of(
                new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(SALES), new Constant(1L))),
                Optional.empty(), List.of(new Filter(new Summarize(List.of(CATEGORY), List.of()), bikes)));
        assertThat(single("EVALUATE CALCULATETABLE(FILTER(VALUES('Markets'[Country]), [Sales] > 1), "
                + "'Product'[Category] = \"Bikes\")")).isEqualTo(byMeasure);
        assertThat(single("EVALUATE CALCULATETABLE(TOPN(2, ADDCOLUMNS(VALUES('Product'[Category]), "
                + "\"S\", [Sales Amount]), 'Product'[Category]), NOT('Markets'[Country] IN {\"USA\"}))"))
                .isEqualTo(new TopN(new Summarize(List.of(CATEGORY), List.of(), Optional.empty(), Optional.empty(),
                        List.of(new Filter(new Summarize(List.of(MARKETS), List.of()), notUsa)),
                        List.of(new NamedMeasure("S", SALES_AMOUNT))), 2, List.of(new SortKey(0, false))));
    }

    @Test
    void calculateTableOfValuesFilteredOnItsHierarchy() throws Exception {
        assertThat(single("EVALUATE CALCULATETABLE(VALUES('Product'[Subcategory]), "
                + "'Product'[Category] = \"Bikes\" || 'Product'[Category] = \"Clothes\")"))
                .isEqualTo(new Summarize(List.of(SUBCATEGORY), List.of(), Optional.empty(), Optional.empty(),
                        List.of(new Filter(new Summarize(List.of(CATEGORY), List.of()), new Logical(LogicalOperator.OR,
                                new Comparison(BooleanOperator.EQUAL, new ColumnValue(0), new Constant("Bikes")),
                                new Comparison(BooleanOperator.EQUAL, new ColumnValue(0), new Constant("Clothes")))))));
    }

    @Test
    void comparisonsAndLogicOfConstants() throws Exception {
        ConstantTable table = (ConstantTable) single("EVALUATE ROW(\"lt\", 1 < 2.5, \"case\", \"a\" = \"A\", "
                + "\"blankZero\", BLANK() = 0, \"blankText\", BLANK() < \"a\", \"inStrict\", BLANK() IN {0}, "
                + "\"in\", 2 IN {1, 2.0}, \"and\", TRUE && BLANK(), \"or\", FALSE || 1, "
                + "\"date\", dt\"2020-01-01\" < dt\"2021-01-01\")");
        assertThat(table.rows()).containsExactly(List.of(true, true, true, true, false, true, false, true, true));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { //
            "EVALUATE 'Nope'|the table 'Nope' does not exist", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Color])|the column 'Product'[Color] does not exist", //
            "EVALUATE ROW(\"x\", [Profit])|the measure [Profit] does not exist", //
            "EVALUATE {@missing}|the parameter @missing has no value", //
            "EVALUATE {1} ORDER BY [Other]|ORDER BY [Other]: no such column in the result", //
            "DEFINE MEASURE 'Product'[M] = 1 EVALUATE {1}|DEFINE MEASURE is not supported yet", //
            "EVALUATE FILTER('Product')|FILTER takes a table and a condition", //
            "EVALUATE FILTER('Product', 'Date'[Year] = \"2020\")|the column Date[Year] is not in the table the condition is computed on", //
            "EVALUATE FILTER('Product', [Other] = 1)|the column [Other] is not in the table the condition is computed on", //
            "EVALUATE FILTER({1}, [Sales Amount] > 1)|FILTER of a table constructor by a measure is not supported yet", //
            "EVALUATE FILTER(ROW(\"S\", [Sales Amount]), [Unit Sales] > 1)|FILTER by a measure of ROW is not supported yet", //
            "EVALUATE FILTER('Product', [Sales Amount] IN {1, 2})|FILTER by a condition joining measures with columns other than grouped by and compared with text, with IN of other than text, BLANK or dates other than by && is not supported yet", //
            "EVALUATE FILTER('Product', 'Product'[Category] IN VALUES('Product'[Category]))|IN an expression of kind FunctionCall is not supported yet", //
            "EVALUATE FILTER({1}, [Value] = \"a\")|values of type INTEGER cannot be compared with values of type STRING", //
            "EVALUATE FILTER({\"a\"}, [Value])|FILTER cannot convert the value 'a' of type STRING to TRUE/FALSE", //
            "EVALUATE {'Product'[Category]}|an expression of kind Identifier where a constant is expected is not supported yet", //
            "EVALUATE DISTINCT('Product', 'Date')|DISTINCT takes one column or table", //
            "EVALUATE DISTINCT('Product'[Color])|the column 'Product'[Color] does not exist", //
            "EVALUATE DISTINCT('Store')|columns of several hierarchies of the table 'Store' without a measure is not supported yet", //
            "EVALUATE VALUES()|VALUES takes one column or table name", //
            "EVALUATE VALUES('Product'[Color])|the column 'Product'[Color] does not exist", //
            "EVALUATE VALUES('Nope')|the table 'Nope' does not exist", //
            "EVALUATE VALUES({1, 2})|VALUES takes a column or table name, not an expression of kind TableConstructor", //
            "EVALUATE TOPN(1, 'Product')|TOPN takes a number of rows, a table and expressions to order by", //
            "EVALUATE TOPN(\"x\", 'Product', 'Product'[Category])|TOPN takes a number of rows", //
            "EVALUATE TOPN(1, 'Product', 'Date'[Year])|the column Date[Year] is not in the table the condition is computed on", //
            "EVALUATE TOPN(1, 'Product', [Sales Amount], 'Product'[Category])|TOPN by a measure and other expressions is not supported yet", //
            "EVALUATE TOPN(1, {1}, [Sales Amount])|TOPN by a measure of other than a grouping of columns, e.g. VALUES is not supported yet", //
            "EVALUATE TOPN(1, FILTER('Product', 'Product'[Category] = \"Bikes\"), [Sales Amount])|TOPN by a measure of other than a grouping of columns, e.g. VALUES is not supported yet", //
            "EVALUATE FILTER(TOPN(1, 'Product', [Sales Amount]), [Unit Sales] > 1)|FILTER of TOPN by a measure is not supported yet", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Category], \"x\", [Sales Amount] IN {1})|SUMMARIZECOLUMNS with a measure in an expression with IN, BLANK or dates is not supported yet", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Category], \"x\", 1)|SUMMARIZECOLUMNS with an expression of kind NumericLiteral of no measure is not supported yet", //
            "EVALUATE ROW(\"a\", 1, \"b\", ISBLANK([Sales Amount]))|ROW mixing measures and other expressions is not supported yet", //
            "EVALUATE KEEPFILTERS('Product')|KEEPFILTERS can only be used as a filter table, e.g. of SUMMARIZECOLUMNS, or as the table FILTER, TOPN or ADDCOLUMNS iterates", //
            "EVALUATE DISTINCT(KEEPFILTERS(VALUES('Product'[Category])))|KEEPFILTERS can only be used as a filter table, e.g. of SUMMARIZECOLUMNS, or as the table FILTER, TOPN or ADDCOLUMNS iterates", //
            "EVALUATE FILTER(KEEPFILTERS('Product', 'Date'), TRUE)|KEEPFILTERS takes one table", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Category], KEEPFILTERS(), \"S\", [Sales Amount])|KEEPFILTERS takes one table", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Category], {1}, \"S\", [Sales Amount])|SUMMARIZECOLUMNS with a filter table of kind ConstantTable or with conditions other than on the text of columns and on measures is not supported yet", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Category], FILTER(VALUES('Markets'[Country]), 'Markets'[Country] = 1), \"S\", [Sales Amount])|SUMMARIZECOLUMNS with a filter table of kind Filter or with conditions other than on the text of columns and on measures is not supported yet", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Category], FILTER(VALUES('Product'[Subcategory]), 'Product'[Subcategory] = \"Road\"), \"S\", [Sales Amount])|a filter table on Product[Subcategory], deeper than SUMMARIZECOLUMNS groups by, with measures other than stored ones or on several hierarchies is not supported yet", //
            "EVALUATE SUMMARIZECOLUMNS('Product'[Category], FILTER(SUMMARIZECOLUMNS('Product'[Category], 'Markets'[Country]), 'Markets'[Country] = \"USA\"), \"S\", [Sales Amount])|a filter table on hierarchies grouped by and others is not supported yet", //
            "EVALUATE FILTER(SUMMARIZECOLUMNS('Product'[Category], FILTER(VALUES('Markets'[Country]), [Sales] > 1), \"S\", [Sales Amount]), [Unit Sales] > 1)|FILTER by a measure of SUMMARIZECOLUMNS with filter tables is not supported yet", //
            "EVALUATE CALCULATETABLE('Product', 1 = 1)|a condition filtering CALCULATETABLE must refer to a column", //
            "EVALUATE CALCULATETABLE('Product', 'Markets'[Country] = \"USA\" && 'Date'[Year] = \"2020\")|a condition filtering CALCULATETABLE must refer to columns of one table", //
            "EVALUATE CALCULATETABLE('Product', 'Markets'[Country] = 1)|CALCULATETABLE with a condition other than on the text of columns is not supported yet", //
            "EVALUATE CALCULATETABLE('Product', [Sales Amount] > 1)|a condition filtering CALCULATETABLE must refer to a column", //
            "EVALUATE CALCULATETABLE('Product', {1})|CALCULATETABLE with a filter table of kind ConstantTable or with conditions other than on the text of columns and on measures is not supported yet", //
            "EVALUATE CALCULATETABLE('Product', ALL('Product'))|the table function ALL is not supported yet", //
            "EVALUATE CALCULATETABLE(CALCULATETABLE('Product', 'Markets'[Country] = \"USA\"), 'Markets'[Country] = \"France\")|CALCULATETABLE filtering Markets[Country] of a table filtered on it already is not supported yet", //
            "EVALUATE CALCULATETABLE(ROW(\"S\", [Sales]), FILTER(VALUES('Markets'[Country]), [Sales] > 1), 'Date'[Year] = \"2020\")|CALCULATETABLE with several filters, of them one by a measure is not supported yet", //
            "EVALUATE CALCULATETABLE(SUMMARIZECOLUMNS('Product'[Category], TOPN(3, VALUES('Markets'[Country]), [Sales]), \"S\", [Sales]), 'Date'[Year] = \"2020\")|CALCULATETABLE with several filters, of them one by a measure is not supported yet", //
            "EVALUATE CALCULATETABLE(VALUES('Store'[City]), 'Store'[Type] = \"Big\")|CALCULATETABLE of columns without measures filtered on other hierarchies of their table is not supported yet", //
            "EVALUATE CALCULATETABLE(FILTER(VALUES('Product'[Category]), [Sales] > 1), 'Product'[Subcategory] = \"Road\")|a filter table on Product[Subcategory], deeper than SUMMARIZECOLUMNS groups by, with measures other than stored ones or on several hierarchies is not supported yet", //
            "EVALUATE FILTER(CALCULATETABLE(VALUES('Product'[Category]), 'Markets'[Country] = \"USA\"), [Sales] > 1)|FILTER by a measure of SUMMARIZECOLUMNS with filter tables is not supported yet", //
            "EVALUATE ADDCOLUMNS('Product')|ADDCOLUMNS takes a table and pairs of a name and an expression", //
            "EVALUATE ADDCOLUMNS('Product', \"x\")|ADDCOLUMNS takes a table and pairs of a name and an expression", //
            "EVALUATE ADDCOLUMNS('Product', \"category\", 1)|ADDCOLUMNS: the column [category] exists already", //
            "EVALUATE GENERATE(VALUES('Product'[Category]))|GENERATE takes two tables", //
            "EVALUATE SUMMARIZE('Product')|SUMMARIZE takes a table, columns and pairs of a name and an expression", //
            "EVALUATE SUMMARIZE('Product', 'Product'[Category], \"x\")|SUMMARIZE takes pairs of a name and an expression after its columns", //
            "EVALUATE SUMMARIZE('Product', 'Markets'[Country])|SUMMARIZE: the column Markets[Country] is not of its table", //
            "EVALUATE SUMMARIZE(VALUES('Product'[Category]), 'Product'[Subcategory])|SUMMARIZE: the column Product[Subcategory] is not of its table", //
            "EVALUATE SUMMARIZE('Product', ROLLUPGROUP('Product'[Category]))|SUMMARIZE with ROLLUPGROUP is not supported yet", //
            "EVALUATE SUMMARIZE('Product', ROLLUP(ROLLUPGROUP('Product'[Category])))|SUMMARIZE with ROLLUPGROUP is not supported yet", //
            "EVALUATE SUMMARIZE('Product', ROLLUP('Product'[Category]), 'Product'[Subcategory])|SUMMARIZE with columns after ROLLUP is not supported yet", //
            "EVALUATE SUMMARIZE('Product', 'Product'[Category], \"s\", ISSUBTOTAL('Date'[Year]))|ISSUBTOTAL: SUMMARIZE does not group by Date[Year]", //
            "EVALUATE SUMMARIZE('Product', 'Product'[Category], \"x\", 1)|SUMMARIZE with an expression of kind NumericLiteral of no measure is not supported yet", //
            "EVALUATE SUMMARIZE('Product', 'Product'[Category], \"x\", [Sales], \"X\", [Sales])|SUMMARIZE: the column [X] exists already", //
            "EVALUATE SUMMARIZE(FILTER(SUMMARIZECOLUMNS('Product'[Category], 'Markets'[Country]), [Sales] > 1), 'Markets'[Country])|SUMMARIZE by some of the columns of FILTER by measures, other than stored ones not BLANK is not supported yet", //
            "EVALUATE SAMPLE(3, VALUES('Markets'[Country]))|SAMPLE takes a number of rows, a table and expressions to order by", //
            "EVALUATE SAMPLE(3, VALUES('Markets'[Country]), [Sales])|SAMPLE by a measure is not supported yet", //
            "EVALUATE GENERATE(VALUES('Product'[Category]), CALCULATETABLE(VALUES('Date'[Year]), 'Markets'[Country] = \"USA\"))|GENERATE with a second table other than a grouping of columns and measures, e.g. VALUES, TOPN or FILTER by a measure, SUMMARIZE, SUMMARIZECOLUMNS without filter tables or ROW, or TOPN, FILTER or ADDCOLUMNS of one is not supported yet", //
            "EVALUATE GENERATE(SUMMARIZECOLUMNS('Product'[Category], \"S\", [Sales]), VALUES('Date'[Year]))|GENERATE of a first table of kind Summarize, with measures or with conditions other than on the text of columns and on measures is not supported yet", //
            "EVALUATE GENERATE({1}, VALUES('Date'[Year]))|GENERATE of a first table of kind ConstantTable, with measures or with conditions other than on the text of columns and on measures is not supported yet", //
            "EVALUATE ADDCOLUMNS({1}, \"a\", 1, \"A\", 2)|ADDCOLUMNS: the column [A] exists already", //
            "EVALUATE ADDCOLUMNS({1}, \"S\", [Sales Amount])|ADDCOLUMNS of a table constructor by a measure is not supported yet", //
            "EVALUATE ADDCOLUMNS(ROW(\"a\", [Sales Amount]), \"b\", [Unit Sales])|ADDCOLUMNS by a measure of ROW is not supported yet", //
            "EVALUATE ADDCOLUMNS('Product', \"x\", [Sales Amount] > 1 && 'Product'[Category] = \"Bikes\")|ADDCOLUMNS with a measure in an expression with columns, IN, BLANK or dates is not supported yet", //
            "EVALUATE ADDCOLUMNS(ADDCOLUMNS('Product', \"a\", 1), \"S\", [Sales Amount])|ADDCOLUMNS by a measure of ADDCOLUMNS computing columns is not supported yet", //
            "EVALUATE ADDCOLUMNS(SUMMARIZECOLUMNS('Product'[Category], FILTER(VALUES('Markets'[Country]), [Sales] > 1), \"S\", [Sales Amount]), \"U\", [Unit Sales])|ADDCOLUMNS by a measure of SUMMARIZECOLUMNS with filter tables is not supported yet", //
            "EVALUATE ADDCOLUMNS({\"a\"}, \"n\", NOT([Value]))|NOT cannot convert the value 'a' of type STRING to TRUE/FALSE", //
            "EVALUATE {ISBLANK(1, 2)}|ISBLANK takes one value", //
            "EVALUATE {ISBLANK()}|ISBLANK takes one value", //
            "EVALUATE {ISBLANK(@missing)}|the parameter @missing has no value", //
            "EVALUATE {NOT()}|NOT takes one value", //
            "EVALUATE {NOT(TRUE, FALSE)}|NOT takes one value", //
            "EVALUATE {NOT(\"x\")}|NOT cannot convert the value 'x' of type STRING to TRUE/FALSE", //
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
