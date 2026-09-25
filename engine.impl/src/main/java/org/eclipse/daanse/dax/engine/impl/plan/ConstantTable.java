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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.daanse.dax.engine.api.DaxColumn;

/**
 * A table whose rows the query gives, e.g. by a table constructor or
 * {@code ROW} of constants; computed without the cube.
 *
 * @param columns the columns
 * @param rows    the rows; a value is {@code null} for BLANK
 */
public record ConstantTable(List<DaxColumn> columns, List<List<Object>> rows) implements TablePlan {

    public ConstantTable {
        columns = List.copyOf(columns);
        List<List<Object>> copied = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            // not List.copyOf: it rejects null, and BLANK values are null
            copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(copied);
    }
}
