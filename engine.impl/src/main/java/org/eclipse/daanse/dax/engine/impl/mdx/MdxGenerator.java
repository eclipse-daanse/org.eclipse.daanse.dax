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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;
import org.eclipse.daanse.dax.engine.impl.model.ModelMeasure;
import org.eclipse.daanse.dax.engine.impl.plan.NamedMeasure;
import org.eclipse.daanse.dax.engine.impl.plan.ScalarPlan;
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
import org.eclipse.daanse.dax.engine.impl.plan.Filter;
import org.eclipse.daanse.dax.engine.impl.plan.Generate;
import org.eclipse.daanse.dax.engine.impl.plan.Summarize;
import org.eclipse.daanse.dax.engine.impl.plan.TablePlan;
import org.eclipse.daanse.dax.model.api.expression.LogicalExpression.LogicalOperator;

/**
 * Translates plans into MDX.
 * <p>
 * A {@link Summarize} puts its measures on the columns axis and the cross join
 * of its hierarchies on the rows axis, {@code NON EMPTY} when it has measures.
 * Of the columns of one hierarchy only the deepest level goes on the axis; the
 * others are read from the ancestors of its members. Its condition filters
 * the rows set by MDX {@code Filter}, its top then by {@code TopCount} or
 * {@code BottomCount} of the groups where the measure is not empty. A result
 * expression other than a measure is a calculated member of {@code WITH},
 * e.g. {@code ISBLANK} of a measure is {@code IsEmpty} of it. Added measures
 * follow the measures on the columns axis. An aggregation of a column is one
 * of the members of its level {@code Existing} in the context: the counts
 * count them, the others aggregate their names that are numbers; with them, {@code NonEmpty} of the
 * measures replaces {@code NON EMPTY}.
 * </p>
 * <p>
 * A filter table on hierarchies grouped by keeps the rows set by
 * {@code Exists}; the filter tables on others are the slicer, {@code WHERE}.
 * A filter table is the set of its members, filtered by a condition on their
 * names or property values ignoring case, as DAX compares text.
 * </p>
 */
public final class MdxGenerator {

    private MdxGenerator() {
    }

    /**
     * @param cube      the cube name, as MDX writes it
     * @param summarize the plan
     * @return the MDX query computing the plan's table
     */
    public static MdxQuery summarize(String cube, Summarize summarize) {
        return summarize(cube, summarize, Map.of());
    }

    /**
     * @param cube      the cube name, as MDX writes it
     * @param summarize the plan
     * @param groups    of each hierarchy of {@link Summarize#byValues()}, the
     *                  values of its columns grouped by, in their order, of
     *                  each group
     * @return the MDX query computing the plan's table: a group of the values
     *         of a hierarchy is a calculated member aggregating the members
     *         of the values, its measures are computed over them
     */
    public static MdxQuery summarize(String cube, Summarize summarize, Map<String, List<List<Object>>> groups) {
        Map<String, ModelColumn> deepest = deepest(summarize.groupBy());
        List<String> hierarchies = new ArrayList<>(deepest.keySet());

        StringBuilder mdx = new StringBuilder();
        Map<String, String> groupSets = new LinkedHashMap<>();
        Map<ModelColumn, Map<String, Object>> groupValues = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<Object>>> entry : groups.entrySet()) {
            groupSets.put(entry.getKey(), groupMembers(entry.getKey(), entry.getValue(), summarize.groupBy(), mdx,
                    groupValues));
        }
        List<ValueSource> sources = new ArrayList<>();
        for (ModelColumn column : summarize.groupBy()) {
            int member = hierarchies.indexOf(column.hierarchy());
            sources.add(groupValues.containsKey(column) ? new ValueSource.GroupValue(member, groupValues.get(column))
                    : source(member, column));
        }
        List<TablePlan> kept = new ArrayList<>();
        Map<String, List<TablePlan>> sliced = new LinkedHashMap<>();
        for (TablePlan filter : summarize.filters()) {
            List<ModelColumn> columns = Summarize.filterColumns(filter);
            if (columns.stream().anyMatch(c -> deepest.containsKey(c.hierarchy()))) {
                kept.add(filter);
            } else {
                // the filters on the same hierarchies are one set of the slicer
                String together = String.join(", ", columns.stream().map(ModelColumn::hierarchy).distinct().toList());
                sliced.computeIfAbsent(together, h -> new ArrayList<>()).add(filter);
            }
        }
        summarize = inFilters(summarize, deepest, kept, mdx);
        Measures measures = measures(summarize, mdx, sources, !groups.isEmpty());
        String keeping = measures.keeping();

        mdx.append("SELECT ").append(measures.all()).append(" ON COLUMNS");
        String slicer = null;
        for (List<TablePlan> filters : sliced.values()) {
            String set = intersection(filters);
            slicer = slicer == null ? set : "CrossJoin(" + slicer + ", " + set + ")";
        }
        boolean rows = !deepest.isEmpty();
        if (rows) {
            String set = set(summarize, groupSets);
            for (TablePlan filter : kept) {
                set = "Exists(" + set + ", " + filterSet(filter) + ")";
            }
            set = conditionAndTop(set, summarize);
            mdx.append(", ");
            if (!summarize.measures().isEmpty() && !summarize.added().isEmpty()) {
                // NON EMPTY of the axis would count the added measures too
                set = "NonEmpty(" + set + ", " + keeping + ")";
            } else if (!summarize.measures().isEmpty()) {
                mdx.append("NON EMPTY ");
            }
            mdx.append(set).append(" ON ROWS");
        }
        mdx.append(" FROM ").append(cube);
        if (slicer != null) {
            mdx.append(" WHERE ").append(slicer);
        }
        return new MdxQuery(mdx.toString(), sources, rows);
    }

    /**
     * Filter tables on a level of a hierarchy grouped by deeper than the groups
     * restrict what the measures compute for a group to its members of that
     * level the filters keep: each measure is replaced by a calculated member
     * aggregating it over them, if all are stored; else they are computed for
     * the groups. The groups are kept as before, by {@code Exists} of the
     * filters.
     *
     * @return the grouping computing the measures over the members kept
     */
    private static Summarize inFilters(Summarize summarize, Map<String, ModelColumn> deepest, List<TablePlan> kept,
            StringBuilder mdx) {
        StringJoiner scope = new StringJoiner(", ");
        int hierarchies = 0;
        for (Map.Entry<String, ModelColumn> grouped : deepest.entrySet()) {
            List<TablePlan> filters = kept.stream().filter(f -> Summarize.filterColumns(f).stream()
                    .allMatch(c -> c.hierarchy().equals(grouped.getKey()))).toList();
            ModelColumn level = deepest(filters.stream().flatMap(f -> Summarize.filterColumns(f).stream()).toList())
                    .get(grouped.getKey());
            if (level == null || level.depth() <= grouped.getValue().depth()) {
                continue;
            }
            String members = "Descendants(" + grouped.getKey() + ".CurrentMember, " + level.level() + ")";
            for (TablePlan filter : filters) {
                members = "Exists(" + members + ", " + filterSet(filter) + ")";
            }
            scope.add(members);
            hierarchies++;
        }
        Set<ModelMeasure> measures = new LinkedHashSet<>();
        summarize.withMeasures(measure -> {
            measures.add(measure);
            return measure;
        });
        // a calculated measure is not aggregated: it is computed for the group,
        // as for one of SUMMARIZE of GENERATE keeping where it is not BLANK
        if (hierarchies == 0 || !measures.stream().allMatch(ModelMeasure::stored)) {
            return summarize;
        }
        String set = hierarchies == 1 ? scope.toString() : "CrossJoin(" + scope + ")";
        Map<ModelMeasure, ModelMeasure> replaced = new LinkedHashMap<>();
        Summarize computed = summarize.withMeasures(measure -> replaced.computeIfAbsent(measure,
                m -> new ModelMeasure(m.name(), "[Measures]." + MdxNames.quote("DAX in filters " + m.name()),
                        m.stored(), m.type())));
        for (Map.Entry<ModelMeasure, ModelMeasure> measure : replaced.entrySet()) {
            mdx.append(mdx.isEmpty() ? "WITH " : " ").append("MEMBER ").append(measure.getValue().uniqueName())
                    .append(" AS Aggregate(").append(set).append(", ").append(measure.getKey().uniqueName())
                    .append(")");
        }
        return computed;
    }

    /**
     * @param filters filter tables on one hierarchy
     * @return the members of the deepest level of them all keep: of each, those
     *         related to members of all others
     */
    private static String intersection(List<TablePlan> filters) {
        List<TablePlan> deepestFirst = new ArrayList<>(filters);
        deepestFirst.sort((a, b) -> Integer.compare(depth(b), depth(a)));
        String set = filterSet(deepestFirst.getFirst());
        for (TablePlan filter : deepestFirst.subList(1, deepestFirst.size())) {
            set = "Exists(" + set + ", " + filterSet(filter) + ")";
        }
        return set;
    }

    private static int depth(TablePlan filter) {
        return Summarize.filterColumns(filter).stream().mapToInt(ModelColumn::depth).max().orElse(0);
    }

    /**
     * Appends a calculated member for each group of the values of the columns
     * of the hierarchy: the aggregate of the members of the deepest level of
     * them whose names and properties have the values.
     *
     * @param values the values of each group, of the columns of the hierarchy
     *               grouped by, in their order
     * @param byName where the values of each column of the hierarchy are put,
     *               by the name of the member of their group
     * @return the set of the calculated members
     */
    private static String groupMembers(String hierarchy, List<List<Object>> values, List<ModelColumn> groupBy,
            StringBuilder mdx, Map<ModelColumn, Map<String, Object>> byName) {
        List<ModelColumn> columns = groupBy.stream().filter(c -> c.hierarchy().equals(hierarchy)).toList();
        String level = deepest(columns).get(hierarchy).level();
        columns.forEach(c -> byName.put(c, new LinkedHashMap<>()));
        StringJoiner set = new StringJoiner(", ", "{", "}");
        for (int g = 0; g < values.size(); g++) {
            List<Object> group = values.get(g);
            String name = "DAX group " + (g + 1);
            String member = hierarchy + "." + MdxNames.quote(name);
            StringJoiner condition = new StringJoiner(" AND ");
            for (int i = 0; i < columns.size(); i++) {
                ModelColumn column = columns.get(i);
                Object value = group.get(i);
                byName.get(column).put(name, value);
                String term = memberName(column, columns);
                // a property may have no value; a name always has
                term = value == null ? "IsEmpty(" + term + ")" : equal(term, column, value);
                condition.add(columns.size() == 1 ? term : "(" + term + ")");
            }
            mdx.append(mdx.isEmpty() ? "WITH " : " ").append("MEMBER ").append(member).append(" AS Aggregate(Filter(")
                    .append(level).append(".Members, ").append(condition).append("))");
            set.add(member);
        }
        return set.toString();
    }

    /**
     * @param all     the members of the columns axis: the measures, then the
     *                added ones
     * @param keeping the members of the measures, not of the added ones, which
     *                keep no group
     */
    private record Measures(String all, String keeping) {
    }

    /**
     * Appends the {@code WITH} clause of the measures that are no measure of
     * the cube, with a space after it, and a cell source for each measure.
     *
     * @param overGroups whether they are computed over calculated members of
     *                   groups, which they are computed after
     */
    private static Measures measures(Summarize summarize, StringBuilder mdx, List<ValueSource> sources,
            boolean overGroups) {
        StringJoiner measures = new StringJoiner(", ", "{", "}");
        StringJoiner keeping = new StringJoiner(", ", "{", "}");
        List<NamedMeasure> all = summarize.allMeasures();
        for (int i = 0; i < all.size(); i++) {
            NamedMeasure measure = all.get(i);
            String member;
            if (measure.expression() instanceof MeasureValue value) {
                member = value.measure().uniqueName();
            } else {
                member = "[Measures]." + MdxNames.quote("DAX " + measure.name());
                mdx.append(mdx.isEmpty() ? "WITH " : " ").append("MEMBER ").append(member).append(" AS ")
                        .append(condition(measure.expression()));
                if (overGroups) {
                    mdx.append(", SOLVE_ORDER = 1");
                }
            }
            measures.add(member);
            if (i < summarize.measures().size()) {
                keeping.add(member);
            }
            sources.add(new ValueSource.CellValue(i));
        }
        if (!mdx.isEmpty()) {
            mdx.append(" ");
        }
        return new Measures(measures.toString(), keeping.toString());
    }

    /**
     * @param cube     the cube name, as MDX writes it
     * @param generate the plan
     * @return the MDX query computing the plan's table: its rows are MDX
     *         {@code Generate} of the outer set, joining each of its tuples
     *         with the inner set computed for it
     */
    public static MdxQuery generate(String cube, Generate generate) {
        Summarize inner = generate.inner();
        List<ModelColumn> outerColumns = Summarize.filterColumns(generate.outer());
        // the outer hierarchies the inner grouping goes deeper in are its own on the axis
        List<String> hierarchies = new ArrayList<>(deepest(outerColumns).keySet());
        hierarchies.removeAll(deepest(inner.groupBy()).keySet());
        hierarchies.addAll(deepest(inner.groupBy()).keySet());

        List<ValueSource> sources = new ArrayList<>();
        List<ModelColumn> grouped = new ArrayList<>(outerColumns);
        grouped.addAll(inner.groupBy());
        for (ModelColumn column : grouped) {
            sources.add(source(hierarchies.indexOf(column.hierarchy()), column));
        }
        StringBuilder mdx = new StringBuilder();
        Measures measures = measures(inner, mdx, sources, false);

        String set = filterSet(generate.outer());
        if (!inner.groupBy().isEmpty()) {
            // computed for the members of each outer tuple
            String innerSet = conditionAndTop(innerSet(generate), inner, Summarize.filterColumns(generate));
            if (!inner.measures().isEmpty()) {
                innerSet = "NonEmpty(" + innerSet + ", " + measures.keeping() + ")";
            }
            set = generateSet(generate, innerSet);
        }
        mdx.append("SELECT ").append(measures.all()).append(" ON COLUMNS, ").append(set).append(" ON ROWS FROM ")
                .append(cube);
        return new MdxQuery(mdx.toString(), sources, true);
    }

    /**
     * @param innerSet the inner set, computed for the members of each outer tuple
     * @return MDX {@code Generate} of the outer set, joining each of its tuples
     *         with the inner set
     */
    private static String generateSet(Generate generate, String innerSet) {
        // the members of a hierarchy the inner set goes deeper in are its own
        List<String> outer = new ArrayList<>(deepest(Summarize.filterColumns(generate.outer())).keySet());
        outer.removeAll(deepest(generate.inner().groupBy()).keySet());
        if (outer.isEmpty()) {
            return "Generate(" + filterSet(generate.outer()) + ", " + innerSet + ")";
        }
        StringJoiner current = new StringJoiner(", ", outer.size() == 1 ? "{" : "{(", outer.size() == 1 ? "}" : ")}");
        outer.forEach(h -> current.add(h + ".CurrentMember"));
        return "Generate(" + filterSet(generate.outer()) + ", CrossJoin(" + current + ", " + innerSet + "))";
    }

    /**
     * @return the cross join of the deepest level of each hierarchy the inner
     *         grouping groups by: of a hierarchy of the outer table the
     *         members under its current member, or the current member itself
     *         when the level is not deeper, as its ancestor is the inner one
     */
    private static String innerSet(Generate generate) {
        Map<String, ModelColumn> outer = deepest(Summarize.filterColumns(generate.outer()));
        String set = null;
        for (ModelColumn column : deepest(generate.inner().groupBy()).values()) {
            ModelColumn outerColumn = outer.get(column.hierarchy());
            String members;
            if (outerColumn == null) {
                members = column.level() + ".Members";
            } else if (column.depth() > outerColumn.depth()) {
                members = "Descendants(" + column.hierarchy() + ".CurrentMember, " + column.level() + ")";
            } else {
                members = "{" + column.hierarchy() + ".CurrentMember}";
            }
            set = set == null ? members : "CrossJoin(" + set + ", " + members + ")";
        }
        return set;
    }

    /** @return the cross join of the deepest level of each hierarchy grouped by */
    private static String set(Summarize summarize) {
        return set(summarize, Map.of());
    }

    /**
     * @param groups the set of the calculated members of the groups of each
     *               hierarchy grouped by values
     */
    private static String set(Summarize summarize, Map<String, String> groups) {
        String set = null;
        for (ModelColumn column : deepest(summarize.groupBy()).values()) {
            String members = groups.getOrDefault(column.hierarchy(), column.level() + ".Members");
            set = set == null ? members : "CrossJoin(" + set + ", " + members + ")";
        }
        return set;
    }

    private static String conditionAndTop(String set, Summarize summarize) {
        return conditionAndTop(set, summarize, summarize.groupBy());
    }

    /**
     * @param current the columns whose deepest of each hierarchy is the level of
     *                the members of the set
     */
    private static String conditionAndTop(String set, Summarize summarize, List<ModelColumn> current) {
        if (summarize.condition().isPresent()) {
            set = "Filter(" + set + ", " + condition(summarize.condition().get(), summarize.groupBy(), current) + ")";
        }
        if (summarize.top().isPresent()) {
            Summarize.Top top = summarize.top().get();
            String measure = top.measure().uniqueName();
            set = (top.ascending() ? "BottomCount(" : "TopCount(") + "NonEmpty(" + set + ", {" + measure + "}), "
                    + top.count() + ", " + measure + ")";
        }
        return set;
    }

    /** @return the set of the members of a filter table, see {@link Summarize#filters()} */
    private static String filterSet(TablePlan filter) {
        return switch (filter) {
        case Summarize summarize -> conditionAndTop(set(summarize), summarize);
        case Filter f -> "Filter(" + filterSet(f.source()) + ", "
                + condition(f.condition(), Summarize.filterColumns(f.source())) + ")";
        case Generate generate -> generateSet(generate,
                conditionAndTop(innerSet(generate), generate.inner(), Summarize.filterColumns(generate)));
        default -> throw new IllegalArgumentException("no filter table: " + filter);
        };
    }

    /** @return the deepest column of each hierarchy, in order of first appearance */
    private static Map<String, ModelColumn> deepest(List<ModelColumn> columns) {
        Map<String, ModelColumn> deepest = new LinkedHashMap<>();
        for (ModelColumn column : columns) {
            deepest.merge(column.hierarchy(), column, (a, b) -> b.depth() > a.depth() ? b : a);
        }
        return deepest;
    }

    /**
     * @param plan an expression of measures and non-BLANK constants, as
     *             {@link Summarize#condition()} has; {@code ISBLANK} of a
     *             measure is {@code IsEmpty}
     * @return it as an MDX expression
     */
    static String condition(ScalarPlan plan) {
        return condition(plan, List.of());
    }

    /**
     * @param plan    a condition as {@link #condition(ScalarPlan)} takes, or on
     *                the text of columns
     * @param columns the columns a {@link ColumnValue} refers to, each the name
     *                of a member of the row or of its ancestor
     * @return it as an MDX expression
     */
    static String condition(ScalarPlan plan, List<ModelColumn> columns) {
        return condition(plan, columns, columns);
    }

    /**
     * @param current the columns whose deepest of each hierarchy is the level of
     *                the current members
     */
    private static String condition(ScalarPlan plan, List<ModelColumn> columns, List<ModelColumn> current) {
        return switch (plan) {
        case MeasureValue measure -> measure.measure().uniqueName();
        case Constant constant -> literal(constant.value());
        // text compared ignoring case; a property of another type as it is
        case ColumnValue column -> columns.get(column.column()).type() == DaxType.STRING
                ? "UCase(" + memberName(columns.get(column.column()), current) + ")"
                : memberName(columns.get(column.column()), current);
        case InList in -> {
            StringJoiner any = new StringJoiner(" OR ", "(", ")").setEmptyValue("(1 = 0)");
            for (Object value : in.values()) {
                // a member's name is never BLANK
                if (value != null) {
                    any.add(condition(in.value(), columns, current) + " = "
                            + literal(value.toString().toUpperCase(Locale.ROOT)));
                }
            }
            yield any.toString();
        }
        case Comparison comparison -> term(comparison.left(), comparison, columns, current) + " " + switch (comparison.operator()) {
            case EQUAL -> "=";
            case NOT_EQUAL -> "<>";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            case IN -> throw new IllegalArgumentException("IN is an InList");
            } + " " + term(comparison.right(), comparison, columns, current);
        case Logical logical -> operand(logical.left(), columns, current)
                + (logical.operator() == LogicalOperator.AND ? " AND " : " OR ")
                + operand(logical.right(), columns, current);
        case Not not -> "NOT " + operand(not.operand(), columns, current);
        case IsBlank isBlank -> "IsEmpty(" + condition(isBlank.operand(), columns, current) + ")";
        case ColumnAggregate aggregate -> aggregate(aggregate);
        // CCur, which the cube does not know: the number rounded to four places
        case Currency currency -> "Round(CDbl(" + condition(currency.operand(), columns, current) + "), "
                + ScalarPlan.CURRENCY_SCALE + ")";
        };
    }

    /**
     * @param term  the value of the column, as MDX computes it
     * @param value its value, not BLANK
     * @return whether the term is the value: a logical one is the condition
     *         itself or its negation, which MDX {@code =} cannot compare
     */
    private static String equal(String term, ModelColumn column, Object value) {
        if (column.type() == DaxType.BOOLEAN && value instanceof Boolean bool) {
            return bool ? term : "NOT " + term;
        }
        return term + " = " + literal(value);
    }

    private static String aggregate(ColumnAggregate aggregate) {
        ModelColumn column = aggregate.column();
        String members = "Existing " + column.level() + ".Members";
        String name = value(column.hierarchy() + ".CurrentMember", column);
        String number = switch (column.type()) {
        case INTEGER, DOUBLE, DECIMAL -> name;
        default -> "IIf(IsNumeric(" + name + "), CDbl(" + name + "), NULL)";
        };
        return switch (aggregate.aggregation()) {
        case SUM -> "Sum(" + members + ", " + number + ")";
        case AVERAGE -> "Avg(" + members + ", " + number + ")";
        case MIN -> "Min(" + members + ", " + number + ")";
        case MAX -> "Max(" + members + ", " + number + ")";
        // the names of members are never BLANK; nothing to count is BLANK, as in DAX
        case COUNT, COUNTA, DISTINCTCOUNT -> "IIf(Count(" + members + ") = 0, NULL, Count(" + members + "))";
        };
    }

    /** @return an operand of a comparison; text compared with a column in upper case, as the column is */
    private static String term(ScalarPlan plan, Comparison comparison, List<ModelColumn> columns,
            List<ModelColumn> current) {
        boolean withColumn = comparison.left() instanceof ColumnValue || comparison.right() instanceof ColumnValue;
        if (withColumn && plan instanceof Constant constant && constant.value() instanceof String text) {
            return literal(text.toUpperCase(Locale.ROOT));
        }
        return condition(plan, columns, current);
    }

    /** @return the value of the column of the member: the current one, or its ancestor at the column's level */
    private static String memberName(ModelColumn column, List<ModelColumn> current) {
        String member = column.hierarchy() + ".CurrentMember";
        if (deepest(current).get(column.hierarchy()).depth() == column.depth()) {
            return value(member, column);
        }
        return value("Ancestor(" + member + ", " + column.level() + ")", column);
    }

    /** @return the value of the column of a member: its name, or the value of its property */
    private static String value(String member, ModelColumn column) {
        return column.property().map(p -> member + ".Properties(" + literal(p) + ")").orElse(member + ".Name");
    }

    /** @return where the value of the column is found, of the member of the hierarchy at the index */
    private static ValueSource source(int member, ModelColumn column) {
        return column.property()
                .<ValueSource>map(p -> new ValueSource.MemberProperty(member, column.depth(), p, column.type()))
                .orElseGet(() -> new ValueSource.MemberName(member, column.depth()));
    }

    private static String operand(ScalarPlan plan, List<ModelColumn> columns, List<ModelColumn> current) {
        String mdx = condition(plan, columns, current);
        return plan instanceof Logical || plan instanceof Comparison ? "(" + mdx + ")" : mdx;
    }

    private static String literal(Object value) {
        return switch (value) {
        case String string -> "\"" + string.replace("\"", "\"\"") + "\"";
        case Boolean bool -> bool ? "TRUE" : "FALSE";
        case BigDecimal decimal -> decimal.toPlainString();
        case Double number -> BigDecimal.valueOf(number).toPlainString();
        case Number number -> number.toString();
        case LocalDateTime time when time.toLocalTime().equals(LocalTime.MIDNIGHT) ->
            "DateSerial(" + time.getYear() + ", " + time.getMonthValue() + ", " + time.getDayOfMonth() + ")";
        default -> throw new IllegalArgumentException("no MDX literal for " + value);
        };
    }
}
