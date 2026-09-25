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

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.daanse.dax.engine.impl.TestModel.CATEGORY;
import static org.eclipse.daanse.dax.engine.impl.TestModel.SALES_AMOUNT;
import static org.eclipse.daanse.dax.engine.impl.TestModel.SUBCATEGORY;
import static org.eclipse.daanse.dax.engine.impl.TestModel.UNIT_SALES;
import static org.eclipse.daanse.dax.engine.impl.TestModel.YEAR;

import java.util.List;

import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource.CellValue;
import org.eclipse.daanse.dax.engine.impl.mdx.MdxQuery.ValueSource.MemberName;
import org.eclipse.daanse.dax.engine.impl.plan.NamedMeasure;
import org.eclipse.daanse.dax.engine.impl.plan.Summarize;
import org.junit.jupiter.api.Test;

class MdxGeneratorTest {

    @Test
    void measuresByColumnsOfTwoHierarchies() {
        MdxQuery query = MdxGenerator.summarize("[Sales]", new Summarize(List.of(CATEGORY, YEAR),
                List.of(new NamedMeasure("S", SALES_AMOUNT), new NamedMeasure("U", UNIT_SALES))));
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales Amount], [Measures].[Unit Sales]} ON 0, "
                + "NON EMPTY CrossJoin([Product].[Category].Members, [Date].[Year].Members) ON 1 FROM [Sales]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(1, 1), new CellValue(0),
                new CellValue(1));
        assertThat(query.rows()).isTrue();
    }

    @Test
    void onlyTheDeepestLevelOfAHierarchyGoesOnTheAxis() {
        MdxQuery query = MdxGenerator.summarize("[Sales]", new Summarize(List.of(CATEGORY, SUBCATEGORY), List.of()));
        assertThat(query.text()).isEqualTo("SELECT {} ON 0, [Product].[Subcategory].Members ON 1 FROM [Sales]");
        assertThat(query.sources()).containsExactly(new MemberName(0, 1), new MemberName(0, 2));
    }

    @Test
    void measuresAloneHaveNoRowsAxis() {
        MdxQuery query = MdxGenerator.summarize("[Sales]",
                new Summarize(List.of(), List.of(new NamedMeasure("S", SALES_AMOUNT))));
        assertThat(query.text()).isEqualTo("SELECT {[Measures].[Sales Amount]} ON 0 FROM [Sales]");
        assertThat(query.rows()).isFalse();
    }

    @Test
    void quotesNames() {
        assertThat(MdxNames.quote("a]b")).isEqualTo("[a]]b]");
    }
}
