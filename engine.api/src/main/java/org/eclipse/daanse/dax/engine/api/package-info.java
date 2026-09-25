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
/**
 * Connection-side API of the DAX engine.
 * <p>
 * An engine creates, for a connection it serves - typically the OLAP
 * connection - {@link org.eclipse.daanse.dax.engine.api.DaxQueryStatement}s,
 * which execute a query text with its parameters and timeout and can be
 * cancelled. A query
 * answers a {@link org.eclipse.daanse.dax.engine.api.DaxResult} with one
 * {@link org.eclipse.daanse.dax.engine.api.DaxTable} per {@code EVALUATE}, read
 * one after the other; the columns of a table are
 * {@link org.eclipse.daanse.dax.engine.api.DaxColumn}s typed by
 * {@link org.eclipse.daanse.dax.engine.api.DaxType}. Failures are
 * {@link org.eclipse.daanse.dax.engine.api.DaxException}s, one subclass per
 * kind. {@link org.eclipse.daanse.dax.engine.api.DaxQueries} tells DAX from
 * other statement texts.
 * </p>
 * <p>
 * The package depends on nothing but the JDK: the syntax tree, the catalog
 * model and the translation of queries do not appear here.
 * </p>
 */
@org.osgi.annotation.bundle.Export
@org.osgi.annotation.versioning.Version("0.0.1")
package org.eclipse.daanse.dax.engine.api;
