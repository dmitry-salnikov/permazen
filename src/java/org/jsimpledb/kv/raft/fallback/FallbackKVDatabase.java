
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.kv.raft.fallback;

import com.google.common.base.Preconditions;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.dellroad.stuff.io.AtomicUpdateFileOutputStream;
import org.jsimpledb.kv.KVDatabase;
import org.jsimpledb.kv.KVTransaction;
import org.jsimpledb.kv.RetryTransactionException;
import org.jsimpledb.kv.raft.Consistency;
import org.jsimpledb.kv.raft.RaftKVDatabase;
import org.jsimpledb.kv.raft.RaftKVTransaction;
import org.jsimpledb.kv.raft.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A partition-tolerant {@link KVDatabase} that automatically migrates between a clustered {@link RaftKVDatabase}
 * and a local, non-clustered "standalone mode" {@link KVDatabase}, based on availability of the Raft cluster.
 *
 * <p>
 * A {@link RaftKVDatabase} requires that the local node be part of a cluster majority, otherwise, transactions
 * cannot commit (even read-only ones) and application progress halts. This class adds partition tolerance to
 * a {@link RaftKVDatabase}, by maintaining a separate private "standalone mode" {@link KVDatabase} that can be used
 * in lieu of the normal {@link RaftKVDatabase} when the Raft cluster is unavailable.
 *
 * <p>
 * Instances transparently and automatically switch over to standalone mode {@link KVDatabase} when they determine
 * that the {@link RaftKVDatabase} is unavailable.
 *
 * <p>
 * Of course, this sacrifices consistency. To address that, a configurable {@link MergeStrategy} is used to migrate the data
 * When switching between normal mode and standalone mode. The {@link MergeStrategy} is given read-only access to the
 * database being switched away from, and read-write access to the database being switched to; when switching away from
 * the {@link RaftKVDatabase}, {@link Consistency#EVENTUAL_COMMITTED} is used to eliminate the requirement for
 * communication with the rest of the cluster.
 *
 * <p>
 * Although more exotic, instances support migrating between multiple {@link RaftKVDatabase}s in a prioritized list.
 * For example, the local node may be part of two independent {@link RaftKVDatabase} clusters: a higher priority one
 * containing every node, and a lower priority one containing only nodes in the same data center as the local node.
 * In any case, the "standalone mode" database (which is not a clustered database) always has the lowest priority.
 *
 * <p>
 * Raft cluster availability is determined by {@link FallbackTarget#checkAvailability}; subclasses may override the default
 * implementation if desired.
 */
public class FallbackKVDatabase implements KVDatabase {

    private static final int MIGRATION_CHECK_INTERVAL = 1000;                   // every 1 second
    private static final int STATE_FILE_COOKIE = 0xe2bd1a96;
    private static final int CURRENT_FORMAT_VERSION = 1;

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    // Configured state
    private final ArrayList<FallbackTarget> targets = new ArrayList<>();
    private File stateFile;
    private KVDatabase standaloneKV;

    // Runtime state
    private boolean migrating;
    private int migrationCount;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> migrationCheckFuture;
    private int startCount;
    private boolean started;
    private int currentTargetIndex;
    private Date lastStandaloneActiveTime;

// Methods

    /**
     * Get this instance's persistent state file.
     *
     * @return file for persistent state
     */
    public synchronized File getStateFile() {
        return this.stateFile;
    }

    /**
     * Configure this instance's persistent state file.
     *
     * <p>
     * Required property.
     *
     * @param stateFile file for persistent state
     * @throws IllegalArgumentException if {@code stateFile} is null
     * @throws IllegalStateException if this instance is already started
     */
    public synchronized void setStateFile(File stateFile) {
        Preconditions.checkArgument(stateFile != null, "null stateFile");
        Preconditions.checkState(!this.started, "already started");
        this.stateFile = stateFile;
    }

    /**
     * Get the configured "standalone mode" {@link KVDatabase} to be used when all {@link FallbackTarget}s are unavailable.
     *
     * @return "standalone mode" database
     */
    public synchronized KVDatabase getStandaloneTarget() {
        return this.standaloneKV;
    }

    /**
     * Configure the local "standalone mode" {@link KVDatabase} to be used when all {@link FallbackTarget}s are unavailable.
     *
     * @param standaloneKV "standalone mode" database
     * @throws IllegalArgumentException if {@code standaloneKV} is null
     * @throws IllegalStateException if this instance is already started
     */
    public synchronized void setStandaloneTarget(KVDatabase standaloneKV) {
        Preconditions.checkArgument(standaloneKV != null, "null standaloneKV");
        Preconditions.checkState(!this.started, "already started");
        this.standaloneKV = standaloneKV;
    }

    /**
     * Get most preferred {@link FallbackTarget}.
     *
     * <p>
     * Targets will be sorted in order of increasing preference.
     *
     * @return top fallback target, or null if none are configured yet
     */
    public synchronized FallbackTarget getFallbackTarget() {
        return !this.targets.isEmpty() ? this.targets.get(this.targets.size() - 1) : null;
    }

    /**
     * Get the {@link FallbackTarget}(s).
     *
     * <p>
     * Targets will be sorted in order of increasing preference.
     *
     * @return list of one or more fallback targets; the returned list is a snapshot-in-time copy of each target
     */
    public synchronized List<FallbackTarget> getFallbackTargets() {
        final ArrayList<FallbackTarget> result = new ArrayList<>(this.targets);
        for (int i = 0; i < result.size(); i++)
            result.set(i, result.get(i).clone());
        return result;
    }

    /**
     * Configure a single {@link FallbackTarget}.
     *
     * @param target fallback target
     * @throws IllegalArgumentException if {@code target} is null
     * @throws IllegalArgumentException if any target does not have a {@link RaftKVDatabase} configured
     * @throws IllegalStateException if this instance is already started
     */
    public synchronized void setFallbackTarget(FallbackTarget target) {
        this.setFallbackTargets(Collections.singletonList(target));
    }

    /**
     * Configure multiple {@link FallbackTarget}(s).
     *
     * <p>
     * Targets should be sorted in order of increasing preference.
     *
     * @param targets targets in order of increasing preference
     * @throws IllegalArgumentException if {@code targets} is null
     * @throws IllegalArgumentException if {@code targets} is empty
     * @throws IllegalArgumentException if any target is null
     * @throws IllegalArgumentException if any target does not have a {@link RaftKVDatabase} configured
     * @throws IllegalStateException if this instance is already started
     */
    public synchronized void setFallbackTargets(List<? extends FallbackTarget> targets) {
        Preconditions.checkArgument(targets != null, "null targets");
        Preconditions.checkArgument(!targets.isEmpty(), "empty targets");
        Preconditions.checkState(!this.started, "already started");
        this.targets.clear();
        for (FallbackTarget target : targets) {
            Preconditions.checkArgument(target != null, "null target");
            Preconditions.checkArgument(target.getRaftKVDatabase() != null, "target with no database configured");
            this.targets.add(target.clone());
        }
    }

    /**
     * Get the index of the currently active database.
     *
     * @return index into fallback target list, or -1 for standalone mode
     */
    public synchronized int getCurrentTargetIndex() {
        return this.currentTargetIndex;
    }

    /**
     * Get the last time the standalone database was active.
     *
     * @return last active time of the standalone database, or null if never active
     */
    public synchronized Date getLastStandaloneActiveTime() {
        return this.lastStandaloneActiveTime;
    }

// KVDatabase

    @Override
    public synchronized void start() {

        // Already started?
        if (this.started)
            return;
        this.startCount++;

        // Sanity check
        Preconditions.checkState(this.stateFile != null, "no state file configured");
        Preconditions.checkState(this.targets != null, "no targets configured");
        try {

            // Logging
            if (this.log.isDebugEnabled())
                this.log.info("starting up " + this);

            // Create executor
            this.executor = Executors.newScheduledThreadPool(this.targets.size(), new ExecutorThreadFactory());

            // Start underlying databases
            this.standaloneKV.start();
            for (FallbackTarget target : this.targets)
                target.getRaftKVDatabase().start();

            // Start periodic availability checks
            for (FallbackTarget target : this.targets) {
                target.future = this.executor.scheduleWithFixedDelay(
                  new AvailabilityCheckTask(target), 0, target.getCheckInterval(), TimeUnit.MILLISECONDS);
            }

            // Initialize target runtime state
            for (FallbackTarget target : this.targets) {
                target.available = true;
                target.lastChangeTimestamp = null;
            }
            this.currentTargetIndex = this.targets.size() - 1;
            this.migrationCount = 0;

            // Read state file, if present
            if (this.stateFile.exists()) {
                try {
                    this.readStateFile();
                } catch (IOException e) {
                    throw new RuntimeException("error reading persistent state file " + this.stateFile, e);
                }
            }

            // Set up periodic migration checks
            this.migrationCheckFuture = this.executor.scheduleWithFixedDelay(
              new MigrationCheckTask(), MIGRATION_CHECK_INTERVAL, MIGRATION_CHECK_INTERVAL, TimeUnit.MILLISECONDS);

            // Done
            this.started = true;
        } finally {
            if (!this.started)
                this.cleanup();
        }
    }

    @Override
    public synchronized void stop() {

        // Already stopped?
        if (!this.started)
            return;
        this.cleanup();
    }

    private void cleanup() {

        // Sanity check
        assert Thread.holdsLock(this);

        // Logging
        if (this.log.isDebugEnabled())
            this.log.info("shutting down " + this);

        // Wait for migration to complete
        if (this.migrating) {
            if (this.log.isDebugEnabled())
                this.log.info("waiting for migration to finish to shut down " + this);
            do {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    this.log.warn("interrupted during " + this + " shutdown while waiting for migration to finish (ignoring)", e);
                }
                if (!this.started)              // we lost a race with another thread invoking stop()
                    return;
            } while (this.migrating);
            if (this.log.isDebugEnabled())
                this.log.info("migration finished, continuing with shut down of " + this);
        }

        // Reset target runtime state
        for (FallbackTarget target : this.targets) {
            target.available = false;
            target.lastChangeTimestamp = null;
        }

        // Stop periodic checks
        for (FallbackTarget target : this.targets) {
            if (target.future != null) {
                target.future.cancel(true);
                target.future = null;
            }
        }
        if (this.migrationCheckFuture != null) {
            this.migrationCheckFuture.cancel(true);
            this.migrationCheckFuture = null;
        }

        // Shut down thread pool
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }

        // Stop databases
        for (FallbackTarget target : this.targets) {
            try {
                target.getRaftKVDatabase().stop();
            } catch (Exception e) {
                this.log.warn("error stopping database target " + target + " (ignoring)", e);
            }
        }
        try {
            this.standaloneKV.stop();
        } catch (Exception e) {
            this.log.warn("error stopping fallback database " + this.standaloneKV + " (ignoring)", e);
        }

        // Done
        this.started = false;
    }

    @Override
    public FallbackKVTransaction createTransaction(Map<String, ?> options) {
        return this.createTransaction();                                            // no options supported yet
    }

    @Override
    public synchronized FallbackKVTransaction createTransaction() {

        // Sanity check
        Preconditions.checkState(this.started, "not started");

        // Create inner transaction from current database
        final KVDatabase currentKV = this.currentTargetIndex == -1 ?
          this.standaloneKV : this.targets.get(this.currentTargetIndex).getRaftKVDatabase();
        KVTransaction tx = currentKV.createTransaction();

        // Wrap it
        return new FallbackKVTransaction(this, tx, this.migrationCount);
    }

// Object

    @Override
    public String toString() {
        return this.getClass().getSimpleName()
          + "[standalone=" + this.standaloneKV
          + ",targets=" + this.targets + "]";
    }

// Package methods

    boolean isMigrating() {
        return this.migrating;
    }

    int getMigrationCount() {
        return this.migrationCount;
    }

// Internal methods

    // Perform availability check on the specified target
    private void performCheck(FallbackTarget target, final int startCount) {

        // Check for shutdown race condition
        synchronized (this) {
            if (!this.started || startCount != this.startCount)
                return;
        }

        // Logging
        if (this.log.isTraceEnabled())
            this.log.trace("performing availability check for " + target);

        // Perform check
        boolean available = false;
        try {
            available = target.checkAvailability();
        } catch (Exception e) {
            if (this.log.isDebugEnabled())
                this.log.debug("checkAvailable() for " + target + " threw exception", e);
        }

        // Handle result
        synchronized (this) {

            // Check for shutdown race condition
            if (!this.started || startCount != this.startCount)
                return;

            // Prevent timestamp roll-over
            if (target.lastChangeTimestamp != null && target.lastChangeTimestamp.isRolloverDanger())
                target.lastChangeTimestamp = null;

            // Any state change?
            if (available == target.available)
                return;

            // Update availability and schedule an immediate migration check
            this.log.info(target + " has become " + (available ? "" : "un") + "available");
            target.available = available;
            target.lastChangeTimestamp = new Timestamp();
            this.executor.submit(new MigrationCheckTask());
        }
    }

    // Perform migration if necessary
    private void checkMigration(final int startCount) {
        final int currIndex;
        int bestIndex;
        final FallbackTarget currTarget;
        final FallbackTarget bestTarget;
        synchronized (this) {

            // Check for shutdown race condition
            if (!this.started || startCount != this.startCount)
                return;

            // Logging
            if (this.log.isTraceEnabled())
                this.log.trace("performing migration check");

            // Allow only one migration at a time
            if (this.migrating)
                return;

            // Get the highest priority (i.e., best choice) database that is currently available
            bestIndex = this.targets.size() - 1;
            while (bestIndex >= 0) {
                final FallbackTarget target = this.targets.get(bestIndex);

                // Enforce hysteresis: don't change state unless sufficient time has past since target's last state change
                final boolean previousAvailable = bestIndex >= this.currentTargetIndex;
                final boolean currentAvailable = target.available;
                final int timeSinceChange = target.lastChangeTimestamp != null ?
                  -target.lastChangeTimestamp.offsetFromNow() : Integer.MAX_VALUE;
                final boolean hysteresisAvailable;
                if (currentAvailable)
                    hysteresisAvailable = previousAvailable || timeSinceChange >= target.getMinAvailableTime();
                else
                    hysteresisAvailable = previousAvailable && timeSinceChange < target.getMinUnavailableTime();
                if (this.log.isTraceEnabled()) {
                    this.log.trace(target + " availability: previous=" + previousAvailable + ", current=" + currentAvailable
                      + ", hysteresis=" + hysteresisAvailable);
                }

                // If this target is available, use it
                if (hysteresisAvailable)
                    break;

                // Try next best one
                bestIndex--;
            }

            // Already there?
            currIndex = this.currentTargetIndex;
            if (currIndex == bestIndex)
                return;

            // Get targets
            currTarget = currIndex != -1 ? this.targets.get(currIndex) : null;
            bestTarget = bestIndex != -1 ? this.targets.get(bestIndex) : null;

            // Start migration
            this.migrating = true;
        }
        try {
            final String desc = "migration from "
              + (currIndex != -1 ? "fallback target #" + currIndex : "standalone database")
              + " to "
              + (bestIndex != -1 ? "fallback target #" + bestIndex : "standalone database");
            try {

                // Gather info
                final KVDatabase currKV = currTarget != null ? currTarget.getRaftKVDatabase() : this.standaloneKV;
                final KVDatabase bestKV = bestTarget != null ? bestTarget.getRaftKVDatabase() : this.standaloneKV;
                final Date lastActiveTime = bestTarget != null ? bestTarget.lastActiveTime : this.lastStandaloneActiveTime;
                final MergeStrategy mergeStrategy = bestIndex < currIndex ?
                  currTarget.getUnavailableMergeStrategy() : bestTarget.getRejoinMergeStrategy();

                // Logit
                this.log.info("starting fallback " + desc + " using " + mergeStrategy);

                // Create source transaction. Note the combination of read-only and EVENTUAL_COMMITTED is important, because this
                // guarantees that the transaction will generate no network traffic (and not require any majority) on commit().
                final KVTransaction src;
                if (currKV instanceof RaftKVDatabase) {
                    src = ((RaftKVDatabase)currKV).createTransaction(Consistency.EVENTUAL_COMMITTED);
                    ((RaftKVTransaction)src).setReadOnly(true);
                } else
                    src = currKV.createTransaction();
                boolean srcCommitted = false;
                try {

                    // Create destination transaction
                    final KVTransaction dst = bestKV.createTransaction();
                    boolean dstCommitted = false;
                    try {

                        // Get timestamp
                        final Date currentTime = new Date();

                        // Perform merge
                        mergeStrategy.merge(src, dst, lastActiveTime);

                        // Commit transactions
                        src.commit();
                        srcCommitted = true;
                        dst.commit();
                        dstCommitted = true;

                        // Redirect new transactions
                        this.log.info(desc + " succeeded");
                        synchronized (this) {
                            if (currTarget != null)
                                currTarget.lastActiveTime = currentTime;
                            else
                                this.lastStandaloneActiveTime = currentTime;
                            this.currentTargetIndex = bestIndex;
                            this.migrationCount++;
                        }

                    } finally {
                        if (!dstCommitted)
                            dst.rollback();
                    }
                } finally {
                    if (!srcCommitted)
                        src.rollback();
                }
            } catch (RetryTransactionException e) {
                this.log.info(desc + " failed (will try again later): " + e);
            } catch (Throwable t) {
                this.log.error(desc + " failed", t);
            }
        } finally {
            synchronized (this) {
                this.migrating = false;
                this.notifyAll();
            }
        }

        // Update state file
        try {
            this.writeStateFile();
        } catch (IOException e) {
            this.log.error("error writing state to state file " + this.stateFile, e);
        }
    }

    private void readStateFile() throws IOException {

        // Sanity check
        assert Thread.holdsLock(this);

        // Read data
        final int targetIndex;
        final long standaloneActiveTime;
        final long[] lastActiveTimes;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(this.stateFile)))) {
            final int cookie = input.readInt();
            if (cookie != STATE_FILE_COOKIE)
                throw new IOException("invalid state file " + this.stateFile + " (incorrect header)");
            final int formatVersion = input.readInt();
            switch (formatVersion) {
            case CURRENT_FORMAT_VERSION:
                break;
            default:
                throw new IOException("invalid state file " + this.stateFile + " format version (expecting "
                  + CURRENT_FORMAT_VERSION + ", found " + formatVersion + ")");
            }
            final int numTargets = input.readInt();
            if (numTargets != this.targets.size()) {
                this.log.warn("state file " + this.stateFile + " lists " + numTargets + " != " + this.targets.size()
                  + ", assuming configuration change and ignoring file");
                return;
            }
            targetIndex = input.readInt();
            if (targetIndex < -1 || targetIndex >= this.targets.size())
                throw new IOException("invalid state file " + this.stateFile + " target index " + targetIndex);
            standaloneActiveTime = input.readLong();
            lastActiveTimes = new long[numTargets];
            for (int i = 0; i < numTargets; i++)
                lastActiveTimes[i] = input.readLong();
        }

        // Apply data
        this.currentTargetIndex = targetIndex;
        for (int i = 0; i < this.targets.size(); i++) {
            final FallbackTarget target = this.targets.get(i);
            target.lastActiveTime = lastActiveTimes[i] != 0 ? new Date(lastActiveTimes[i]) : null;
        }
        this.lastStandaloneActiveTime = standaloneActiveTime != 0 ? new Date(standaloneActiveTime) : null;
    }

    private void writeStateFile() throws IOException {

        // Sanity check
        assert Thread.holdsLock(this);

        // Write data
        try (DataOutputStream output = new DataOutputStream(
          new BufferedOutputStream(new AtomicUpdateFileOutputStream(this.stateFile)))) {
            output.writeInt(STATE_FILE_COOKIE);
            output.writeInt(CURRENT_FORMAT_VERSION);
            output.writeInt(this.targets.size());
            output.writeInt(this.currentTargetIndex);
            output.writeLong(this.lastStandaloneActiveTime != null ? this.lastStandaloneActiveTime.getTime() : 0);
            for (int i = 0; i < this.targets.size(); i++) {
                final FallbackTarget target = this.targets.get(i);
                output.writeLong(target.lastActiveTime != null ? target.lastActiveTime.getTime() : 0);
            }
        }
    }

// ExecutorThreadFactory

    private class ExecutorThreadFactory implements ThreadFactory {

        private final AtomicInteger id = new AtomicInteger();

        @Override
        public Thread newThread(Runnable action) {
            final Thread thread = new Thread(action);
            thread.setName("Executor#" + this.id.incrementAndGet() + " for " + FallbackKVDatabase.this);
            return thread;
        }
    }

// AvailabilityCheckTask

    private class AvailabilityCheckTask implements Runnable {

        private final FallbackTarget target;
        private final int startCount;

        AvailabilityCheckTask(FallbackTarget target) {
            assert Thread.holdsLock(FallbackKVDatabase.this);
            this.target = target;
            this.startCount = FallbackKVDatabase.this.startCount;
        }

        @Override
        public void run() {
            try {
                FallbackKVDatabase.this.performCheck(this.target, this.startCount);
            } catch (Throwable t) {
                FallbackKVDatabase.this.log.error("exception from " + this.target + " availability check", t);
            }
        }
    }

// MigrationCheckTask

    private class MigrationCheckTask implements Runnable {

        private final int startCount;

        MigrationCheckTask() {
            assert Thread.holdsLock(FallbackKVDatabase.this);
            this.startCount = FallbackKVDatabase.this.startCount;
        }

        @Override
        public void run() {
            try {
                FallbackKVDatabase.this.checkMigration(this.startCount);
            } catch (Throwable t) {
                FallbackKVDatabase.this.log.error("exception from migration check", t);
            }
        }
    }
}
