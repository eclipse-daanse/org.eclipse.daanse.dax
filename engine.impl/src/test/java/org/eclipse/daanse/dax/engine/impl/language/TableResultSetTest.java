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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxTable;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.junit.jupiter.api.Test;

class TableResultSetTest {

    private static final LocalDateTime NEW_YEAR = LocalDateTime.of(2026, 1, 1, 0, 0);

    /** A table held in a list, as the engine's tables are. */
    private record ListTable(List<DaxColumn> columns, List<List<Object>> rows, int[] row) implements DaxTable {

        ListTable(List<DaxColumn> columns, List<List<Object>> rows) {
            this(columns, rows, new int[] { -1 });
        }

        @Override
        public boolean next() {
            return ++row[0] < rows.size();
        }

        @Override
        public Object getObject(int column) {
            return rows.get(row[0]).get(column);
        }
    }

    private static ResultSet resultSet() throws Exception {
        return TableResultSet.of(new ListTable(List.of(
                new DaxColumn("Product[Category]", Optional.of("Product"), DaxType.STRING),
                new DaxColumn("[Units]", Optional.empty(), DaxType.INTEGER),
                new DaxColumn("[Amount]", Optional.empty(), DaxType.DECIMAL),
                new DaxColumn("[Day]", Optional.empty(), DaxType.DATETIME)),
                List.of(List.of("Bikes", 3L, new BigDecimal("10.5000"), NEW_YEAR),
                        Arrays.asList("Caps", null, null, null))));
    }

    @Test
    void describesTheColumns() throws Exception {
        ResultSetMetaData metaData = resultSet().getMetaData();
        assertThat(metaData.getColumnCount()).isEqualTo(4);
        assertThat(metaData.getColumnLabel(1)).isEqualTo("Product[Category]");
        assertThat(metaData.getTableName(1)).isEqualTo("Product");
        assertThat(metaData.getColumnType(1)).isEqualTo(Types.VARCHAR);
        assertThat(metaData.getColumnType(2)).isEqualTo(Types.BIGINT);
        assertThat(metaData.getColumnType(3)).isEqualTo(Types.DECIMAL);
        assertThat(metaData.getPrecision(3)).isEqualTo(19);
        assertThat(metaData.getScale(3)).isEqualTo(4);
        assertThat(metaData.getColumnType(4)).isEqualTo(Types.TIMESTAMP);
        assertThat(metaData.getColumnClassName(4)).isEqualTo(Timestamp.class.getName());
        assertThat(metaData.isNullable(2)).isEqualTo(ResultSetMetaData.columnNullable);
    }

    @Test
    void readsTheRows() throws Exception {
        ResultSet resultSet = resultSet();
        assertThat(resultSet.isBeforeFirst()).isTrue();

        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getRow()).isEqualTo(1);
        assertThat(resultSet.getString(1)).isEqualTo("Bikes");
        assertThat(resultSet.getObject("[units]")).isEqualTo(3L);
        assertThat(resultSet.getInt(2)).isEqualTo(3);
        assertThat(resultSet.getBigDecimal(3)).isEqualByComparingTo("10.5");
        assertThat(resultSet.getObject(4)).isEqualTo(Timestamp.valueOf(NEW_YEAR));
        assertThat(resultSet.getObject(4, LocalDateTime.class)).isEqualTo(NEW_YEAR);
        assertThat(resultSet.wasNull()).isFalse();

        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getObject(2)).isNull();
        assertThat(resultSet.wasNull()).isTrue();
        assertThat(resultSet.getLong(2)).isZero();

        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.isAfterLast()).isTrue();
        assertThatThrownBy(() -> resultSet.getString(1)).isInstanceOf(SQLException.class);
    }

    @Test
    void isForwardOnlyAndReadOnly() throws Exception {
        ResultSet resultSet = resultSet();
        assertThat(resultSet.getType()).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
        assertThat(resultSet.getConcurrency()).isEqualTo(ResultSet.CONCUR_READ_ONLY);
        assertThatThrownBy(resultSet::previous).isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(() -> resultSet.updateString(1, "x")).isInstanceOf(SQLFeatureNotSupportedException.class);
    }

    @Test
    void closes() throws Exception {
        ResultSet resultSet = resultSet();
        resultSet.close();
        assertThat(resultSet.isClosed()).isTrue();
        assertThatThrownBy(resultSet::next).isInstanceOf(SQLException.class).hasMessage("the result set is closed");
    }
}
