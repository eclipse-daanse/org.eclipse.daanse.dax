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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxExecutionException;
import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;
import org.eclipse.daanse.dax.engine.impl.model.ModelMeasure;
import org.eclipse.daanse.dax.engine.impl.model.ModelTable;
import org.eclipse.daanse.dax.engine.impl.model.TabularModel;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan.SortKey;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Aggregation;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.ColumnAggregate;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.ColumnValue;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Comparison;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Constant;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Currency;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.InList;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.IsBlank;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Logical;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.MeasureValue;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan.Not;
import org.eclipse.daanse.dax.model.api.ColumnDefinition;
import org.eclipse.daanse.dax.model.api.DaxStatement;
import org.eclipse.daanse.dax.model.api.DefineClause;
import org.eclipse.daanse.dax.model.api.EvaluateStatement;
import org.eclipse.daanse.dax.model.api.MeasureDefinition;
import org.eclipse.daanse.dax.model.api.OrderByItem;
import org.eclipse.daanse.dax.model.api.ParameterDefinition;
import org.eclipse.daanse.dax.model.api.TableDefinition;
import org.eclipse.daanse.dax.model.api.VariableDefinition;
import org.eclipse.daanse.dax.model.api.expression.BooleanExpression;
import org.eclipse.daanse.dax.model.api.expression.BooleanExpression.BooleanOperator;
import org.eclipse.daanse.dax.model.api.expression.BooleanLiteral;
import org.eclipse.daanse.dax.model.api.expression.DateTimeLiteral;
import org.eclipse.daanse.dax.model.api.expression.DaxExpression;
import org.eclipse.daanse.dax.model.api.expression.Entity;
import org.eclipse.daanse.dax.model.api.expression.FunctionCall;
import org.eclipse.daanse.dax.model.api.expression.Identifier;
import org.eclipse.daanse.dax.model.api.expression.Keyword;
import org.eclipse.daanse.dax.model.api.expression.LogicalExpression;
import org.eclipse.daanse.dax.model.api.expression.LogicalExpression.LogicalOperator;
import org.eclipse.daanse.dax.model.api.expression.NumericLiteral;
import org.eclipse.daanse.dax.model.api.expression.Parameter;
import org.eclipse.daanse.dax.model.api.expression.RowConstructor;
import org.eclipse.daanse.dax.model.api.expression.Scalar;
import org.eclipse.daanse.dax.model.api.expression.StringLiteral;
import org.eclipse.daanse.dax.model.api.expression.TableConstructor;
import org.eclipse.daanse.dax.model.api.expression.VariableReference;

/**
 * Binds a parsed query to a {@link TabularModel}: resolves its names and
 * turns it into a {@link QueryPlan}. Every error of the query is found here,
 * before anything is computed.
 * <p>
 * Supported so far: table references, table constructors and {@code ROW} of
 * constants, {@code ROW} and {@code SUMMARIZECOLUMNS} of columns and measure
 * references, {@code DISTINCT} of a column or of a supported table,
 * {@code VALUES} of a column or a table name, {@code FILTER} of a supported
 * table by a condition on its columns and, computed by the cube, on measures,
 * {@code TOPN} of a supported table by its columns and measures, {@code SAMPLE}
 * of one by its columns, {@code SUMMARIZE} of a table, its {@code VALUES} or
 * {@code DISTINCT} or a grouping by its columns and measures, filter tables
 * of {@code SUMMARIZECOLUMNS} of these, {@code KEEPFILTERS} of a filter table
 * or of the table {@code FILTER}, {@code TOPN} or {@code ADDCOLUMNS} iterates, e.g. of
 * {@code VALUES}, {@code ADDCOLUMNS} of a supported table by expressions of
 * its columns, or of measures computed by the cube, {@code CALCULATETABLE} of
 * a supported table by filter tables as of {@code SUMMARIZECOLUMNS} and by
 * conditions on the text of columns of one table, {@code GENERATE} of a
 * table as a filter table is and a grouping of other hierarchies or of
 * levels of its own, computed by the cube for each row, the comparisons, {@code IN} a table
 * constructor, {@code &&}, {@code ||}, {@code AND}, {@code OR}, {@code NOT} and {@code ISBLANK} of
 * constants and columns, {@code SUM}, {@code AVERAGE}, {@code MIN}, {@code MAX},
 * {@code COUNT}, {@code COUNTA} and {@code DISTINCTCOUNT} of a column and
 * {@code CALCULATE} without filters, computed by the cube, constant {@code VAR} and parameter definitions, and {@code ORDER BY} result columns. Anything else
 * fails as not supported yet.
 * </p>
 */
public final class Binder {

    private final TabularModel model;
    private final Map<String, Object> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Object> variables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * @param model      the model the query refers to
     * @param parameters the values of the query parameters by name, without
     *                   {@code @}; a value is {@code null} for BLANK
     */
    public Binder(TabularModel model, Map<String, Object> parameters) {
        this.model = model;
        this.parameters.putAll(parameters);
    }

    /**
     * @param statement the parsed query
     * @return its plan
     * @throws DaxSemanticException if the query does not fit the model or uses
     *                              what is not supported yet
     */
    public QueryPlan bind(DaxStatement statement) throws DaxSemanticException {
        for (DefineClause clause : statement.defineClauses()) {
            define(clause);
        }
        List<EvaluatePlan> evaluates = new ArrayList<>();
        for (EvaluateStatement evaluate : statement.evaluateStatements()) {
            TablePlan table = groupingProperties(table(evaluate.tableExpression()));
            checkProperties(table);
            evaluates.add(new EvaluatePlan(table, orderBy(evaluate.orderBy(), table.columns())));
        }
        return new QueryPlan(evaluates);
    }

    private void define(DefineClause clause) throws DaxSemanticException {
        switch (clause) {
        case VariableDefinition variable -> variables.put(variable.name(), constant(variable.expression()));
        case ParameterDefinition parameter -> {
            // a value the caller gives overrides the query's own
            if (!parameters.containsKey(parameter.name())) {
                parameters.put(parameter.name(), constant(parameter.expression()));
            }
        }
        case MeasureDefinition m -> throw notSupported("DEFINE MEASURE");
        case TableDefinition t -> throw notSupported("DEFINE TABLE");
        case ColumnDefinition c -> throw notSupported("DEFINE COLUMN");
        }
    }

    // --- tables

    private TablePlan table(DaxExpression expression) throws DaxSemanticException {
        return switch (expression) {
        case Entity entity -> tableReference(entity.name());
        case Keyword keyword -> tableReference(keyword.name());
        case TableConstructor constructor -> tableConstructor(constructor);
        case FunctionCall call -> switch (call.functionName().toUpperCase(Locale.ROOT)) {
            case "ROW" -> row(call.arguments());
            case "SUMMARIZECOLUMNS" -> summarizeColumns(call.arguments());
            case "SUMMARIZE" -> summarizeTable(call.arguments());
            case "DISTINCT" -> distinct(call.arguments());
            case "VALUES" -> values(call.arguments());
            case "FILTER" -> filter(call.arguments());
            case "TOPN" -> topN(call.arguments());
            case "SAMPLE" -> sample(call.arguments());
            case "ADDCOLUMNS" -> addColumns(call.arguments());
            case "CALCULATETABLE" -> calculateTable(call.arguments());
            case "GENERATE" -> generate(call.arguments());
            case "KEEPFILTERS" -> throw new DaxSemanticException("KEEPFILTERS can only be used as a filter table, "
                    + "e.g. of SUMMARIZECOLUMNS, or as the table FILTER, TOPN or ADDCOLUMNS iterates");
            default -> throw notSupported("the table function " + call.functionName());
            };
        default -> throw notSupported("a table expression of kind " + kind(expression));
        };
    }

    private TablePlan tableReference(String name) throws DaxSemanticException {
        ModelTable table = model.table(name)
                .orElseThrow(() -> new DaxSemanticException("the table '" + name + "' does not exist"));
        return summarize(table.columns(), List.of());
    }

    private TablePlan tableConstructor(TableConstructor constructor) throws DaxSemanticException {
        List<List<Object>> rows = new ArrayList<>();
        int width = -1;
        for (RowConstructor row : constructor.rows()) {
            if (width >= 0 && row.columns().size() != width) {
                throw new DaxSemanticException("the rows of a table constructor must have the same number of values");
            }
            width = row.columns().size();
            List<Object> values = new ArrayList<>();
            for (DaxExpression value : row.columns()) {
                values.add(constant(value));
            }
            rows.add(values);
        }
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= Math.max(width, 0); i++) {
            names.add(width == 1 ? "Value" : "Value" + i);
        }
        return constantTable(names, rows);
    }

    private TablePlan row(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.isEmpty() || arguments.size() % 2 != 0) {
            throw new DaxSemanticException("ROW takes pairs of a name and an expression");
        }
        List<String> names = new ArrayList<>();
        List<DaxExpression> expressions = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i += 2) {
            names.add(columnName(arguments.get(i), "ROW"));
            expressions.add(arguments.get(i + 1));
        }
        List<NamedMeasure> measures = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            Optional<ScalarPlan> onCube = cubeExpression(expressions.get(i), "ROW");
            if (onCube.isPresent()) {
                measures.add(new NamedMeasure(names.get(i), onCube.get()));
            }
        }
        if (measures.size() == expressions.size()) {
            return summarize(List.of(), measures);
        }
        if (!measures.isEmpty()) {
            throw notSupported("ROW mixing measures and other expressions");
        }
        List<Object> values = new ArrayList<>();
        for (DaxExpression expression : expressions) {
            values.add(constant(expression));
        }
        return constantTable(names, List.of(values));
    }

    private TablePlan summarizeColumns(List<DaxExpression> arguments) throws DaxSemanticException {
        List<ModelColumn> groupBy = new ArrayList<>();
        List<TablePlan> filters = new ArrayList<>();
        int i = 0;
        for (; i < arguments.size() && !(arguments.get(i) instanceof StringLiteral); i++) {
            DaxExpression argument = arguments.get(i);
            if (argument instanceof Identifier identifier) {
                groupBy.add(column(identifier));
            } else {
                filterTable(argument, "SUMMARIZECOLUMNS").ifPresent(filters::add);
            }
        }
        if ((arguments.size() - i) % 2 != 0) {
            throw new DaxSemanticException("SUMMARIZECOLUMNS takes pairs of a name and an expression after its columns");
        }
        List<NamedMeasure> measures = new ArrayList<>();
        for (; i < arguments.size(); i += 2) {
            String name = columnName(arguments.get(i), "SUMMARIZECOLUMNS");
            DaxExpression expression = arguments.get(i + 1);
            ScalarPlan onCube = cubeExpression(expression, "SUMMARIZECOLUMNS")
                    .orElseThrow(() -> notSupported("SUMMARIZECOLUMNS with an expression of kind " + kind(expression)
                            + " of no measure"));
            measures.add(new NamedMeasure(name, onCube));
        }
        Summarize summarize = (Summarize) summarize(groupBy, measures);
        filters = withoutNonBlankFilters(summarize, filters);
        if (filters.isEmpty()) {
            return summarize;
        }
        checkFilters(summarize, filters);
        return new Summarize(summarize.groupBy(), measures, Optional.empty(), Optional.empty(), filters);
    }

    /**
     * @param function the function the filter table is of, for errors
     * @return the plan of a filter table of SUMMARIZECOLUMNS or
     *         CALCULATETABLE; empty if it filters nothing, as all values of
     *         columns
     */
    /**
     * {@code SUMMARIZE} of a table, its {@code VALUES} or {@code DISTINCT}, or a
     * grouping of columns: a grouping by its columns, the measures added, as they
     * keep the groups where they are BLANK. With {@code ROLLUP} of its last
     * columns, a {@link Rollup} of the groupings by fewer of them too, whose
     * {@code ISSUBTOTAL} tells the subtotals.
     */
    private TablePlan summarizeTable(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.size() < 2) {
            throw new DaxSemanticException("SUMMARIZE takes a table, columns and pairs of a name and an expression");
        }
        Summarized summarized = summarized(withoutKeepFilters(arguments.get(0)));
        List<ModelColumn> source = summarized.columns();
        List<ModelColumn> groupBy = new ArrayList<>();
        List<ModelColumn> rolled = new ArrayList<>();
        int i = 1;
        for (; i < arguments.size() && !(arguments.get(i) instanceof StringLiteral); i++) {
            DaxExpression argument = arguments.get(i);
            List<DaxExpression> columns = List.of(argument);
            boolean rolling = argument instanceof FunctionCall call && call.functionName().equalsIgnoreCase("ROLLUP");
            if (rolling) {
                FunctionCall call = (FunctionCall) argument;
                if (!rolled.isEmpty() || call.arguments().isEmpty()) {
                    throw notSupported("SUMMARIZE with several ROLLUP or one of no columns");
                }
                columns = call.arguments();
            } else if (!rolled.isEmpty()) {
                throw notSupported("SUMMARIZE with columns after ROLLUP");
            }
            for (DaxExpression expression : columns) {
                if (expression instanceof FunctionCall call && (call.functionName().equalsIgnoreCase("ROLLUPGROUP")
                        || call.functionName().equalsIgnoreCase("ROLLUP"))) {
                    throw notSupported("SUMMARIZE with " + call.functionName().toUpperCase(Locale.ROOT)
                            + (columns.size() == 1 ? "" : " in ROLLUP"));
                }
                if (!(expression instanceof Identifier identifier)) {
                    throw new DaxSemanticException("SUMMARIZE takes columns to group by, not an expression of kind "
                            + kind(expression));
                }
                ModelColumn column = column(identifier);
                if (!source.contains(column)) {
                    throw new DaxSemanticException("SUMMARIZE: the column " + column.daxName()
                            + " is not of its table");
                }
                if (groupBy.contains(column) || rolled.contains(column)) {
                    throw new DaxSemanticException("SUMMARIZE: the column " + column.daxName() + " is twice");
                }
                (rolling ? rolled : groupBy).add(column);
            }
        }
        if ((arguments.size() - i) % 2 != 0) {
            throw new DaxSemanticException("SUMMARIZE takes pairs of a name and an expression after its columns");
        }
        List<NamedMeasure> measures = new ArrayList<>();
        // of each named column, the measure or the column rolled up it is ISSUBTOTAL of; -1 if none is
        List<String> named = new ArrayList<>();
        List<Integer> subtotals = new ArrayList<>();
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (; i < arguments.size(); i += 2) {
            String name = columnName(arguments.get(i), "SUMMARIZE");
            if (!names.add(name)) {
                throw new DaxSemanticException("SUMMARIZE: the column [" + name + "] exists already");
            }
            named.add(name);
            DaxExpression expression = arguments.get(i + 1);
            if (expression instanceof FunctionCall call && call.functionName().equalsIgnoreCase("ISSUBTOTAL")) {
                if (!(onlyArgument(call, "ISSUBTOTAL takes one column") instanceof Identifier identifier)) {
                    throw new DaxSemanticException("ISSUBTOTAL takes a column");
                }
                ModelColumn column = column(identifier);
                if (!groupBy.contains(column) && !rolled.contains(column)) {
                    throw new DaxSemanticException("ISSUBTOTAL: SUMMARIZE does not group by " + column.daxName());
                }
                subtotals.add(rolled.indexOf(column));
                continue;
            }
            ScalarPlan onCube = cubeExpression(expression, "SUMMARIZE")
                    .orElseThrow(() -> notSupported("SUMMARIZE with an expression of kind " + kind(expression)
                            + " of no measure"));
            measures.add(new NamedMeasure(name, onCube));
            subtotals.add(null);
        }
        List<ModelColumn> all = new ArrayList<>(groupBy);
        all.addAll(rolled);
        if (summarized.ofAllColumns() && !all.containsAll(source)) {
            throw notSupported("SUMMARIZE by some of the columns of FILTER by measures, other than stored ones"
                    + " not BLANK");
        }
        Summarize summarize = summarizeGrouping(all, measures, summarized);
        if (rolled.isEmpty() && subtotals.stream().allMatch(Objects::isNull)) {
            return summarize;
        }
        TablePlan table = summarize;
        if (!rolled.isEmpty()) {
            List<TablePlan> levels = new ArrayList<>(List.of(summarize));
            for (int j = 1; j <= rolled.size(); j++) {
                // a group of the subtotals is kept where the groups have rows of it
                List<ModelColumn> fewer = all.subList(0, all.size() - j);
                levels.add(fewer.isEmpty() ? new Summarize(List.of(), measures)
                        : ((Summarize) summarize(fewer, List.of())).withAdded(measures));
            }
            table = new Rollup(levels, groupBy.size(), rolled.size());
        }
        // the named columns in their order
        List<AddColumns.Added> added = new ArrayList<>();
        int measure = all.size();
        for (int n = 0; n < named.size(); n++) {
            Integer subtotal = subtotals.get(n);
            if (subtotal == null) {
                NamedMeasure computed = measures.get(measure - all.size());
                added.add(new AddColumns.Added(named.get(n), new ColumnValue(measure++), computed.type()));
            } else {
                added.add(new AddColumns.Added(named.get(n), subtotal < 0 ? new Constant(Boolean.FALSE)
                        : new ColumnValue(all.size() + measures.size() + subtotal), DaxType.BOOLEAN));
            }
        }
        return new AddColumns(table, all.size(), added);
    }

    /** @return the grouping of SUMMARIZE by the columns, the measures added */
    private Summarize summarizeGrouping(List<ModelColumn> groupBy, List<NamedMeasure> measures,
            Summarized summarized)
            throws DaxSemanticException {
        Summarize summarize = (Summarize) summarize(groupBy, List.of());
        if (summarized.condition().isPresent()) {
            // of the columns of its table
            int[] index = summarized.columns().stream().mapToInt(groupBy::indexOf).toArray();
            summarize = summarize.filtered(remapped(summarized.condition().get(), index));
        }
        if (summarized.keeping().isPresent()) {
            if (groupBy.isEmpty()) {
                throw notSupported("SUMMARIZE without columns of GENERATE keeping where measures not stored"
                        + " are not BLANK");
            }
            summarize = new Summarize(summarize.groupBy(), summarize.measures(), summarize.condition(),
                    summarize.top(), List.of(summarized.keeping().get()));
        }
        return measures.isEmpty() ? summarize : summarize.withAdded(measures);
    }

    /**
     * The table {@code SUMMARIZE} groups.
     *
     * @param columns   its columns
     * @param condition of measures only, for which a group is one of its rows
     * @param keeping      the filter table whose rows the groups are related
     *                     to, where the condition is of measures not stored:
     *                     one of them may be BLANK of a group though not of its
     *                     members
     * @param ofAllColumns whether the condition is of the groups by all the
     *                     columns only, as of {@code FILTER} of their values
     *                     by measures other than stored ones not BLANK
     */
    private record Summarized(List<ModelColumn> columns, Optional<ScalarPlan> condition,
            Optional<TablePlan> keeping, boolean ofAllColumns) {

        Summarized(List<ModelColumn> columns, Optional<ScalarPlan> condition) {
            this(columns, condition, Optional.empty(), false);
        }
    }

    /**
     * @return the table SUMMARIZE groups: a table, its VALUES or DISTINCT, a
     *         grouping of columns, {@code FILTER} of one by measures, whose
     *         groups by all the columns are its rows, those by fewer, where
     *         stored measures are not BLANK, the groups where they are not
     *         BLANK, or {@code GENERATE} of such ones and of one
     *         keeping the values where measures are not BLANK, whose groups
     *         are those where these measures are not BLANK, as a stored
     *         measure is BLANK of a group where it is of each member; with
     *         others, those related to its rows
     */
    private Summarized summarized(DaxExpression table) throws DaxSemanticException {
        String name = switch (table) {
        case Entity entity -> entity.name();
        case Keyword keyword -> keyword.name();
        case FunctionCall call when call.arguments().size() == 1
                && List.of("VALUES", "DISTINCT").contains(call.functionName().toUpperCase(Locale.ROOT)) ->
            switch (call.arguments().get(0)) {
            case Entity entity -> entity.name();
            case Keyword keyword -> keyword.name();
            default -> null;
            };
        default -> null;
        };
        if (name != null) {
            // its columns only: the table of several hierarchies is not computed
            return new Summarized(model.table(name)
                    .orElseThrow(() -> new DaxSemanticException("the table '" + name + "' does not exist")).columns(),
                    Optional.empty());
        }
        TablePlan plan = table(table);
        if (plan instanceof Summarize summarize && summarize.allMeasures().isEmpty() && summarize.top().isEmpty()
                && summarize.filters().isEmpty()) {
            // FILTER of the values by measures: its rows are the groups by all its columns
            boolean exists = summarize.condition()
                    .map(c -> Summarize.existsCondition(c) && collectMeasures(c, new LinkedHashSet<>())).orElse(true);
            return new Summarized(summarize.groupBy(), summarize.condition(), Optional.empty(), !exists);
        }
        if (plan instanceof Generate generate) {
            Summarize inner = generate.inner();
            Set<ModelMeasure> kept = new LinkedHashSet<>();
            boolean stored = inner.condition().map(c -> collectMeasures(c, kept)).orElse(true);
            if (inner.allMeasures().isEmpty() && inner.top().isEmpty() && inner.filters().isEmpty()
                    && inner.condition().map(Summarize::existsCondition).orElse(true)
                    && keepsWhereNotBlank(generate.outer(), kept)) {
                return stored ? new Summarized(Summarize.filterColumns(generate), inner.condition())
                        : new Summarized(Summarize.filterColumns(generate), Optional.empty(),
                                Optional.of(generate), false);
            }
        }
        throw notSupported("SUMMARIZE of a table other than a table, its VALUES or DISTINCT or a grouping of columns");
    }

    private Optional<TablePlan> filterTable(DaxExpression argument, String function) throws DaxSemanticException {
        TablePlan table = table(withoutKeepFilters(argument));
        if (!filterTableOnCube(table)) {
            throw notSupported(function + " with a filter table of kind " + table.getClass().getSimpleName()
                    + " or with conditions other than on the text of columns and on measures");
        }
        if (table instanceof Summarize summarize && summarize.condition().isEmpty() && summarize.top().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(table);
    }

    /**
     * @return the table KEEPFILTERS is of, as a filter table or the table of an
     *         iterator; the argument if it is no KEEPFILTERS
     */
    private static DaxExpression withoutKeepFilters(DaxExpression argument) throws DaxSemanticException {
        // without outer filters there is nothing KEEPFILTERS keeps: it is its table
        while (argument instanceof FunctionCall call && call.functionName().equalsIgnoreCase("KEEPFILTERS")) {
            if (call.arguments().size() != 1) {
                throw new DaxSemanticException("KEEPFILTERS takes one table");
            }
            argument = call.arguments().get(0);
        }
        return argument;
    }

    /** @return whether the cube computes the table as a filter, see {@link Summarize#filters()} */
    private static boolean filterTableOnCube(TablePlan table) {
        return switch (table) {
        case Summarize summarize -> !summarize.groupBy().isEmpty() && summarize.measures().isEmpty()
                && summarize.filters().isEmpty();
        case Filter filter -> filterTableOnCube(filter.source()) && onMembers(filter.condition());
        // the tuples of its rows, without measures
        case Generate generate -> filterTableOnCube(generate.outer()) && !generate.inner().groupBy().isEmpty()
                && generate.inner().allMeasures().isEmpty();
        default -> false;
        };
    }

    /** @return whether the cube computes the condition on the names of members, see {@code MdxGenerator} */
    private static boolean onMembers(ScalarPlan plan) {
        return switch (plan) {
        case Comparison comparison -> (comparison.left() instanceof ColumnValue
                || comparison.left() instanceof Constant c && c.value() instanceof String)
                && (comparison.right() instanceof ColumnValue
                        || comparison.right() instanceof Constant c && c.value() instanceof String);
        case InList in -> in.value() instanceof ColumnValue
                && in.values().stream().allMatch(v -> v == null || v instanceof String);
        case Logical logical -> onMembers(logical.left()) && onMembers(logical.right());
        case Not not -> onMembers(not.operand());
        default -> false;
        };
    }

    /**
     * A filter on hierarchies not grouped by goes to the slicer, which takes one
     * filter per hierarchy; one on hierarchies grouped by keeps the groups, and
     * with measures it must not be deeper than the groups, or they would compute
     * too much.
     */
    private static void checkFilters(Summarize summarize, List<TablePlan> filters) throws DaxSemanticException {
        Map<String, Integer> grouped = new TreeMap<>();
        for (ModelColumn column : summarize.groupBy()) {
            grouped.merge(column.hierarchy(), column.depth(), Math::max);
        }
        // the filters on the same hierarchies are one set of the slicer, their intersection
        Map<String, Set<String>> sliced = new TreeMap<>();
        for (TablePlan filter : filters) {
            List<ModelColumn> columns = Summarize.filterColumns(filter);
            Set<String> hierarchies = new LinkedHashSet<>();
            columns.forEach(c -> hierarchies.add(c.hierarchy()));
            if (hierarchies.stream().noneMatch(grouped::containsKey)) {
                for (String hierarchy : hierarchies) {
                    Set<String> together = sliced.putIfAbsent(hierarchy, hierarchies);
                    if (together != null && !together.equals(hierarchies)) {
                        throw notSupported("filter tables on the hierarchy " + hierarchy + " together with others");
                    }
                }
            } else if (grouped.keySet().containsAll(hierarchies)) {
                for (ModelColumn column : columns) {
                    // the measures are then aggregated over the members the filters keep, see MdxGenerator
                    if (computesMeasures(summarize) && column.depth() > grouped.get(column.hierarchy())
                            && (hierarchies.size() > 1 || !summarize.byValues().isEmpty()
                                    || !computesStoredMeasures(summarize))) {
                        throw notSupported("a filter table on " + column.daxName()
                                + ", deeper than SUMMARIZECOLUMNS groups by, with measures other than stored ones"
                                + " or on several hierarchies");
                    }
                }
            } else {
                throw notSupported("a filter table on hierarchies grouped by and others");
            }
        }
    }

    /** @return whether all the cube computes for the groups are stored measures, which it can aggregate */
    private static boolean computesStoredMeasures(Summarize summarize) {
        Set<ModelMeasure> measures = new LinkedHashSet<>();
        for (NamedMeasure measure : summarize.allMeasures()) {
            if (!collectMeasures(measure.expression(), measures)) {
                return false;
            }
        }
        return summarize.condition().map(c -> collectMeasures(c, measures)).orElse(true)
                && summarize.top().map(t -> t.measure().stored()).orElse(true);
    }

    /** @return whether the cube computes measures for the groups, to keep them or not */
    private static boolean computesMeasures(Summarize summarize) {
        return !summarize.measures().isEmpty() || !summarize.added().isEmpty() || summarize.condition().isPresent()
                || summarize.top().isPresent();
    }

    /**
     * Replaces each {@code GENERATE} computing measures by a property without
     * its level, which MDX {@code Generate} of members cannot, by the grouping
     * by all its columns where it is one: the inner rows of each outer row are
     * then the groups of the values together, the measures computed for each.
     *
     * @return the table, with such groupings
     */
    private static TablePlan groupingProperties(TablePlan table) {
        return switch (table) {
        case Generate generate when computesByProperties(generate) -> grouping(generate).<TablePlan>map(s -> s)
                .orElse(generate);
        case Filter filter -> new Filter(groupingProperties(filter.source()), filter.condition());
        case TopN topN -> new TopN(groupingProperties(topN.source()), topN.count(), topN.keys(), topN.partition());
        case Sample sample -> new Sample(groupingProperties(sample.source()), sample.count(), sample.keys());
        case Rollup rollup -> new Rollup(rollup.levels().stream().map(Binder::groupingProperties).toList(),
                rollup.keys(), rollup.rolled());
        case AddColumns addColumns -> new AddColumns(groupingProperties(addColumns.source()), addColumns.width(),
                addColumns.added());
        default -> table;
        };
    }

    /** @return whether the inner grouping computes measures by a property without its level */
    private static boolean computesByProperties(Generate generate) {
        List<ModelColumn> columns = Summarize.filterColumns(generate);
        Summarize inner = generate.inner();
        boolean computes = !inner.allMeasures().isEmpty() || inner.top().isPresent()
                || inner.condition().filter(c -> !Summarize.existsCondition(c)).isPresent();
        return computes && columns.stream().anyMatch(c -> Summarize.withoutItsLevel(c, columns));
    }

    /**
     * @return the grouping by the columns of the {@code GENERATE}, computing
     *         the measures of the inner grouping, if it has the same rows: the
     *         inner grouping has no top and keeps the groups where one of its
     *         measures is not BLANK, if any; the outer tables are groupings
     *         without measures, or {@code GENERATE} of them, keeping all values
     *         or those where one of these measures is not BLANK, which the
     *         inner ones are values of
     */
    private static Optional<Summarize> grouping(Generate generate) {
        Summarize inner = generate.inner();
        if (inner.top().isPresent() || !inner.filters().isEmpty()
                || inner.condition().filter(c -> !Summarize.existsCondition(c)).isPresent()) {
            return Optional.empty();
        }
        Set<ModelMeasure> kept = new LinkedHashSet<>();
        inner.condition().ifPresent(c -> collectMeasures(c, kept));
        if (!keepsWhereNotBlank(generate.outer(), kept)) {
            return Optional.empty();
        }
        return Optional.of(new Summarize(Summarize.filterColumns(generate), inner.measures(), inner.condition(),
                Optional.empty(), List.of(), inner.added()));
    }

    /**
     * @param kept the measures one of which is not BLANK in each group kept
     * @return whether the outer table of a {@code GENERATE} keeps all values of
     *         its columns or those where one of the measures is not BLANK, so
     *         at least each of the groups kept
     */
    private static boolean keepsWhereNotBlank(TablePlan outer, Set<ModelMeasure> kept) {
        return switch (outer) {
        case Summarize table -> table.allMeasures().isEmpty() && table.top().isEmpty() && table.filters().isEmpty()
                && table.condition().map(c -> {
                    Set<ModelMeasure> measures = new LinkedHashSet<>();
                    collectMeasures(c, measures);
                    return Summarize.existsCondition(c) && !kept.isEmpty() && measures.containsAll(kept);
                }).orElse(true);
        case Generate generate -> keepsWhereNotBlank(generate.outer(), kept)
                && keepsWhereNotBlank(generate.inner(), kept);
        default -> false;
        };
    }

    /**
     * A property of the members of a level grouped by without the level or a
     * deeper one of its hierarchy has a row for each member: they are made
     * distinct after the query. Measures for its values are computed by
     * calculated members of the groups, see {@link Summarize#byValues()},
     * which have no names or properties to filter on.
     */
    private static void checkProperties(TablePlan table) throws DaxSemanticException {
        switch (table) {
        case Summarize summarize -> checkByValues(summarize);
        case Generate generate -> {
            List<ModelColumn> columns = new ArrayList<>(Summarize.filterColumns(generate.outer()));
            columns.addAll(generate.inner().groupBy());
            Summarize inner = generate.inner();
            boolean computes = !inner.allMeasures().isEmpty() || inner.top().isPresent()
                    || inner.condition().filter(c -> !Summarize.existsCondition(c)).isPresent();
            for (ModelColumn column : columns) {
                if (computes && Summarize.withoutItsLevel(column, columns)) {
                    throw notSupported("GENERATE computing measures by " + column.daxName()
                            + ", a property, without its level");
                }
            }
        }
        case Filter filter -> checkProperties(filter.source());
        case TopN topN -> checkProperties(topN.source());
        case Sample sample -> checkProperties(sample.source());
        case Rollup rollup -> {
            for (TablePlan level : rollup.levels()) {
                checkProperties(level);
            }
        }
        case AddColumns addColumns -> checkProperties(addColumns.source());
        case ConstantTable constant -> {
        }
        }
    }

    private static void checkByValues(Summarize summarize) throws DaxSemanticException {
        Set<String> byValues = summarize.byValues();
        if (byValues.isEmpty()) {
            return;
        }
        Set<Integer> grouped = new TreeSet<>();
        for (int i = 0; i < summarize.groupBy().size(); i++) {
            ModelColumn column = summarize.groupBy().get(i);
            if (byValues.contains(column.hierarchy())) {
                grouped.add(i);
            }
        }
        if (summarize.condition().isPresent() && usesColumns(summarize.condition().get(), grouped)) {
            throw notSupported("a condition by measures on the columns of " + String.join(", ", byValues)
                    + ", grouped by the values of a property without its level");
        }
        for (TablePlan filter : summarize.filters()) {
            for (ModelColumn column : Summarize.filterColumns(filter)) {
                if (byValues.contains(column.hierarchy())) {
                    throw notSupported("a filter table on " + column.daxName()
                            + ", grouped by the values of a property without its level");
                }
            }
        }
    }

    /** @return whether the plan refers to one of the columns */
    private static boolean usesColumns(ScalarPlan plan, Set<Integer> columns) {
        return switch (plan) {
        case ColumnValue column -> columns.contains(column.column());
        case InList in -> usesColumns(in.value(), columns);
        case Comparison comparison -> usesColumns(comparison.left(), columns)
                || usesColumns(comparison.right(), columns);
        case Logical logical -> usesColumns(logical.left(), columns) || usesColumns(logical.right(), columns);
        case Not not -> usesColumns(not.operand(), columns);
        case IsBlank isBlank -> usesColumns(isBlank.operand(), columns);
        case Currency currency -> usesColumns(currency.operand(), columns);
        default -> false;
        };
    }

    /** @return whether a filter table, see {@link Summarize#filters()}, keeps rows by a measure */
    private static boolean filtersByMeasure(TablePlan filter) {
        return switch (filter) {
        case Summarize summarize -> computesMeasures(summarize);
        case Filter f -> usesMeasure(f.condition()) || filtersByMeasure(f.source());
        case Generate generate -> filtersByMeasure(generate.outer()) || filtersByMeasure(generate.inner());
        default -> false;
        };
    }

    private TablePlan generate(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.size() != 2) {
            throw new DaxSemanticException("GENERATE takes two tables");
        }
        TablePlan outer = table(withoutKeepFilters(arguments.get(0)));
        TablePlan inner = table(withoutKeepFilters(arguments.get(1)));
        // its columns must be those of the set of its members, not also added ones
        if (!filterTableOnCube(outer) || Summarize.filterColumns(outer).size() != outer.columns().size()) {
            throw notSupported("GENERATE of a first table of kind " + outer.getClass().getSimpleName()
                    + ", with measures or with conditions other than on the text of columns and on measures");
        }
        // a level of an outer hierarchy: its members under the outer one, or the outer one's ancestor
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (DaxColumn column : outer.columns()) {
            names.add(column.name());
        }
        for (DaxColumn column : inner.columns()) {
            if (!names.add(column.name())) {
                throw new DaxSemanticException("GENERATE: the column " + column.name() + " is in both tables");
            }
        }
        return perRow(outer, inner);
    }

    /**
     * @param outer the outer table of {@code GENERATE}
     * @return the inner table computed for each outer row, the outer columns
     *         first: of a grouping a {@link Generate}; of the tables computed
     *         on the rows of their source, those of the source computed so,
     *         {@code TOPN} for each outer row
     */
    private static TablePlan perRow(TablePlan outer, TablePlan inner) throws DaxSemanticException {
        int width = outer.columns().size();
        return switch (inner) {
        case Summarize summarize when summarize.filters().isEmpty() -> new Generate(outer, summarize);
        case TopN topN -> new TopN(perRow(outer, topN.source()), topN.count(),
                topN.keys().stream().map(k -> new SortKey(k.column() + width, k.ascending())).toList(),
                width + topN.partition());
        case AddColumns addColumns -> {
            List<AddColumns.Added> added = new ArrayList<>();
            for (AddColumns.Added column : addColumns.added()) {
                added.add(new AddColumns.Added(column.name(), shifted(column.expression(), width), column.type()));
            }
            yield new AddColumns(perRow(outer, addColumns.source()), width + addColumns.width(), added);
        }
        case Filter filter -> new Filter(perRow(outer, filter.source()), shifted(filter.condition(), width));
        case Rollup rollup -> {
            List<TablePlan> levels = new ArrayList<>();
            for (TablePlan level : rollup.levels()) {
                levels.add(perRow(outer, level));
            }
            yield new Rollup(levels, width + rollup.keys(), rollup.rolled());
        }
        default -> throw notSupported("GENERATE with a second table other than a grouping of columns and measures, "
                + "e.g. VALUES, TOPN or FILTER by a measure, SUMMARIZE, SUMMARIZECOLUMNS without filter tables or ROW,"
                + " or TOPN, FILTER or ADDCOLUMNS of one");
        };
    }

    /** @return the plan on rows with more columns before */
    private static ScalarPlan shifted(ScalarPlan plan, int columns) {
        int[] index = new int[usedColumns(plan) + 1];
        for (int i = 0; i < index.length; i++) {
            index[i] = i + columns;
        }
        return remapped(plan, index);
    }

    /** @return the greatest column index the plan refers to; -1 if none */
    private static int usedColumns(ScalarPlan plan) {
        return switch (plan) {
        case ColumnValue column -> column.column();
        case InList in -> usedColumns(in.value());
        case Comparison comparison -> Math.max(usedColumns(comparison.left()), usedColumns(comparison.right()));
        case Logical logical -> Math.max(usedColumns(logical.left()), usedColumns(logical.right()));
        case Not not -> usedColumns(not.operand());
        case IsBlank isBlank -> usedColumns(isBlank.operand());
        case Currency currency -> usedColumns(currency.operand());
        default -> -1;
        };
    }

    private TablePlan calculateTable(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.isEmpty()) {
            throw new DaxSemanticException("CALCULATETABLE takes a table and filters");
        }
        TablePlan table = table(arguments.get(0));
        List<TablePlan> filters = new ArrayList<>();
        for (DaxExpression argument : arguments.subList(1, arguments.size())) {
            // without outer filters there is nothing KEEPFILTERS keeps
            DaxExpression filter = withoutKeepFilters(argument);
            if (isCondition(filter)) {
                filters.add(conditionFilter(filter));
            } else {
                filterTable(filter, "CALCULATETABLE").ifPresent(filters::add);
            }
        }
        if (filters.isEmpty()) {
            return table;
        }
        return calculated(table, filters);
    }

    /** @return whether a filter argument is a condition, as {@code 'Product'[Category] = "Bikes"} */
    private static boolean isCondition(DaxExpression argument) {
        return argument instanceof BooleanExpression || argument instanceof LogicalExpression
                || argument instanceof FunctionCall call
                        && List.of("NOT", "AND", "OR").contains(call.functionName().toUpperCase(Locale.ROOT));
    }

    /**
     * @return the filter table of a condition on columns of one table: the
     *         values of the columns for which it is TRUE, as {@code FILTER} of
     *         {@code ALL} of them
     */
    private TablePlan conditionFilter(DaxExpression condition) throws DaxSemanticException {
        Set<ModelColumn> columns = new LinkedHashSet<>();
        collectColumns(condition, columns);
        if (columns.isEmpty()) {
            throw new DaxSemanticException("a condition filtering CALCULATETABLE must refer to a column");
        }
        if (columns.stream().map(c -> c.table().toLowerCase(Locale.ROOT)).distinct().count() > 1) {
            throw new DaxSemanticException("a condition filtering CALCULATETABLE must refer to columns of one table");
        }
        TablePlan values = summarize(List.copyOf(columns), List.of());
        ScalarPlan plan = scalar(condition, values.columns());
        if (!onMembers(plan)) {
            throw notSupported("CALCULATETABLE with a condition other than on the text of columns");
        }
        return new Filter(values, plan);
    }

    /** Collects the columns of the model the expression refers to; other names are left to {@link #scalar}. */
    private void collectColumns(DaxExpression expression, Set<ModelColumn> columns) throws DaxSemanticException {
        switch (expression) {
        case Identifier identifier -> {
            String[] name = tableAndName(identifier);
            model.table(name[0]).flatMap(t -> t.column(name[1])).ifPresent(columns::add);
        }
        case BooleanExpression comparison -> {
            collectColumns(comparison.left(), columns);
            collectColumns(comparison.right(), columns);
        }
        case LogicalExpression logical -> {
            collectColumns(logical.left(), columns);
            collectColumns(logical.right(), columns);
        }
        case FunctionCall call -> {
            for (DaxExpression argument : call.arguments()) {
                collectColumns(argument, columns);
            }
        }
        default -> {
        }
        }
    }

    /**
     * @return the table computed with the filter tables too, as CALCULATETABLE
     *         computes it: they filter all the cube computes for it
     */
    private static TablePlan calculated(TablePlan table, List<TablePlan> filters) throws DaxSemanticException {
        return switch (table) {
        case Summarize summarize -> {
            List<TablePlan> all = new ArrayList<>(summarize.filters());
            Set<ModelColumn> filtered = new LinkedHashSet<>();
            all.forEach(f -> filtered.addAll(Summarize.filterColumns(f)));
            for (TablePlan filter : filters) {
                for (ModelColumn column : Summarize.filterColumns(filter)) {
                    // an inner filter on a column may replace the outer one, not keep both
                    if (filtered.contains(column)) {
                        throw notSupported("CALCULATETABLE filtering " + column.daxName()
                                + " of a table filtered on it already");
                    }
                }
            }
            all.addAll(filters);
            all = withoutNonBlankFilters(summarize, all);
            // a filter by a measure would be computed within the others, or they within it
            if (all.size() > 1 && all.stream().anyMatch(Binder::filtersByMeasure)) {
                throw notSupported("CALCULATETABLE with several filters, of them one by a measure");
            }
            Summarize calculated = new Summarize(summarize.groupBy(), summarize.measures(), summarize.condition(),
                    summarize.top(), all, summarize.added());
            if (!computesMeasures(calculated)) {
                // without measures a slicer does not filter the groups, as a filter on their table would
                Set<String> grouped = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                Set<String> hierarchies = new TreeSet<>();
                for (ModelColumn column : calculated.groupBy()) {
                    grouped.add(column.table());
                    hierarchies.add(column.hierarchy());
                }
                for (TablePlan filter : filters) {
                    List<ModelColumn> columns = Summarize.filterColumns(filter);
                    if (columns.stream().noneMatch(c -> hierarchies.contains(c.hierarchy()))
                            && columns.stream().anyMatch(c -> grouped.contains(c.table()))) {
                        throw notSupported("CALCULATETABLE of columns without measures filtered on other "
                                + "hierarchies of their table");
                    }
                }
            }
            checkFilters(calculated, all);
            yield calculated;
        }
        // the rows are computed as before, of the filtered source
        case Filter filter -> new Filter(calculated(filter.source(), filters), filter.condition());
        case TopN topN -> new TopN(calculated(topN.source(), filters), topN.count(), topN.keys(), topN.partition());
        case Sample sample -> new Sample(calculated(sample.source(), filters), sample.count(), sample.keys());
        case Rollup rollup -> {
            List<TablePlan> levels = new ArrayList<>();
            for (TablePlan level : rollup.levels()) {
                levels.add(calculated(level, filters));
            }
            yield new Rollup(levels, rollup.keys(), rollup.rolled());
        }
        case AddColumns addColumns ->
            new AddColumns(calculated(addColumns.source(), filters), addColumns.width(), addColumns.added());
        // constants are not filtered
        case ConstantTable constant -> constant;
        // the outer rows the filters keep, each joined with the inner ones computed for it
        case Generate generate -> {
            TablePlan outer = generate.outer();
            for (TablePlan filter : filters) {
                outer = restricted(outer, filter);
            }
            yield new Generate(outer, generate.inner());
        }
        };
    }

    /**
     * @param table  a filter table, see {@link Summarize#filters()}
     * @param filter a filter table on columns of the table only, by the text of
     *               columns, as {@code FILTER(VALUES(column), column = "x")}
     * @return the rows of the table the filter keeps
     */
    private static TablePlan restricted(TablePlan table, TablePlan filter) throws DaxSemanticException {
        List<ModelColumn> columns = Summarize.filterColumns(table);
        return switch (filter) {
        // all values of its columns: keeps all rows
        case Summarize values when values.measures().isEmpty() && values.condition().isEmpty()
                && values.top().isEmpty() && values.filters().isEmpty()
                && columns.containsAll(values.groupBy()) -> table;
        case Filter f when onMembers(f.condition()) -> {
            List<ModelColumn> filtered = Summarize.filterColumns(f.source());
            int[] index = new int[filtered.size()];
            for (int i = 0; i < index.length; i++) {
                index[i] = columns.indexOf(filtered.get(i));
            }
            if (Arrays.stream(index).anyMatch(i -> i < 0)) {
                throw notSupported("CALCULATETABLE of GENERATE filtered on columns other than of its first table");
            }
            yield new Filter(restricted(table, f.source()), remapped(f.condition(), index));
        }
        default -> throw notSupported("CALCULATETABLE of GENERATE filtered other than by the text of columns of "
                + "its first table");
        };
    }

    /** @return the plan with each column index {@code i} replaced by {@code index[i]} */
    private static ScalarPlan remapped(ScalarPlan plan, int[] index) {
        return switch (plan) {
        case ColumnValue column -> new ColumnValue(index[column.column()]);
        case Comparison comparison -> new Comparison(comparison.operator(), remapped(comparison.left(), index),
                remapped(comparison.right(), index));
        case InList in -> new InList(remapped(in.value(), index), in.values());
        case Logical logical -> new Logical(logical.operator(), remapped(logical.left(), index),
                remapped(logical.right(), index));
        case Not not -> new Not(remapped(not.operand(), index));
        case IsBlank isBlank -> new IsBlank(remapped(isBlank.operand(), index));
        case Currency currency -> new Currency(remapped(currency.operand(), index));
        case Constant constant -> constant;
        case MeasureValue measure -> measure;
        case ColumnAggregate aggregate -> aggregate;
        };
    }

    /**
     * Drops the filter tables on hierarchies not grouped by that keep the
     * members where stored measures are not BLANK, as
     * {@code FILTER(VALUES(column), NOT(ISBLANK([measure])))} or
     * {@code GENERATE} of such ones and of {@code VALUES}, when the
     * grouping computes only these measures: a stored measure is BLANK where
     * it is BLANK of each member, so the members dropped change none of them.
     * In the slicer, the members kept would be aggregated by the cube, which
     * it cannot for more than its maximum constraints.
     *
     * @return the filter tables without those
     */
    private static List<TablePlan> withoutNonBlankFilters(Summarize summarize, List<TablePlan> filters) {
        Set<ModelMeasure> computed = new LinkedHashSet<>();
        for (NamedMeasure measure : summarize.allMeasures()) {
            if (!collectMeasures(measure.expression(), computed)) {
                return filters;
            }
        }
        if (summarize.condition().isPresent() && !collectMeasures(summarize.condition().get(), computed)) {
            return filters;
        }
        summarize.top().ifPresent(top -> computed.add(top.measure()));
        if (computed.isEmpty()) {
            return filters;
        }
        Set<String> grouped = new TreeSet<>();
        summarize.groupBy().forEach(c -> grouped.add(c.hierarchy()));
        List<TablePlan> kept = new ArrayList<>();
        for (TablePlan filter : filters) {
            if (!(filter instanceof Summarize || filter instanceof Generate)
                    || Summarize.filterColumns(filter).stream().anyMatch(c -> grouped.contains(c.hierarchy()))
                    || !keepsNonBlank(filter, computed)) {
                kept.add(filter);
            }
        }
        return kept;
    }

    /**
     * @param computed the stored measures computed within the filter table
     * @return whether the filter table keeps at least the members where one of
     *         the measures is not BLANK: values of columns, each kept where one
     *         of them is not BLANK or all of them, as {@code GENERATE} of
     *         {@code FILTER(VALUES(column), NOT(ISBLANK([measure])))} and
     *         {@code VALUES(column)} gives them
     */
    private static boolean keepsNonBlank(TablePlan filter, Set<ModelMeasure> computed) {
        return switch (filter) {
        case Summarize table -> !table.groupBy().isEmpty() && table.allMeasures().isEmpty() && table.top().isEmpty()
                && table.filters().isEmpty() && table.condition()
                        .map(c -> nonBlankMeasures(c).filter(m -> m.containsAll(computed)).isPresent()).orElse(true);
        case Generate generate -> keepsNonBlank(generate.outer(), computed)
                && keepsNonBlank(generate.inner(), computed);
        default -> false;
        };
    }

    /**
     * @param measures the measures the plan uses are added to
     * @return whether the plan uses only stored measures, of no columns
     *         aggregated, which members of other hierarchies filter
     */
    private static boolean collectMeasures(ScalarPlan plan, Set<ModelMeasure> measures) {
        return switch (plan) {
        case MeasureValue measure -> {
            measures.add(measure.measure());
            yield measure.measure().stored();
        }
        case Constant constant -> true;
        case ColumnValue column -> true;
        case Comparison comparison -> collectMeasures(comparison.left(), measures)
                && collectMeasures(comparison.right(), measures);
        case InList in -> collectMeasures(in.value(), measures);
        case Logical logical -> collectMeasures(logical.left(), measures)
                && collectMeasures(logical.right(), measures);
        case Not not -> collectMeasures(not.operand(), measures);
        case IsBlank isBlank -> collectMeasures(isBlank.operand(), measures);
        case Currency currency -> collectMeasures(currency.operand(), measures);
        case ColumnAggregate aggregate -> false;
        };
    }

    /**
     * @return the stored measures of a condition that is TRUE where one of
     *         them is not BLANK, as {@code NOT(ISBLANK([a])) || NOT(ISBLANK([b]))};
     *         empty if it is another
     */
    private static Optional<Set<ModelMeasure>> nonBlankMeasures(ScalarPlan condition) {
        return switch (condition) {
        case Not not when not.operand() instanceof IsBlank isBlank
                && isBlank.operand() instanceof MeasureValue measure && measure.measure().stored() ->
            Optional.of(Set.of(measure.measure()));
        case Logical logical when logical.operator() == LogicalOperator.OR ->
            nonBlankMeasures(logical.left()).flatMap(left -> nonBlankMeasures(logical.right()).map(right -> {
                Set<ModelMeasure> both = new LinkedHashSet<>(left);
                both.addAll(right);
                return both;
            }));
        default -> Optional.empty();
        };
    }

    /**
     * @return the expression as the cube computes it, e.g. a measure or
     *         {@code ISBLANK} of one; empty if it uses no measure
     */
    private Optional<ScalarPlan> cubeExpression(DaxExpression expression, String function)
            throws DaxSemanticException {
        Optional<ModelMeasure> measure = measureReference(expression);
        if (measure.isPresent()) {
            return Optional.of(new MeasureValue(measure.get()));
        }
        // no columns: a name is a measure
        ScalarPlan plan = scalar(expression, List.of());
        if (!usesMeasure(plan)) {
            return Optional.empty();
        }
        if (!onCube(plan)) {
            throw notSupported(function + " with a measure in an expression with IN, BLANK or dates");
        }
        return Optional.of(plan);
    }

    private TablePlan distinct(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.size() != 1) {
            throw new DaxSemanticException("DISTINCT takes one column or table");
        }
        DaxExpression argument = arguments.get(0);
        if (argument instanceof Identifier identifier) {
            // the values of a column: a grouping by it alone
            return summarize(List.of(column(identifier)), List.of());
        }
        return switch (table(argument)) {
        // a grouping has one row per group already
        case Summarize summarize -> summarize;
        // so has a filtered one: only a grouping is filtered after binding
        case Filter filter -> filter;
        case TopN topN -> topN;
        case Sample sample -> sample;
        // added columns depend on the rest of the row
        case AddColumns addColumns -> addColumns;
        // as MDX Generate gives each tuple once
        case Generate generate -> generate;
        // the subtotals are of distinct groups
        case Rollup rollup -> rollup;
        case ConstantTable constant ->
            new ConstantTable(constant.columns(), new ArrayList<>(new LinkedHashSet<>(constant.rows())));
        };
    }

    private TablePlan values(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.size() != 1) {
            throw new DaxSemanticException("VALUES takes one column or table name");
        }
        // the cube has no rows breaking a relationship, so no blank row is added
        return switch (arguments.get(0)) {
        case Identifier identifier -> summarize(List.of(column(identifier)), List.of());
        case Entity entity -> tableReference(entity.name());
        case Keyword keyword -> tableReference(keyword.name());
        default -> throw new DaxSemanticException("VALUES takes a column or table name, not an expression of kind "
                + kind(arguments.get(0)));
        };
    }

    private TablePlan filter(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.size() != 2) {
            throw new DaxSemanticException("FILTER takes a table and a condition");
        }
        TablePlan source = table(withoutKeepFilters(arguments.get(0)));
        // the parts of a condition that are and-ed are computed apart: those on
        // measures by the cube, the others on the rows it answers
        List<ScalarPlan> onRows = new ArrayList<>();
        List<ScalarPlan> onCube = new ArrayList<>();
        for (ScalarPlan part : conjuncts(scalar(arguments.get(1), source.columns()))) {
            (usesMeasure(part) ? onCube : onRows).add(part);
        }
        if (!onCube.isEmpty()) {
            ScalarPlan condition = and(onCube);
            // a part on measures may compare the text of columns grouped by too, e.g. by ||
            if (!onCube(condition, groupedColumns(source))) {
                throw notSupported("FILTER by a condition joining measures with columns other than grouped by "
                        + "and compared with text, with IN of other than text, BLANK or dates other than by &&");
            }
            source = filteredByCube(source, condition);
        }
        if (onRows.isEmpty()) {
            return source;
        }
        Filter filter = new Filter(source, and(onRows));
        if (source instanceof ConstantTable constant) {
            // nothing to query: filtered right away
            try {
                return new ConstantTable(constant.columns(), filter.apply(constant.rows()));
            } catch (DaxExecutionException e) {
                throw new DaxSemanticException(e.getMessage(), e);
            }
        }
        return filter;
    }

    private static TablePlan filteredByCube(TablePlan table, ScalarPlan condition) throws DaxSemanticException {
        return switch (table) {
        case Summarize summarize when summarize.groupBy().isEmpty() -> throw notSupported("FILTER by a measure of ROW");
        case Summarize summarize when summarize.top().isPresent() ->
            throw notSupported("FILTER of TOPN by a measure");
        // the filter tables filter within SUMMARIZECOLUMNS only, not the measures of FILTER
        case Summarize summarize when !summarize.filters().isEmpty() ->
            throw notSupported("FILTER by a measure of SUMMARIZECOLUMNS with filter tables");
        case Summarize summarize -> summarize.filtered(condition);
        case Filter filter -> new Filter(filteredByCube(filter.source(), condition), filter.condition());
        // added columns keep the rows: filtered before
        case AddColumns addColumns ->
            new AddColumns(filteredByCube(addColumns.source(), condition), addColumns.width(), addColumns.added());
        case TopN topN -> throw notSupported("FILTER of TOPN by a measure");
        case Sample sample -> throw notSupported("FILTER of SAMPLE by a measure");
        case ConstantTable constant -> throw notSupported("FILTER of a table constructor by a measure");
        case Generate generate -> throw notSupported("FILTER of GENERATE by a measure");
        case Rollup rollup -> throw notSupported("FILTER of SUMMARIZE with ROLLUP by a measure");
        };
    }

    private TablePlan topN(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.size() < 3) {
            throw new DaxSemanticException("TOPN takes a number of rows, a table and expressions to order by");
        }
        if (!(constant(arguments.get(0)) instanceof Number count)) {
            throw new DaxSemanticException("TOPN takes a number of rows");
        }
        TablePlan source = table(withoutKeepFilters(arguments.get(1)));
        List<SortKey> keys = new ArrayList<>();
        List<Summarize.Top> byMeasure = new ArrayList<>();
        for (int i = 2; i < arguments.size(); i++) {
            DaxExpression expression = arguments.get(i);
            // descending unless told
            boolean ascending = false;
            if (i + 1 < arguments.size() && isOrder(arguments.get(i + 1))) {
                ascending = ascending(arguments.get(++i));
            }
            switch (scalar(expression, source.columns())) {
            case ColumnValue column -> keys.add(new SortKey(column.column(), ascending));
            case MeasureValue measure -> byMeasure.add(new Summarize.Top(count.longValue(), measure.measure(), ascending));
            default -> throw notSupported("TOPN by an expression of kind " + kind(expression));
            }
        }
        if (!byMeasure.isEmpty()) {
            // computed by the cube, as MDX TopCount
            if (byMeasure.size() > 1 || !keys.isEmpty()) {
                throw notSupported("TOPN by a measure and other expressions");
            }
            if (!(source instanceof Summarize summarize) || summarize.groupBy().isEmpty()
                    || summarize.top().isPresent()) {
                throw notSupported("TOPN by a measure of other than a grouping of columns, e.g. VALUES");
            }
            if (!summarize.filters().isEmpty()) {
                throw notSupported("TOPN by a measure of SUMMARIZECOLUMNS with filter tables");
            }
            return summarize.topped(byMeasure.get(0));
        }
        TopN topN = new TopN(source, count.longValue(), keys);
        if (source instanceof ConstantTable constant) {
            // nothing to query: computed right away
            return new ConstantTable(topN.columns(), topN.apply(constant.rows()));
        }
        return topN;
    }

    private TablePlan addColumns(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.size() < 3 || arguments.size() % 2 == 0) {
            throw new DaxSemanticException("ADDCOLUMNS takes a table and pairs of a name and an expression");
        }
        TablePlan source = table(withoutKeepFilters(arguments.get(0)));
        List<DaxColumn> columns = source.columns();
        int width = columns.size();
        List<AddColumns.Added> added = new ArrayList<>();
        List<NamedMeasure> onCube = new ArrayList<>();
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < arguments.size(); i += 2) {
            String name = columnName(arguments.get(i), "ADDCOLUMNS");
            // a column of a table has its name in brackets after the table's
            String bracketed = ("[" + name + "]").toLowerCase(Locale.ROOT);
            if (columns.stream().anyMatch(c -> c.name().toLowerCase(Locale.ROOT).endsWith(bracketed))
                    || !names.add(name)) {
                throw new DaxSemanticException("ADDCOLUMNS: the column [" + name + "] exists already");
            }
            ScalarPlan expression = scalar(arguments.get(i + 1), columns);
            if (usesMeasure(expression)) {
                if (!onCube(expression)) {
                    throw notSupported("ADDCOLUMNS with a measure in an expression with columns, IN, BLANK or dates");
                }
                // computed by the cube, for each row
                NamedMeasure measure = new NamedMeasure(name, expression);
                onCube.add(measure);
                added.add(new AddColumns.Added(name, new ColumnValue(width + onCube.size() - 1), measure.type()));
            } else {
                added.add(new AddColumns.Added(name, expression, type(expression, columns)));
            }
        }
        if (!onCube.isEmpty()) {
            source = withAdded(source, onCube);
            if (onCube.size() == added.size()) {
                // the cube's columns are the last ones, in order
                return source;
            }
        }
        AddColumns addColumns = new AddColumns(source, width, added);
        if (source instanceof ConstantTable constant) {
            // nothing to query: computed right away
            try {
                return new ConstantTable(addColumns.columns(), addColumns.apply(constant.rows()));
            } catch (DaxExecutionException e) {
                throw new DaxSemanticException(e.getMessage(), e);
            }
        }
        return addColumns;
    }

    /** @return the table computing the measures too, as its last columns, for the rows it has */
    private static TablePlan withAdded(TablePlan table, List<NamedMeasure> measures) throws DaxSemanticException {
        return switch (table) {
        case Summarize summarize when summarize.groupBy().isEmpty() ->
            throw notSupported("ADDCOLUMNS by a measure of ROW");
        // the filter tables filter within SUMMARIZECOLUMNS only, not the measures of ADDCOLUMNS
        case Summarize summarize when !summarize.filters().isEmpty() ->
            throw notSupported("ADDCOLUMNS by a measure of SUMMARIZECOLUMNS with filter tables");
        case Summarize summarize -> summarize.withAdded(measures);
        // the columns are added after those the filter and the order refer to
        case Filter filter -> new Filter(withAdded(filter.source(), measures), filter.condition());
        case TopN topN -> new TopN(withAdded(topN.source(), measures), topN.count(), topN.keys(), topN.partition());
        case Rollup rollup -> throw notSupported("ADDCOLUMNS by a measure of SUMMARIZE with ROLLUP");
        case Sample sample -> new Sample(withAdded(sample.source(), measures), sample.count(), sample.keys());
        case AddColumns addColumns -> throw notSupported("ADDCOLUMNS by a measure of ADDCOLUMNS computing columns");
        case ConstantTable constant -> throw notSupported("ADDCOLUMNS of a table constructor by a measure");
        // computed for the rows as the inner measures are
        case Generate generate -> new Generate(generate.outer(), generate.inner().withAdded(measures));
        };
    }

    /** @return the type of the values of the expression on rows of the columns */
    private static DaxType type(ScalarPlan plan, List<DaxColumn> columns) {
        return switch (plan) {
        case Constant constant -> constant.value() == null ? DaxType.VARIANT : DaxType.of(constant.value());
        case ColumnValue column -> columns.get(column.column()).type();
        case MeasureValue measure -> measure.measure().type();
        case Comparison comparison -> DaxType.BOOLEAN;
        case InList in -> DaxType.BOOLEAN;
        case Logical logical -> DaxType.BOOLEAN;
        case Not not -> DaxType.BOOLEAN;
        case IsBlank isBlank -> DaxType.BOOLEAN;
        case Currency currency -> DaxType.DECIMAL;
        case ColumnAggregate aggregate -> DaxType.VARIANT;
        };
    }

    private TablePlan sample(List<DaxExpression> arguments) throws DaxSemanticException {
        if (arguments.size() < 3) {
            throw new DaxSemanticException("SAMPLE takes a number of rows, a table and expressions to order by");
        }
        if (!(constant(arguments.get(0)) instanceof Number count)) {
            throw new DaxSemanticException("SAMPLE takes a number of rows");
        }
        TablePlan source = table(withoutKeepFilters(arguments.get(1)));
        List<SortKey> keys = new ArrayList<>();
        for (int i = 2; i < arguments.size(); i++) {
            DaxExpression expression = arguments.get(i);
            // ascending unless told
            boolean ascending = true;
            if (i + 1 < arguments.size() && isOrder(arguments.get(i + 1))) {
                ascending = ascending(arguments.get(++i));
            }
            switch (scalar(expression, source.columns())) {
            case ColumnValue column -> keys.add(new SortKey(column.column(), ascending));
            case MeasureValue measure -> throw notSupported("SAMPLE by a measure");
            default -> throw notSupported("SAMPLE by an expression of kind " + kind(expression));
            }
        }
        Sample sample = new Sample(source, count.longValue(), keys);
        if (source instanceof ConstantTable constant) {
            // nothing to query: computed right away
            return new ConstantTable(sample.columns(), sample.apply(constant.rows()));
        }
        return sample;
    }

    /** @return whether the argument is an order of TOPN or SAMPLE: ASC, DESC, TRUE, FALSE, 0 or 1 */
    private static boolean isOrder(DaxExpression argument) {
        return switch (argument) {
        case Keyword keyword -> List.of("ASC", "DESC", "TRUE", "FALSE").contains(keyword.name().toUpperCase(Locale.ROOT));
        case BooleanLiteral bool -> true;
        case NumericLiteral number -> number.value().signum() == 0 || number.value().compareTo(BigDecimal.ONE) == 0;
        case FunctionCall call -> call.arguments().isEmpty()
                && List.of("TRUE", "FALSE").contains(call.functionName().toUpperCase(Locale.ROOT));
        default -> false;
        };
    }

    private static boolean ascending(DaxExpression order) {
        return switch (order) {
        case Keyword keyword -> List.of("ASC", "TRUE").contains(keyword.name().toUpperCase(Locale.ROOT));
        case BooleanLiteral bool -> bool.value();
        case NumericLiteral number -> number.value().signum() != 0;
        case FunctionCall call -> call.functionName().equalsIgnoreCase("TRUE");
        default -> throw new IllegalArgumentException("no order: " + order);
        };
    }

    /** @return the parts of the condition joined by {@code &&} */
    private static List<ScalarPlan> conjuncts(ScalarPlan condition) {
        if (condition instanceof Logical logical && logical.operator() == LogicalOperator.AND) {
            List<ScalarPlan> parts = new ArrayList<>(conjuncts(logical.left()));
            parts.addAll(conjuncts(logical.right()));
            return parts;
        }
        return List.of(condition);
    }

    private static ScalarPlan and(List<ScalarPlan> parts) {
        ScalarPlan joined = parts.get(0);
        for (ScalarPlan part : parts.subList(1, parts.size())) {
            joined = new Logical(LogicalOperator.AND, joined, part);
        }
        return joined;
    }

    private static boolean usesMeasure(ScalarPlan plan) {
        return switch (plan) {
        case MeasureValue measure -> true;
        case Constant constant -> false;
        case ColumnValue column -> false;
        case Comparison comparison -> usesMeasure(comparison.left()) || usesMeasure(comparison.right());
        case InList in -> usesMeasure(in.value());
        case Logical logical -> usesMeasure(logical.left()) || usesMeasure(logical.right());
        case Not not -> usesMeasure(not.operand());
        case IsBlank isBlank -> usesMeasure(isBlank.operand());
        case Currency currency -> usesMeasure(currency.operand());
        // computed by the cube, as a measure
        case ColumnAggregate aggregate -> true;
        };
    }

    /** @return whether the cube can compute the condition, see {@code MdxGenerator} */
    private static boolean onCube(ScalarPlan plan) {
        return onCube(plan, 0);
    }

    /**
     * @param columns how many first columns of the rows are the columns a
     *                grouping groups by, whose text the cube compares by the
     *                names of members
     * @return whether the cube computes the condition, see {@code MdxGenerator}
     */
    private static boolean onCube(ScalarPlan plan, int columns) {
        return switch (plan) {
        case MeasureValue measure -> true;
        case Constant constant -> constant.value() instanceof Number || constant.value() instanceof String
                || constant.value() instanceof Boolean;
        case ColumnValue column -> false;
        case InList in -> text(in.value(), columns)
                && in.values().stream().allMatch(v -> v == null || v instanceof String);
        case Comparison comparison when comparison.left() instanceof ColumnValue
                || comparison.right() instanceof ColumnValue ->
            text(comparison.left(), columns) && text(comparison.right(), columns);
        case Comparison comparison -> onCube(comparison.left(), columns) && onCube(comparison.right(), columns);
        case Logical logical -> onCube(logical.left(), columns) && onCube(logical.right(), columns);
        case Not not -> onCube(not.operand(), columns);
        case IsBlank isBlank -> isBlank.operand() instanceof MeasureValue;
        // a number the cube computes: a measure, or of measures
        case Currency currency -> !(currency.operand() instanceof ColumnValue) && onCube(currency.operand(), columns);
        case ColumnAggregate aggregate -> true;
        };
    }

    /** @return whether the plan is text the cube compares: a column grouped by or a text constant */
    private static boolean text(ScalarPlan plan, int columns) {
        return plan instanceof ColumnValue column ? column.column() < columns
                : plan instanceof Constant constant && constant.value() instanceof String;
    }

    /**
     * @return how many first columns of the table are those the grouping it is
     *         computed of groups by; 0 if none is
     */
    private static int groupedColumns(TablePlan table) {
        return switch (table) {
        case Summarize summarize -> summarize.groupBy().size();
        // these keep the first columns of their source
        case Filter filter -> groupedColumns(filter.source());
        case TopN topN -> groupedColumns(topN.source());
        case Sample sample -> groupedColumns(sample.source());
        case AddColumns addColumns -> Math.min(addColumns.width(), groupedColumns(addColumns.source()));
        case ConstantTable constant -> 0;
        case Generate generate -> 0;
        case Rollup rollup -> 0;
        };
    }

    private TablePlan summarize(List<ModelColumn> groupBy, List<NamedMeasure> measures) throws DaxSemanticException {
        Set<ModelColumn> distinct = new LinkedHashSet<>(groupBy);
        if (measures.isEmpty()) {
            // Without measures nothing tells which combinations of two hierarchies of one
            // table exist, and the cross product would invent rows.
            Map<String, Set<String>> hierarchiesByTable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (ModelColumn column : distinct) {
                hierarchiesByTable.computeIfAbsent(column.table(), t -> new LinkedHashSet<>()).add(column.hierarchy());
            }
            for (Map.Entry<String, Set<String>> entry : hierarchiesByTable.entrySet()) {
                if (entry.getValue().size() > 1) {
                    throw notSupported("columns of several hierarchies of the table '" + entry.getKey()
                            + "' without a measure");
                }
            }
        }
        return new Summarize(List.copyOf(distinct), measures);
    }

    private static TablePlan constantTable(List<String> names, List<List<Object>> rows) {
        List<DaxColumn> columns = new ArrayList<>();
        for (int c = 0; c < names.size(); c++) {
            Set<DaxType> types = EnumSet.noneOf(DaxType.class);
            for (List<Object> row : rows) {
                if (row.get(c) != null) {
                    types.add(DaxType.of(row.get(c)));
                }
            }
            DaxType type;
            if (types.size() == 1) {
                type = types.iterator().next();
            } else if (types.equals(EnumSet.of(DaxType.INTEGER, DaxType.DOUBLE))) {
                type = DaxType.DOUBLE;
                for (List<Object> row : rows) {
                    if (row.get(c) instanceof Long whole) {
                        row.set(c, whole.doubleValue());
                    }
                }
            } else {
                type = DaxType.VARIANT;
            }
            columns.add(new DaxColumn("[" + names.get(c) + "]", Optional.empty(), type));
        }
        return new ConstantTable(columns, rows);
    }

    // --- references

    private ModelColumn column(Identifier identifier) throws DaxSemanticException {
        String[] name = tableAndName(identifier);
        ModelTable table = model.table(name[0])
                .orElseThrow(() -> new DaxSemanticException("the table '" + name[0] + "' does not exist"));
        return table.column(name[1]).orElseThrow(
                () -> new DaxSemanticException("the column '" + name[0] + "'[" + name[1] + "] does not exist"));
    }

    /**
     * @return the measure the expression refers to, as {@code [Measure]} or
     *         {@code 'Table'[Measure]}; empty if it is no such reference
     */
    private Optional<ModelMeasure> measureReference(DaxExpression expression) throws DaxSemanticException {
        if (expression instanceof Scalar scalar) {
            return Optional.of(model.measure(scalar.name())
                    .orElseThrow(() -> new DaxSemanticException("the measure [" + scalar.name() + "] does not exist")));
        }
        if (expression instanceof Identifier identifier) {
            String[] name = tableAndName(identifier);
            Optional<ModelMeasure> measure = model.measure(name[1]);
            if (measure.isEmpty()) {
                throw new DaxSemanticException(
                        "'" + name[0] + "'[" + name[1] + "] is no measure; only measures are supported here yet");
            }
            return measure;
        }
        return Optional.empty();
    }

    private static String[] tableAndName(Identifier identifier) throws DaxSemanticException {
        List<DaxExpression> parts = identifier.parts();
        if (parts.size() == 2 && parts.get(1) instanceof Scalar column) {
            if (parts.get(0) instanceof Entity table) {
                return new String[] { table.name(), column.name() };
            }
            if (parts.get(0) instanceof Keyword table) {
                return new String[] { table.name(), column.name() };
            }
        }
        throw notSupported("the reference " + identifier);
    }

    /**
     * @return the name of the referenced column as a result column has it, e.g.
     *         {@code Product[Category]}, also if referred to by the unique name
     *         of its level, as {@code 'Product'[Product.Product.Category]}
     */
    private String qualifiedName(Identifier identifier) throws DaxSemanticException {
        String[] name = tableAndName(identifier);
        return model.table(name[0]).flatMap(t -> t.column(name[1])).map(ModelColumn::daxName)
                .orElse(name[0] + "[" + name[1] + "]");
    }

    private static String columnName(DaxExpression expression, String function) throws DaxSemanticException {
        if (expression instanceof StringLiteral name) {
            return name.value();
        }
        throw new DaxSemanticException(function + " takes a string as column name");
    }

    // --- scalars

    private Object constant(DaxExpression expression) throws DaxSemanticException {
        try {
            return scalar(expression, null).evaluate(List.of());
        } catch (DaxExecutionException e) {
            throw new DaxSemanticException(e.getMessage(), e);
        }
    }

    /**
     * @param row the columns of the row the expression is computed on;
     *            {@code null} where a constant is expected
     */
    private ScalarPlan scalar(DaxExpression expression, List<DaxColumn> row) throws DaxSemanticException {
        return switch (expression) {
        case NumericLiteral number -> new Constant(number(number.value()));
        case StringLiteral string -> new Constant(string.value());
        case BooleanLiteral bool -> new Constant(bool.value());
        case DateTimeLiteral dateTime -> new Constant(dateTime.value());
        case Keyword keyword when keyword.name().equalsIgnoreCase("TRUE") -> new Constant(Boolean.TRUE);
        case Keyword keyword when keyword.name().equalsIgnoreCase("FALSE") -> new Constant(Boolean.FALSE);
        case FunctionCall call when call.functionName().equalsIgnoreCase("ISBLANK") ->
            new IsBlank(scalar(onlyArgument(call, "ISBLANK takes one value"), row));
        case FunctionCall call when call.functionName().equalsIgnoreCase("NOT") ->
            new Not(scalar(onlyArgument(call, "NOT takes one value"), row));
        case FunctionCall call when call.functionName().equalsIgnoreCase("AND") -> logical(call, LogicalOperator.AND, row);
        case FunctionCall call when call.functionName().equalsIgnoreCase("OR") -> logical(call, LogicalOperator.OR, row);
        case FunctionCall call when call.functionName().equalsIgnoreCase("CALCULATE") -> calculate(call, row);
        case FunctionCall call when call.functionName().equalsIgnoreCase("CURRENCY") -> currency(call, row);
        case FunctionCall call when Aggregation.of(call.functionName()).isPresent() -> columnAggregate(call, row);
        case FunctionCall call when call.arguments().isEmpty() -> switch (call.functionName().toUpperCase(Locale.ROOT)) {
            case "BLANK" -> new Constant(null);
            case "TRUE" -> new Constant(Boolean.TRUE);
            case "FALSE" -> new Constant(Boolean.FALSE);
            default -> throw notSupported("the function " + call.functionName() + where(row));
            };
        case BooleanExpression comparison when comparison.operator() == BooleanOperator.IN -> in(comparison, row);
        case BooleanExpression comparison -> new Comparison(comparison.operator(), scalar(comparison.left(), row),
                scalar(comparison.right(), row));
        case LogicalExpression logical -> new Logical(logical.operator(), scalar(logical.left(), row),
                scalar(logical.right(), row));
        case Parameter parameter -> {
            if (!parameters.containsKey(parameter.name())) {
                throw new DaxSemanticException("the parameter @" + parameter.name() + " has no value");
            }
            yield new Constant(parameters.get(parameter.name()));
        }
        case VariableReference variable -> {
            if (!variables.containsKey(variable.name())) {
                throw new DaxSemanticException("the variable " + variable.name() + " is not defined");
            }
            yield new Constant(variables.get(variable.name()));
        }
        case Identifier identifier when row != null -> {
            // 'Measures'[Sales] is a measure, unless the row has such a column
            String name = qualifiedName(identifier);
            Optional<ModelMeasure> measure = model.measure(tableAndName(identifier)[1]);
            yield indexOf(row, name) < 0 && measure.isPresent() ? new MeasureValue(measure.get())
                    : columnValue(name, row);
        }
        case Scalar scalar when row != null -> {
            // [S] is a column of the row first, e.g. of SUMMARIZECOLUMNS, then a measure
            Optional<ModelMeasure> measure = model.measure(scalar.name());
            yield indexOf(row, "[" + scalar.name() + "]") < 0 && measure.isPresent()
                    ? new MeasureValue(measure.get())
                    : columnValue("[" + scalar.name() + "]", row);
        }
        default -> throw notSupported("an expression of kind " + kind(expression) + where(row));
        };
    }

    /** @return {@code AND} or {@code OR} of two values, as {@code &&} or {@code ||} of them */
    private ScalarPlan logical(FunctionCall call, LogicalOperator operator, List<DaxColumn> row)
            throws DaxSemanticException {
        if (call.arguments().size() != 2) {
            throw new DaxSemanticException(operator + " takes two values");
        }
        return new Logical(operator, scalar(call.arguments().get(0), row), scalar(call.arguments().get(1), row));
    }

    /** @return {@code CURRENCY} of a value; of a constant, as a constant, which the cube compares too */
    private ScalarPlan currency(FunctionCall call, List<DaxColumn> row) throws DaxSemanticException {
        ScalarPlan operand = scalar(onlyArgument(call, "CURRENCY takes one value"), row);
        if (operand instanceof Constant constant) {
            try {
                return new Constant(ScalarPlan.currency(constant.value()));
            } catch (DaxExecutionException e) {
                throw new DaxSemanticException(e.getMessage(), e);
            }
        }
        return new Currency(operand);
    }

    private ScalarPlan calculate(FunctionCall call, List<DaxColumn> row) throws DaxSemanticException {
        if (call.arguments().isEmpty()) {
            throw new DaxSemanticException("CALCULATE takes an expression and filters");
        }
        if (call.arguments().size() > 1) {
            throw notSupported("CALCULATE with filters");
        }
        // without filters it computes the expression as a measure: in the cube, for the row
        return scalar(call.arguments().get(0), row);
    }

    private ScalarPlan columnAggregate(FunctionCall call, List<DaxColumn> row) throws DaxSemanticException {
        String function = call.functionName().toUpperCase(Locale.ROOT);
        if (!(onlyArgument(call, function + " takes one column") instanceof Identifier identifier)) {
            throw new DaxSemanticException(function + " takes a column, not an expression of kind "
                    + kind(call.arguments().get(0)));
        }
        if (row == null) {
            throw notSupported("the function " + function + where(row));
        }
        return new ColumnAggregate(Aggregation.of(function).orElseThrow(), column(identifier));
    }

    private ScalarPlan in(BooleanExpression in, List<DaxColumn> row) throws DaxSemanticException {
        if (!(in.right() instanceof TableConstructor constructor)) {
            throw notSupported("IN an expression of kind " + kind(in.right()));
        }
        TablePlan list = tableConstructor(constructor);
        if (list.columns().size() != 1 || in.left() instanceof RowConstructor) {
            throw notSupported("IN with several columns");
        }
        List<Object> values = new ArrayList<>();
        for (List<Object> value : ((ConstantTable) list).rows()) {
            values.add(value.get(0));
        }
        return new InList(scalar(in.left(), row), values);
    }

    private static ScalarPlan columnValue(String name, List<DaxColumn> row) throws DaxSemanticException {
        int index = indexOf(row, name);
        if (index < 0) {
            throw new DaxSemanticException("the column " + name + " is not in the table the condition is computed on");
        }
        return new ColumnValue(index);
    }

    private static DaxExpression onlyArgument(FunctionCall call, String message) throws DaxSemanticException {
        if (call.arguments().size() != 1) {
            throw new DaxSemanticException(message);
        }
        return call.arguments().get(0);
    }

    private static String where(List<DaxColumn> row) {
        return row == null ? " where a constant is expected" : " in a row condition";
    }

    private static Object number(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.toBigInteger().bitLength() < 64) {
            return stripped.longValueExact();
        }
        return value.doubleValue();
    }

    // --- order

    private List<SortKey> orderBy(List<OrderByItem> items, List<DaxColumn> columns) throws DaxSemanticException {
        List<SortKey> keys = new ArrayList<>();
        for (OrderByItem item : items) {
            if (item.startAt().isPresent()) {
                throw notSupported("START AT");
            }
            String name = switch (item.expression()) {
            case Scalar scalar -> "[" + scalar.name() + "]";
            case Identifier identifier -> qualifiedName(identifier);
            default -> throw notSupported("ORDER BY an expression of kind " + kind(item.expression()));
            };
            int index = indexOf(columns, name);
            if (index < 0) {
                throw new DaxSemanticException("ORDER BY " + name + ": no such column in the result");
            }
            keys.add(new SortKey(index, item.direction() != OrderByItem.SortDirection.DESC));
        }
        return keys;
    }

    private static int indexOf(List<DaxColumn> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    // --- errors

    private static DaxSemanticException notSupported(String what) {
        return new DaxSemanticException(what + " is not supported yet");
    }

    /** @return the name of the model interface the expression implements */
    private static String kind(DaxExpression expression) {
        for (Class<?> type = expression.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> implemented : type.getInterfaces()) {
                if (DaxExpression.class.isAssignableFrom(implemented) && implemented != DaxExpression.class) {
                    return implemented.getSimpleName();
                }
            }
        }
        return expression.getClass().getSimpleName();
    }
}
