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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.eclipse.daanse.dax.engine.api.DaxExecutionException;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource;
import org.eclipse.daanse.olap.api.element.Member;
import org.eclipse.daanse.olap.api.result.Cell;
import org.eclipse.daanse.olap.api.result.CellSet;
import org.eclipse.daanse.olap.api.result.Position;

/**
 * Reads the rows of a table from the {@link CellSet} of its {@link MdxQuery}.
 */
public final class CellSetReader {

    private CellSetReader() {
    }

    /**
     * @return the rows; one per position of the rows axis, or, without a rows
     *         axis, one unless all its cells are empty
     * @throws DaxExecutionException if a cell holds an error
     */
    public static List<List<Object>> read(CellSet cellSet, MdxQuery query) throws DaxExecutionException {
        List<List<Object>> rows = new ArrayList<>();
        if (!query.rows()) {
            List<Object> row = new ArrayList<>();
            boolean blank = true;
            for (ValueSource source : query.sources()) {
                Object value = cellValue(cellSet.getCell(List.of(((ValueSource.CellValue) source).column())));
                blank &= value == null;
                row.add(value);
            }
            if (!blank) {
                rows.add(row);
            }
            return rows;
        }
        List<Position> positions = cellSet.getAxes().get(1).getPositions();
        for (int r = 0; r < positions.size(); r++) {
            List<Member> members = positions.get(r).getMembers();
            List<Object> row = new ArrayList<>(query.sources().size());
            for (ValueSource source : query.sources()) {
                row.add(switch (source) {
                case ValueSource.MemberName name -> memberName(members.get(name.member()), name.depth());
                case ValueSource.CellValue cell -> cellValue(cellSet.getCell(List.of(cell.column(), r)));
                });
            }
            rows.add(row);
        }
        return rows;
    }

    private static String memberName(Member member, int depth) {
        Member ancestor = member;
        while (ancestor != null && ancestor.getLevel().getDepth() > depth) {
            ancestor = ancestor.getParentMember();
        }
        return ancestor == null || ancestor.getLevel().getDepth() != depth ? null : ancestor.getName();
    }

    private static Object cellValue(Cell cell) throws DaxExecutionException {
        if (cell.isError()) {
            throw new DaxExecutionException("a cell holds an error: " + cell.getValue());
        }
        return cell.isNull() ? null : normalize(cell.getValue());
    }

    /** @return the value as one of the Java types of DAX values */
    static Object normalize(Object value) {
        return switch (value) {
        case null -> null;
        case Integer i -> i.longValue();
        case Short s -> s.longValue();
        case Byte b -> b.longValue();
        case Float f -> f.doubleValue();
        case BigInteger i -> new BigDecimal(i);
        case java.sql.Timestamp t -> t.toLocalDateTime();
        case java.sql.Date d -> d.toLocalDate().atStartOfDay();
        case Date d -> LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        case Long l -> l;
        case Double d -> d;
        case BigDecimal d -> d;
        case String s -> s;
        case Boolean b -> b;
        case LocalDateTime t -> t;
        case byte[] bytes -> bytes;
        default -> value.toString();
        };
    }
}
