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

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.daanse.dax.engine.api.DaxCancelledException;
import org.eclipse.daanse.dax.engine.api.DaxCancelledException.Reason;
import org.eclipse.daanse.olap.api.execution.Statement;

/**
 * Cancellation and timeout of one query. Stopping the query cancels the OLAP
 * statement running for it, if any, and fails every later step.
 */
final class QueryControl {

    private final Object lock = new Object();
    private Reason stopped;
    private Statement running;
    private ScheduledFuture<?> timer;

    void startTimer(ScheduledExecutorService timeouts, Duration timeout) {
        if (!timeout.isZero()) {
            timer = timeouts.schedule(() -> stop(Reason.TIMEOUT), timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** Stops the query; the first reason given sticks. Callable from any thread. */
    void stop(Reason reason) {
        Statement statement;
        synchronized (lock) {
            if (stopped != null) {
                return;
            }
            stopped = reason;
            statement = running;
        }
        if (statement != null) {
            cancel(statement);
        }
    }

    /** @throws DaxCancelledException if the query was stopped */
    void check() throws DaxCancelledException {
        Optional<Reason> reason = stopped();
        if (reason.isPresent()) {
            throw new DaxCancelledException(reason.get());
        }
    }

    Optional<Reason> stopped() {
        synchronized (lock) {
            return Optional.ofNullable(stopped);
        }
    }

    /**
     * Registers the OLAP statement now running for the query, so stopping the
     * query cancels it.
     *
     * @throws DaxCancelledException if the query was already stopped
     */
    void running(Statement statement) throws DaxCancelledException {
        synchronized (lock) {
            if (stopped != null) {
                throw new DaxCancelledException(stopped);
            }
            running = statement;
        }
    }

    void finished() {
        synchronized (lock) {
            running = null;
        }
    }

    /** Ends the timer; the query is done. */
    void close() {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private static void cancel(Statement statement) {
        try {
            statement.cancel();
        } catch (SQLException | RuntimeException e) {
            // the query fails on its next check anyway
        }
    }
}
