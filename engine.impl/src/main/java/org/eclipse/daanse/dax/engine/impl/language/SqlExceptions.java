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

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;

import org.eclipse.daanse.dax.engine.api.DaxCancelledException;
import org.eclipse.daanse.dax.engine.api.DaxException;
import org.eclipse.daanse.dax.engine.api.DaxExecutionException;
import org.eclipse.daanse.dax.engine.api.DaxSemanticException;
import org.eclipse.daanse.dax.engine.api.DaxSyntaxException;

/**
 * Turns DAX failures into {@link SQLException}s whose SQL state a caller that
 * does not know DAX can map:
 * <ul>
 * <li>{@code 42000} ({@link SQLSyntaxErrorException}) - the query is not valid
 * DAX or does not fit the catalog</li>
 * <li>{@code HY000} - evaluating the query failed</li>
 * <li>{@code HY008} - the query was cancelled</li>
 * <li>{@code HYT00} ({@link SQLTimeoutException}) - the query timed out</li>
 * </ul>
 * The {@link DaxException} is the cause.
 */
public final class SqlExceptions {

    public static final String SYNTAX_OR_SEMANTIC = "42000";
    public static final String GENERAL = "HY000";
    public static final String CANCELLED = "HY008";
    public static final String TIMEOUT = "HYT00";

    private SqlExceptions() {
    }

    public static SQLException of(DaxException e) {
        return switch (e) {
        case DaxSyntaxException syntax -> new SQLSyntaxErrorException(syntax.getMessage(), SYNTAX_OR_SEMANTIC, syntax);
        case DaxSemanticException semantic -> new SQLSyntaxErrorException(semantic.getMessage(), SYNTAX_OR_SEMANTIC,
                semantic);
        case DaxExecutionException execution -> new SQLException(execution.getMessage(), GENERAL, execution);
        case DaxCancelledException cancelled -> cancelled.reason() == DaxCancelledException.Reason.TIMEOUT
                ? new SQLTimeoutException(cancelled.getMessage(), TIMEOUT, cancelled)
                : new SQLException(cancelled.getMessage(), CANCELLED, cancelled);
        };
    }
}
