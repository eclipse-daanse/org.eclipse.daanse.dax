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
import java.util.List;

import org.eclipse.daanse.dax.engine.api.DaxException;
import org.eclipse.daanse.dax.engine.api.DaxResult;
import org.eclipse.daanse.dax.engine.api.DaxTable;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxGenerator;
import org.eclipse.daanse.dax.engine.impl.plan.ConstantTable;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan;
import org.eclipse.daanse.dax.engine.impl.plan.EvaluatePlan.SortKey;
import org.eclipse.daanse.dax.engine.impl.plan.Summarize;

/**
 * Computes the table of an {@code EVALUATE} when it is asked for, one after
 * the other.
 */
final class DaxResultImpl implements DaxResult {

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
        List<List<Object>> rows = switch (plan.table()) {
        case ConstantTable constant -> constant.rows();
        case Summarize summarize -> runner.run(MdxGenerator.summarize(cube, summarize));
        };
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
}
