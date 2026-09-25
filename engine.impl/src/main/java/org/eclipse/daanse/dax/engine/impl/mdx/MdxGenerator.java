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
package org.eclipse.daanse.dax.engine.impl.mdx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;
import org.eclipse.daanse.dax.engine.impl.plan.NamedMeasure;
import org.eclipse.daanse.dax.engine.impl.plan.Summarize;

/**
 * Translates plans into MDX.
 * <p>
 * A {@link Summarize} puts its measures on the columns axis and the cross join
 * of its hierarchies on the rows axis, {@code NON EMPTY} when it has measures.
 * Of the columns of one hierarchy only the deepest level goes on the axis; the
 * others are read from the ancestors of its members.
 * </p>
 */
public final class MdxGenerator {

    private MdxGenerator() {
    }

    /**
     * @param cube      the cube name, as MDX writes it
     * @param summarize the plan
     * @return the MDX query computing the plan's table
     */
    public static MdxQuery summarize(String cube, Summarize summarize) {
        // the deepest column of each hierarchy, in order of first appearance
        Map<String, ModelColumn> deepest = new LinkedHashMap<>();
        for (ModelColumn column : summarize.groupBy()) {
            deepest.merge(column.hierarchy(), column, (a, b) -> b.depth() > a.depth() ? b : a);
        }
        List<String> hierarchies = new ArrayList<>(deepest.keySet());

        List<ValueSource> sources = new ArrayList<>();
        for (ModelColumn column : summarize.groupBy()) {
            sources.add(new ValueSource.MemberName(hierarchies.indexOf(column.hierarchy()), column.depth()));
        }
        StringJoiner measures = new StringJoiner(", ", "{", "}");
        for (int i = 0; i < summarize.measures().size(); i++) {
            NamedMeasure measure = summarize.measures().get(i);
            measures.add(measure.measure().uniqueName());
            sources.add(new ValueSource.CellValue(i));
        }

        StringBuilder mdx = new StringBuilder("SELECT ").append(measures).append(" ON 0");
        boolean rows = !deepest.isEmpty();
        if (rows) {
            String set = null;
            for (ModelColumn column : deepest.values()) {
                String members = column.level() + ".Members";
                set = set == null ? members : "CrossJoin(" + set + ", " + members + ")";
            }
            mdx.append(", ");
            if (!summarize.measures().isEmpty()) {
                mdx.append("NON EMPTY ");
            }
            mdx.append(set).append(" ON 1");
        }
        mdx.append(" FROM ").append(cube);
        return new MdxQuery(mdx.toString(), sources, rows);
    }
}
