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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxNames;
import org.eclipse.daanse.olap.api.catalog.CatalogReader;
import org.eclipse.daanse.olap.api.element.Cube;
import org.eclipse.daanse.olap.api.element.Dimension;
import org.eclipse.daanse.olap.api.element.Hierarchy;
import org.eclipse.daanse.olap.api.element.Level;
import org.eclipse.daanse.olap.api.element.Member;

/**
 * Builds the {@link TabularModel} of a cube, as a role sees it.
 * <p>
 * Every visible dimension but the measures becomes a table, every visible
 * level but the all level a column. A column is named after its level; if a
 * table has two levels of that name, the later one is named
 * {@code Level (Hierarchy)}. Column values are member names, so every column
 * is of type {@link DaxType#STRING}.
 * </p>
 */
public final class TabularModelBuilder {

    private TabularModelBuilder() {
    }

    /**
     * @param reader the catalog reader of the connection, which applies its
     *               role
     * @param cube   the cube to view
     * @return the tabular model of the cube
     */
    public static TabularModel build(CatalogReader reader, Cube cube) {
        List<ModelTable> tables = new ArrayList<>();
        for (Dimension dimension : reader.getCubeDimensions(cube)) {
            if (dimension.isMeasures() || !dimension.isVisible()) {
                continue;
            }
            tables.add(table(reader, dimension));
        }
        List<ModelMeasure> measures = new ArrayList<>();
        for (Member measure : cube.getMeasures()) {
            if (measure.isVisible()) {
                measures.add(new ModelMeasure(measure.getName(), measure.getUniqueName()));
            }
        }
        return new TabularModel(MdxNames.quote(cube.getName()), tables, measures);
    }

    private static ModelTable table(CatalogReader reader, Dimension dimension) {
        String table = dimension.getName();
        List<ModelColumn> columns = new ArrayList<>();
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Hierarchy hierarchy : reader.getDimensionHierarchies(dimension)) {
            if (!hierarchy.isVisible()) {
                continue;
            }
            for (Level level : reader.getHierarchyLevels(hierarchy)) {
                if (level.isAll() || !level.isVisible()) {
                    continue;
                }
                String name = level.getName();
                if (!names.add(name)) {
                    name = name + " (" + hierarchy.getName() + ")";
                    names.add(name);
                }
                columns.add(new ModelColumn(table, name, hierarchy.getUniqueName(), level.getUniqueName(),
                        level.getDepth(), DaxType.STRING));
            }
        }
        return new ModelTable(table, columns);
    }
}
