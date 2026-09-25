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
package org.eclipse.daanse.dax.engine.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A column of a result table.
 *
 * @param name    the column name as DAX gives it, e.g. {@code Sales[Amount]}
 *                or {@code [Total]}
 * @param table   the model table the column belongs to; empty for a column
 *                the query computes
 * @param type    the type of the values
 */
public record DaxColumn(String name, Optional<String> table, DaxType type) {

    public DaxColumn {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(type, "type");
    }
}
