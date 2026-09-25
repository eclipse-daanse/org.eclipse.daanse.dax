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

import org.eclipse.daanse.dax.engine.impl.model.ModelMeasure;

/**
 * A measure as a result column.
 *
 * @param name    the column name the query gives, without brackets
 * @param measure the measure
 */
public record NamedMeasure(String name, ModelMeasure measure) {

    public NamedMeasure {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(measure, "measure");
    }
}
