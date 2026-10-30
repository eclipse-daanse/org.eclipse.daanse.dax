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
package org.eclipse.daanse.dax.engine.impl.mdx;

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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.TestModel;
import org.eclipse.daanse.dax.engine.impl.model.MeasureDecomposition;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;
import org.eclipse.daanse.dax.engine.impl.model.ModelMeasure;
import org.eclipse.daanse.dax.engine.impl.model.ModelTable;
import org.eclipse.daanse.dax.engine.impl.model.TabularModel;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource.CellValue;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource.GroupValue;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource.MemberName;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource.MemberProperty;
import org.eclipse.daanse.dax.engine.impl.plan.AddColumns;
import org.eclipse.daanse.dax.engine.impl.plan.Binder;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan.SortKey;
import org.eclipse.daanse.dax.engine.impl.plan.Generate;
import org.eclipse.daanse.dax.engine.impl.plan.NamedMeasure;
import org.eclipse.daanse.dax.engine.impl.plan.QueryPlan;
import org.eclipse.daanse.dax.engine.impl.plan.Rollup;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Comparison;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Constant;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.IsBlank;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Logical;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.MeasureValue;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Not;
import org.eclipse.daanse.dax.engine.impl.plan.Summarize;
import org.eclipse.daanse.dax.engine.impl.plan.TopN;
import org.eclipse.daanse.dax.model.api.expression.BooleanExpression.BooleanOperator;
import org.eclipse.daanse.dax.model.api.expression.LogicalExpression.LogicalOperator;
import org.eclipse.daanse.dax.parser.ccc.CCCDaxParserProvider;
import org.junit.jupiter.api.Test;

class MdxGeneratorTest {

    @Test
    void measuresByColumnsOfTwoHierarchies() {
        MdxQuery query = MdxGenerator.summarize("[Sales]", new Summarize(List.of(CATEGORY, YEAR),
                List.of(new NamedMeasure("S", SALES_AMOUNT), new NamedMeasure("U", UNIT_SALES))));
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales Amount], [Measures].[Unit Sales]} ON COLUMNS, "
                + "NON EMPTY CrossJoin([Product].[Category].Members, [Date].[Year].Members) ON ROWS FROM [Sales]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(1, 1), new CellValue(0),
                new CellValue(1));
        assertThat(query.rows()).isTrue();
    }

    @Test
    void onlyTheDeepestLevelOfAHierarchyGoesOnTheAxis() {
        MdxQuery query = MdxGenerator.summarize("[Sales]", new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()));
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, [Product].[Subcategory].Members ON ROWS FROM [Sales]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(0, 2));
    }

    @Test
    void measuresAloneHaveNoRowsAxis() {
        MdxQuery query = MdxGenerator.summarize("[Sales]",
                new Summarize(List.of(), List.of(new NamedMeasure("S", SALES_AMOUNT))));
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales Amount]} ON COLUMNS FROM [Sales]");
        assertThat(query.rows()).isFalse();
    }

    @Test
    void r10FilterBecomesFilterSet() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE FILTER(VALUES('Markets'[Country]), [Sales] > 100000)").parseDaxStatement());
        Summarize summarize = (Summarize) plan.evaluates().get(0).table();
        assertThat(summarize).isEqualTo(new Summarize(List.of(MARKETS), List.of(), Optional.of(
                new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(SALES), new Constant(100000L)))));

        MdxQuery query = MdxGenerator.summarize("[SteelWheelsSales]", summarize);
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, "
                + "Filter([Markets].[Country].Members, [Measures].[Sales] > 100000) ON ROWS FROM [SteelWheelsSales]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1));
    }

    @Test
    void currencyOfTextIsANumberTheCubeCompares() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE FILTER(VALUES('Markets'[Country]), [Sales] >= CURRENCY(\"442\"))")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[SteelWheelsSales]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, "
                + "Filter([Markets].[Country].Members, [Measures].[Sales] >= 442.0000) ON ROWS FROM [SteelWheelsSales]");
    }

    @Test
    void currencyOfMeasureIsRoundedByTheCube() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE FILTER(VALUES('Markets'[Country]), CURRENCY([Sales]) > 100)")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[SteelWheelsSales]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, Filter([Markets].[Country].Members, "
                + "Round(CDbl([Measures].[Sales]), 4) > 100) ON ROWS FROM [SteelWheelsSales]");
    }

    @Test
    void topNByMeasureBecomesTopCount() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE TOPN(10, VALUES('Markets'[Country]), [Sales])").parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[SteelWheelsSales]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, "
                + "TopCount(NonEmpty([Markets].[Country].Members, {[Measures].[Sales]}), 10, [Measures].[Sales]) ON ROWS "
                + "FROM [SteelWheelsSales]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1));
    }

    @Test
    void ascendingTopAfterTheConditionWithMeasures() {
        MdxQuery query = MdxGenerator.summarize("[Sales]", new Summarize(List.of(CATEGORY),
                List.of(new NamedMeasure("U", UNIT_SALES)),
                Optional.of(new Comparison(BooleanOperator.GREATER_THAN, new MeasureValue(SALES), new Constant(1L))),
                Optional.of(new Summarize.Top(3, SALES_AMOUNT, true))));
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Unit Sales]} ON COLUMNS, NON EMPTY BottomCount(NonEmpty("
                + "Filter([Product].[Category].Members, [Measures].[Sales] > 1), {[Measures].[Sales Amount]}), 3, "
                + "[Measures].[Sales Amount]) ON ROWS FROM [Sales]");
    }

    @Test
    void isBlankOfMeasureIsIsEmptyOfCalculatedMember() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser(
                "EVALUATE SUMMARIZECOLUMNS('Markets'[Country], \"S\", [Sales], \"No Sales\", ISBLANK([Sales]))")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[SteelWheelsSales]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("WITH MEMBER [Measures].[DAX No Sales] AS IsEmpty([Measures].[Sales]) "
                + "SELECT {[Measures].[Sales], [Measures].[DAX No Sales]} ON COLUMNS, "
                + "NON EMPTY [Markets].[Country].Members ON ROWS FROM [SteelWheelsSales]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new CellValue(0), new CellValue(1));
    }

    @Test
    void rowOfIsBlankOfMeasures() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE ROW(\"a\", ISBLANK([Sales]), \"b\", NOT(ISBLANK('Measures'[Unit Sales])))")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[Sales]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("WITH MEMBER [Measures].[DAX a] AS IsEmpty([Measures].[Sales]) "
                + "MEMBER [Measures].[DAX b] AS NOT IsEmpty([Measures].[Unit Sales]) "
                + "SELECT {[Measures].[DAX a], [Measures].[DAX b]} ON COLUMNS FROM [Sales]");
        assertThat(query.rows()).isFalse();
    }

    private static String mdx(String dax) throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of())
                .bind(new CCCDaxParserProvider().newParser(dax).parseDaxStatement());
        return MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text();
    }

    @Test
    void filterTableOnOtherHierarchyIsTheSlicer() throws Exception {
        assertThat(mdx("EVALUATE SUMMARIZECOLUMNS('Product'[Category], KEEPFILTERS(FILTER(VALUES('Markets'[Country]), "
                + "'Markets'[Country] IN {\"USA\", \"France\"})), \"S\", [Sales])"))
                .isEqualTo(slicerMember("[Markets]", "Filter([Markets].[Country].Members, "
                        + "(UCase([Markets].CurrentMember.Name) = \"USA\" "
                        + "OR UCase([Markets].CurrentMember.Name) = \"FRANCE\"))",
                        "SELECT {[Measures].[Sales]} ON COLUMNS, NON EMPTY [Product].[Category].Members ON ROWS FROM [C]"));
    }

    @Test
    void filterTableOnHierarchyGroupedByKeepsTheRows() throws Exception {
        assertThat(mdx("EVALUATE SUMMARIZECOLUMNS('Product'[Subcategory], "
                + "FILTER(VALUES('Product'[Category]), 'Product'[Category] = \"Bikes\"), \"S\", [Sales Amount])"))
                .isEqualTo("SELECT {[Measures].[Sales Amount]} ON COLUMNS, NON EMPTY Exists([Product].[Subcategory].Members, "
                        + "Filter([Product].[Category].Members, UCase([Product].CurrentMember.Name) = \"BIKES\")) "
                        + "ON ROWS FROM [C]");
    }

    @Test
    void filterTablesByMeasuresAndAncestors() throws Exception {
        assertThat(mdx("EVALUATE SUMMARIZECOLUMNS('Date'[Year], TOPN(3, VALUES('Markets'[Country]), [Sales]), "
                + "KEEPFILTERS(FILTER('Product', 'Product'[Category] = \"Bikes\" && \"Road\" <> 'Product'[Subcategory])), "
                + "\"S\", [Sales Amount])"))
                .isEqualTo("WITH MEMBER [Markets].[DAX slicer 1] AS Aggregate(TopCount(NonEmpty("
                        + "[Markets].[Country].Members, {[Measures].[Sales]}), 3, [Measures].[Sales])), "
                        + "SOLVE_ORDER = -100 MEMBER [Product].[DAX slicer 2] AS Aggregate(Filter("
                        + "[Product].[Subcategory].Members, "
                        + "(UCase(Ancestor([Product].CurrentMember, [Product].[Category]).Name) = \"BIKES\") "
                        + "AND (\"ROAD\" <> UCase([Product].CurrentMember.Name)))), SOLVE_ORDER = -100 "
                        + "SELECT {[Measures].[Sales Amount]} ON COLUMNS, NON EMPTY [Date].[Year].Members ON ROWS FROM [C] "
                        + "WHERE ([Markets].[DAX slicer 1], [Product].[DAX slicer 2])");
    }

    @Test
    void filterTableByMeasureOrText() throws Exception {
        assertThat(mdx("EVALUATE SUMMARIZECOLUMNS('Product'[Category], KEEPFILTERS(FILTER(VALUES('Markets'[Country]), "
                + "'Markets'[Country] = \"USA\" || [Sales] > 100)), \"S\", [Sales Amount])"))
                .isEqualTo(slicerMember("[Markets]", "Filter([Markets].[Country].Members, "
                        + "(UCase([Markets].CurrentMember.Name) = \"USA\") OR ([Measures].[Sales] > 100))",
                        "SELECT {[Measures].[Sales Amount]} ON COLUMNS, NON EMPTY [Product].[Category].Members "
                                + "ON ROWS FROM [C]"));
    }

    @Test
    void filterTableOfAllValuesFiltersNothing() throws Exception {
        assertThat(mdx("EVALUATE SUMMARIZECOLUMNS('Product'[Category], KEEPFILTERS(VALUES('Markets'[Country])), "
                + "'Markets', \"S\", [Sales])"))
                .isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS, NON EMPTY [Product].[Category].Members ON ROWS FROM [C]");
    }

    @Test
    void calculateTableFiltersTheMeasuresOfItsIterators() throws Exception {
        assertThat(mdx("EVALUATE CALCULATETABLE(FILTER(VALUES('Markets'[Country]), [Sales] > 1), "
                + "'Product'[Category] = \"Bikes\")"))
                .isEqualTo(slicerMember("[Product]", "Filter([Product].[Category].Members, "
                        + "UCase([Product].CurrentMember.Name) = \"BIKES\")",
                        "SELECT {} ON COLUMNS, Filter([Markets].[Country].Members, [Measures].[Sales] > 1) "
                                + "ON ROWS FROM [C]"));
        assertThat(mdx("EVALUATE CALCULATETABLE(VALUES('Product'[Subcategory]), 'Product'[Category] = \"Bikes\")"))
                .isEqualTo("SELECT {} ON COLUMNS, Exists([Product].[Subcategory].Members, "
                        + "Filter([Product].[Category].Members, UCase([Product].CurrentMember.Name) = \"BIKES\")) "
                        + "ON ROWS FROM [C]");
    }

    @Test
    void addedMeasuresKeepTheRows() throws Exception {
        assertThat(mdx("EVALUATE ADDCOLUMNS(VALUES('Markets'[Country]), \"S\", [Sales])"))
                .isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS, [Markets].[Country].Members ON ROWS FROM [C]");
        assertThat(mdx("EVALUATE ADDCOLUMNS(SUMMARIZECOLUMNS('Markets'[Country], \"S\", [Sales]), "
                + "\"Empty\", ISBLANK([Unit Sales]))"))
                .isEqualTo("WITH MEMBER [Measures].[DAX Empty] AS IsEmpty([Measures].[Unit Sales]) "
                        + "SELECT {[Measures].[Sales], [Measures].[DAX Empty]} ON COLUMNS, "
                        + "NonEmpty([Markets].[Country].Members, {[Measures].[Sales]}) ON ROWS FROM [C]");
    }

    @Test
    void summarizeKeepsTheGroupsWhereItsMeasuresAreBlank() throws Exception {
        assertThat(mdx("EVALUATE SUMMARIZE('Markets', 'Markets'[Country], \"S\", [Sales])"))
                .isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS, [Markets].[Country].Members ON ROWS FROM [C]");
    }

    @Test
    void conditionOfSeveralParts() {
        ScalarPlan condition = new Logical(LogicalOperator.OR,
                new Not(new IsBlank(new MeasureValue(UNIT_SALES))),
                new Logical(LogicalOperator.AND,
                        new Comparison(BooleanOperator.LESS_THAN_OR_EQUAL, new MeasureValue(SALES), new Constant(2.5)),
                        new Comparison(BooleanOperator.NOT_EQUAL, new Constant("a\"b"), new Constant(true))));
        assertThat(MdxGenerator.condition(condition)).isEqualTo("NOT IsEmpty([Measures].[Unit Sales]) OR "
                + "(([Measures].[Sales] <= 2.5) AND (\"a\"\"b\" <> TRUE))");
    }

    @Test
    void quotesNames() {
        assertThat(MdxNames.quote("a]b")).isEqualTo("[a]]b]");
    }

    @Test
    void rowOfCalculateSumOfColumnByDottedLevel() throws Exception {
        assertThat(mdx("EVALUATE ROW(\"S\", CALCULATE(SUM('Product'[Product.Category])))"))
                .isEqualTo("WITH MEMBER [Measures].[DAX S] AS Sum(Existing [Product].[Category].Members, "
                        + "IIf(IsNumeric([Product].CurrentMember.Name), CDbl([Product].CurrentMember.Name), NULL)) "
                        + "SELECT {[Measures].[DAX S]} ON COLUMNS FROM [C]");
    }

    @Test
    void countRowsOfTableByGroupsCountsTheMembersOfItsDeepestLevel() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(101, ADDCOLUMNS(KEEPFILTERS(VALUES('Product'[Category])),
                    "M0", CALCULATE(COUNTROWS('Product'))), 'Product'[Category], 1)
                ORDER BY 'Product'[Category]""").parseDaxStatement());
        Summarize summarize = (Summarize) ((TopN) plan.evaluates().get(0).table()).source();
        assertThat(MdxGenerator.summarize("[C]", summarize).text()).isEqualTo("WITH MEMBER [Measures].[DAX M0] AS "
                + "IIf(Count(Existing [Product].[Subcategory].Members) = 0, NULL, "
                + "Count(Existing [Product].[Subcategory].Members)) "
                + "SELECT {[Measures].[DAX M0]} ON COLUMNS, [Product].[Category].Members ON ROWS FROM [C]");
    }

    @Test
    void distinctCountOfColumnByGroups() throws Exception {
        assertThat(mdx("EVALUATE SUMMARIZECOLUMNS('Date'[Year], \"N\", DISTINCTCOUNT('Product'[Subcategory]))"))
                .isEqualTo("WITH MEMBER [Measures].[DAX N] AS IIf(Count(Existing [Product].[Subcategory].Members) = 0, "
                        + "NULL, Count(Existing [Product].[Subcategory].Members)) "
                        + "SELECT {[Measures].[DAX N]} ON COLUMNS, NON EMPTY [Date].[Year].Members ON ROWS FROM [C]");
    }

    @Test
    void generateTopNForEachRow() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE GENERATE(VALUES('Date'[Year]), TOPN(3, VALUES('Markets'[Country]), [Sales]))")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.generate("[C]", (Generate) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, Generate([Date].[Year].Members, "
                + "CrossJoin({[Date].CurrentMember}, TopCount(NonEmpty([Markets].[Country].Members, "
                + "{[Measures].[Sales]}), 3, [Measures].[Sales]))) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(1, 1));
        assertThat(plan.evaluates().get(0).table().columns()).extracting(c -> c.name())
                .containsExactly("Date[Year]", "Markets[Country]");
    }

    @Test
    void generateOfFilteredTableAndGroupingWithMeasures() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser(
                "EVALUATE GENERATE(KEEPFILTERS(FILTER(VALUES('Product'[Category]), 'Product'[Category] = \"Bikes\")), "
                        + "SUMMARIZECOLUMNS('Markets'[Country], 'Date'[Year], \"S\", [Sales]))")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.generate("[C]", (Generate) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS, Generate(Filter("
                + "[Product].[Category].Members, UCase([Product].CurrentMember.Name) = \"BIKES\"), "
                + "CrossJoin({[Product].CurrentMember}, NonEmpty(CrossJoin([Markets].[Country].Members, "
                + "[Date].[Year].Members), {[Measures].[Sales]}))) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(1, 1),
                new MemberName(2, 1), new CellValue(0));
    }

    @Test
    void calculateTableOfGenerateFilteredOnItsFirstTable() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(501, CALCULATETABLE(ADDCOLUMNS(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Product'[Category])),
                        FILTER(KEEPFILTERS(VALUES('Product'[Subcategory])),
                            OR(NOT(ISBLANK('Measures'[Sales])), NOT(ISBLANK('Measures'[Unit Sales])))))),
                        "S", 'Measures'[Sales], "U", 'Measures'[Unit Sales]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Product'[Category])), 'Product'[Category] = "Bikes"))),
                    'Product'[Category], 1, 'Product'[Subcategory], 1)
                ORDER BY 'Product'[Category], 'Product'[Subcategory]
                """).parseDaxStatement());
        TopN topN = (TopN) plan.evaluates().get(0).table();
        MdxQuery query = MdxGenerator.generate("[C]", (Generate) topN.source());
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales], [Measures].[Unit Sales]} ON COLUMNS, "
                + "Generate(Filter([Product].[Category].Members, UCase([Product].CurrentMember.Name) = \"BIKES\"), "
                + "Filter(Descendants([Product].CurrentMember, [Product].[Subcategory]), "
                + "NOT IsEmpty([Measures].[Sales]) OR NOT IsEmpty([Measures].[Unit Sales]))) ON ROWS FROM [C]");
    }

    @Test
    void calculateTableOfGenerateFilteredOnOtherTablesIsTheSlicer() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE CALCULATETABLE(GENERATE(VALUES('Product'[Category]), VALUES('Date'[Year])),
                    FILTER(VALUES('Markets'[Country]), 'Markets'[Country] = "Canada"))
                """).parseDaxStatement());
        // the values of other tables are not filtered by it
        assertThat(MdxGenerator.generate("[C]", (Generate) plan.evaluates().get(0).table()).text()).isEqualTo(
                "SELECT {} ON COLUMNS, Generate([Product].[Category].Members, CrossJoin({[Product].CurrentMember}, "
                        + "[Date].[Year].Members)) ON ROWS FROM [C] WHERE Filter([Markets].[Country].Members, "
                        + "UCase([Markets].CurrentMember.Name) = \"CANADA\")");
    }

    @Test
    void generateOfRowComputesItForEachRow() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE GENERATE(VALUES('Product'[Category]), ROW(\"N\", ISBLANK([Sales])))")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.generate("[C]", (Generate) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("WITH MEMBER [Measures].[DAX N] AS IsEmpty([Measures].[Sales]) "
                + "SELECT {[Measures].[DAX N]} ON COLUMNS, [Product].[Category].Members ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new CellValue(0));
    }

    @Test
    void generateWithoutMeasuresIsASlicerOfItsTuples() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE CALCULATETABLE(ROW("S", [Sales]), KEEPFILTERS(GENERATE(KEEPFILTERS(VALUES('Date'[Year])),
                    FILTER(KEEPFILTERS(VALUES('Markets'[Country])), NOT(ISBLANK([Sales]))))))
                """).parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo(slicerMember("[Date]", "Generate([Date].[Year].Members, "
                + "CrossJoin({[Date].CurrentMember}, Filter([Markets].[Country].Members, "
                + "NOT IsEmpty([Measures].[Sales]))))", "SELECT {[Measures].[Sales]} ON COLUMNS FROM [C]"));
        assertThat(query.sources()).containsExactly(new CellValue(0));
    }

    @Test
    void orFunctionOfMeasuresFiltersTheGroups() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(501, ADDCOLUMNS(KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Markets'[Country])),
                        OR(NOT(ISBLANK('Measures'[Sales])), NOT(ISBLANK('Measures'[Unit Sales]))))),
                    "S", 'Measures'[Sales], "U", 'Measures'[Unit Sales]), 'Markets'[Country], 1)
                ORDER BY 'Markets'[Country]
                """).parseDaxStatement());
        TopN topN = (TopN) plan.evaluates().get(0).table();
        MdxQuery query = MdxGenerator.summarize("[C]", (Summarize) topN.source());
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales], [Measures].[Unit Sales]} ON COLUMNS, "
                + "Filter([Markets].[Country].Members, NOT IsEmpty([Measures].[Sales]) "
                + "OR NOT IsEmpty([Measures].[Unit Sales])) ON ROWS FROM [C]");
        assertThat(topN.columns()).extracting(c -> c.name()).containsExactly("Markets[Country]", "[S]", "[U]");
    }

    @Test
    void generateOfGenerateGoingDeeperInAHierarchyIsASlicer() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE CALCULATETABLE(ROW("S", 'Measures'[Sales], "U", 'Measures'[Unit Sales]),
                    KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(KEEPFILTERS(VALUES('Product'[Category])),
                            VALUES('Date'[Year]))),
                        FILTER(KEEPFILTERS(VALUES('Product'[Subcategory])),
                            OR(NOT(ISBLANK('Measures'[Sales])), NOT(ISBLANK('Measures'[Unit Sales])))))))
                """).parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo(slicerMember("[Product]", "Generate(Generate("
                + "[Product].[Category].Members, CrossJoin({[Product].CurrentMember}, [Date].[Year].Members)), "
                + "CrossJoin({[Date].CurrentMember}, Filter(Descendants([Product].CurrentMember, "
                + "[Product].[Subcategory]), NOT IsEmpty([Measures].[Sales]) "
                + "OR NOT IsEmpty([Measures].[Unit Sales]))))",
                "SELECT {[Measures].[Sales], [Measures].[Unit Sales]} ON COLUMNS FROM [C]"));
    }

    @Test
    void generateGoingDeeperInAHierarchyReadsTheOuterColumnFromTheAncestor() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE GENERATE(VALUES('Date'[Year]), "
                        + "SUMMARIZECOLUMNS('Product'[Category], 'Product'[Subcategory], \"S\", [Sales]))")
                .parseDaxStatement());
        // not deeper: only other hierarchies of the outer table are its own
        assertThat(plan.evaluates().get(0).table().columns()).extracting(c -> c.name())
                .containsExactly("Date[Year]", "Product[Category]", "Product[Subcategory]", "[S]");
        plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE GENERATE(VALUES('Product'[Category]), "
                        + "SUMMARIZECOLUMNS('Product'[Subcategory], 'Date'[Year], \"S\", [Sales]))")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.generate("[C]", (Generate) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS, Generate("
                + "[Product].[Category].Members, NonEmpty(CrossJoin(Descendants([Product].CurrentMember, "
                + "[Product].[Subcategory]), [Date].[Year].Members), {[Measures].[Sales]})) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(0, 2),
                new MemberName(1, 1), new CellValue(0));
    }

    @Test
    void generatesGoingDeeperLevelByLevel() throws Exception {
        ModelColumn product = new ModelColumn("Product", "Name", "[Product]", "[Product].[Name]", 3, DaxType.STRING);
        TabularModel model = new TabularModel("[Sales]",
                List.of(new ModelTable("Product", List.of(CATEGORY, SUBCATEGORY, product)),
                        new ModelTable("Date", List.of(YEAR))),
                List.of(SALES));
        QueryPlan plan = new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE CALCULATETABLE(ROW("S", 'Measures'[Sales]),
                    KEEPFILTERS(GENERATE(
                        KEEPFILTERS(GENERATE(
                            KEEPFILTERS(GENERATE(KEEPFILTERS(VALUES('Date'[Year])), VALUES('Product'[Category]))),
                            VALUES('Product'[Subcategory]))),
                        FILTER(KEEPFILTERS(VALUES('Product'[Name])), NOT(ISBLANK('Measures'[Sales]))))))
                """).parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo(slicerMember("[Date]", "Generate(Generate(Generate("
                + "[Date].[Year].Members, CrossJoin({[Date].CurrentMember}, [Product].[Category].Members)), "
                + "CrossJoin({[Date].CurrentMember}, Descendants([Product].CurrentMember, [Product].[Subcategory]))), "
                + "CrossJoin({[Date].CurrentMember}, Filter(Descendants([Product].CurrentMember, [Product].[Name]), "
                + "NOT IsEmpty([Measures].[Sales]))))", "SELECT {[Measures].[Sales]} ON COLUMNS FROM [C]"));
    }

    @Test
    void tuplesOfSeveralHierarchiesAreAMemberOfTheOneWhoseCurrentMemberIsTaken() throws Exception {
        ModelMeasure sales = new ModelMeasure("Store Sales", "[Measures].[Store Sales]", true);
        ModelMeasure cost = new ModelMeasure("Store Cost", "[Measures].[Store Cost]", true);
        String previous = " at [Time].[Time].PrevMember";
        // CoalesceEmpty(([Measures].[Profit], [Time].CurrentMember.PrevMember), [Measures].[Profit])
        ModelMeasure lastPeriod = new ModelMeasure("Profit last Period", "[Measures].[Profit last Period]", false,
                DaxType.DECIMAL, Optional.of(new MeasureDecomposition(
                        Set.of(sales.uniqueName(), cost.uniqueName(), sales.uniqueName() + previous,
                                cost.uniqueName() + previous),
                        new MeasureDecomposition.Any(List.of(new MeasureDecomposition.Atom(sales.uniqueName()),
                                new MeasureDecomposition.Atom(cost.uniqueName()),
                                new MeasureDecomposition.Atom(sales.uniqueName() + previous),
                                new MeasureDecomposition.Atom(cost.uniqueName() + previous))),
                        Set.of("[Time].[Time]"), Set.of("[Time].[Time]"))));
        ModelColumn storeType = new ModelColumn("Store Type", "Store Type.Store Type.Store Type",
                "[Store Type].[Store Type]", "[Store Type].[Store Type].[Store Type]", 1, DaxType.STRING);
        TabularModel model = new TabularModel("[Sales]",
                List.of(new ModelTable("Time", List.of(time("Year", 1), time("Quarter", 2), time("Month", 3))),
                        new ModelTable("Store Type", List.of(storeType))),
                List.of(sales, cost, lastPeriod));
        QueryPlan plan = new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE CALCULATETABLE(ROW("P", 'Measures'[Profit last Period]),
                    KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                                KEEPFILTERS(VALUES('Time'[Time.Time.Year])), VALUES('Time'[Time.Time.Quarter]))),
                            VALUES('Time'[Time.Time.Month]))),
                        FILTER(KEEPFILTERS(VALUES('Store Type'[Store Type.Store Type.Store Type])),
                            NOT(ISBLANK('Measures'[Profit last Period]))))))
                """).parseDaxStatement());
        // several members of [Time] in the slicer would fail its CurrentMember
        assertThat(MdxGenerator.summarize("[Sales]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo(slicerMember("[Time].[Time]", "Generate(Generate(Generate([Time].[Time].[Year].Members, "
                        + "Descendants([Time].[Time].CurrentMember, [Time].[Time].[Quarter])), "
                        + "Descendants([Time].[Time].CurrentMember, [Time].[Time].[Month])), "
                        + "CrossJoin({[Time].[Time].CurrentMember}, Filter([Store Type].[Store Type].[Store Type].Members, "
                        + "NOT IsEmpty([Measures].[Profit last Period]))))",
                        "SELECT {[Measures].[Profit last Period]} ON COLUMNS FROM [Sales]"));
    }

    private static ModelColumn time(String level, int depth) {
        return new ModelColumn("Time", "Time.Time." + level, "[Time].[Time]", "[Time].[Time].[" + level + "]", depth,
                DaxType.STRING);
    }

    @Test
    void generatesOfLevelsNotDeeperTakeTheAncestorOfTheOuterMember() throws Exception {
        ModelColumn birthDate = customer("BirthDate", 1);
        ModelColumn addressLine1 = customer("AddressLine1", 2);
        ModelColumn addressLine2 = customer("AddressLine2", 3);
        ModelColumn commuteDistance = customer("CommuteDistance", 4);
        ModelMeasure sales = new ModelMeasure("FactInternetSales", "[Measures].[FactInternetSales]");
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("DimCustomer",
                List.of(birthDate, addressLine1, addressLine2, commuteDistance))), List.of(sales));
        QueryPlan plan = new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE CALCULATETABLE(ROW("S", 'Measures'[FactInternetSales]),
                    KEEPFILTERS(GENERATE(
                        KEEPFILTERS(GENERATE(
                            KEEPFILTERS(GENERATE(
                                KEEPFILTERS(VALUES('DimCustomer'[DimCustomer.DimCustomer.AddressLine1])),
                                VALUES('DimCustomer'[DimCustomer.DimCustomer.AddressLine2]))),
                            VALUES('DimCustomer'[DimCustomer.DimCustomer.BirthDate]))),
                        FILTER(KEEPFILTERS(VALUES('DimCustomer'[DimCustomer.DimCustomer.CommuteDistance])),
                            NOT(ISBLANK('Measures'[FactInternetSales]))))))
                """).parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo(slicerMember("[DimCustomer].[DimCustomer]",
                "Generate(Generate(Generate([DimCustomer].[DimCustomer].[AddressLine1].Members, "
                + "Descendants([DimCustomer].[DimCustomer].CurrentMember, [DimCustomer].[DimCustomer].[AddressLine2])), "
                + "{[DimCustomer].[DimCustomer].CurrentMember}), "
                + "Filter(Descendants([DimCustomer].[DimCustomer].CurrentMember, "
                + "[DimCustomer].[DimCustomer].[CommuteDistance]), NOT IsEmpty([Measures].[FactInternetSales])))",
                "SELECT {[Measures].[FactInternetSales]} ON COLUMNS FROM [C]"));
    }

    @Test
    void generateOfAShallowerLevelReadsItFromTheAncestor() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider()
                .newParser("EVALUATE GENERATE(VALUES('Product'[Subcategory]), "
                        + "FILTER(VALUES('Product'[Category]), 'Product'[Category] = \"Bikes\" || [Sales] > 1))")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.generate("[C]", (Generate) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, Generate([Product].[Subcategory].Members, "
                + "Filter({[Product].CurrentMember}, (UCase(Ancestor([Product].CurrentMember, [Product].[Category]).Name) "
                + "= \"BIKES\") OR ([Measures].[Sales] > 1))) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 2), new MemberName(0, 1));
    }

    private static ModelColumn customer(String level, int depth) {
        return new ModelColumn("DimCustomer", "DimCustomer.DimCustomer." + level, "[DimCustomer].[DimCustomer]",
                "[DimCustomer].[DimCustomer].[" + level + "]", depth, DaxType.STRING);
    }

    /** Customers: City with the property Population, Name with Gender; and Date */
    private static TabularModel customers() {
        return customers(List.of(SALES));
    }

    private static TabularModel customers(List<ModelMeasure> measures) {
        ModelColumn city = new ModelColumn("Customers", "Customers.Customers.City", "[Customers].[Customers]",
                "[Customers].[Customers].[City]", 1, DaxType.STRING);
        ModelColumn name = new ModelColumn("Customers", "Customers.Customers.Name", "[Customers].[Customers]",
                "[Customers].[Customers].[Name]", 2, DaxType.STRING);
        return new TabularModel("[C]", List.of(new ModelTable("Customers", List.of(city,
                new ModelColumn("Customers", "Customers.Customers.City.Population", city.hierarchy(), city.level(), 1,
                        DaxType.STRING, Optional.of("Population")),
                name, new ModelColumn("Customers", "Customers.Customers.Name.Gender", name.hierarchy(), name.level(), 2,
                        DaxType.STRING, Optional.of("Gender")))),
                new ModelTable("Date", List.of(YEAR))), measures);
    }

    private static QueryPlan bindCustomers(String dax) throws Exception {
        return bindCustomers(customers(), dax);
    }

    private static QueryPlan bindCustomers(TabularModel model, String dax) throws Exception {
        return new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser(dax).parseDaxStatement());
    }

    @Test
    void generateFilteredOnAnotherTableIsComputedInTheSlicer() throws Exception {
        ModelColumn education = new ModelColumn("Education Level", "Education Level.Education Level.Education Level",
                "[Education Level].[Education Level]", "[Education Level].[Education Level].[Education Level]", 1,
                DaxType.STRING);
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers", List.of(
                customerLevel("Country", 1), customerLevel("City", 3), customerLevel("Name", 4))),
                new ModelTable("Education Level", List.of(education))),
                List.of(new ModelMeasure("Profit", "[Measures].[Profit]")));
        QueryPlan plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ADDCOLUMNS(KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        VALUES('Customers'[Customers.Customers.City]))),
                        FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name])),
                            NOT(ISBLANK('Measures'[Profit]))))),
                        "MeasuresProfit", 'Measures'[Profit]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.City])),
                        'Customers'[Customers.Customers.City] = "Albany")),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES(
                            'Education Level'[Education Level.Education Level.Education Level])),
                        'Education Level'[Education Level.Education Level.Education Level] = "Bachelors Degree")))""");
        // the values of the customers are not filtered by the education level, their profit is
        assertThat(MdxGenerator.generate("[C]", (Generate) plan.evaluates().get(0).table()).text()).isEqualTo(
                "WITH MEMBER [Education Level].[Education Level].[DAX slicer 1] AS Aggregate(Filter("
                        + "[Education Level].[Education Level].[Education Level].Members, UCase([Education Level]."
                        + "[Education Level].CurrentMember.Name) = \"BACHELORS DEGREE\")), SOLVE_ORDER = -100 "
                        + "SELECT {[Measures].[Profit]} ON COLUMNS, Generate(Filter(Generate("
                        + "[Customers].[Customers].[Country].Members, Descendants([Customers].[Customers]."
                        + "CurrentMember, [Customers].[Customers].[City])), UCase([Customers].[Customers]."
                        + "CurrentMember.Name) = \"ALBANY\"), Filter(Descendants([Customers].[Customers]."
                        + "CurrentMember, [Customers].[Customers].[Name]), NOT IsEmpty([Measures].[Profit]))) "
                        + "ON ROWS FROM [C] WHERE ([Education Level].[Education Level].[DAX slicer 1])");
    }

    @Test
    void generatesOfFiltersKeepingWhereTheStoredMeasuresComputedAreNotBlankAreDropped() throws Exception {
        ModelMeasure count = new ModelMeasure("Customer Count", "[Measures].[Customer Count]", true);
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers", List.of(
                customerLevel("Country", 1), customerLevel("State Province", 2), customerLevel("City", 3),
                customerLevel("Name", 4)))), List.of(count, new ModelMeasure("Calculated", "[Measures].[Calculated]")));
        String generate = """
                KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                    KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                    VALUES('Customers'[Customers.Customers.State Province]))),
                    VALUES('Customers'[Customers.Customers.City]))),
                    FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name])),
                        NOT(ISBLANK('Measures'[%s])))))""";
        // the total: in the slicer the cube would aggregate each customer kept, too many of them
        QueryPlan plan = bindCustomers(model, "EVALUATE CALCULATETABLE(ROW(\"MeasuresCustomerCount\", "
                + "'Measures'[Customer Count]), " + generate.formatted("Customer Count") + ")");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("SELECT {[Measures].[Customer Count]} ON COLUMNS FROM [C]");
        // a calculated measure may change; it may take the current customer, one member of the slicer
        plan = bindCustomers(model, "EVALUATE CALCULATETABLE(ROW(\"C\", 'Measures'[Calculated]), "
                + generate.formatted("Calculated") + ")");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .startsWith("WITH MEMBER [Customers].[Customers].[DAX slicer 1] AS Aggregate(Generate(")
                .endsWith(", SOLVE_ORDER = -100 SELECT {[Measures].[Calculated]} ON COLUMNS FROM [C] "
                        + "WHERE ([Customers].[Customers].[DAX slicer 1])");

        // the rows: the customers on the axis, aggregated by none; the outer ones without any left out first
        plan = bindCustomers(model, "EVALUATE TOPN(501, ADDCOLUMNS(" + generate.formatted("Customer Count")
                + """
                , "MeasuresCustomerCount", 'Measures'[Customer Count]),
                    'Customers'[Customers.Customers.Country], 1, 'Customers'[Customers.Customers.State Province], 1,
                    'Customers'[Customers.Customers.City], 1, 'Customers'[Customers.Customers.Name], 1)
                ORDER BY 'Customers'[Customers.Customers.Country], 'Customers'[Customers.Customers.State Province],
                    'Customers'[Customers.Customers.City], 'Customers'[Customers.Customers.Name]""");
        MdxQuery query = MdxGenerator.generate("[C]", (Generate) ((TopN) plan.evaluates().get(0).table()).source());
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Customer Count]} ON COLUMNS, "
                + "Generate(Generate(Generate([Customers].[Customers].[Country].Members, "
                + "NonEmpty(Descendants([Customers].[Customers].CurrentMember, "
                + "[Customers].[Customers].[State Province]), {[Measures].[Customer Count]})), "
                + "NonEmpty(Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[City]), "
                + "{[Measures].[Customer Count]})), "
                + "Filter(Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[Name]), "
                + "NOT IsEmpty([Measures].[Customer Count]))) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(0, 2), new MemberName(0, 3),
                new MemberName(0, 4), new CellValue(0));
    }

    @Test
    void generatesOfFiltersKeepingWhereMeasuresComputedOfStoredOnesAreNotBlankAreDropped() throws Exception {
        ModelMeasure count = new ModelMeasure("Customer Count", "[Measures].[Customer Count]", true);
        ModelMeasure sales = new ModelMeasure("Store Sales", "[Measures].[Store Sales]", true);
        ModelMeasure cost = new ModelMeasure("Store Cost", "[Measures].[Store Cost]", true);
        ModelMeasure profit = decomposed("Profit", any(sales, cost), Set.of());
        // of the previous period, too
        String previous = " at [Time].[Time].PrevMember";
        ModelMeasure growth = decomposed("Profit Growth", new MeasureDecomposition.Any(List.of(any(sales, cost),
                any(sales.uniqueName() + previous, cost.uniqueName() + previous))), Set.of("[Time].[Time]"));
        // of the parent customer
        String parent = " at [Customers].[Customers].Parent";
        ModelMeasure share = decomposed("Share", any(sales.uniqueName() + parent, cost.uniqueName() + parent),
                Set.of("[Customers].[Customers]"));
        ModelMeasure average = new ModelMeasure("Avg Sales", "[Measures].[Avg Sales]");
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers", List.of(
                customerLevel("Country", 1), customerLevel("State Province", 2), customerLevel("City", 3),
                customerLevel("Name", 4)))), List.of(count, sales, cost, profit, growth, share, average));
        String dax = """
                EVALUATE CALCULATETABLE(ROW("P", 'Measures'[%1$s], "C", 'Measures'[Customer Count]),
                    KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        VALUES('Customers'[Customers.Customers.State Province]))),
                        VALUES('Customers'[Customers.Customers.City]))),
                        FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name])), %2$s))))""";
        String both = "OR(NOT(ISBLANK('Measures'[%1$s])), NOT(ISBLANK('Measures'[Customer Count])))";
        // Profit = Store Sales - Store Cost: of the customers where it is not BLANK, the same as of all
        QueryPlan plan = bindCustomers(model, dax.formatted("Profit", both.formatted("Profit")));
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("SELECT {[Measures].[Profit], [Measures].[Customer Count]} ON COLUMNS FROM [C]");
        plan = bindCustomers(model, dax.formatted("Profit Growth", both.formatted("Profit Growth")));
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("SELECT {[Measures].[Profit Growth], [Measures].[Customer Count]} ON COLUMNS FROM [C]");
        // of the customers kept, it is not the same of their parents
        plan = bindCustomers(model, dax.formatted("Share", both.formatted("Share")));
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .startsWith("WITH MEMBER [Customers].[Customers].[DAX slicer 1] AS Aggregate(Generate(")
                .endsWith("SELECT {[Measures].[Share], [Measures].[Customer Count]} ON COLUMNS FROM [C] "
                        + "WHERE ([Customers].[Customers].[DAX slicer 1])");
        // the customers where Customer Count is BLANK are left out of it
        plan = bindCustomers(model, dax.formatted("Profit", "NOT(ISBLANK('Measures'[Profit]))"));
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .startsWith("SELECT {[Measures].[Profit], [Measures].[Customer Count]} ON COLUMNS FROM [C] "
                        + "WHERE Generate(");
        // a quotient may change
        plan = bindCustomers(model, dax.formatted("Avg Sales", both.formatted("Avg Sales")));
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .startsWith("WITH MEMBER [Customers].[Customers].[DAX slicer 1] AS Aggregate(Generate(")
                .endsWith("SELECT {[Measures].[Avg Sales], [Measures].[Customer Count]} ON COLUMNS FROM [C] "
                        + "WHERE ([Customers].[Customers].[DAX slicer 1])");
    }

    @Test
    void generatesOfHierarchiesOfOneTableLeaveOutTheTuplesWithoutStoredMeasuresFirst() throws Exception {
        ModelColumn art = school("Art", "Schule", 1);
        ModelColumn ganztag = school("Ganztag", "Schule", 1);
        String dax = """
                EVALUATE TOPN(501, ADDCOLUMNS(KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Schulen'[Schulen.Art.Schule])), VALUES('Schulen'[Schulen.Ganztag.Schule]))),
                        FILTER(KEEPFILTERS(VALUES('Date'[Year])),
                            OR(NOT(ISBLANK('Measures'[Klassen])), NOT(ISBLANK('Measures'[Schulen])))))),
                    "K", 'Measures'[Klassen], "S", 'Measures'[Schulen]), 'Date'[Year], 1)""";
        // a school of one hierarchy with each of the other: of a stored measure only the pairs of one school
        for (boolean stored : new boolean[] { true, false }) {
            TabularModel model = new TabularModel("[C]",
                    List.of(new ModelTable("Schulen", List.of(art, ganztag)), new ModelTable("Date", List.of(YEAR))),
                    List.of(new ModelMeasure("Klassen", "[Measures].[Klassen]", stored),
                            new ModelMeasure("Schulen", "[Measures].[Schulen]", stored)));
            QueryPlan plan = new Binder(model, Map.of())
                    .bind(new CCCDaxParserProvider().newParser(dax).parseDaxStatement());
            String schools = stored
                    ? "NonEmpty([Schulen].[Ganztag].[Schule].Members, {[Measures].[Klassen], [Measures].[Schulen]})"
                    : "[Schulen].[Ganztag].[Schule].Members";
            assertThat(MdxGenerator.generate("[C]", (Generate) ((TopN) plan.evaluates().get(0).table()).source())
                    .text()).isEqualTo("SELECT {[Measures].[Klassen], [Measures].[Schulen]} ON COLUMNS, "
                            + "Generate(Generate([Schulen].[Art].[Schule].Members, CrossJoin({[Schulen].[Art].CurrentMember}, "
                            + schools + ")), CrossJoin({([Schulen].[Art].CurrentMember, "
                            + "[Schulen].[Ganztag].CurrentMember)}, Filter([Date].[Year].Members, "
                            + "NOT IsEmpty([Measures].[Klassen]) OR NOT IsEmpty([Measures].[Schulen])))) ON ROWS FROM [C]");
        }
    }

    private static ModelColumn school(String hierarchy, String level, int depth) {
        return new ModelColumn("Schulen", "Schulen." + hierarchy + "." + level, "[Schulen].[" + hierarchy + "]",
                "[Schulen].[" + hierarchy + "].[" + level + "]", depth, DaxType.STRING);
    }

    @Test
    void filtersOfNestedCalculateTablesAreKeptTogether() throws Exception {
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers", List.of(
                customerLevel("Country", 1), customerLevel("State Province", 2), customerLevel("City", 3)))),
                List.of(new ModelMeasure("Customer Count", "[Measures].[Customer Count]", true),
                        new ModelMeasure("Calculated", "[Measures].[Calculated]")));
        String dax = """
                EVALUATE CALCULATETABLE(CALCULATETABLE(ROW("M", 'Measures'[%1$s]),
                    KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        VALUES('Customers'[Customers.Customers.State Province]))),
                        FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.City])),
                            NOT(ISBLANK('Measures'[%1$s])))))),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        'Customers'[Customers.Customers.Country] = "USA")))""";
        String usa = "Filter([Customers].[Customers].[Country].Members, "
                + "UCase([Customers].[Customers].CurrentMember.Name) = \"USA\")";
        // the countries kept of the first table of GENERATE
        QueryPlan plan = bindCustomers(model, dax.formatted("Calculated"));
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo(slicerMember("[Customers].[Customers]", "Generate(Generate(" + usa
                        + ", Descendants([Customers].[Customers].CurrentMember, "
                        + "[Customers].[Customers].[State Province])), "
                        + "Filter(Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[City]), "
                        + "NOT IsEmpty([Measures].[Calculated])))",
                        "SELECT {[Measures].[Calculated]} ON COLUMNS FROM [C]"));
        // GENERATE keeping where a stored measure is not BLANK is dropped first
        plan = bindCustomers(model, dax.formatted("Customer Count"));
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("SELECT {[Measures].[Customer Count]} ON COLUMNS FROM [C] WHERE " + usa);
        // the states kept of those GENERATE joins to each country
        String state = dax.substring(0, dax.lastIndexOf("KEEPFILTERS(FILTER(")) + """
                KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.State Province])),
                        'Customers'[Customers.Customers.State Province] = "CA")))""";
        plan = bindCustomers(model, state.formatted("Calculated"));
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo(slicerMember("[Customers].[Customers]", "Generate(Generate("
                        + "[Customers].[Customers].[Country].Members, Filter(Descendants("
                        + "[Customers].[Customers].CurrentMember, [Customers].[Customers].[State Province]), "
                        + "UCase([Customers].[Customers].CurrentMember.Name) = \"CA\")), "
                        + "Filter(Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[City]), "
                        + "NOT IsEmpty([Measures].[Calculated])))",
                        "SELECT {[Measures].[Calculated]} ON COLUMNS FROM [C]"));
        // without KEEPFILTERS the inner filter replaces the outer one on its columns
        plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(CALCULATETABLE(ROW("M", 'Measures'[Calculated]),
                    FILTER(VALUES('Customers'[Customers.Customers.Country]),
                        'Customers'[Customers.Customers.Country] = "France")),
                    'Customers'[Customers.Customers.Country] = "USA")""");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo(slicerMember("[Customers].[Customers]", "Filter([Customers].[Customers].[Country].Members, "
                        + "UCase([Customers].[Customers].CurrentMember.Name) = \"FRANCE\")",
                        "SELECT {[Measures].[Calculated]} ON COLUMNS FROM [C]"));
    }

    @Test
    void membersInTheSlicerOfAHierarchyWhoseCurrentMemberAMeasureMayTakeAreAggregatedByOne() throws Exception {
        // ([Employees].CurrentMember.DataMember, [Measures].[Org Salary]): CurrentMember fails of several
        ModelColumn employee = new ModelColumn("Employees", "Employees.Employees.Employee Id1",
                "[Employees].[Employees1]", "[Employees].[Employees1].[Employee Id1]", 1, DaxType.STRING);
        TabularModel model = new TabularModel("[HR]", List.of(new ModelTable("Employees", List.of(employee))),
                List.of(new ModelMeasure("Employee Salary", "[Measures].[Employee Salary]")));
        QueryPlan plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ROW("MeasuresEmployeeSalary", 'Measures'[Employee Salary]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Employees'[Employees.Employees.Employee Id1])),
                        NOT(ISBLANK('Measures'[Employee Salary])))))""");
        assertThat(MdxGenerator.summarize("[HR]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("WITH MEMBER [Employees].[Employees1].[DAX slicer 1] AS Aggregate(Filter("
                        + "[Employees].[Employees1].[Employee Id1].Members, NOT IsEmpty([Measures].[Employee Salary]))), "
                        + "SOLVE_ORDER = -100 SELECT {[Measures].[Employee Salary]} ON COLUMNS FROM [HR] "
                        + "WHERE ([Employees].[Employees1].[DAX slicer 1])");
    }

    @Test
    void filterByMeasuresOfAnInnerCalculateTableIsComputedWithinTheOuterFilters() throws Exception {
        ModelColumn year = new ModelColumn("Time", "Time.Time.Year", "[Time].[Time]", "[Time].[Time].[Year]", 1,
                DaxType.STRING);
        TabularModel model = new TabularModel("[Sales]", List.of(new ModelTable("Time", List.of(year)),
                new ModelTable("Customers", List.of(customerLevel("Name", 4)))),
                List.of(new ModelMeasure("Profit", "[Measures].[Profit]"),
                        new ModelMeasure("Store Cost", "[Measures].[Store Cost]", true),
                        new ModelMeasure("Unit Sales", "[Measures].[Unit Sales]", true)));
        String dax = """
                EVALUATE CALCULATETABLE(CALCULATETABLE(ROW("M", 'Measures'[%1$s]),
                        KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Time'[Time.Time.Year])),
                            NOT(ISBLANK('Measures'[%2$s]))))),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name])),
                        'Customers'[Customers.Customers.Name] = "A. Catherine Binkley")))""";
        String customer = "MEMBER [Customers].[Customers].[DAX slicer 1] AS Aggregate(Filter("
                + "[Customers].[Customers].[Name].Members, UCase([Customers].[Customers].CurrentMember.Name) = "
                + "\"A. CATHERINE BINKLEY\")), SOLVE_ORDER = -200 ";
        String slicer = " WHERE ([Customers].[Customers].[DAX slicer 1], [Time].[Time].[DAX slicer 2])";
        // the years where the customer's profit is not BLANK: the customer aggregated within them
        QueryPlan plan = bindCustomers(model, dax.formatted("Profit", "Profit"));
        assertThat(MdxGenerator.summarize("[Sales]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("WITH " + customer + "MEMBER [Time].[Time].[DAX slicer 2] AS Aggregate(Filter("
                        + "[Time].[Time].[Year].Members, NOT IsEmpty([Measures].[Profit]))), SOLVE_ORDER = -100 "
                        + "SELECT {[Measures].[Profit]} ON COLUMNS FROM [Sales]" + slicer);
        // of stored measures too, which take no current member
        plan = bindCustomers(model, dax.formatted("Store Cost", "Unit Sales"));
        assertThat(MdxGenerator.summarize("[Sales]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("WITH " + customer + "MEMBER [Time].[Time].[DAX slicer 2] AS Aggregate(Filter("
                        + "[Time].[Time].[Year].Members, NOT IsEmpty([Measures].[Unit Sales]))), SOLVE_ORDER = -100 "
                        + "SELECT {[Measures].[Store Cost]} ON COLUMNS FROM [Sales]" + slicer);
        // beside the other filter it is computed without it
        assertThatThrownBy(() -> bindCustomers(model, """
                EVALUATE CALCULATETABLE(ROW("M", 'Measures'[Profit]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Time'[Time.Time.Year])), NOT(ISBLANK('Measures'[Profit])))),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name])),
                        'Customers'[Customers.Customers.Name] = "A. Catherine Binkley")))"""))
                .hasMessageContaining("CALCULATETABLE with several filters, of them one by a measure");
    }

    @Test
    void filtersOfGenerateOnColumnsOfItsInnerTableRestrictTheInnerTable() throws Exception {
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers", List.of(
                customerLevel("Country", 1), customerLevel("State Province", 2), customerLevel("City", 3)))),
                List.of(new ModelMeasure("Store Cost", "[Measures].[Store Cost]", true)));
        QueryPlan plan = bindCustomers(model, """
                EVALUATE TOPN(501, CALCULATETABLE(ADDCOLUMNS(KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        VALUES('Customers'[Customers.Customers.State Province]))),
                        FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.City])),
                            NOT(ISBLANK('Measures'[Store Cost]))))), "MeasuresStoreCost", 'Measures'[Store Cost]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        OR('Customers'[Customers.Customers.Country] = "Mexico",
                            'Customers'[Customers.Customers.Country] = "USA"))),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.City])),
                        'Customers'[Customers.Customers.City] = "Acapulco"))),
                    'Customers'[Customers.Customers.Country], 1, 'Customers'[Customers.Customers.State Province], 1,
                    'Customers'[Customers.Customers.City], 1)""");
        Generate generate = (Generate) ((TopN) plan.evaluates().get(0).table()).source();
        String country = "UCase(Ancestor([Customers].[Customers].CurrentMember, [Customers].[Customers].[Country]).Name)";
        assertThat(MdxGenerator.generate("[C]", generate).text()).isEqualTo("SELECT {[Measures].[Store Cost]} "
                + "ON COLUMNS, Generate(Filter(Generate([Customers].[Customers].[Country].Members, NonEmpty("
                + "Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[State Province]), "
                + "{[Measures].[Store Cost]})), (" + country + " = \"MEXICO\") OR (" + country + " = \"USA\")), "
                + "Filter(Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[City]), "
                + "(UCase([Customers].[Customers].CurrentMember.Name) = \"ACAPULCO\") "
                + "AND NOT IsEmpty([Measures].[Store Cost]))) ON ROWS FROM [C]");
    }

    @Test
    void filtersOnColumnsOfBothTablesOfAGenerateFilterTableRestrictBoth() throws Exception {
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers", List.of(
                customerLevel("Country", 1), customerLevel("State Province", 2), customerLevel("City", 3)))),
                List.of(new ModelMeasure("Store Cost", "[Measures].[Store Cost]", true),
                        new ModelMeasure("Unit Sales", "[Measures].[Unit Sales]", true)));
        QueryPlan plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(CALCULATETABLE(ROW("MeasuresStoreCost", 'Measures'[Store Cost]),
                        KEEPFILTERS(GENERATE(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                            FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.City])),
                                NOT(ISBLANK('Measures'[Unit Sales])))))),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        'Customers'[Customers.Customers.Country] = "USA")),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.City])),
                        OR('Customers'[Customers.Customers.City] = "Albany",
                            'Customers'[Customers.Customers.City] = "Altadena"))))""");
        String name = "UCase([Customers].[Customers].CurrentMember.Name)";
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("SELECT {[Measures].[Store Cost]} ON COLUMNS FROM [C] WHERE Generate(Filter("
                        + "[Customers].[Customers].[Country].Members, " + name + " = \"USA\"), Filter(Descendants("
                        + "[Customers].[Customers].CurrentMember, [Customers].[Customers].[City]), "
                        + "NOT IsEmpty([Measures].[Unit Sales]) AND ((" + name + " = \"ALBANY\") OR (" + name
                        + " = \"ALTADENA\"))))");
    }

    @Test
    void filtersOfGenerateDeeperThanItsColumnsRestrictItsRowsAndAggregateItsMeasuresOverTheMembersKept()
            throws Exception {
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers", List.of(
                customerLevel("Country", 1), customerLevel("State Province", 2), customerLevel("City", 3)))),
                List.of(new ModelMeasure("Store Cost", "[Measures].[Store Cost]", true)));
        QueryPlan plan = bindCustomers(model, """
                EVALUATE TOPN(501, CALCULATETABLE(ADDCOLUMNS(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.State Province])),
                            NOT(ISBLANK('Measures'[Store Cost]))))), "MeasuresStoreCost", 'Measures'[Store Cost]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.City])),
                        'Customers'[Customers.Customers.City] = "Albany"))),
                    'Customers'[Customers.Customers.Country], 1, 'Customers'[Customers.Customers.State Province], 1)""");
        Generate generate = (Generate) ((TopN) plan.evaluates().get(0).table()).source();
        String albany = "Filter([Customers].[Customers].[City].Members, "
                + "UCase([Customers].[Customers].CurrentMember.Name) = \"ALBANY\")";
        assertThat(MdxGenerator.generate("[C]", generate).text()).isEqualTo("WITH MEMBER [Measures].[DAX in "
                + "filters Store Cost] AS Aggregate(Exists(Descendants([Customers].[Customers].CurrentMember, "
                + "[Customers].[Customers].[City]), " + albany + "), [Measures].[Store Cost]) "
                + "SELECT {[Measures].[DAX in filters Store Cost]} ON COLUMNS, Generate("
                + "[Customers].[Customers].[Country].Members, Filter("
                + "Exists(Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[State Province]"
                + "), " + albany + "), NOT IsEmpty([Measures].[DAX in filters Store Cost]))) ON ROWS FROM [C]");
    }

    @Test
    void calculatedMeasuresFilteredDeeperThanTheGroupsAreComputedOverCalculatedMembersOfThem() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(501, CALCULATETABLE(ADDCOLUMNS(KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Product'[Category])),
                        NOT(ISBLANK('Measures'[Sales Amount])))), "S", 'Measures'[Sales Amount]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Product'[Subcategory])), 'Product'[Subcategory] = "Road"))),
                    'Product'[Category], 1)
                ORDER BY 'Product'[Category]""").parseDaxStatement());
        Summarize summarize = (Summarize) ((TopN) plan.evaluates().get(0).table()).source();
        assertThat(summarize.byFilters()).containsExactly("[Product]");
        // the groups the filter keeps are queried first
        assertThat(MdxGenerator.summarize("[C]", new Summarize(summarize.groupBy(), List.of(), Optional.empty(),
                Optional.empty(), summarize.filters())).text())
                .isEqualTo("SELECT {} ON COLUMNS, Exists([Product].[Category].Members, Filter([Product].[Subcategory]"
                        + ".Members, UCase([Product].CurrentMember.Name) = \"ROAD\")) ON ROWS FROM [C]");
        String road = "Filter([Product].[Subcategory].Members, UCase([Product].CurrentMember.Name) = \"ROAD\")";
        MdxQuery query = MdxGenerator.summarize("[C]", summarize, Map.of("[Product]", List.of(List.of("Bikes"))));
        assertThat(query.text()).isEqualTo("WITH MEMBER [Product].[DAX group 1] AS Aggregate(Exists(" + road
                + ", Filter([Product].[Category].Members, [Product].CurrentMember.Name = \"Bikes\"))) "
                + "SELECT {[Measures].[Sales Amount]} ON COLUMNS, Filter({[Product].[DAX group 1]}, "
                + "NOT IsEmpty([Measures].[Sales Amount])) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new GroupValue(0, Map.of("DAX group 1", "Bikes")),
                new CellValue(0));
    }

    @Test
    void filterByMeasuresWithinOneOnTheTextOfItsColumnKeepsTheValuesOfBoth() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE CALCULATETABLE(CALCULATETABLE(ROW("S", 'Measures'[Sales Amount]),
                        KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Product'[Subcategory])),
                            OR(NOT(ISBLANK('Measures'[Sales Amount])), NOT(ISBLANK('Measures'[Unit Sales])))))),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Product'[Subcategory])),
                        OR('Product'[Subcategory] = "Road", 'Product'[Subcategory] = "Caps"))))""")
                .parseDaxStatement());
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo(slicerMember("[Product]", "Filter("
                        + "[Product].[Subcategory].Members, ((UCase([Product].CurrentMember.Name) = \"ROAD\") "
                        + "OR (UCase([Product].CurrentMember.Name) = \"CAPS\")) AND (NOT IsEmpty([Measures].[Sales Amount]) "
                        + "OR NOT IsEmpty([Measures].[Unit Sales])))",
                        "SELECT {[Measures].[Sales Amount]} ON COLUMNS FROM [C]"));
    }

    /**
     * @return the query of a measure that may take the current member of the
     *         hierarchy, with the set of its members in the slicer: one
     *         calculated member aggregating them
     */
    private static String slicerMember(String hierarchy, String set, String select) {
        String member = hierarchy + ".[DAX slicer 1]";
        return "WITH MEMBER " + member + " AS Aggregate(" + set + "), SOLVE_ORDER = -100 " + select + " WHERE ("
                + member + ")";
    }

    @Test
    void filtersKeepingWhereTheStoredMeasuresComputedOfAreNotBlankAreDropped() throws Exception {
        ModelColumn employee = new ModelColumn("Employees", "Employees.Employees.Employee Id1",
                "[Employees].[Employees1]", "[Employees].[Employees1].[Employee Id1]", 1, DaxType.STRING);
        ModelColumn payType = new ModelColumn("Pay Type", "Pay Type.Pay Type.Pay Type", "[Pay Type].[Pay Type]",
                "[Pay Type].[Pay Type].[Pay Type]", 1, DaxType.STRING);
        ModelMeasure employees = new ModelMeasure("Number of Employees", "[Measures].[Number of Employees]", true);
        ModelMeasure salary = new ModelMeasure("Org Salary", "[Measures].[Org Salary]", true);
        ModelMeasure count = new ModelMeasure("Count", "[Measures].[Count]", true);
        // BLANK where one of them is
        ModelMeasure average = new ModelMeasure("Avg Salary", "[Measures].[Avg Salary]", false, DaxType.DECIMAL,
                Optional.of(new MeasureDecomposition(Set.of(salary.uniqueName(), employees.uniqueName()),
                        new MeasureDecomposition.All(List.of(new MeasureDecomposition.Atom(salary.uniqueName()),
                                new MeasureDecomposition.Atom(employees.uniqueName()))),
                        Set.of(), Set.of())));
        // ([Employees].CurrentMember.DataMember, [Measures].[Org Salary])
        ModelMeasure employeeSalary = new ModelMeasure("Employee Salary", "[Measures].[Employee Salary]", false,
                DaxType.DECIMAL, Optional.of(new MeasureDecomposition(Set.of(salary.uniqueName()),
                        new MeasureDecomposition.Atom(salary.uniqueName() + " at [Employees].[Employees1].DataMember"),
                        Set.of(), Set.of("[Employees].[Employees1]"))));
        TabularModel model = new TabularModel("[HR]", List.of(new ModelTable("Employees", List.of(employee)),
                new ModelTable("Pay Type", List.of(payType))),
                List.of(employees, salary, count, average, employeeSalary));
        String dax = """
                EVALUATE CALCULATETABLE(ROW(%s),
                    KEEPFILTERS(GENERATE(KEEPFILTERS(VALUES('Employees'[Employees.Employees.Employee Id1])),
                        FILTER(KEEPFILTERS(VALUES('Pay Type'[Pay Type.Pay Type.Pay Type])), %s))))""";
        String all = "\"E\", 'Measures'[Number of Employees], \"S\", 'Measures'[Org Salary], \"C\", 'Measures'[Count], "
                + "\"A\", 'Measures'[Avg Salary], \"ES\", 'Measures'[Employee Salary]";
        String condition = "OR(OR(OR(OR(NOT(ISBLANK('Measures'[Number of Employees])), "
                + "NOT(ISBLANK('Measures'[Org Salary]))), NOT(ISBLANK('Measures'[Count]))), "
                + "NOT(ISBLANK('Measures'[Avg Salary]))), NOT(ISBLANK('Measures'[Employee Salary])))";
        String select = "SELECT {[Measures].[Number of Employees], [Measures].[Org Salary], [Measures].[Count], "
                + "[Measures].[Avg Salary], [Measures].[Employee Salary]} ON COLUMNS FROM [HR]";
        // where one of the stored measures is not BLANK
        QueryPlan plan = bindCustomers(model, dax.formatted(all, condition));
        assertThat(MdxGenerator.summarize("[HR]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo(select);

        // the employees where Number of Employees alone is not BLANK are left out of Avg Salary
        plan = bindCustomers(model, dax.formatted("\"A\", 'Measures'[Avg Salary]",
                "NOT(ISBLANK('Measures'[Avg Salary]))"));
        assertThat(MdxGenerator.summarize("[HR]", (Summarize) plan.evaluates().get(0).table()).text())
                .startsWith("SELECT {[Measures].[Avg Salary]} ON COLUMNS FROM [HR] WHERE Generate(");
        // those with Org Salary of their subordinates only, of Employee Salary; it takes the current employee
        plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ROW("ES", 'Measures'[Employee Salary]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Employees'[Employees.Employees.Employee Id1])),
                        NOT(ISBLANK('Measures'[Employee Salary])))))""");
        assertThat(MdxGenerator.summarize("[HR]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("WITH MEMBER [Employees].[Employees1].[DAX slicer 1] AS Aggregate(Filter("
                        + "[Employees].[Employees1].[Employee Id1].Members, NOT IsEmpty([Measures].[Employee Salary]))), "
                        + "SOLVE_ORDER = -100 SELECT {[Measures].[Employee Salary]} ON COLUMNS FROM [HR] "
                        + "WHERE ([Employees].[Employees1].[DAX slicer 1])");
        // of Org Salary too
        plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ROW("ES", 'Measures'[Employee Salary]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Employees'[Employees.Employees.Employee Id1])),
                        OR(NOT(ISBLANK('Measures'[Employee Salary])), NOT(ISBLANK('Measures'[Org Salary]))))))""");
        assertThat(MdxGenerator.summarize("[HR]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("SELECT {[Measures].[Employee Salary]} ON COLUMNS FROM [HR]");
    }

    private static ModelMeasure decomposed(String name, MeasureDecomposition.Support support, Set<String> shifted) {
        Set<String> atoms = new LinkedHashSet<>();
        collectAtoms(support, atoms);
        return new ModelMeasure(name, "[Measures]." + MdxNames.quote(name), false, DaxType.DECIMAL,
                Optional.of(new MeasureDecomposition(atoms, support, shifted, shifted)));
    }

    private static void collectAtoms(MeasureDecomposition.Support support, Set<String> atoms) {
        switch (support) {
        case MeasureDecomposition.Atom atom -> atoms.add(atom.key());
        case MeasureDecomposition.Any any -> any.supports().forEach(s -> collectAtoms(s, atoms));
        case MeasureDecomposition.All all -> all.supports().forEach(s -> collectAtoms(s, atoms));
        }
    }

    private static MeasureDecomposition.Support any(ModelMeasure... measures) {
        return any(Arrays.stream(measures).map(ModelMeasure::uniqueName).toArray(String[]::new));
    }

    private static MeasureDecomposition.Support any(String... atoms) {
        return new MeasureDecomposition.Any(
                Arrays.stream(atoms).<MeasureDecomposition.Support>map(MeasureDecomposition.Atom::new).toList());
    }

    private static ModelColumn customerLevel(String level, int depth) {
        return new ModelColumn("Customers", "Customers.Customers." + level, "[Customers].[Customers]",
                "[Customers].[Customers].[" + level + "]", depth, DaxType.STRING);
    }

    @Test
    void filtersKeepingWhereTheStoredMeasuresComputedAreNotBlankAreDropped() throws Exception {
        TabularModel model = customers(List.of(new ModelMeasure("Customer Count", "[Measures].[Customer Count]", true),
                new ModelMeasure("Unit Sales", "[Measures].[Unit Sales]", true), SALES));
        // in the slicer the cube would aggregate each customer kept, too many of them
        QueryPlan plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ROW("MeasuresCustomerCount", 'Measures'[Customer Count]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name.Gender])),
                        NOT(ISBLANK('Measures'[Customer Count])))))""");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("SELECT {[Measures].[Customer Count]} ON COLUMNS FROM [C]");
        plan = bindCustomers(model, """
                EVALUATE SUMMARIZECOLUMNS('Date'[Year],
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name])),
                        NOT(ISBLANK('Measures'[Customer Count])) || NOT(ISBLANK('Measures'[Unit Sales])))),
                    "C", 'Measures'[Customer Count], "U", 'Measures'[Unit Sales])""");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo("SELECT {[Measures].[Customer Count], [Measures].[Unit Sales]} ON COLUMNS, "
                        + "NON EMPTY [Date].[Year].Members ON ROWS FROM [C]");

        // a measure not filtered on, or a calculated one, may change
        String where = "SELECT {[Measures].[Unit Sales]} ON COLUMNS FROM [C] WHERE Filter("
                + "[Customers].[Customers].[Name].Members, NOT IsEmpty([Measures].[Customer Count]))";
        plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ROW("U", 'Measures'[Unit Sales]),
                    FILTER(VALUES('Customers'[Customers.Customers.Name]), NOT(ISBLANK('Measures'[Customer Count]))))""");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text()).isEqualTo(where);
        plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ROW("S", 'Measures'[Sales]),
                    FILTER(VALUES('Customers'[Customers.Customers.Name]), NOT(ISBLANK('Measures'[Sales]))))""");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo(slicerMember("[Customers].[Customers]", "Filter("
                        + "[Customers].[Customers].[Name].Members, NOT IsEmpty([Measures].[Sales]))",
                        "SELECT {[Measures].[Sales]} ON COLUMNS FROM [C]"));
        // nor are filters kept where both are not BLANK
        plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ROW("C", 'Measures'[Customer Count]),
                    FILTER(VALUES('Customers'[Customers.Customers.Name]),
                        NOT(ISBLANK('Measures'[Customer Count])) && NOT(ISBLANK('Measures'[Unit Sales]))))""");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .contains(" WHERE Filter(");
    }

    @Test
    void propertiesAreReadFromTheMembersOfTheirLevel() throws Exception {
        QueryPlan plan = bindCustomers("EVALUATE SUMMARIZECOLUMNS('Customers'[Customers.Customers.Name], "
                + "'Customers'[Customers.Customers.Name.Gender], 'Customers'[Customers.Customers.City.Population], \"S\", [Sales])");
        MdxQuery query = MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS, "
                + "NON EMPTY [Customers].[Customers].[Name].Members ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 2), new MemberProperty(0, 2, "Gender", DaxType.STRING),
                new MemberProperty(0, 1, "Population", DaxType.STRING), new CellValue(0));
    }

    @Test
    void conditionsOnPropertiesCompareTheirValues() throws Exception {
        QueryPlan plan = bindCustomers("EVALUATE SUMMARIZECOLUMNS('Date'[Year], "
                + "KEEPFILTERS(FILTER(VALUES('Customers'[Customers.Customers.Name.Gender]), "
                + "'Customers'[Customers.Customers.Name.Gender] = \"f\")), \"S\", [Sales])");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .isEqualTo(slicerMember("[Customers].[Customers]", "Filter([Customers].[Customers].[Name].Members, "
                        + "UCase([Customers].[Customers].CurrentMember.Properties(\"Gender\")) = \"F\")",
                        "SELECT {[Measures].[Sales]} ON COLUMNS, NON EMPTY [Date].[Year].Members ON ROWS FROM [C]"));
        plan = bindCustomers("EVALUATE GENERATE(VALUES('Customers'[Customers.Customers.Name]), "
                + "FILTER(VALUES('Customers'[Customers.Customers.City.Population]), "
                + "'Customers'[Customers.Customers.City.Population] = \"1000\" || [Sales] > 1))");
        MdxQuery query = MdxGenerator.generate("[C]", (Generate) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, Generate([Customers].[Customers].[Name].Members, "
                + "Filter({[Customers].[Customers].CurrentMember}, (UCase(Ancestor([Customers].[Customers].CurrentMember, "
                + "[Customers].[Customers].[City]).Properties(\"Population\")) = \"1000\") OR ([Measures].[Sales] > 1))) "
                + "ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 2), new MemberProperty(0, 1, "Population", DaxType.STRING));
    }

    @Test
    void propertyWithoutItsLevelWithoutMeasuresIsReadFromItsMembers() throws Exception {
        QueryPlan plan = bindCustomers("EVALUATE FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name.Gender])), "
                + "NOT(ISBLANK([Sales])))");
        Summarize summarize = (Summarize) plan.evaluates().get(0).table();
        // whether one of the members of a value has measures needs no groups
        assertThat(summarize.byValues()).isEmpty();
        MdxQuery query = MdxGenerator.summarize("[C]", summarize);
        assertThat(query.text()).isEqualTo("SELECT {} ON COLUMNS, Filter([Customers].[Customers].[Name].Members, "
                + "NOT IsEmpty([Measures].[Sales])) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberProperty(0, 2, "Gender", DaxType.STRING));
        // grouped by a deeper level, a property of an ancestor has its own measures
        plan = bindCustomers("EVALUATE SUMMARIZECOLUMNS('Customers'[Customers.Customers.City.Population], "
                + "'Customers'[Customers.Customers.Name], \"S\", [Sales])");
        assertThat(((Summarize) plan.evaluates().get(0).table()).byValues()).isEmpty();
    }

    @Test
    void groupsOfPropertyValuesAreCalculatedMembers() throws Exception {
        QueryPlan plan = bindCustomers("""
                EVALUATE TOPN(501, ADDCOLUMNS(KEEPFILTERS(FILTER(
                        KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name.Gender])), NOT(ISBLANK('Measures'[Sales])))),
                    "S", 'Measures'[Sales]), 'Customers'[Customers.Customers.Name.Gender], 1)
                ORDER BY 'Customers'[Customers.Customers.Name.Gender]""");
        Summarize summarize = (Summarize) ((TopN) plan.evaluates().get(0).table()).source();
        assertThat(summarize.byValues()).containsExactly("[Customers].[Customers]");

        MdxQuery query = MdxGenerator.summarize("[C]", summarize, Map.of("[Customers].[Customers]",
                List.of(List.of("F"), List.of("M"), Arrays.asList((Object) null))));
        String gender = "[Customers].[Customers].CurrentMember.Properties(\"Gender\")";
        assertThat(query.text()).isEqualTo("WITH MEMBER [Customers].[Customers].[DAX group 1] AS Aggregate(Filter("
                + "[Customers].[Customers].[Name].Members, " + gender + " = \"F\")) "
                + "MEMBER [Customers].[Customers].[DAX group 2] AS Aggregate(Filter("
                + "[Customers].[Customers].[Name].Members, " + gender + " = \"M\")) "
                + "MEMBER [Customers].[Customers].[DAX group 3] AS Aggregate(Filter("
                + "[Customers].[Customers].[Name].Members, IsEmpty(" + gender + "))) "
                + "SELECT {[Measures].[Sales]} ON COLUMNS, Filter({[Customers].[Customers].[DAX group 1], "
                + "[Customers].[Customers].[DAX group 2], [Customers].[Customers].[DAX group 3]}, "
                + "NOT IsEmpty([Measures].[Sales])) ON ROWS FROM [C]");
        Map<String, Object> values = new HashMap<>();
        values.put("DAX group 1", "F");
        values.put("DAX group 2", "M");
        values.put("DAX group 3", null);
        assertThat(query.sources()).containsExactly(new GroupValue(0, values), new CellValue(0));
    }

    @Test
    void generatesOfPropertiesComputingMeasuresAreGroupingsByTheirValues() throws Exception {
        ModelColumn storeName = new ModelColumn("Store", "Store.Store.Store Name", "[Store].[Store]",
                "[Store].[Store].[Store Name]", 4, DaxType.STRING);
        ModelMeasure sqft = new ModelMeasure("Store Sqft", "[Measures].[Store Sqft]", true);
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Store", List.of(storeName,
                storeProperty(storeName, "Frozen Sqft", DaxType.INTEGER), storeProperty(storeName, "Store Type", DaxType.STRING),
                storeProperty(storeName, "Street address", DaxType.STRING)))), List.of(sqft));
        QueryPlan plan = new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(501, ADDCOLUMNS(KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Store'[Store.Store.Store Name.Frozen Sqft])),
                        VALUES('Store'[Store.Store.Store Name.Store Type]))),
                        FILTER(KEEPFILTERS(VALUES('Store'[Store.Store.Store Name.Street address])),
                            NOT(ISBLANK('Measures'[Store Sqft]))))),
                    "MeasuresStoreSqft", 'Measures'[Store Sqft]),
                    'Store'[Store.Store.Store Name.Frozen Sqft], 1, 'Store'[Store.Store.Store Name.Store Type], 1,
                    'Store'[Store.Store.Store Name.Street address], 1)
                ORDER BY 'Store'[Store.Store.Store Name.Frozen Sqft], 'Store'[Store.Store.Store Name.Store Type],
                    'Store'[Store.Store.Store Name.Street address]""").parseDaxStatement());
        // the rows of each outer row are the groups of the values together
        Summarize summarize = (Summarize) ((TopN) plan.evaluates().get(0).table()).source();
        assertThat(summarize.groupBy()).extracting(ModelColumn::name).containsExactly(
                "Store.Store.Store Name.Frozen Sqft", "Store.Store.Store Name.Store Type",
                "Store.Store.Store Name.Street address");
        assertThat(summarize.byValues()).containsExactly("[Store].[Store]");

        MdxQuery query = MdxGenerator.summarize("[C]", summarize,
                Map.of("[Store].[Store]", List.of(List.of(2678L, "Small", "1 Main St"))));
        String current = "[Store].[Store].CurrentMember.Properties(";
        assertThat(query.text()).isEqualTo("WITH MEMBER [Store].[Store].[DAX group 1] AS Aggregate(Filter("
                + "[Store].[Store].[Store Name].Members, (" + current + "\"Frozen Sqft\") = 2678) AND ("
                + current + "\"Store Type\") = \"Small\") AND (" + current + "\"Street address\") = \"1 Main St\"))) "
                + "SELECT {[Measures].[Store Sqft]} ON COLUMNS, Filter({[Store].[Store].[DAX group 1]}, "
                + "NOT IsEmpty([Measures].[Store Sqft])) ON ROWS FROM [C]");

        // a top of each outer row: the first groups of each value of the outer one
        TopN top = (TopN) new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE GENERATE(VALUES('Store'[Store.Store.Store Name.Frozen Sqft]),
                    TOPN(1, SUMMARIZECOLUMNS('Store'[Store.Store.Store Name.Store Type], "S", [Store Sqft]), [S]))
                """).parseDaxStatement()).evaluates().get(0).table();
        assertThat(top.partition()).isEqualTo(1);
        assertThat(((Summarize) top.source()).byValues()).containsExactly("[Store].[Store]");
    }

    @Test
    void summarizeOfGenerateKeepingNonBlankGroupsWhereTheMeasureIsNotBlank() throws Exception {
        ModelColumn country = new ModelColumn("Store", "Store.Store.Store Country", "[Store].[Store]",
                "[Store].[Store].[Store Country]", 1, DaxType.STRING);
        ModelColumn state = new ModelColumn("Store", "Store.Store.Store State", "[Store].[Store]",
                "[Store].[Store].[Store State]", 2, DaxType.STRING);
        ModelMeasure sqft = new ModelMeasure("Store Sqft", "[Measures].[Store Sqft]", true);
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Store", List.of(country, state))),
                List.of(sqft));
        QueryPlan plan = new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(501, SUMMARIZE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Store'[Store.Store.Store Country])),
                        FILTER(KEEPFILTERS(VALUES('Store'[Store.Store.Store State])),
                            NOT(ISBLANK('Measures'[Store Sqft]))))),
                    'Store'[Store.Store.Store Country], "MeasuresStoreSqft", 'Measures'[Store Sqft]),
                    'Store'[Store.Store.Store Country], 1)
                ORDER BY 'Store'[Store.Store.Store Country]""").parseDaxStatement());
        Summarize summarize = (Summarize) ((TopN) plan.evaluates().get(0).table()).source();
        assertThat(summarize.groupBy()).containsExactly(country);
        MdxQuery query = MdxGenerator.summarize("[C]", summarize);
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Store Sqft]} ON COLUMNS, "
                + "Filter([Store].[Store].[Store Country].Members, NOT IsEmpty([Measures].[Store Sqft])) ON ROWS FROM [C]");

        // a measure not stored may be BLANK of a group though not of its members: the groups of the rows,
        // the measures computed for them
        TabularModel calculated = new TabularModel("[C]", List.of(new ModelTable("Store", List.of(country, state))),
                List.of(sqft, new ModelMeasure("Growth", "[Measures].[Growth]")));
        plan = new Binder(calculated, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(501, SUMMARIZE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Store'[Store.Store.Store Country])),
                        FILTER(KEEPFILTERS(VALUES('Store'[Store.Store.Store State])),
                            OR(NOT(ISBLANK('Measures'[Store Sqft])), NOT(ISBLANK('Measures'[Growth])))))),
                    'Store'[Store.Store.Store Country], "S", 'Measures'[Store Sqft], "G", 'Measures'[Growth]),
                    'Store'[Store.Store.Store Country], 1)
                ORDER BY 'Store'[Store.Store.Store Country]""").parseDaxStatement());
        summarize = (Summarize) ((TopN) plan.evaluates().get(0).table()).source();
        assertThat(MdxGenerator.summarize("[C]", summarize).text()).isEqualTo(
                "SELECT {[Measures].[Store Sqft], [Measures].[Growth]} ON COLUMNS, "
                        + "Exists([Store].[Store].[Store Country].Members, Generate([Store].[Store].[Store Country].Members, "
                        + "Filter(Descendants([Store].[Store].CurrentMember, [Store].[Store].[Store State]), "
                        + "NOT IsEmpty([Measures].[Store Sqft]) OR NOT IsEmpty([Measures].[Growth])))) ON ROWS FROM [C]");
        assertThatThrownBy(() -> new Binder(calculated, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE SUMMARIZE(GENERATE(VALUES('Store'[Store.Store.Store Country]),
                        FILTER(VALUES('Store'[Store.Store.Store State]), NOT(ISBLANK([Growth])))),
                    "G", [Growth])""").parseDaxStatement()))
                .isInstanceOf(DaxSemanticException.class).hasMessageContaining("SUMMARIZE without columns");
    }

    @Test
    void calculateTableOfSummarizeOfGeneratesFilteredOnLevelsOfTheGroupedHierarchy() throws Exception {
        ModelColumn country = new ModelColumn("Store", "Store.Store.Store Country", "[Store].[Store]",
                "[Store].[Store].[Store Country]", 1, DaxType.STRING);
        ModelColumn state = new ModelColumn("Store", "Store.Store.Store State", "[Store].[Store]",
                "[Store].[Store].[Store State]", 2, DaxType.STRING);
        ModelColumn city = new ModelColumn("Store", "Store.Store.Store City", "[Store].[Store]",
                "[Store].[Store].[Store City]", 3, DaxType.STRING);
        ModelMeasure sqft = new ModelMeasure("Store Sqft", "[Measures].[Store Sqft]", true);
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Store", List.of(country, state, city))),
                List.of(sqft));
        QueryPlan plan = new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(501, CALCULATETABLE(SUMMARIZE(KEEPFILTERS(GENERATE(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Store'[Store.Store.Store Country])), VALUES('Store'[Store.Store.Store State]))),
                        FILTER(KEEPFILTERS(VALUES('Store'[Store.Store.Store City])), NOT(ISBLANK('Measures'[Store Sqft]))))),
                    'Store'[Store.Store.Store Country], "MeasuresStoreSqft", 'Measures'[Store Sqft]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Store'[Store.Store.Store Country])),
                        'Store'[Store.Store.Store Country] = "USA")),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Store'[Store.Store.Store State])),
                        'Store'[Store.Store.Store State] = "WA"))),
                    'Store'[Store.Store.Store Country], 1)
                ORDER BY 'Store'[Store.Store.Store Country]""").parseDaxStatement());
        Summarize summarize = (Summarize) ((TopN) plan.evaluates().get(0).table()).source();
        MdxQuery query = MdxGenerator.summarize("[C]", summarize);
        String usa = "Filter([Store].[Store].[Store Country].Members, UCase([Store].[Store].CurrentMember.Name) = \"USA\")";
        String wa = "Filter([Store].[Store].[Store State].Members, UCase([Store].[Store].CurrentMember.Name) = \"WA\")";
        // the groups related to both filters; the measure aggregated over the states of each both keep
        assertThat(query.text()).isEqualTo("WITH MEMBER [Measures].[DAX in filters Store Sqft] AS Aggregate(Exists("
                + "Exists(Descendants([Store].[Store].CurrentMember, [Store].[Store].[Store State]), " + usa + "), " + wa
                + "), [Measures].[Store Sqft]) SELECT {[Measures].[DAX in filters Store Sqft]} ON COLUMNS, Filter("
                + "Exists(Exists([Store].[Store].[Store Country].Members, " + usa + "), " + wa + "), "
                + "NOT IsEmpty([Measures].[DAX in filters Store Sqft])) ON ROWS FROM [C]");
    }

    @Test
    void severalFilterTablesOnAHierarchyNotGroupedByAreTheirIntersectionInTheSlicer() throws Exception {
        QueryPlan plan = new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser(
                "EVALUATE SUMMARIZECOLUMNS('Product'[Category], FILTER(VALUES('Markets'[Country]), [Sales] > 1), "
                        + "FILTER(VALUES('Markets'[Country]), [Sales] < 9), \"S\", [Sales Amount])")
                .parseDaxStatement());
        MdxQuery query = MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table());
        assertThat(query.text()).isEqualTo(slicerMember("[Markets]",
                "Exists(Filter([Markets].[Country].Members, [Measures].[Sales] > 1), "
                        + "Filter([Markets].[Country].Members, [Measures].[Sales] < 9))",
                "SELECT {[Measures].[Sales Amount]} ON COLUMNS, NON EMPTY [Product].[Category].Members ON ROWS FROM [C]"));
    }

    private static ModelColumn storeProperty(ModelColumn level, String property, DaxType type) {
        return new ModelColumn("Store", level.name() + "." + property, level.hierarchy(), level.level(), level.depth(),
                type, Optional.of(property));
    }

    @Test
    void groupsOfValuesOfAPropertyAndAShallowerLevelBesideOtherHierarchies() throws Exception {
        QueryPlan plan = bindCustomers("EVALUATE SUMMARIZECOLUMNS('Customers'[Customers.Customers.City], "
                + "'Customers'[Customers.Customers.Name.Gender], 'Date'[Year], \"S\", [Sales], \"E\", ISBLANK([Sales]))");
        Summarize summarize = (Summarize) plan.evaluates().get(0).table();
        MdxQuery query = MdxGenerator.summarize("[C]", summarize,
                Map.of("[Customers].[Customers]", List.of(List.of("Paris", "F"))));
        // the measures of the cube aggregate over the group; a calculated one is computed after
        assertThat(query.text()).isEqualTo("WITH MEMBER [Customers].[Customers].[DAX group 1] AS Aggregate(Filter("
                + "[Customers].[Customers].[Name].Members, (Ancestor([Customers].[Customers].CurrentMember, "
                + "[Customers].[Customers].[City]).Name = \"Paris\") AND "
                + "([Customers].[Customers].CurrentMember.Properties(\"Gender\") = \"F\"))) "
                + "MEMBER [Measures].[DAX E] AS IsEmpty([Measures].[Sales]), SOLVE_ORDER = 1 "
                + "SELECT {[Measures].[Sales], [Measures].[DAX E]} ON COLUMNS, NON EMPTY "
                + "CrossJoin({[Customers].[Customers].[DAX group 1]}, [Date].[Year].Members) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new GroupValue(0, Map.of("DAX group 1", "Paris")),
                new GroupValue(0, Map.of("DAX group 1", "F")), new MemberName(1, 1), new CellValue(0),
                new CellValue(1));
    }

    @Test
    void generateOfTopNOfSummarizeWithRollupOfAPropertyGroupsEachOuterRow() throws Exception {
        ModelColumn country = customerLevel("Country", 1);
        ModelColumn name = customerLevel("Name", 4);
        ModelColumn education = new ModelColumn("Customers", "Customers.Customers.Name.Education", name.hierarchy(),
                name.level(), 4, DaxType.STRING, Optional.of("Education"));
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers",
                List.of(country, name, education))), List.of(new ModelMeasure("Profit", "[Measures].[Profit]")));
        QueryPlan plan = bindCustomers(model, """
                EVALUATE TOPN(10201, GENERATE(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Country])),
                        TOPN(102, SUMMARIZE(KEEPFILTERS(FILTER(
                                KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name.Education])),
                                NOT(ISBLANK('Measures'[Profit])))),
                            ROLLUP('Customers'[Customers.Customers.Name.Education]),
                            "IsAggregate", ISSUBTOTAL('Customers'[Customers.Customers.Name.Education]),
                            "MeasuresProfit", 'Measures'[Profit]),
                            [IsAggregate], 1, 'Customers'[Customers.Customers.Name.Education], 1)),
                    'Customers'[Customers.Customers.Country], 1, [IsAggregate], 1,
                    'Customers'[Customers.Customers.Name.Education], 1)
                ORDER BY 'Customers'[Customers.Customers.Country], [IsAggregate],
                    'Customers'[Customers.Customers.Name.Education]""");
        TopN outer = (TopN) plan.evaluates().get(0).table();
        assertThat(outer.columns()).extracting(c -> c.name()).containsExactly(
                "Customers[Customers.Customers.Country]", "Customers[Customers.Customers.Name.Education]",
                "[IsAggregate]", "[MeasuresProfit]");
        // the first of each country, the groups before their subtotal
        TopN inner = (TopN) outer.source();
        assertThat(inner.partition()).isEqualTo(1);
        assertThat(inner.keys()).containsExactly(new SortKey(2, true), new SortKey(1, true));
        AddColumns named = (AddColumns) inner.source();
        Rollup rollup = (Rollup) named.source();
        assertThat(rollup.keys()).isEqualTo(1);

        // the groups of the values of each country, kept where the profit is not BLANK
        Summarize groups = (Summarize) rollup.levels().get(0);
        assertThat(groups.groupBy()).containsExactly(country, education);
        assertThat(groups.byValues()).containsExactly("[Customers].[Customers]");
        assertThat(MdxGenerator.summarize("[C]", groups, Map.of("[Customers].[Customers]",
                List.of(List.of("USA", "College")))).text()).isEqualTo("WITH MEMBER [Customers].[Customers]."
                        + "[DAX group 1] AS Aggregate(Filter([Customers].[Customers].[Name].Members, "
                        + "(Ancestor([Customers].[Customers].CurrentMember, [Customers].[Customers].[Country]).Name"
                        + " = \"USA\") AND ([Customers].[Customers].CurrentMember.Properties(\"Education\") = "
                        + "\"College\"))) SELECT {[Measures].[Profit]} ON COLUMNS, Filter({[Customers].[Customers]."
                        + "[DAX group 1]}, NOT IsEmpty([Measures].[Profit])) ON ROWS FROM [C]");
        // the subtotal of each country
        assertThat(MdxGenerator.generate("[C]", (Generate) rollup.levels().get(1)).text()).isEqualTo(
                "SELECT {[Measures].[Profit]} ON COLUMNS, [Customers].[Customers].[Country].Members ON ROWS FROM [C]");

        // a subtotal is kept where there are groups
        List<List<Object>> rows = rollup.apply(List.of(
                List.of(Arrays.asList("USA", "College", 5.0), Arrays.asList("USA", "Bachelors", 7.0)),
                List.of(Arrays.asList("USA", 12.0), Arrays.<Object>asList("Mexico", 3.0))));
        assertThat(rows).containsExactly(Arrays.asList("USA", "College", 5.0, false),
                Arrays.asList("USA", "Bachelors", 7.0, false), Arrays.asList("USA", null, 12.0, true));
        List<List<Object>> result = inner.apply(named.apply(rows));
        assertThat(result).containsExactly(Arrays.asList("USA", "Bachelors", false, 7.0),
                Arrays.asList("USA", "College", false, 5.0), Arrays.asList("USA", null, true, 12.0));
    }

    @Test
    void summarizeOfSomeColumnsOfAFilterByCalculatedMeasuresIsNotSupported() {
        assertThatThrownBy(() -> bindCustomers("""
                EVALUATE SUMMARIZE(FILTER(SUMMARIZECOLUMNS('Customers'[Customers.Customers.City],
                        'Date'[Year]), NOT(ISBLANK([Sales]))), 'Date'[Year])""")).isInstanceOf(
                                DaxSemanticException.class);
    }

    @Test
    void groupsOfNumericPropertyValuesCompareNumbers() throws Exception {
        ModelColumn store = new ModelColumn("Store", "Store.Store.Store Name", "[Store].[Store]",
                "[Store].[Store].[Store Name]", 4, DaxType.STRING);
        ModelColumn meat = new ModelColumn("Store", "Store.Store.Store Name.Meat Sqft", "[Store].[Store]",
                "[Store].[Store].[Store Name]", 4, DaxType.INTEGER, Optional.of("Meat Sqft"));
        TabularModel model = new TabularModel("[Store]", List.of(new ModelTable("Store", List.of(store, meat))),
                List.of(new ModelMeasure("Store Sqft", "[Measures].[Store Sqft]")));
        QueryPlan plan = new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE TOPN(501, ADDCOLUMNS(KEEPFILTERS(FILTER(
                        KEEPFILTERS(VALUES('Store'[Store.Store.Store Name.Meat Sqft])),
                        NOT(ISBLANK('Measures'[Store Sqft])))), "S", 'Measures'[Store Sqft]),
                    'Store'[Store.Store.Store Name.Meat Sqft], 1)""").parseDaxStatement());
        Summarize summarize = (Summarize) ((TopN) plan.evaluates().get(0).table()).source();
        MdxQuery query = MdxGenerator.summarize("[Store]", summarize,
                Map.of("[Store].[Store]", List.of(List.of(2678L), Arrays.asList((Object) null))));
        String meatSqft = "[Store].[Store].CurrentMember.Properties(\"Meat Sqft\")";
        assertThat(query.text()).isEqualTo("WITH MEMBER [Store].[Store].[DAX group 1] AS Aggregate(Filter("
                + "[Store].[Store].[Store Name].Members, " + meatSqft + " = 2678)) "
                + "MEMBER [Store].[Store].[DAX group 2] AS Aggregate(Filter("
                + "[Store].[Store].[Store Name].Members, IsEmpty(" + meatSqft + "))) "
                + "SELECT {[Measures].[Store Sqft]} ON COLUMNS, Filter({[Store].[Store].[DAX group 1], "
                + "[Store].[Store].[DAX group 2]}, NOT IsEmpty([Measures].[Store Sqft])) ON ROWS FROM [Store]");
        assertThat(plan.evaluates().get(0).table().columns()).extracting(c -> c.type())
                .containsExactly(DaxType.INTEGER, DaxType.VARIANT);
    }

    @Test
    void filtersOnNumericPropertyValuesCompareNumbers() throws Exception {
        ModelColumn store = new ModelColumn("Store", "Store.Store.Store Name", "[Store].[Store]",
                "[Store].[Store].[Store Name]", 4, DaxType.STRING);
        ModelColumn frozen = new ModelColumn("Store", "Store.Store.Store Name.Frozen Sqft", "[Store].[Store]",
                "[Store].[Store].[Store Name]", 4, DaxType.INTEGER, Optional.of("Frozen Sqft"));
        TabularModel model = new TabularModel("[Store]", List.of(new ModelTable("Store", List.of(store, frozen))),
                List.of(new ModelMeasure("Store Cost", "[Measures].[Store Cost]", true)));
        String frozenSqft = "[Store].[Store].CurrentMember.Properties(\"Frozen Sqft\")";
        for (String[] filter : new String[][] {
                { "'Store'[Store.Store.Store Name.Frozen Sqft] = 4016", frozenSqft + " = 4016" },
                { "'Store'[Store.Store.Store Name.Frozen Sqft] IN {4016, 5000}",
                        "(" + frozenSqft + " = 4016 OR " + frozenSqft + " = 5000)" } }) {
            QueryPlan plan = new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser(
                    "EVALUATE CALCULATETABLE(ROW(\"S\", 'Measures'[Store Cost]), KEEPFILTERS(FILTER("
                            + "KEEPFILTERS(VALUES('Store'[Store.Store.Store Name.Frozen Sqft])), " + filter[0] + ")))")
                    .parseDaxStatement());
            assertThat(MdxGenerator.summarize("[Store]", (Summarize) plan.evaluates().get(0).table()).text())
                    .isEqualTo("SELECT {[Measures].[Store Cost]} ON COLUMNS FROM [Store] WHERE Filter("
                            + "[Store].[Store].[Store Name].Members, " + filter[1] + ")");
        }
        // a name is text: compared with a number it is not computed by the cube
        assertThatThrownBy(() -> new Binder(model, Map.of()).bind(new CCCDaxParserProvider().newParser(
                "EVALUATE CALCULATETABLE(ROW(\"S\", 'Measures'[Store Cost]), KEEPFILTERS(FILTER("
                        + "KEEPFILTERS(VALUES('Store'[Store.Store.Store Name])), "
                        + "'Store'[Store.Store.Store Name] = 4016)))").parseDaxStatement()))
                .isInstanceOf(DaxSemanticException.class);
    }

    @Test
    void generateOfPropertyValuesFilteredOnAShallowerLevelAndAnotherTableIsTheirGrouping() throws Exception {
        ModelColumn city = customerLevel("City", 3);
        ModelColumn name = customerLevel("Name", 4);
        ModelColumn education = new ModelColumn("Customers", "Customers.Customers.Name.Education", name.hierarchy(),
                name.level(), 4, DaxType.STRING, Optional.of("Education"));
        ModelColumn gender = new ModelColumn("Customers", "Customers.Customers.Name.Gender", name.hierarchy(),
                name.level(), 4, DaxType.STRING, Optional.of("Gender"));
        ModelColumn level = new ModelColumn("Education Level", "Education Level.Education Level.Education Level",
                "[Education Level].[Education Level]", "[Education Level].[Education Level].[Education Level]", 1,
                DaxType.STRING);
        TabularModel model = new TabularModel("[C]", List.of(new ModelTable("Customers",
                List.of(city, name, education, gender)), new ModelTable("Education Level", List.of(level))),
                List.of(new ModelMeasure("Profit", "[Measures].[Profit]"),
                        new ModelMeasure("Customer Count", "[Measures].[Customer Count]", true)));
        QueryPlan plan = bindCustomers(model, """
                EVALUATE CALCULATETABLE(ADDCOLUMNS(KEEPFILTERS(GENERATE(
                        KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name.Education])),
                        FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.Name.Gender])),
                            OR(NOT(ISBLANK('Measures'[Profit])), NOT(ISBLANK('Measures'[Customer Count])))))),
                        "MeasuresProfit", 'Measures'[Profit], "MeasuresCustomerCount", 'Measures'[Customer Count]),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES('Customers'[Customers.Customers.City])),
                        'Customers'[Customers.Customers.City] = "Albany")),
                    KEEPFILTERS(FILTER(KEEPFILTERS(VALUES(
                            'Education Level'[Education Level.Education Level.Education Level])),
                        'Education Level'[Education Level.Education Level.Education Level] = "Bachelors Degree")))""");
        // the groups of the values of the customers of the city, their measures of the education level
        Summarize groups = (Summarize) plan.evaluates().get(0).table();
        assertThat(groups.groupBy()).containsExactly(education, gender);
        assertThat(groups.byValues()).containsExactly("[Customers].[Customers]");
        assertThat(MdxGenerator.summarize("[C]", groups, Map.of("[Customers].[Customers]",
                List.of(List.of("Bachelors Degree", "F")))).text()).isEqualTo("WITH MEMBER [Customers].[Customers]."
                        + "[DAX group 1] AS Aggregate(Exists(Filter([Customers].[Customers].[Name].Members, "
                        + "([Customers].[Customers].CurrentMember.Properties(\"Education\") = \"Bachelors Degree\") "
                        + "AND ([Customers].[Customers].CurrentMember.Properties(\"Gender\") = \"F\")), "
                        + "Filter([Customers].[Customers].[City].Members, UCase([Customers].[Customers]."
                        + "CurrentMember.Name) = \"ALBANY\"))) MEMBER [Education Level].[Education Level]."
                        + "[DAX slicer 1] AS Aggregate(Filter([Education Level].[Education Level].[Education Level]."
                        + "Members, UCase([Education Level].[Education Level].CurrentMember.Name) = "
                        + "\"BACHELORS DEGREE\")), SOLVE_ORDER = -100 SELECT {[Measures].[Profit], "
                        + "[Measures].[Customer Count]} ON COLUMNS, Filter({[Customers].[Customers].[DAX group 1]}, "
                        + "NOT IsEmpty([Measures].[Profit]) OR NOT IsEmpty([Measures].[Customer Count])) ON ROWS "
                        + "FROM [C] WHERE ([Education Level].[Education Level].[DAX slicer 1])");
    }

    @Test
    void groupsOfPropertyValuesHaveNoNamesToFilterOn() throws Exception {
        for (String[] dax : new String[][] {
                { "EVALUATE FILTER(VALUES('Customers'[Customers.Customers.Name.Gender]), "
                        + "'Customers'[Customers.Customers.Name.Gender] = \"F\" || [Sales] > 1)",
                        "a condition by measures on the columns of [Customers].[Customers], grouped by the values "
                                + "of a property without its level is not supported yet" },
                { "EVALUATE SUMMARIZECOLUMNS('Customers'[Customers.Customers.Name.Gender], "
                        + "KEEPFILTERS(FILTER(VALUES('Customers'[Customers.Customers.City]), "
                        + "[Sales] > 1)), \"S\", [Sales])",
                        "a filter table on Customers[Customers.Customers.City], grouped by the values of a property "
                                + "without its level is not supported yet" },
                // the outer rows kept by a measure the inner groups do not keep by
                { "EVALUATE GENERATE(FILTER(VALUES('Date'[Year]), [Sales] > 1), "
                        + "SUMMARIZECOLUMNS('Customers'[Customers.Customers.Name.Gender], \"S\", [Sales]))",
                        "GENERATE computing measures by Customers[Customers.Customers.Name.Gender], a property, "
                                + "without its level is not supported yet" } }) {
            assertThatThrownBy(() -> bindCustomers(dax[0])).isInstanceOf(DaxSemanticException.class)
                    .hasMessage(dax[1]);
        }
    }
}
