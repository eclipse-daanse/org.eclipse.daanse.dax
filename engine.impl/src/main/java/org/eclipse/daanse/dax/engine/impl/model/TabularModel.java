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
package org.eclipse.daanse.dax.engine.impl.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The tabular view of a cube, which DAX queries refer to: each dimension is a
 * table whose columns are the levels of its hierarchies, and the measures of
 * the cube are measures. Names are looked up ignoring case, as DAX does.
 */
public final class TabularModel {

    private final String cube;
    private final Map<String, ModelTable> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, ModelMeasure> measures = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * @param cube     the cube name, as MDX writes it, e.g. {@code [Sales]}
     * @param tables   the tables; a later one of the same name is dropped
     * @param measures the measures; a later one of the same name is dropped
     */
    public TabularModel(String cube, List<ModelTable> tables, List<ModelMeasure> measures) {
        this.cube = Objects.requireNonNull(cube, "cube");
        tables.forEach(t -> this.tables.putIfAbsent(t.name(), t));
        measures.forEach(m -> this.measures.putIfAbsent(m.name(), m));
    }

    /** @return the cube name, as MDX writes it */
    public String cube() {
        return cube;
    }

    public Optional<ModelTable> table(String name) {
        return Optional.ofNullable(tables.get(name));
    }

    public Optional<ModelMeasure> measure(String name) {
        return Optional.ofNullable(measures.get(name));
    }

    public Map<String, ModelTable> tables() {
        return Collections.unmodifiableMap(tables);
    }

    public Map<String, ModelMeasure> measures() {
        return Collections.unmodifiableMap(measures);
    }
}
