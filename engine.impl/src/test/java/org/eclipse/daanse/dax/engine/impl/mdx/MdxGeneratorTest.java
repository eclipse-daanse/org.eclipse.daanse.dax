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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.TestModel;
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
                .isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS, NON EMPTY [Product].[Category].Members ON ROWS FROM [C] "
                        + "WHERE Filter([Markets].[Country].Members, (UCase([Markets].CurrentMember.Name) = \"USA\" "
                        + "OR UCase([Markets].CurrentMember.Name) = \"FRANCE\"))");
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
                .isEqualTo("SELECT {[Measures].[Sales Amount]} ON COLUMNS, NON EMPTY [Date].[Year].Members ON ROWS FROM [C] "
                        + "WHERE CrossJoin(TopCount(NonEmpty([Markets].[Country].Members, {[Measures].[Sales]}), 3, "
                        + "[Measures].[Sales]), Filter([Product].[Subcategory].Members, "
                        + "(UCase(Ancestor([Product].CurrentMember, [Product].[Category]).Name) = \"BIKES\") "
                        + "AND (\"ROAD\" <> UCase([Product].CurrentMember.Name))))");
    }

    @Test
    void filterTableByMeasureOrText() throws Exception {
        assertThat(mdx("EVALUATE SUMMARIZECOLUMNS('Product'[Category], KEEPFILTERS(FILTER(VALUES('Markets'[Country]), "
                + "'Markets'[Country] = \"USA\" || [Sales] > 100)), \"S\", [Sales Amount])"))
                .isEqualTo("SELECT {[Measures].[Sales Amount]} ON COLUMNS, NON EMPTY [Product].[Category].Members "
                        + "ON ROWS FROM [C] WHERE Filter([Markets].[Country].Members, "
                        + "(UCase([Markets].CurrentMember.Name) = \"USA\") OR ([Measures].[Sales] > 100))");
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
                .isEqualTo("SELECT {} ON COLUMNS, Filter([Markets].[Country].Members, [Measures].[Sales] > 1) "
                        + "ON ROWS FROM [C] WHERE Filter([Product].[Category].Members, "
                        + "UCase([Product].CurrentMember.Name) = \"BIKES\")");
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
    void calculateTableOfGenerateFilteredOnOtherColumnsIsNotSupported() {
        assertThatThrownBy(() -> new Binder(TestModel.model(), Map.of()).bind(new CCCDaxParserProvider().newParser("""
                EVALUATE CALCULATETABLE(GENERATE(VALUES('Product'[Category]), VALUES('Date'[Year])),
                    FILTER(VALUES('Markets'[Country]), 'Markets'[Country] = "Canada"))
                """).parseDaxStatement())).isInstanceOf(DaxSemanticException.class)
                .hasMessageContaining("CALCULATETABLE of GENERATE filtered on columns other than of its first table");
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
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS FROM [C] WHERE "
                + "Generate([Date].[Year].Members, CrossJoin({[Date].CurrentMember}, "
                + "Filter([Markets].[Country].Members, NOT IsEmpty([Measures].[Sales]))))");
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
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales], [Measures].[Unit Sales]} ON COLUMNS "
                + "FROM [C] WHERE Generate(Generate([Product].[Category].Members, "
                + "CrossJoin({[Product].CurrentMember}, [Date].[Year].Members)), "
                + "CrossJoin({[Date].CurrentMember}, Filter(Descendants([Product].CurrentMember, "
                + "[Product].[Subcategory]), NOT IsEmpty([Measures].[Sales]) "
                + "OR NOT IsEmpty([Measures].[Unit Sales]))))");
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
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS FROM [C] WHERE "
                + "Generate(Generate(Generate([Date].[Year].Members, "
                + "CrossJoin({[Date].CurrentMember}, [Product].[Category].Members)), "
                + "CrossJoin({[Date].CurrentMember}, Descendants([Product].CurrentMember, [Product].[Subcategory]))), "
                + "CrossJoin({[Date].CurrentMember}, Filter(Descendants([Product].CurrentMember, [Product].[Name]), "
                + "NOT IsEmpty([Measures].[Sales]))))");
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
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[FactInternetSales]} ON COLUMNS FROM [C] WHERE "
                + "Generate(Generate(Generate([DimCustomer].[DimCustomer].[AddressLine1].Members, "
                + "Descendants([DimCustomer].[DimCustomer].CurrentMember, [DimCustomer].[DimCustomer].[AddressLine2])), "
                + "{[DimCustomer].[DimCustomer].CurrentMember}), "
                + "Filter(Descendants([DimCustomer].[DimCustomer].CurrentMember, "
                + "[DimCustomer].[DimCustomer].[CommuteDistance]), NOT IsEmpty([Measures].[FactInternetSales])))");
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
        // a calculated measure may change
        plan = bindCustomers(model, "EVALUATE CALCULATETABLE(ROW(\"C\", 'Measures'[Calculated]), "
                + generate.formatted("Calculated") + ")");
        assertThat(MdxGenerator.summarize("[C]", (Summarize) plan.evaluates().get(0).table()).text())
                .startsWith("SELECT {[Measures].[Calculated]} ON COLUMNS FROM [C] WHERE Generate(");

        // the rows: the customers on the axis, aggregated by none
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
                + "Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[State Province])), "
                + "Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[City])), "
                + "Filter(Descendants([Customers].[Customers].CurrentMember, [Customers].[Customers].[Name]), "
                + "NOT IsEmpty([Measures].[Customer Count]))) ON ROWS FROM [C]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(0, 2), new MemberName(0, 3),
                new MemberName(0, 4), new CellValue(0));
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
                .isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS FROM [C] WHERE Filter("
                        + "[Customers].[Customers].[Name].Members, NOT IsEmpty([Measures].[Sales]))");
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
                .isEqualTo("SELECT {[Measures].[Sales]} ON COLUMNS, NON EMPTY [Date].[Year].Members ON ROWS FROM [C] "
                        + "WHERE Filter([Customers].[Customers].[Name].Members, "
                        + "UCase([Customers].[Customers].CurrentMember.Properties(\"Gender\")) = \"F\")");
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
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales Amount]} ON COLUMNS, "
                + "NON EMPTY [Product].[Category].Members ON ROWS FROM [C] WHERE "
                + "Exists(Filter([Markets].[Country].Members, [Measures].[Sales] > 1), "
                + "Filter([Markets].[Country].Members, [Measures].[Sales] < 9))");
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
    void groupsOfPropertyValuesHaveNoNamesToFilterOn() throws Exception {
        for (String[] dax : new String[][] {
                { "EVALUATE FILTER(VALUES('Customers'[Customers.Customers.Name.Gender]), "
                        + "'Customers'[Customers.Customers.Name.Gender] = \"F\" || [Sales] > 1)",
                        "a condition by measures on the columns of [Customers].[Customers], grouped by the values "
                                + "of a property without its level is not supported yet" },
                { "EVALUATE SUMMARIZECOLUMNS('Customers'[Customers.Customers.Name.Gender], "
                        + "KEEPFILTERS(FILTER(VALUES('Customers'[Customers.Customers.City]), "
                        + "'Customers'[Customers.Customers.City] = \"Paris\")), \"S\", [Sales])",
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
