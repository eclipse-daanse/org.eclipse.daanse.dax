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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;

import org.eclipse.daanse.dax.engine.api.DaxType;

/**
 * How DAX types and values appear through JDBC.
 */
final class JdbcTypes {

    /** Precision and scale of {@link DaxType#DECIMAL}, a fixed decimal of four places. */
    static final int DECIMAL_PRECISION = 19;
    static final int DECIMAL_SCALE = 4;

    private JdbcTypes() {
    }

    /** @return the {@link Types} constant of the type */
    static int sqlType(DaxType type) {
        return switch (type) {
        case INTEGER -> Types.BIGINT;
        case DECIMAL -> Types.DECIMAL;
        case DOUBLE -> Types.DOUBLE;
        case STRING -> Types.VARCHAR;
        case BOOLEAN -> Types.BOOLEAN;
        case DATETIME -> Types.TIMESTAMP;
        case BINARY -> Types.VARBINARY;
        case VARIANT -> Types.JAVA_OBJECT;
        };
    }

    static String sqlTypeName(DaxType type) {
        return switch (type) {
        case INTEGER -> "BIGINT";
        case DECIMAL -> "DECIMAL";
        case DOUBLE -> "DOUBLE";
        case STRING -> "VARCHAR";
        case BOOLEAN -> "BOOLEAN";
        case DATETIME -> "TIMESTAMP";
        case BINARY -> "VARBINARY";
        case VARIANT -> "JAVA_OBJECT";
        };
    }

    /** @return the class of the values {@link #jdbcValue(Object)} gives for the type */
    static Class<?> jdbcClass(DaxType type) {
        return type == DaxType.DATETIME ? Timestamp.class : type.javaType();
    }

    /** @return the value as JDBC gives it: date-times as {@link Timestamp} */
    static Object jdbcValue(Object value) {
        return value instanceof LocalDateTime dateTime ? Timestamp.valueOf(dateTime) : value;
    }

    static int precision(DaxType type) {
        return switch (type) {
        case INTEGER -> 19;
        case DECIMAL -> DECIMAL_PRECISION;
        case DOUBLE -> 15;
        default -> 0;
        };
    }

    static int scale(DaxType type) {
        return type == DaxType.DECIMAL ? DECIMAL_SCALE : 0;
    }

    static boolean signed(DaxType type) {
        return type == DaxType.INTEGER || type == DaxType.DECIMAL || type == DaxType.DOUBLE;
    }

    static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }
}
