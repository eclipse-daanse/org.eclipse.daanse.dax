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

import org.eclipse.daanse.dax.engine.api.DaxColumn;
import org.eclipse.daanse.dax.engine.api.DaxException;
import org.eclipse.daanse.dax.engine.api.DaxTable;

/**
 * A computed table, held in memory.
 */
final class DaxTableImpl implements DaxTable {

    private final List<DaxColumn> columns;
    private final List<List<Object>> rows;
    private final QueryControl control;

    private int row = -1;
    private boolean valid = true;

    DaxTableImpl(List<DaxColumn> columns, List<List<Object>> rows, QueryControl control) {
        this.columns = columns;
        this.rows = rows;
        this.control = control;
    }

    @Override
    public List<DaxColumn> columns() {
        return columns;
    }

    @Override
    public boolean next() throws DaxException {
        if (!valid) {
            throw new IllegalStateException("the table can no longer be read");
        }
        control.check();
        if (row < rows.size()) {
            row++;
        }
        return row < rows.size();
    }

    @Override
    public Object getObject(int column) {
        if (!valid || row < 0 || row >= rows.size()) {
            throw new IllegalStateException("there is no current row");
        }
        return rows.get(row).get(column);
    }

    void invalidate() {
        valid = false;
    }
}
