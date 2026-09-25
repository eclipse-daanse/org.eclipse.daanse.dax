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

import java.time.Duration;

/**
 * Executes DAX queries on the connection an engine created it for. Named so
 * to not clash with the syntax tree's {@code DaxStatement}.
 * <p>
 * A statement runs one query at a time; executing another one closes the
 * result of the previous. Only {@link #cancel()} may be called from
 * another thread.
 * </p>
 */
public interface DaxQueryStatement extends AutoCloseable {

    /**
     * Sets the value of a query parameter. It also overrides the value of a
     * parameter the query defines itself ({@code DEFINE @name = ...}).
     *
     * @param name  the parameter name, without the leading {@code @}
     * @param value the value, {@code null} for BLANK; otherwise of a Java type
     *              of {@link DaxType}
     * @throws IllegalArgumentException if the value's type is none of
     *                                  {@link DaxType}
     */
    void setParameter(String name, Object value);

    /**
     * @param timeout the time after which executing a query fails with
     *                {@link DaxCancelledException.Reason#TIMEOUT};
     *                {@link Duration#ZERO} for none, the default
     * @throws IllegalArgumentException if the timeout is negative
     */
    void setTimeout(Duration timeout);

    /**
     * Executes a DAX query.
     * <p>
     * The whole text is checked here, before any row is read: an error in
     * any of its {@code EVALUATE}s fails the call.
     * </p>
     *
     * @param daxText the query text, e.g. {@code EVALUATE 'Sales'}; it may hold
     *                several {@code EVALUATE}s
     * @return the result, before its first table
     * @throws DaxSyntaxException    if the text is not valid DAX
     * @throws DaxSemanticException  if the query refers to what the catalog
     *                               does not have, or misuses a function
     * @throws DaxExecutionException if evaluating the query failed
     * @throws DaxCancelledException if the query was cancelled or timed out
     */
    DaxResult execute(String daxText) throws DaxException;

    /**
     * Cancels the running query, if any; its {@link #execute(String)} or the
     * reading of its result fails with
     * {@link DaxCancelledException.Reason#CANCELLED}. Callable from any
     * thread.
     */
    void cancel();

    /** Closes this statement and its open result. Idempotent. */
    @Override
    void close();
}
