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

import java.math.BigDecimal;

/**
 * Compares DAX values as {@code ORDER BY} does: BLANK first, numbers by value,
 * text ignoring case.
 */
final class DaxValues {

    private DaxValues() {
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static int compare(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        if (a instanceof Number x && b instanceof Number y) {
            if (x instanceof Long l && y instanceof Long m) {
                return Long.compare(l, m);
            }
            return decimal(x).compareTo(decimal(y));
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareToIgnoreCase(y);
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable comparable) {
            return comparable.compareTo(b);
        }
        return a.toString().compareToIgnoreCase(b.toString());
    }

    private static BigDecimal decimal(Number number) {
        return number instanceof BigDecimal d ? d : new BigDecimal(number.toString());
    }
}
