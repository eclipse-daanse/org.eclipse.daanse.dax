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

/**
 * The result of one query: one {@link DaxTable} per {@code EVALUATE}, in query
 * order.
 * <p>
 * The {@code EVALUATE}s of a query form one query, not several: they share its
 * {@code DEFINE} section, parameters, timeout and cancellation, and see the
 * same data.
 * </p>
 * <p>
 * Tables are read one after the other and their rows on demand, so the result
 * holds resources until it is closed or read to its end.
 * </p>
 */
public interface DaxResult extends AutoCloseable {

    /**
     * Moves to the table of the next {@code EVALUATE}; rows left in the
     * previous table are skipped and that table can no longer be read.
     *
     * @return the next table, before its first row; {@code null} if there is
     *         none
     * @throws DaxExecutionException if evaluating the table failed; the tables
     *                               read before stay valid
     * @throws DaxCancelledException if the query was cancelled or timed out
     */
    DaxTable nextTable() throws DaxException;

    /** Releases the resources of the query. Idempotent. */
    @Override
    void close();
}
