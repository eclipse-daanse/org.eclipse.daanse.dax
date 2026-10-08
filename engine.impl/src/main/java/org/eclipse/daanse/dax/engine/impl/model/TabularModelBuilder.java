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
import java.util.Optional;

import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.olap.api.DataTypeJdbc;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxNames;
import org.eclipse.daanse.olap.api.catalog.CatalogReader;
import org.eclipse.daanse.olap.api.element.Cube;
import org.eclipse.daanse.olap.api.element.Dimension;
import org.eclipse.daanse.olap.api.element.Hierarchy;
import org.eclipse.daanse.olap.api.element.Level;
import org.eclipse.daanse.olap.api.element.Member;
import org.eclipse.daanse.olap.api.element.Property;
import org.eclipse.daanse.olap.api.element.StoredMeasure;
import org.eclipse.daanse.olap.api.result.Property.StandardCellProperty;

/**
 * Builds the {@link TabularModel} of a cube, as a role sees it.
 * <p>
 * Every visible dimension but the measures becomes a table, every visible
 * level but the all level a column. A column is named by its dimension,
 * hierarchy and level, e.g. {@code Product.ProductHierarchy.Category}, as
 * queries refer to it. Every property of the members of a level, but the
 * internal ones, is a column too, after it, named by the level and the
 * property, e.g. {@code Customers.Customers.Name.Gender}, as CSDL refers to it.
 * The values of a level's column are member names, of type
 * {@link DaxType#STRING}; those of a property's column are of the
 * property's type, as MDX gives them.
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
                measures.add(new ModelMeasure(measure.getName(), measure.getUniqueName(), !measure.isCalculated(),
                        measureType(measure)));
            }
        }
        return new TabularModel(MdxNames.quote(cube.getName()), tables, measures);
    }

    private static ModelTable table(CatalogReader reader, Dimension dimension) {
        String table = dimension.getName();
        List<ModelColumn> columns = new ArrayList<>();
        for (Hierarchy hierarchy : reader.getDimensionHierarchies(dimension)) {
            if (!hierarchy.isVisible()) {
                continue;
            }
            for (Level level : reader.getHierarchyLevels(hierarchy)) {
                if (level.isAll() || !level.isVisible()) {
                    continue;
                }
                // named as queries refer to it: Dimension.Hierarchy.Level
                String name = table + "." + hierarchy.getName() + "." + level.getName();
                columns.add(new ModelColumn(table, name, hierarchy.getUniqueName(), level.getUniqueName(),
                        level.getDepth(), DaxType.STRING));
                for (Property property : level.getProperties()) {
                    if (property.isInternal() || property.getName().startsWith("$")) {
                        continue;
                    }
                    // named as CSDL refers to it: the level's unique name without brackets, then the property
                    String propertyName = withoutBrackets(level.getUniqueName()) + "." + property.getName();
                    columns.add(new ModelColumn(table, propertyName, hierarchy.getUniqueName(), level.getUniqueName(),
                            level.getDepth(), type(property.getType()), Optional.of(property.getName())));
                }
            }
        }
        return new ModelTable(table, columns);
    }

    private static DaxType type(Property.Datatype type) {
        return switch (type) {
        case TYPE_INTEGER, TYPE_LONG -> DaxType.INTEGER;
        case TYPE_NUMERIC -> DaxType.DOUBLE;
        case TYPE_BOOLEAN -> DaxType.BOOLEAN;
        case TYPE_DATE, TYPE_TIME, TYPE_TIMESTAMP -> DaxType.DATETIME;
        case TYPE_STRING, TYPE_OTHER -> DaxType.STRING;
        };
    }

    /**
     * The type of a measure's values, as the CSDL of the cube declares it
     * (DefaultTypeMapper.measureType of the XMLA connector): by the aggregator,
     * else by the type of the measure's source.
     */
    static DaxType measureType(Member measure) {
        String aggregator = measure instanceof StoredMeasure stored ? stored.getAggregateFunction() : "None";
        Optional<DaxType> source = sourceType(measure);
        return switch (aggregator) {
        case "count", "distinct-count" -> DaxType.INTEGER;
        case "sum" -> DaxType.DECIMAL;
        case "avg" -> source.filter(DaxType.DECIMAL::equals).orElse(DaxType.DOUBLE);
        case "min", "max" -> source.orElse(DaxType.DECIMAL);
        case "listagg" -> DaxType.STRING;
        default -> DaxType.DECIMAL;
        };
    }

    private static Optional<DaxType> sourceType(Member measure) {
        Object datatype = measure.getPropertyValue(StandardCellProperty.DATATYPE.getName());
        if (datatype instanceof String name) {
            return switch (name) {
            case "UNDEFINED", "String" -> Optional.of(DaxType.STRING);
            case "NUMERIC" -> Optional.of(DaxType.DECIMAL);
            case "Integer" -> Optional.of(DaxType.INTEGER);
            case "Boolean" -> Optional.of(DaxType.BOOLEAN);
            case "Date", "Time", "Timestamp" -> Optional.of(DaxType.DATETIME);
            default -> Optional.empty();
            };
        }
        if (measure instanceof StoredMeasure stored) {
            return stored.getDataType().map(TabularModelBuilder::type);
        }
        return Optional.empty();
    }

    private static DaxType type(DataTypeJdbc type) {
        return switch (type) {
        case VARCHAR -> DaxType.STRING;
        case NUMERIC, FLOAT, REAL, DOUBLE -> DaxType.DECIMAL;
        case INTEGER, BIGINT, SMALLINT -> DaxType.INTEGER;
        case BOOLEAN -> DaxType.BOOLEAN;
        case DATE, TIME, TIMESTAMP -> DaxType.DATETIME;
        default -> DaxType.VARIANT;
        };
    }

    private static String withoutBrackets(String uniqueName) {
        return uniqueName.replace("[", "").replace("]", "");
    }
}
