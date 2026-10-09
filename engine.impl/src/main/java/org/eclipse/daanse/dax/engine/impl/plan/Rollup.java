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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxType;

/**
 * The groups of {@code SUMMARIZE} with {@code ROLLUP}: the rows of the
 * grouping by all columns, then a subtotal row for each group of them by fewer
 * of the rolled-up columns, its measures computed for that group; the columns
 * rolled up are BLANK in it. A subtotal row is kept only if the grouping by
 * all columns has rows of its group.
 * <p>
 * Its columns are those of the first level, then a {@link DaxType#BOOLEAN} for
 * each column rolled up, TRUE where it is subtotaled, as {@code ISSUBTOTAL}
 * gives it.
 * </p>
 *
 * @param levels the groupings: the first by the columns not rolled up and
 *               those rolled up, then their measures; the {@code j}-th of the
 *               others by the columns not rolled up and the first rolled up
 *               but the last {@code j}, then the same measures
 * @param keys   how many first columns are not rolled up, the same in each
 *               level
 * @param rolled how many columns after them are rolled up
 */
public record Rollup(List<TablePlan> levels, int keys, int rolled) implements TablePlan {

    public Rollup {
        levels = List.copyOf(levels);
        if (rolled < 1 || levels.size() != rolled + 1) {
            throw new IllegalArgumentException(levels.size() + " levels of " + rolled + " columns rolled up");
        }
        int measures = levels.get(0).columns().size() - keys - rolled;
        for (int j = 0; j < levels.size(); j++) {
            if (measures < 0 || levels.get(j).columns().size() != keys + rolled - j + measures) {
                throw new IllegalArgumentException("level " + j + " has " + levels.get(j).columns().size()
                        + " columns");
            }
        }
    }

    @Override
    public List<DaxColumn> columns() {
        List<DaxColumn> columns = new ArrayList<>(levels.get(0).columns());
        for (int i = 0; i < rolled; i++) {
            columns.add(new DaxColumn("[DAX subtotal " + (i + 1) + "]", Optional.empty(), DaxType.BOOLEAN));
        }
        return columns;
    }

    /**
     * @param rows the rows of each level, in order
     * @return the rows of the first level, then those of the others whose
     *         group it has rows of
     */
    public List<List<Object>> apply(List<List<List<Object>>> rows) {
        int measures = levels.get(0).columns().size() - keys - rolled;
        List<List<Object>> result = new ArrayList<>();
        List<Set<List<Object>>> groups = new ArrayList<>();
        for (int j = 0; j <= rolled; j++) {
            groups.add(new HashSet<>());
        }
        for (List<Object> row : rows.get(0)) {
            for (int j = 1; j <= rolled; j++) {
                groups.get(j).add(new ArrayList<>(row.subList(0, keys + rolled - j)));
            }
            List<Object> flagged = new ArrayList<>(row);
            flagged.addAll(Collections.nCopies(rolled, Boolean.FALSE));
            result.add(flagged);
        }
        for (int j = 1; j <= rolled; j++) {
            int grouped = keys + rolled - j;
            for (List<Object> row : rows.get(j)) {
                if (!groups.get(j).contains(row.subList(0, grouped))) {
                    continue;
                }
                List<Object> subtotal = new ArrayList<>(row.subList(0, grouped));
                subtotal.addAll(Collections.nCopies(j, null));
                subtotal.addAll(row.subList(grouped, grouped + measures));
                subtotal.addAll(Collections.nCopies(rolled - j, Boolean.FALSE));
                subtotal.addAll(Collections.nCopies(j, Boolean.TRUE));
                result.add(subtotal);
            }
        }
        return result;
    }
}
