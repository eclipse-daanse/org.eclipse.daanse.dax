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

import org.eclipse.daanse.dax.engine.api.DaxCancelledException;
import org.eclipse.daanse.dax.engine.api.DaxException;
import org.eclipse.daanse.dax.engine.api.DaxExecutionException;
import org.eclipse.daanse.dax.engine.impl.mdx.CellSetReader;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery;
import org.eclipse.daanse.olap.api.connection.Connection;
import org.eclipse.daanse.olap.api.execution.QueryCanceledException;
import org.eclipse.daanse.olap.api.execution.QueryTimeoutException;
import org.eclipse.daanse.olap.api.execution.Statement;
import org.eclipse.daanse.olap.api.result.CellSet;

/**
 * Executes MDX translations on the OLAP connection, each in a statement of its
 * own that the query's {@link QueryControl} can cancel.
 */
final class MdxRunner {

    private final Connection connection;
    private final QueryControl control;

    MdxRunner(Connection connection, QueryControl control) {
        this.connection = connection;
        this.control = control;
    }

    List<List<Object>> run(MdxQuery query) throws DaxException {
        control.check();
        Statement statement = connection.createStatement();
        try {
            control.running(statement);
            CellSet cellSet = statement.executeQuery(query.text());
            try {
                return CellSetReader.read(cellSet, query);
            } finally {
                cellSet.close();
            }
        } catch (QueryCanceledException e) {
            throw new DaxCancelledException(control.stopped().orElse(DaxCancelledException.Reason.CANCELLED));
        } catch (QueryTimeoutException e) {
            throw new DaxCancelledException(DaxCancelledException.Reason.TIMEOUT);
        } catch (RuntimeException e) {
            if (control.stopped().isPresent()) {
                throw new DaxCancelledException(control.stopped().get());
            }
            throw new DaxExecutionException("executing the MDX translation failed: " + e.getMessage() + "\n"
                    + query.text(), e);
        } finally {
            control.finished();
            statement.close();
        }
    }
}
