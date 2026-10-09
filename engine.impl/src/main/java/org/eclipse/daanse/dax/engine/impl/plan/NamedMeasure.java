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
package org.eclipse.daanse.dax.engine.impl.plan;

import java.util.Objects;

import org.eclipse.daanse.dax.engine.api.DaxType;
import org.eclipse.daanse.dax.engine.impl.model.ModelMeasure;

/**
 * A measure, or an expression the cube computes of measures, as a result
 * column.
 *
 * @param name       the column name the query gives, without brackets
 * @param expression a {@link ScalarPlan.MeasureValue}, or an expression of
 *                   measures and constants as {@link Summarize#condition()}
 *                   has
 */
public record NamedMeasure(String name, ScalarPlan expression) {

    public NamedMeasure {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expression, "expression");
    }

    /** A measure of the model as a result column. */
    public NamedMeasure(String name, ModelMeasure measure) {
        this(name, new ScalarPlan.MeasureValue(measure));
    }

    /**
     * @return the type of the column: the measure's, or {@link DaxType#VARIANT}
     *         for an expression of measures
     */
    public DaxType type() {
        return expression instanceof ScalarPlan.MeasureValue value ? value.measure().type() : DaxType.VARIANT;
    }
}
