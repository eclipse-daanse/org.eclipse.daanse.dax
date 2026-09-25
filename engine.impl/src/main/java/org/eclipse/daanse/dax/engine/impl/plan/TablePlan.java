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

import java.util.List;

import org.eclipse.daanse.dax.engine.api.DaxColumn;

/**
 * How to compute the table of one {@code EVALUATE}.
 */
public sealed interface TablePlan permits ConstantTable, Summarize {

    /** @return the columns of the table, in order */
    List<DaxColumn> columns();
}
