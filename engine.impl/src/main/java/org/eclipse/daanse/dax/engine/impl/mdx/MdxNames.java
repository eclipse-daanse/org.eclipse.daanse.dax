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

/**
 * Writes names as MDX identifiers.
 */
public final class MdxNames {

    private MdxNames() {
    }

    /** @return the name in brackets, e.g. {@code [Sales]}; a {@code ]} in it doubled */
    public static String quote(String name) {
        return "[" + name.replace("]", "]]") + "]";
    }
}
