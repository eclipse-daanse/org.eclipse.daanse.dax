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

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

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
 * (updates, scrolling, streams, large objects).
 * </p>
 */
final class TableResultSet implements ResultSet {

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
                values[c] = JdbcTypes.jdbcValue(columns.get(c).type(), table.getObject(c));
            }
            rows.add(values);
        }
        return new TableResultSet(columns, rows);
    }

    // --- state and cursor

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (row < rows.size()) {
            row++;
        }
        return row < rows.size();
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return row >= 0 && row < rows.size() ? row + 1 : 0;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return row < 0 && !rows.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return row >= rows.size() && !rows.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return row == 0 && !rows.isEmpty();
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return row == rows.size() - 1 && !rows.isEmpty();
    }

    @Override
    public boolean previous() throws SQLException {
        throw forwardOnly("previous");
    }

    @Override
    public boolean first() throws SQLException {
        throw forwardOnly("first");
    }

    @Override
    public boolean last() throws SQLException {
        throw forwardOnly("last");
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw forwardOnly("beforeFirst");
    }

    @Override
    public void afterLast() throws SQLException {
        throw forwardOnly("afterLast");
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw forwardOnly("absolute");
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw forwardOnly("relative");
    }

    // --- properties

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new TableMetaData(columns);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("no column " + columnLabel);
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != FETCH_FORWARD) {
            throw new SQLFeatureNotSupportedException("the result set is forward only");
        }
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return 0;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public String getCursorName() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return lastValue == null;
    }

    // --- values by index

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return value(columnIndex);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return value(columnIndex);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return convert(value(columnIndex), type);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        return value == null ? null : value.toString();
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        return value instanceof Boolean b ? b : number(value).intValue() != 0;
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return number(value(columnIndex)).byteValue();
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return number(value(columnIndex)).shortValue();
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return number(value(columnIndex)).intValue();
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return number(value(columnIndex)).longValue();
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return number(value(columnIndex)).floatValue();
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return number(value(columnIndex)).doubleValue();
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        return value == null ? null : JdbcTypes.decimal(value);
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal value = getBigDecimal(columnIndex);
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return convert(value(columnIndex), Timestamp.class);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return convert(value(columnIndex), byte[].class);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        throw unsupported("getTimestamp with a calendar");
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        throw unsupported("getDate");
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        throw unsupported("getDate");
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        throw unsupported("getTime");
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        throw unsupported("getTime");
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        throw unsupported("getAsciiStream");
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        throw unsupported("getUnicodeStream");
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        throw unsupported("getBinaryStream");
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        throw unsupported("getCharacterStream");
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        throw unsupported("getNCharacterStream");
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw unsupported("getRef");
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        throw unsupported("getBlob");
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        throw unsupported("getClob");
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw unsupported("getNClob");
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw unsupported("getArray");
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        throw unsupported("getURL");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw unsupported("getRowId");
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw unsupported("getSQLXML");
    }

    // --- values by label

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel), map);
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getNString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel), cal);
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel), cal);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel), cal);
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return getUnicodeStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getNCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return getRef(findColumn(columnLabel));
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return getBlob(findColumn(columnLabel));
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return getClob(findColumn(columnLabel));
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return getNClob(findColumn(columnLabel));
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return getRowId(findColumn(columnLabel));
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return getSQLXML(findColumn(columnLabel));
    }

    // --- updates: the result set is read only

    @Override
    public boolean rowUpdated() throws SQLException {
        throw readOnly();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        throw readOnly();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void insertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(int columnIndex, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(String columnLabel, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, NClob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, NClob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }
    // --- wrapper

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("not a wrapper of " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    @Override
    public String toString() {
        return "ResultSet of DAX table " + columns.stream().map(DaxColumn::name).toList();
    }

    // --- helpers

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("the result set is closed");
        }
    }

    /** @return the value of the column, given by 1-based index, in the current row */
    private Object value(int columnIndex) throws SQLException {
        checkOpen();
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("no column " + columnIndex + "; the result set has " + columns.size());
        }
        if (row < 0 || row >= rows.size()) {
            throw new SQLException("there is no current row");
        }
        lastValue = rows.get(row)[columnIndex - 1];
        return lastValue;
    }

    private SQLException unsupported(String method) throws SQLException {
        checkOpen();
        return new SQLFeatureNotSupportedException(method + " is not supported by the result set of a DAX table");
    }

    private SQLException forwardOnly(String method) throws SQLException {
        checkOpen();
        return new SQLFeatureNotSupportedException(method + " is not supported; the result set is forward only");
    }

    private SQLException readOnly() throws SQLException {
        checkOpen();
        return new SQLFeatureNotSupportedException("the result set of a DAX table is read only");
    }

    /** @return the value as a number, blank as 0 */
    private static Number number(Object value) throws SQLException {
        if (value == null) {
            return 0;
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

    private static <T> T convert(Object value, Class<T> type) throws SQLException {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (type == LocalDateTime.class && value instanceof Timestamp timestamp) {
            return type.cast(timestamp.toLocalDateTime());
        }
        if (type == String.class) {
            return type.cast(value.toString());
        }
        throw new SQLException("a " + value.getClass().getName() + " is no " + type.getName());
    }

    /** The metadata of the columns. */
    private record TableMetaData(List<DaxColumn> columns) implements ResultSetMetaData {

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnLabel(int column) throws SQLException {
            return column(column).name();
        }

        @Override
        public String getColumnName(int column) throws SQLException {
            return column(column).name();
        }

        @Override
        public String getTableName(int column) throws SQLException {
            return column(column).table().orElse("");
        }

        @Override
        public String getSchemaName(int column) throws SQLException {
            column(column);
            return "";
        }

        @Override
        public String getCatalogName(int column) throws SQLException {
            column(column);
            return "";
        }

        @Override
        public int getColumnType(int column) throws SQLException {
            return JdbcTypes.sqlType(column(column).type());
        }

        @Override
        public String getColumnTypeName(int column) throws SQLException {
            return JdbcTypes.sqlTypeName(column(column).type());
        }

        @Override
        public String getColumnClassName(int column) throws SQLException {
            return JdbcTypes.jdbcClass(column(column).type()).getName();
        }

        @Override
        public int getPrecision(int column) throws SQLException {
            return JdbcTypes.precision(column(column).type());
        }

        @Override
        public int getScale(int column) throws SQLException {
            return JdbcTypes.scale(column(column).type());
        }

        @Override
        public int getColumnDisplaySize(int column) throws SQLException {
            DaxColumn daxColumn = column(column);
            return Math.max(JdbcTypes.precision(daxColumn.type()), daxColumn.name().length());
        }

        @Override
        public int isNullable(int column) throws SQLException {
            column(column);
            return columnNullable;
        }

        @Override
        public boolean isSigned(int column) throws SQLException {
            return JdbcTypes.signed(column(column).type());
        }

        @Override
        public boolean isAutoIncrement(int column) throws SQLException {
            column(column);
            return false;
        }

        @Override
        public boolean isCurrency(int column) throws SQLException {
            column(column);
            return false;
        }

        @Override
        public boolean isCaseSensitive(int column) throws SQLException {
            column(column);
            return false;
        }

        @Override
        public boolean isWritable(int column) throws SQLException {
            column(column);
            return false;
        }

        @Override
        public boolean isDefinitelyWritable(int column) throws SQLException {
            column(column);
            return false;
        }

        @Override
        public boolean isReadOnly(int column) throws SQLException {
            column(column);
            return true;
        }

        @Override
        public boolean isSearchable(int column) throws SQLException {
            column(column);
            return true;
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new SQLException("not a wrapper of " + type.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return type.isInstance(this);
        }

        @Override
        public String toString() {
            return "metadata of DAX table " + columns.stream().map(DaxColumn::name).toList();
        }

        private DaxColumn column(int column) throws SQLException {
            if (column < 1 || column > columns.size()) {
                throw new SQLException("no column " + column + "; the result set has " + columns.size());
            }
            return columns.get(column - 1);
        }
    }
}
