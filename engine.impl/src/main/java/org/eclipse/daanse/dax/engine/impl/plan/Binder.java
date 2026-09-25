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
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;
import org.eclipse.daanse.dax.engine.impl.model.ModelMeasure;
import org.eclipse.daanse.dax.engine.impl.model.ModelTable;
import org.eclipse.daanse.dax.engine.impl.model.TabularModel;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan.SortKey;
import org.eclipse.daanse.dax.model.api.ColumnDefinition;
import org.eclipse.daanse.dax.model.api.DaxStatement;
import org.eclipse.daanse.dax.model.api.DefineClause;
import org.eclipse.daanse.dax.model.api.EvaluateStatement;
import org.eclipse.daanse.dax.model.api.MeasureDefinition;
import org.eclipse.daanse.dax.model.api.OrderByItem;
import org.eclipse.daanse.dax.model.api.ParameterDefinition;
import org.eclipse.daanse.dax.model.api.TableDefinition;
import org.eclipse.daanse.dax.model.api.VariableDefinition;
import org.eclipse.daanse.dax.model.api.expression.BooleanLiteral;
import org.eclipse.daanse.dax.model.api.expression.DateTimeLiteral;
import org.eclipse.daanse.dax.model.api.expression.DaxExpression;
import org.eclipse.daanse.dax.model.api.expression.Entity;
import org.eclipse.daanse.dax.model.api.expression.FunctionCall;
import org.eclipse.daanse.dax.model.api.expression.Identifier;
import org.eclipse.daanse.dax.model.api.expression.Keyword;
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
 * references, constant {@code VAR} and parameter definitions, and
 * {@code ORDER BY} result columns. Anything else fails as not supported yet.
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
            TablePlan table = table(evaluate.tableExpression());
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
            Optional<ModelMeasure> measure = measureReference(expressions.get(i));
            if (measure.isPresent()) {
                measures.add(new NamedMeasure(names.get(i), measure.get()));
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
        int i = 0;
        for (; i < arguments.size() && !(arguments.get(i) instanceof StringLiteral); i++) {
            DaxExpression argument = arguments.get(i);
            if (!(argument instanceof Identifier identifier)) {
                throw notSupported("SUMMARIZECOLUMNS with a filter table");
            }
            groupBy.add(column(identifier));
        }
        if ((arguments.size() - i) % 2 != 0) {
            throw new DaxSemanticException("SUMMARIZECOLUMNS takes pairs of a name and an expression after its columns");
        }
        List<NamedMeasure> measures = new ArrayList<>();
        for (; i < arguments.size(); i += 2) {
            String name = columnName(arguments.get(i), "SUMMARIZECOLUMNS");
            DaxExpression expression = arguments.get(i + 1);
            ModelMeasure measure = measureReference(expression)
                    .orElseThrow(() -> notSupported("SUMMARIZECOLUMNS with an expression of kind " + kind(expression)
                            + "; only measure references are"));
            measures.add(new NamedMeasure(name, measure));
        }
        return summarize(groupBy, measures);
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

    private static String columnName(DaxExpression expression, String function) throws DaxSemanticException {
        if (expression instanceof StringLiteral name) {
            return name.value();
        }
        throw new DaxSemanticException(function + " takes a string as column name");
    }

    // --- scalars

    private Object constant(DaxExpression expression) throws DaxSemanticException {
        return switch (expression) {
        case NumericLiteral number -> number(number.value());
        case StringLiteral string -> string.value();
        case BooleanLiteral bool -> bool.value();
        case DateTimeLiteral dateTime -> dateTime.value();
        case Keyword keyword when keyword.name().equalsIgnoreCase("TRUE") -> Boolean.TRUE;
        case Keyword keyword when keyword.name().equalsIgnoreCase("FALSE") -> Boolean.FALSE;
        case FunctionCall call when call.arguments().isEmpty() -> switch (call.functionName().toUpperCase(Locale.ROOT)) {
            case "BLANK" -> null;
            case "TRUE" -> Boolean.TRUE;
            case "FALSE" -> Boolean.FALSE;
            default -> throw notSupported("the function " + call.functionName() + " in a constant");
            };
        case Parameter parameter -> {
            if (!parameters.containsKey(parameter.name())) {
                throw new DaxSemanticException("the parameter @" + parameter.name() + " has no value");
            }
            yield parameters.get(parameter.name());
        }
        case VariableReference variable -> {
            if (!variables.containsKey(variable.name())) {
                throw new DaxSemanticException("the variable " + variable.name() + " is not defined");
            }
            yield variables.get(variable.name());
        }
        default -> throw notSupported("an expression of kind " + kind(expression) + " where a constant is expected");
        };
    }

    private static Object number(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.toBigInteger().bitLength() < 64) {
            return stripped.longValueExact();
        }
        return value.doubleValue();
    }

    // --- order

    private static List<SortKey> orderBy(List<OrderByItem> items, List<DaxColumn> columns) throws DaxSemanticException {
        List<SortKey> keys = new ArrayList<>();
        for (OrderByItem item : items) {
            if (item.startAt().isPresent()) {
                throw notSupported("START AT");
            }
            String name = switch (item.expression()) {
            case Scalar scalar -> "[" + scalar.name() + "]";
            case Identifier identifier -> {
                String[] parts = tableAndName(identifier);
                yield parts[0] + "[" + parts[1] + "]";
            }
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
