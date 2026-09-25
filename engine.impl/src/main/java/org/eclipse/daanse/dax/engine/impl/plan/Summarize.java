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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;

/**
 * Groups the cube by columns and computes measures per group, as
 * {@code SUMMARIZECOLUMNS} does; a table reference is the grouping by all its
 * columns without measures.
 * <p>
 * With measures, groups whose measures are all BLANK are left out. Without
 * measures, every combination of the columns' values is a group.
 * </p>
 *
 * @param groupBy  the columns to group by; the first result columns
 * @param measures the measures; the result columns after the group-by columns
 */
public record Summarize(List<ModelColumn> groupBy, List<NamedMeasure> measures) implements TablePlan {

    public Summarize {
        groupBy = List.copyOf(groupBy);
        measures = List.copyOf(measures);
    }

    @Override
    public List<DaxColumn> columns() {
        List<DaxColumn> columns = new ArrayList<>();
        for (ModelColumn column : groupBy) {
            columns.add(new DaxColumn(column.daxName(), Optional.of(column.table()), column.type()));
        }
        for (NamedMeasure measure : measures) {
            columns.add(new DaxColumn("[" + measure.name() + "]", Optional.empty(), DaxType.VARIANT));
        }
        return columns;
    }
}
