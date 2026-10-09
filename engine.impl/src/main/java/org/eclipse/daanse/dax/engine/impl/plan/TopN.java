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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan.SortKey;

/**
 * The first rows of a table by an order of its columns, as {@code TOPN} gives
 * them: the rows tied with the last of them on all keys are kept too, so there
 * may be more. Computed on the rows of its source, in the order. {@code TOPN}
 * by a measure is a {@link Summarize.Top} instead.
 *
 * @param source    the table
 * @param count     how many rows; none if 0 or less
 * @param keys      the order, by columns of the source; not empty
 * @param partition how many first columns of the source the rows are grouped
 *                  by, the first rows of each group kept, as {@code TOPN} in
 *                  {@code GENERATE} keeps them for each outer row; 0 for all
 *                  rows together
 */
public record TopN(TablePlan source, long count, List<SortKey> keys, int partition) implements TablePlan {

    public TopN {
        Objects.requireNonNull(source, "source");
        keys = List.copyOf(keys);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("no keys");
        }
        if (partition < 0 || partition > source.columns().size()) {
            throw new IllegalArgumentException("partition " + partition);
        }
    }

    /** The first rows of all rows together. */
    public TopN(TablePlan source, long count, List<SortKey> keys) {
        this(source, count, keys, 0);
    }

    @Override
    public List<DaxColumn> columns() {
        return source.columns();
    }

    /**
     * @param rows the rows of the source
     * @return the first rows and those tied with the last of them, in order;
     *         of each group of the partition, in the order of their first rows
     */
    public List<List<Object>> apply(List<List<Object>> rows) {
        if (partition == 0) {
            return first(rows);
        }
        Map<List<Object>, List<List<Object>>> groups = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            groups.computeIfAbsent(row.subList(0, partition), g -> new ArrayList<>()).add(row);
        }
        List<List<Object>> first = new ArrayList<>();
        for (List<List<Object>> group : groups.values()) {
            first.addAll(first(group));
        }
        return first;
    }

    private List<List<Object>> first(List<List<Object>> rows) {
        Comparator<List<Object>> order = null;
        for (SortKey key : keys) {
            Comparator<List<Object>> byKey = (a, b) -> DaxValues.compare(a.get(key.column()), b.get(key.column()));
            if (!key.ascending()) {
                byKey = byKey.reversed();
            }
            order = order == null ? byKey : order.thenComparing(byKey);
        }
        List<List<Object>> sorted = new ArrayList<>(rows);
        sorted.sort(order);
        int end = (int) Math.min(Math.max(count, 0), sorted.size());
        // the rows tied with the last one
        while (end > 0 && end < sorted.size() && order.compare(sorted.get(end - 1), sorted.get(end)) == 0) {
            end++;
        }
        return sorted.subList(0, end);
    }
}
