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

/**
 * A measure of the cube.
 *
 * @param name       the measure name
 * @param uniqueName the unique name, e.g. {@code [Measures].[Sales Amount]}
 */
public record ModelMeasure(String name, String uniqueName) {

    public ModelMeasure {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(uniqueName, "uniqueName");
    }
}
