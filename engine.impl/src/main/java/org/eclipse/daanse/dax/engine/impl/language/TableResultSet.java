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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxException;
import org.eclipse.daanse.dax.engine.api.DaxTable;

/**
 * A forward-only, read-only {@link ResultSet} over the rows of a
 * {@link DaxTable}, read into memory.
 * <p>
 * It serves what a consumer of query results needs - moving to the next row,
 * reading values by index or label, the metadata of the columns - and throws
 * {@link SQLFeatureNotSupportedException} for the rest of the interface
 * (updates, scrolling, streams), which is why it is a proxy rather than a class
 * with some two hundred methods.
 * </p>
 */
final class TableResultSet implements InvocationHandler {

    private final List<DaxColumn> columns;
    private final List<Object[]> rows;
    private int row = -1;
    private boolean closed;
    private Object lastValue;

    private TableResultSet(List<DaxColumn> columns, List<Object[]> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    /**
     * Reads the table to its end.
     *
     * @return a result set over its rows, before the first
     */
    static ResultSet of(DaxTable table) throws DaxException {
        List<DaxColumn> columns = table.columns();
        List<Object[]> rows = new ArrayList<>();
        while (table.next()) {
            Object[] values = new Object[columns.size()];
            for (int c = 0; c < values.length; c++) {
                values[c] = JdbcTypes.jdbcValue(table.getObject(c));
            }
            rows.add(values);
        }
        return (ResultSet) Proxy.newProxyInstance(TableResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class }, new TableResultSet(columns, rows));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
        case "close" -> {
            closed = true;
            return null;
        }
        case "isClosed" -> {
            return closed;
        }
        case "hashCode" -> {
            return System.identityHashCode(proxy);
        }
        case "equals" -> {
            return proxy == args[0];
        }
        case "toString" -> {
            return "ResultSet of DAX table " + columns.stream().map(DaxColumn::name).toList();
        }
        case "unwrap" -> {
            Class<?> type = (Class<?>) args[0];
            if (type.isInstance(proxy)) {
                return proxy;
            }
            throw new SQLException("not a wrapper of " + type.getName());
        }
        case "isWrapperFor" -> {
            return ((Class<?>) args[0]).isInstance(proxy);
        }
        default -> {
            // everything else needs an open result set
        }
        }
        if (closed) {
            throw new SQLException("the result set is closed");
        }
        return switch (name) {
        case "next" -> {
            if (row < rows.size()) {
                row++;
            }
            yield row < rows.size();
        }
        case "getMetaData" -> TableMetaData.of(columns);
        case "findColumn" -> findColumn((String) args[0]);
        case "wasNull" -> lastValue == null;
        case "getRow" -> row >= 0 && row < rows.size() ? row + 1 : 0;
        case "isBeforeFirst" -> row < 0 && !rows.isEmpty();
        case "isAfterLast" -> row >= rows.size() && !rows.isEmpty();
        case "isFirst" -> row == 0 && !rows.isEmpty();
        case "isLast" -> row == rows.size() - 1 && !rows.isEmpty();
        case "getType" -> ResultSet.TYPE_FORWARD_ONLY;
        case "getConcurrency" -> ResultSet.CONCUR_READ_ONLY;
        case "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
        case "getFetchDirection" -> ResultSet.FETCH_FORWARD;
        case "getFetchSize" -> 0;
        case "setFetchSize", "clearWarnings" -> null;
        case "setFetchDirection" -> {
            if ((Integer) args[0] != ResultSet.FETCH_FORWARD) {
                throw new SQLFeatureNotSupportedException("the result set is forward only");
            }
            yield null;
        }
        case "getWarnings", "getStatement", "getCursorName" -> null;
        case "getObject" -> args.length == 2 && args[1] instanceof Class<?> type ? convert(value(args[0]), type)
                : value(args[0]);
        case "getString", "getNString" -> {
            Object value = value(args[0]);
            yield value == null ? null : value.toString();
        }
        case "getBoolean" -> value(args[0]) instanceof Boolean b ? b : number(value(args[0]), 0).intValue() != 0;
        case "getByte" -> number(value(args[0]), 0).byteValue();
        case "getShort" -> number(value(args[0]), 0).shortValue();
        case "getInt" -> number(value(args[0]), 0).intValue();
        case "getLong" -> number(value(args[0]), 0).longValue();
        case "getFloat" -> number(value(args[0]), 0).floatValue();
        case "getDouble" -> number(value(args[0]), 0).doubleValue();
        case "getBigDecimal" -> {
            Object value = value(args[0]);
            yield value == null ? null : JdbcTypes.decimal(value);
        }
        case "getTimestamp" -> convert(value(args[0]), Timestamp.class);
        case "getBytes" -> convert(value(args[0]), byte[].class);
        default -> throw new SQLFeatureNotSupportedException(
                name + " is not supported by the result set of a DAX table");
        };
    }

    /** @return the value of the column, given by 1-based index or label, in the current row */
    private Object value(Object column) throws SQLException {
        int index = column instanceof String label ? findColumn(label) : (Integer) column;
        if (index < 1 || index > columns.size()) {
            throw new SQLException("no column " + index + "; the result set has " + columns.size());
        }
        if (row < 0 || row >= rows.size()) {
            throw new SQLException("there is no current row");
        }
        lastValue = rows.get(row)[index - 1];
        return lastValue;
    }

    private int findColumn(String label) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(label)) {
                return i + 1;
            }
        }
        throw new SQLException("no column " + label);
    }

    private static Number number(Object value, int blank) throws SQLException {
        if (value == null) {
            return blank;
        }
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new SQLException("not a number: " + value, e);
        }
    }

    private static Object convert(Object value, Class<?> type) throws SQLException {
        if (value == null || type.isInstance(value)) {
            return value;
        }
        if (type == java.time.LocalDateTime.class && value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (type == String.class) {
            return value.toString();
        }
        throw new SQLException("a " + value.getClass().getName() + " is no " + type.getName());
    }

    /** The metadata of the columns, also a proxy for the same reason. */
    private record TableMetaData(List<DaxColumn> columns) implements InvocationHandler {

        static ResultSetMetaData of(List<DaxColumn> columns) {
            return (ResultSetMetaData) Proxy.newProxyInstance(TableResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSetMetaData.class }, new TableMetaData(columns));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
            case "getColumnCount" -> {
                return columns.size();
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == args[0];
            }
            case "toString" -> {
                return "metadata of DAX table " + columns.stream().map(DaxColumn::name).toList();
            }
            case "unwrap" -> {
                if (((Class<?>) args[0]).isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("not a wrapper of " + ((Class<?>) args[0]).getName());
            }
            case "isWrapperFor" -> {
                return ((Class<?>) args[0]).isInstance(proxy);
            }
            default -> {
                // the rest is about one column
            }
            }
            int index = (Integer) args[0];
            if (index < 1 || index > columns.size()) {
                throw new SQLException("no column " + index + "; the result set has " + columns.size());
            }
            DaxColumn column = columns.get(index - 1);
            return switch (name) {
            case "getColumnLabel", "getColumnName" -> column.name();
            case "getTableName" -> column.table().orElse("");
            case "getSchemaName", "getCatalogName" -> "";
            case "getColumnType" -> JdbcTypes.sqlType(column.type());
            case "getColumnTypeName" -> JdbcTypes.sqlTypeName(column.type());
            case "getColumnClassName" -> JdbcTypes.jdbcClass(column.type()).getName();
            case "getPrecision" -> JdbcTypes.precision(column.type());
            case "getScale" -> JdbcTypes.scale(column.type());
            case "getColumnDisplaySize" -> Math.max(JdbcTypes.precision(column.type()), column.name().length());
            case "isNullable" -> ResultSetMetaData.columnNullable;
            case "isSigned" -> JdbcTypes.signed(column.type());
            case "isAutoIncrement", "isCurrency", "isCaseSensitive", "isWritable", "isDefinitelyWritable" -> false;
            case "isReadOnly", "isSearchable" -> true;
            default -> throw new SQLFeatureNotSupportedException(name + " is not supported for a DAX table");
            };
        }
    }
}
