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
package org.eclipse.daanse.dax.engine.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.daanse.dax.engine.api.DaxException;
import org.eclipse.daanse.dax.engine.api.DaxExecutionException;
import org.eclipse.daanse.dax.engine.api.DaxResult;
import org.eclipse.daanse.dax.engine.api.DaxTable;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxGenerator;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;
import org.eclipse.daanse.dax.engine.impl.plan.AddColumns;
import org.eclipse.daanse.dax.engine.impl.plan.ConstantTable;
import org.eclipse.daanse.dax.engine.impl.plan.DaxValues;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan.SortKey;
import org.eclipse.daanse.dax.engine.impl.plan.Filter;
import org.eclipse.daanse.dax.engine.impl.plan.Generate;
import org.eclipse.daanse.dax.engine.impl.plan.Rollup;
import org.eclipse.daanse.dax.engine.impl.plan.Sample;
import org.eclipse.daanse.dax.engine.impl.plan.Summarize;
import org.eclipse.daanse.dax.engine.impl.plan.TablePlan;
import org.eclipse.daanse.dax.engine.impl.plan.TopN;

/**
 * Computes the table of an {@code EVALUATE} when it is asked for, one after
 * the other.
 */
final class DaxResultImpl implements DaxResult {

    /** At most this many groups of the values of properties are calculated members of a query. */
    static final int MAX_GROUPS = 10_000;

    private final List<EvaluatePlan> plans;
    private final String cube;
    private final MdxRunner runner;
    private final QueryControl control;

    private int next;
    private DaxTableImpl current;
    private boolean closed;

    DaxResultImpl(List<EvaluatePlan> plans, String cube, MdxRunner runner, QueryControl control) {
        this.plans = plans;
        this.cube = cube;
        this.runner = runner;
        this.control = control;
    }

    @Override
    public DaxTable nextTable() throws DaxException {
        if (closed) {
            throw new IllegalStateException("the result is closed");
        }
        invalidateCurrent();
        if (next >= plans.size()) {
            control.close();
            return null;
        }
        control.check();
        EvaluatePlan plan = plans.get(next);
        List<List<Object>> rows = rows(plan.table());
        next++;
        current = new DaxTableImpl(plan.table().columns(), sorted(rows, plan.orderBy()), control);
        return current;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            invalidateCurrent();
            control.close();
        }
    }

    private void invalidateCurrent() {
        if (current != null) {
            current.invalidate();
            current = null;
        }
    }

    private List<List<Object>> rows(TablePlan table) throws DaxException {
        return switch (table) {
        case ConstantTable constant -> constant.rows();
        case Summarize summarize -> summarize(summarize);
        case Generate generate -> distinct(runner.run(MdxGenerator.generate(cube, generate)),
                generate.inner().allMeasures().isEmpty());
        case Filter filter -> filter.apply(rows(filter.source()));
        case TopN topN -> topN.apply(rows(topN.source()));
        case Sample sample -> sample.apply(rows(sample.source()));
        case AddColumns addColumns -> addColumns.apply(rows(addColumns.source()));
        case Rollup rollup -> {
            List<List<List<Object>>> levels = new ArrayList<>();
            for (TablePlan level : rollup.levels()) {
                levels.add(rows(level));
            }
            yield rollup.apply(levels);
        }
        };
    }

    /**
     * Without measures a group is its values: members of the same names or
     * property values are one. A grouping by the values of properties first
     * queries the values of each such hierarchy, then the measures of their
     * groups.
     */
    private List<List<Object>> summarize(Summarize summarize) throws DaxException {
        Map<String, List<List<Object>>> groups = new LinkedHashMap<>();
        for (String hierarchy : summarize.byValues()) {
            List<ModelColumn> columns = summarize.groupBy().stream().filter(c -> c.hierarchy().equals(hierarchy))
                    .toList();
            List<List<Object>> values = distinct(runner.run(MdxGenerator.summarize(cube,
                    new Summarize(columns, List.of()))), true);
            if (values.isEmpty()) {
                return List.of();
            }
            if (values.size() > MAX_GROUPS) {
                throw new DaxExecutionException("grouping by the values of " + columns.stream()
                        .map(ModelColumn::daxName).collect(Collectors.joining(", ")) + " gives " + values.size()
                        + " groups, more than " + MAX_GROUPS + " computed as calculated members");
            }
            groups.put(hierarchy, values);
        }
        return distinct(runner.run(MdxGenerator.summarize(cube, summarize, groups)),
                summarize.allMeasures().isEmpty());
    }

    private static List<List<Object>> sorted(List<List<Object>> rows, List<SortKey> keys) {
        if (keys.isEmpty()) {
            return rows;
        }
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
        return sorted;
    }

    private static List<List<Object>> distinct(List<List<Object>> rows, boolean distinct) {
        return distinct ? new ArrayList<>(new LinkedHashSet<>(rows)) : rows;
    }
}
