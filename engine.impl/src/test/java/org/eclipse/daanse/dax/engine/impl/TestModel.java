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

import java.util.List;

import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.model.ModelColumn;
import org.eclipse.daanse.dax.engine.impl.model.ModelMeasure;
import org.eclipse.daanse.dax.engine.impl.model.ModelTable;
import org.eclipse.daanse.dax.engine.impl.model.TabularModel;

/** A small tabular model: Product (one hierarchy of two levels), Date, Store (two hierarchies). */
public final class TestModel {

    public static final ModelColumn CATEGORY = column("Product", "Category", "[Product]", "[Product].[Category]", 1);
    public static final ModelColumn SUBCATEGORY = column("Product", "Subcategory", "[Product]",
            "[Product].[Subcategory]", 2);
    public static final ModelColumn YEAR = column("Date", "Year", "[Date]", "[Date].[Year]", 1);
    public static final ModelColumn CITY = column("Store", "City", "[Store]", "[Store].[City]", 1);
    public static final ModelColumn STORE_TYPE = column("Store", "Type", "[Store Type]", "[Store Type].[Type]", 1);
    public static final ModelMeasure SALES_AMOUNT = new ModelMeasure("Sales Amount", "[Measures].[Sales Amount]");
    public static final ModelMeasure UNIT_SALES = new ModelMeasure("Unit Sales", "[Measures].[Unit Sales]");

    private TestModel() {
    }

    public static TabularModel model() {
        return new TabularModel("[Sales]",
                List.of(new ModelTable("Product", List.of(CATEGORY, SUBCATEGORY)), new ModelTable("Date", List.of(YEAR)),
                        new ModelTable("Store", List.of(CITY, STORE_TYPE))),
                List.of(SALES_AMOUNT, UNIT_SALES));
    }

    private static ModelColumn column(String table, String name, String hierarchy, String level, int depth) {
        return new ModelColumn(table, name, hierarchy, level, depth, DaxType.STRING);
    }
}
