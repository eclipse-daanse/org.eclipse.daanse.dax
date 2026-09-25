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

import java.util.List;

/**
 * The plan of a query.
 *
 * @param evaluates one plan per {@code EVALUATE}, in query order
 */
public record QueryPlan(List<EvaluatePlan> evaluates) {

    public QueryPlan {
        evaluates = List.copyOf(evaluates);
    }
}
