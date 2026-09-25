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

/**
 * The query text is not valid DAX. The message tells where, as the parser
 * does.
 */
public final class DaxSyntaxException extends DaxException {

    private static final long serialVersionUID = 1L;

    public DaxSyntaxException(String message) {
        super(message);
    }

    /**
     * @param message the description of the error, with its position
     * @param cause   the parser's failure
     */
    public DaxSyntaxException(String message, Throwable cause) {
        super(message, cause);
    }
}
