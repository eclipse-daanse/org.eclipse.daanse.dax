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
package org.eclipse.daanse.dax.engine.impl.mdx;

import java.util.List;
import java.util.Objects;

/**
 * An MDX query computing a table, and where each column of the table is found
 * in its result.
 *
 * @param text    the MDX text
 * @param sources one source per table column, in column order
 * @param rows    whether the query has a rows axis; without, the result is at
 *                most one row
 */
public record MdxQuery(String text, List<ValueSource> sources, boolean rows) {

    public MdxQuery {
        Objects.requireNonNull(text, "text");
        sources = List.copyOf(sources);
    }

    /** Where the value of a table column is found in the result. */
    public sealed interface ValueSource {

        /**
         * The name of the ancestor, at the given depth, of a member of the
         * rows axis.
         *
         * @param member the index of the member in a position of the rows axis
         * @param depth  the depth of the ancestor's level
         */
        record MemberName(int member, int depth) implements ValueSource {
        }

        /**
         * The value of a cell.
         *
         * @param column the index of the position of the columns axis
         */
        record CellValue(int column) implements ValueSource {
        }
    }
}
