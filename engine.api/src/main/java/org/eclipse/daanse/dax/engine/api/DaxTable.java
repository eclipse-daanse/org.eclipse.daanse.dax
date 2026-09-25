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
package org.eclipse.daanse.dax.engine.api;

import java.util.List;

/**
 * The result table of one {@code EVALUATE}: a cursor over its rows, which
 * starts before the first row. It is read up to the next
 * {@link DaxResult#nextTable()} or the closing of its {@link DaxResult}.
 */
public interface DaxTable {

    /** @return the columns, in order */
    List<DaxColumn> columns();

    /**
     * Moves to the next row.
     *
     * @return {@code false} if there are no more rows
     * @throws DaxExecutionException if reading the row failed
     * @throws DaxCancelledException if the query was cancelled or timed out
     * @throws IllegalStateException if the table can no longer be read
     */
    boolean next() throws DaxException;

    /**
     * @param column the 0-based column index
     * @return the value of the column in the current row, {@code null} for
     *         BLANK; its Java type is the {@link DaxType#javaType()} of the
     *         column
     * @throws IllegalStateException     if there is no current row
     * @throws IndexOutOfBoundsException if there is no such column
     */
    Object getObject(int column);
}
