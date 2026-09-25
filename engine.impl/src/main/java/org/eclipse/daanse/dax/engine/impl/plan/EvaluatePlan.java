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
import java.util.Objects;

/**
 * The plan of one {@code EVALUATE}.
 *
 * @param table   how to compute the table
 * @param orderBy how to sort it; empty to keep the order of computing
 */
public record EvaluatePlan(TablePlan table, List<SortKey> orderBy) {

    public EvaluatePlan {
        Objects.requireNonNull(table, "table");
        orderBy = List.copyOf(orderBy);
    }

    /**
     * A sort key of {@code ORDER BY}.
     *
     * @param column    the 0-based index of the result column
     * @param ascending whether to sort ascending
     */
    public record SortKey(int column, boolean ascending) {
    }
}
