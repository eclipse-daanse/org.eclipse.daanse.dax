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

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.dimension;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.hierarchy;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.level;
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.measure;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.olap.api.catalog.CatalogReader;
import org.eclipse.daanse.olap.api.element.Cube;
import org.eclipse.daanse.olap.api.element.Dimension;
import org.eclipse.daanse.olap.api.element.Hierarchy;
import org.eclipse.daanse.olap.api.element.Level;
import org.eclipse.daanse.olap.api.element.Member;
import org.junit.jupiter.api.Test;

class TabularModelBuilderTest {

    @Test
    void dimensionsBecomeTablesAndLevelsColumns() {
        CatalogReader reader = mock(CatalogReader.class);
        Cube cube = mock(Cube.class);
        when(cube.getName()).thenReturn("Sales");

        Dimension measures = dimension("Measures", true);
        Dimension product = dimension("Product", false);
        Dimension hidden = dimension("Hidden", false);
        when(hidden.isVisible()).thenReturn(false);
        when(reader.getCubeDimensions(cube)).thenReturn(List.of(measures, product, hidden));

        Hierarchy main = hierarchy("Product", "[Product]");
        Hierarchy alternative = hierarchy("Product.Alt", "[Product.Alt]");
        when(reader.getDimensionHierarchies(product)).thenReturn(List.of(main, alternative));
        List<Level> mainLevels = List.of(level("(All)", "[Product].[(All)]", 0),
                level("Category", "[Product].[Category]", 1), level("Subcategory", "[Product].[Subcategory]", 2));
        List<Level> alternativeLevels = List.of(level("Category", "[Product.Alt].[Category]", 1));
        when(reader.getHierarchyLevels(main)).thenReturn(mainLevels);
        when(reader.getHierarchyLevels(alternative)).thenReturn(alternativeLevels);

        Member sales = measure("Sales Amount");
        Member secret = measure("Secret");
        when(secret.isVisible()).thenReturn(false);
        when(cube.getMeasures()).thenReturn(List.of(sales, secret));

        TabularModel model = TabularModelBuilder.build(reader, cube);

        assertThat(model.cube()).isEqualTo("[Sales]");
        assertThat(model.tables()).containsOnlyKeys("Product");
        assertThat(model.table("PRODUCT").orElseThrow().columns()).containsExactly(
                new ModelColumn("Product", "Category", "[Product]", "[Product].[Category]", 1, DaxType.STRING),
                new ModelColumn("Product", "Subcategory", "[Product]", "[Product].[Subcategory]", 2, DaxType.STRING),
                new ModelColumn("Product", "Category (Product.Alt)", "[Product.Alt]", "[Product.Alt].[Category]", 1,
                        DaxType.STRING));
        assertThat(model.measures()).containsOnlyKeys("Sales Amount");
        assertThat(model.measure("sales amount")).contains(new ModelMeasure("Sales Amount", "[Measures].[Sales Amount]"));
    }
}
