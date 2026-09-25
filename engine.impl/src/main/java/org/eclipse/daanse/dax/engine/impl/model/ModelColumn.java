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

import java.util.Objects;

import org.eclipse.daanse.dax.engine.api.DaxType;

/**
 * A column of a {@link ModelTable}: a level of a hierarchy of the cube.
 *
 * @param table     the name of the table the column belongs to
 * @param name      the column name
 * @param hierarchy the unique name of the hierarchy, e.g. {@code [Product]}
 * @param level     the unique name of the level, e.g.
 *                  {@code [Product].[Category]}
 * @param depth     the depth of the level within its hierarchy
 * @param type      the type of the values
 */
public record ModelColumn(String table, String name, String hierarchy, String level, int depth, DaxType type) {

    public ModelColumn {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(type, "type");
    }

    /** @return the column name as DAX writes it, e.g. {@code Product[Category]} */
    public String daxName() {
        return table + "[" + name + "]";
    }
}
