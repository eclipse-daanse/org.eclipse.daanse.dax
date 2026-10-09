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
import static org.eclipse.daanse.dax.engine.impl.OlapMocks.property;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.olap.api.catalog.CatalogReader;
import org.eclipse.daanse.olap.api.element.Cube;
import org.eclipse.daanse.olap.api.element.Dimension;
import org.eclipse.daanse.olap.api.element.Hierarchy;
import org.eclipse.daanse.olap.api.element.Level;
import org.eclipse.daanse.olap.api.element.Member;
import org.eclipse.daanse.olap.api.element.Property;
import org.eclipse.daanse.olap.api.element.StoredMeasure;
import org.eclipse.daanse.olap.api.DataTypeJdbc;
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

        Hierarchy main = hierarchy("ProductHierarchy", "[Product].[ProductHierarchy]");
        Hierarchy alternative = hierarchy("Alt", "[Product].[Alt]");
        when(reader.getDimensionHierarchies(product)).thenReturn(List.of(main, alternative));
        List<Level> mainLevels = List.of(level("(All)", "[Product].[ProductHierarchy].[(All)]", 0),
                level("Category", "[Product].[ProductHierarchy].[Category]", 1), level("Subcategory", "[Product].[ProductHierarchy].[Subcategory]", 2));
        List<Level> alternativeLevels = List.of(level("Category", "[Product].[Alt].[Category]", 1));
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
                new ModelColumn("Product", "Product.ProductHierarchy.Category", "[Product].[ProductHierarchy]",
                        "[Product].[ProductHierarchy].[Category]", 1,
                        DaxType.STRING),
                new ModelColumn("Product", "Product.ProductHierarchy.Subcategory", "[Product].[ProductHierarchy]",
                        "[Product].[ProductHierarchy].[Subcategory]", 2,
                        DaxType.STRING),
                new ModelColumn("Product", "Product.Alt.Category", "[Product].[Alt]", "[Product].[Alt].[Category]", 1,
                        DaxType.STRING));
        assertThat(model.measures()).containsOnlyKeys("Sales Amount");
        assertThat(model.measure("sales amount")).contains(new ModelMeasure("Sales Amount", "[Measures].[Sales Amount]", true, DaxType.DECIMAL));
    }

    @Test
    void columnIsFoundByDottedLevelUniqueName() {
        ModelColumn category = new ModelColumn("Product", "Category", "[Product].[Product]",
                "[Product].[Product].[Category]", 1, DaxType.STRING);
        ModelTable table = new ModelTable("Product", List.of(category));

        assertThat(table.column("Product.Product.Category")).contains(category);
        assertThat(table.column("product.PRODUCT.category")).contains(category);
        assertThat(table.column("Category")).contains(category);
        assertThat(table.column("Product.Product.Subcategory")).isEmpty();
    }

    @Test
    void levelPropertiesAreColumnsAfterTheirLevel() {
        CatalogReader reader = mock(CatalogReader.class);
        Cube cube = mock(Cube.class);
        when(cube.getName()).thenReturn("Sales");
        Dimension customers = dimension("Customers", false);
        when(reader.getCubeDimensions(cube)).thenReturn(List.of(customers));
        Hierarchy hierarchy = hierarchy("Customers", "[Customers].[Customers]");
        when(reader.getDimensionHierarchies(customers)).thenReturn(List.of(hierarchy));
        Level city = level("City", "[Customers].[Customers].[City]", 1);
        Level name = level("Name", "[Customers].[Customers].[Name]", 2);
        // internal ones are left out; those of the same name of two levels and one named as a level are not
        Property[] cityProperties = { property("Population", false, Property.Datatype.TYPE_INTEGER),
                property("Area", false, Property.Datatype.TYPE_NUMERIC), property("$key", false),
                property("Key", true), property("Code", false) };
        Property[] nameProperties = { property("Gender", false), property("Code", false), property("City", false) };
        when(city.getProperties()).thenReturn(cityProperties);
        when(name.getProperties()).thenReturn(nameProperties);
        when(reader.getHierarchyLevels(hierarchy)).thenReturn(List.of(city, name));
        when(cube.getMeasures()).thenReturn(List.of());

        TabularModel model = TabularModelBuilder.build(reader, cube);

        assertThat(model.table("Customers").orElseThrow().columns()).containsExactly(
                new ModelColumn("Customers", "Customers.Customers.City", "[Customers].[Customers]",
                        "[Customers].[Customers].[City]", 1, DaxType.STRING),
                new ModelColumn("Customers", "Customers.Customers.City.Population", "[Customers].[Customers]",
                        "[Customers].[Customers].[City]", 1, DaxType.INTEGER, Optional.of("Population")),
                new ModelColumn("Customers", "Customers.Customers.City.Area", "[Customers].[Customers]",
                        "[Customers].[Customers].[City]", 1, DaxType.DOUBLE, Optional.of("Area")),
                new ModelColumn("Customers", "Customers.Customers.City.Code", "[Customers].[Customers]",
                        "[Customers].[Customers].[City]", 1, DaxType.STRING, Optional.of("Code")),
                new ModelColumn("Customers", "Customers.Customers.Name", "[Customers].[Customers]",
                        "[Customers].[Customers].[Name]", 2, DaxType.STRING),
                new ModelColumn("Customers", "Customers.Customers.Name.Gender", "[Customers].[Customers]",
                        "[Customers].[Customers].[Name]", 2, DaxType.STRING, Optional.of("Gender")),
                new ModelColumn("Customers", "Customers.Customers.Name.Code", "[Customers].[Customers]",
                        "[Customers].[Customers].[Name]", 2, DaxType.STRING, Optional.of("Code")),
                new ModelColumn("Customers", "Customers.Customers.Name.City", "[Customers].[Customers]",
                        "[Customers].[Customers].[Name]", 2, DaxType.STRING, Optional.of("City")));
        ModelTable table = model.table("Customers").orElseThrow();
        assertThat(table.column("customers.customers.name.gender")).map(ModelColumn::property)
                .contains(Optional.of("Gender"));
        assertThat(table.column("Customers.Customers.City")).map(ModelColumn::property).contains(Optional.empty());
    }

    @Test
    void measuresAreTypedAsCsdlDeclaresThem() {
        assertThat(TabularModelBuilder.measureType(stored("sum", Optional.of(DataTypeJdbc.INTEGER))))
                .isEqualTo(DaxType.DECIMAL);
        assertThat(TabularModelBuilder.measureType(stored("count", Optional.empty()))).isEqualTo(DaxType.INTEGER);
        assertThat(TabularModelBuilder.measureType(stored("avg", Optional.of(DataTypeJdbc.INTEGER))))
                .isEqualTo(DaxType.DOUBLE);
        assertThat(TabularModelBuilder.measureType(stored("max", Optional.of(DataTypeJdbc.INTEGER))))
                .isEqualTo(DaxType.INTEGER);
        assertThat(TabularModelBuilder.measureType(measure("Calculated"))).isEqualTo(DaxType.DECIMAL);
    }

    private static StoredMeasure stored(String aggregator, Optional<DataTypeJdbc> dataType) {
        StoredMeasure measure = mock(StoredMeasure.class);
        when(measure.getAggregateFunction()).thenReturn(aggregator);
        when(measure.getDataType()).thenReturn(dataType);
        return measure;
    }
}
