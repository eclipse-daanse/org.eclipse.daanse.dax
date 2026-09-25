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

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.olap.api.element.Dimension;
import org.eclipse.daanse.olap.api.element.Hierarchy;
import org.eclipse.daanse.olap.api.element.Level;
import org.eclipse.daanse.olap.api.element.Member;
import org.eclipse.daanse.olap.api.result.Cell;
import org.eclipse.daanse.olap.api.result.CellSet;
import org.eclipse.daanse.olap.api.result.CellSetAxis;
import org.eclipse.daanse.olap.api.result.Position;

/** Mockito stand-ins for OLAP metadata and results. */
public final class OlapMocks {

    private OlapMocks() {
    }

    public static Dimension dimension(String name, boolean measures) {
        Dimension dimension = mock(Dimension.class);
        when(dimension.getName()).thenReturn(name);
        when(dimension.isMeasures()).thenReturn(measures);
        when(dimension.isVisible()).thenReturn(true);
        return dimension;
    }

    public static Hierarchy hierarchy(String name, String uniqueName) {
        Hierarchy hierarchy = mock(Hierarchy.class);
        when(hierarchy.getName()).thenReturn(name);
        when(hierarchy.getUniqueName()).thenReturn(uniqueName);
        when(hierarchy.isVisible()).thenReturn(true);
        return hierarchy;
    }

    public static Level level(String name, String uniqueName, int depth) {
        Level level = mock(Level.class);
        when(level.getName()).thenReturn(name);
        when(level.getUniqueName()).thenReturn(uniqueName);
        when(level.getDepth()).thenReturn(depth);
        when(level.isAll()).thenReturn(depth == 0);
        when(level.isVisible()).thenReturn(true);
        return level;
    }

    public static Member member(String name, Level level, Member parent) {
        Member member = mock(Member.class);
        when(member.getName()).thenReturn(name);
        when(member.getLevel()).thenReturn(level);
        when(member.getParentMember()).thenReturn(parent);
        return member;
    }

    public static Member measure(String name) {
        Member measure = mock(Member.class);
        when(measure.getName()).thenReturn(name);
        when(measure.getUniqueName()).thenReturn("[Measures].[" + name + "]");
        when(measure.isVisible()).thenReturn(true);
        return measure;
    }

    /**
     * @param rows  the members of each position of the rows axis; {@code null}
     *              for a result without rows axis
     * @param cells the cell values, per row, per column; {@code null} for an
     *              empty cell
     */
    public static CellSet cellSet(List<List<Member>> rows, Object[][] cells) {
        CellSet cellSet = mock(CellSet.class);
        List<CellSetAxis> axes = new ArrayList<>();
        axes.add(mock(CellSetAxis.class));
        if (rows != null) {
            CellSetAxis rowsAxis = mock(CellSetAxis.class);
            List<Position> positions = new ArrayList<>();
            for (List<Member> members : rows) {
                Position position = mock(Position.class);
                when(position.getMembers()).thenReturn(members);
                positions.add(position);
            }
            when(rowsAxis.getPositions()).thenReturn(positions);
            axes.add(rowsAxis);
        }
        when(cellSet.getAxes()).thenReturn(axes);
        when(cellSet.getCell(anyList())).thenAnswer(invocation -> {
            List<Integer> coordinates = invocation.getArgument(0);
            int column = coordinates.get(0);
            int row = coordinates.size() > 1 ? coordinates.get(1) : 0;
            Object value = cells[row][column];
            Cell cell = mock(Cell.class);
            when(cell.getValue()).thenReturn(value);
            when(cell.isNull()).thenReturn(value == null);
            return cell;
        });
        return cellSet;
    }
}
