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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.eclipse.daanse.dax.engine.api.DaxExecutionException;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;
import org.eclipse.daanse.dax.engine.impl.model.ModelMeasure;
import org.eclipse.daanse.dax.model.api.expression.BooleanExpression.BooleanOperator;
import org.eclipse.daanse.dax.model.api.expression.LogicalExpression.LogicalOperator;

/**
 * How to compute a scalar value from a row of a table, e.g. the condition of
 * {@code FILTER}. A value is {@code null} for BLANK.
 * <p>
 * A {@link MeasureValue} and a {@link ColumnAggregate} only the cube computes:
 * a plan containing one is translated into MDX, not evaluated.
 * </p>
 */
public sealed interface ScalarPlan {

    /**
     * @param row the values of the row; empty where no row is iterated
     * @return the value
     * @throws DaxExecutionException if a value cannot be converted as needed
     */
    Object evaluate(List<Object> row) throws DaxExecutionException;

    /** A value the query gives. */
    record Constant(Object value) implements ScalarPlan {

        @Override
        public Object evaluate(List<Object> row) {
            return value;
        }
    }

    /**
     * The value of a column of the row.
     *
     * @param column the 0-based index of the column
     */
    record ColumnValue(int column) implements ScalarPlan {

        @Override
        public Object evaluate(List<Object> row) {
            return row.get(column);
        }
    }

    /**
     * The value of a measure for the row, as the cube computes it for the
     * row's members.
     */
    record MeasureValue(ModelMeasure measure) implements ScalarPlan {

        public MeasureValue {
            Objects.requireNonNull(measure, "measure");
        }

        @Override
        public Object evaluate(List<Object> row) {
            throw new IllegalStateException("the measure " + measure.uniqueName() + " is computed by the cube only");
        }
    }

    /** An aggregation of the values of a column, as DAX's {@code SUM(column)}. */
    enum Aggregation {
        SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, DISTINCTCOUNT;

        /** @return the aggregation the DAX function computes; empty if it is none */
        public static Optional<Aggregation> of(String function) {
            try {
                return Optional.of(valueOf(function.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * An aggregation of a column, as the cube computes it for the row: of the
     * names of the members of the column's level that exist with the filters
     * and the row's members.
     */
    record ColumnAggregate(Aggregation aggregation, ModelColumn column) implements ScalarPlan {

        public ColumnAggregate {
            Objects.requireNonNull(aggregation, "aggregation");
            Objects.requireNonNull(column, "column");
        }

        @Override
        public Object evaluate(List<Object> row) {
            throw new IllegalStateException(aggregation + " of " + column.daxName() + " is computed by the cube only");
        }
    }

    /** A comparison, as DAX's {@code =}, {@code <>}, {@code <} and so on. */
    record Comparison(BooleanOperator operator, ScalarPlan left, ScalarPlan right) implements ScalarPlan {

        public Comparison {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            if (operator == BooleanOperator.IN) {
                throw new IllegalArgumentException("IN is an InList");
            }
        }

        @Override
        public Object evaluate(List<Object> row) throws DaxExecutionException {
            int order = compare(left.evaluate(row), right.evaluate(row));
            return switch (operator) {
            case EQUAL -> order == 0;
            case NOT_EQUAL -> order != 0;
            case LESS_THAN -> order < 0;
            case LESS_THAN_OR_EQUAL -> order <= 0;
            case GREATER_THAN -> order > 0;
            case GREATER_THAN_OR_EQUAL -> order >= 0;
            case IN -> throw new IllegalStateException();
            };
        }
    }

    /**
     * {@code value IN {...}}: whether a value is one of the list. As DAX's
     * {@code IN}, it compares strictly: BLANK is only BLANK.
     *
     * @param values the values of the list; {@code null} for BLANK
     */
    record InList(ScalarPlan value, List<Object> values) implements ScalarPlan {

        public InList {
            Objects.requireNonNull(value, "value");
            // not List.copyOf: it rejects null, and BLANK values are null
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }

        @Override
        public Object evaluate(List<Object> row) throws DaxExecutionException {
            Object candidate = value.evaluate(row);
            for (Object element : values) {
                if (candidate == null || element == null ? candidate == element
                        : comparable(candidate, element) && compare(candidate, element) == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code &&} or {@code ||}. */
    record Logical(LogicalOperator operator, ScalarPlan left, ScalarPlan right) implements ScalarPlan {

        public Logical {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }

        @Override
        public Object evaluate(List<Object> row) throws DaxExecutionException {
            String function = operator == LogicalOperator.AND ? "&&" : "||";
            boolean first = logical(left.evaluate(row), function);
            if (first == (operator == LogicalOperator.OR)) {
                return first;
            }
            return logical(right.evaluate(row), function);
        }
    }

    /** {@code NOT}. */
    record Not(ScalarPlan operand) implements ScalarPlan {

        public Not {
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public Object evaluate(List<Object> row) throws DaxExecutionException {
            return !logical(operand.evaluate(row), "NOT");
        }
    }

    /** {@code ISBLANK}: BLANK only; an empty string or zero is not. */
    record IsBlank(ScalarPlan operand) implements ScalarPlan {

        public IsBlank {
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public Object evaluate(List<Object> row) throws DaxExecutionException {
            return operand.evaluate(row) == null;
        }
    }

    /**
     * {@code CURRENCY}: the value as a fixed decimal of four places, see
     * {@link ScalarPlan#currency(Object)}.
     */
    record Currency(ScalarPlan operand) implements ScalarPlan {

        public Currency {
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public Object evaluate(List<Object> row) throws DaxExecutionException {
            return currency(operand.evaluate(row));
        }
    }

    /** The places of a currency value, as of {@link DaxType#DECIMAL}. */
    int CURRENCY_SCALE = 4;

    /**
     * @return the value as DAX's {@code CURRENCY} converts it: a number, a text
     *         of a number or TRUE/FALSE as a fixed decimal rounded to four
     *         places; BLANK is BLANK
     * @throws DaxExecutionException if the value is no number
     */
    static BigDecimal currency(Object value) throws DaxExecutionException {
        BigDecimal decimal;
        try {
            decimal = switch (value) {
            case null -> null;
            case BigDecimal d -> d;
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> BigDecimal.valueOf(f.doubleValue());
            case Number number -> new BigDecimal(number.toString());
            case Boolean bool -> bool ? BigDecimal.ONE : BigDecimal.ZERO;
            case String text -> new BigDecimal(text.strip());
            default -> throw new NumberFormatException();
            };
        } catch (NumberFormatException notANumber) {
            throw new DaxExecutionException("CURRENCY cannot convert the value '" + value + "' of type "
                    + DaxType.of(value) + " to currency", notANumber);
        }
        return decimal == null ? null : decimal.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * @param measures the measure to compute instead of each
     * @return the plan computing the measures it gives instead
     */
    static ScalarPlan withMeasures(ScalarPlan plan, UnaryOperator<ModelMeasure> measures) {
        return switch (plan) {
        case MeasureValue measure -> new MeasureValue(measures.apply(measure.measure()));
        case Comparison comparison -> new Comparison(comparison.operator(),
                withMeasures(comparison.left(), measures), withMeasures(comparison.right(), measures));
        case InList in -> new InList(withMeasures(in.value(), measures), in.values());
        case Logical logical -> new Logical(logical.operator(), withMeasures(logical.left(), measures),
                withMeasures(logical.right(), measures));
        case Not not -> new Not(withMeasures(not.operand(), measures));
        case IsBlank isBlank -> new IsBlank(withMeasures(isBlank.operand(), measures));
        case Currency currency -> new Currency(withMeasures(currency.operand(), measures));
        case Constant constant -> constant;
        case ColumnValue column -> column;
        case ColumnAggregate aggregate -> aggregate;
        };
    }

    /**
     * @return the value as TRUE/FALSE, as DAX converts it: BLANK is FALSE, a
     *         number is TRUE unless zero
     */
    static boolean logical(Object value, String function) throws DaxExecutionException {
        return switch (value) {
        case null -> false;
        case Boolean bool -> bool;
        case Number number -> number.doubleValue() != 0;
        default -> throw new DaxExecutionException(function + " cannot convert the value '" + value + "' of type "
                + DaxType.of(value) + " to TRUE/FALSE");
        };
    }

    /**
     * Compares as DAX's comparison operators: BLANK as the zero of the other
     * value's type (0, the empty string, FALSE), numbers by value, text
     * ignoring case.
     */
    private static int compare(Object a, Object b) throws DaxExecutionException {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null || b == null) {
            Object other = a == null ? b : a;
            int order = switch (other) {
            case Number number -> compareNumbers(number, 0L);
            case String string -> string.isEmpty() ? 0 : 1;
            case Boolean bool -> bool ? 1 : 0;
            default -> 1;
            };
            return a == null ? -order : order;
        }
        if (!comparable(a, b)) {
            throw new DaxExecutionException("values of type " + DaxType.of(a) + " cannot be compared with values of type "
                    + DaxType.of(b));
        }
        return switch (a) {
        case Number x -> compareNumbers(x, (Number) b);
        case String x -> x.compareToIgnoreCase((String) b);
        case Boolean x -> Boolean.compare(x, (Boolean) b);
        case LocalDateTime x -> x.compareTo((LocalDateTime) b);
        default -> throw new DaxExecutionException("values of type " + DaxType.of(a) + " cannot be compared");
        };
    }

    private static boolean comparable(Object a, Object b) {
        return a instanceof Number ? b instanceof Number : a.getClass() == b.getClass();
    }

    private static int compareNumbers(Number x, Number y) {
        if (x instanceof Long l && y instanceof Long m) {
            return Long.compare(l, m);
        }
        if (!finite(x) || !finite(y)) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return decimal(x).compareTo(decimal(y));
    }

    private static boolean finite(Number number) {
        return !(number instanceof Double || number instanceof Float) || Double.isFinite(number.doubleValue());
    }

    private static BigDecimal decimal(Number number) {
        return number instanceof BigDecimal d ? d : new BigDecimal(number.toString());
    }
}
