package de.lostesburger.mySqlPlayerBridge.Sync.Lease;

import de.lostesburger.mySqlPlayerBridge.Database.DatabaseException;
import de.lostesburger.mySqlPlayerBridge.Database.DatabaseExecutor;

import java.time.Duration;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class PlayerLeaseCoordinator implements AutoCloseable {
    private static final long RETRY_DELAY_MS = 250L;

    private final UUID instanceUuid = UUID.randomUUID();
    private final PlayerLeaseRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final ScheduledExecutorService timer;
    private final Map<UUID, ActiveLease> activeLeases = new ConcurrentHashMap<>();
    private final long joinWaitTimeoutNanos;
    private final Consumer<String> warningLogger;
    private final AtomicLong acquireRetryCount = new AtomicLong();

    public PlayerLeaseCoordinator(
            PlayerLeaseRepository repository,
            DatabaseExecutor databaseExecutor,
            int heartbeatSeconds,
            int joinWaitTimeoutSeconds,
            Consumer<String> warningLogger
    ) {
        if (heartbeatSeconds < 1) {
            throw new IllegalArgumentException("Lease heartbeat must be at least 1 second");
        }
        if (joinWaitTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Lease join wait timeout must be at least 1 second");
        }
        if (heartbeatSeconds >= repository.leaseDurationSeconds()) {
            throw new IllegalArgumentException("Lease heartbeat must be shorter than the lease duration");
        }
        this.repository = repository;
        this.databaseExecutor = databaseExecutor;
        this.joinWaitTimeoutNanos = TimeUnit.SECONDS.toNanos(joinWaitTimeoutSeconds);
        this.warningLogger = warningLogger;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "MySqlPlayerBridge-Lease-Timer");
            thread.setDaemon(true);
            return thread;
        };
        this.timer = Executors.newSingleThreadScheduledExecutor(factory);
        this.timer.scheduleAtFixedRate(this::heartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    public UUID instanceUuid() {
        return this.instanceUuid;
    }

    public CompletableFuture<PlayerLease> acquire(UUID playerUuid) {
        PlayerLease lease = new PlayerLease(playerUuid, this.instanceUuid, UUID.randomUUID());
        CompletableFuture<PlayerLease> future = new CompletableFuture<>();
        attemptAcquire(lease, System.nanoTime() + this.joinWaitTimeoutNanos, future);
        return future;
    }

    public PlayerLease activeLease(UUID playerUuid) {
        ActiveLease activeLease = this.activeLeases.get(playerUuid);
        return activeLease == null ? null : activeLease.lease();
    }

    public boolean isActive(PlayerLease lease) {
        ActiveLease activeLease = this.activeLeases.get(lease.playerUuid());
        return activeLease != null && activeLease.lease().equals(lease);
    }

    public long acquireRetryCount() {
        return this.acquireRetryCount.get();
    }

    /** Must be called from the database executor or another non-tick bootstrap context. */
    public boolean hasDatabaseLease(UUID playerUuid) throws DatabaseException {
        return this.repository.hasUnexpiredLease(playerUuid);
    }

    /** Must be called from the database executor or another non-tick bootstrap context. */
    public boolean hasAnyDatabaseLease() throws DatabaseException {
        return this.repository.hasAnyUnexpiredLease();
    }

    public boolean markOnline(PlayerLease lease) {
        ActiveLease activeLease = this.activeLeases.get(lease.playerUuid());
        if (activeLease == null || !activeLease.lease().sessionToken().equals(lease.sessionToken())) {
            return false;
        }
        activeLease.state(LeaseState.ONLINE);
        return true;
    }

    public CompletableFuture<Boolean> markSaving(PlayerLease lease) {
        ActiveLease activeLease = this.activeLeases.get(lease.playerUuid());
        if (activeLease != null) {
            activeLease.state(LeaseState.SAVING);
        }
        return this.databaseExecutor.supply(() -> this.repository.renew(lease, LeaseState.SAVING));
    }

    public CompletableFuture<Boolean> release(PlayerLease lease) {
        this.activeLeases.computeIfPresent(lease.playerUuid(), (ignored, current) ->
                current.lease().sessionToken().equals(lease.sessionToken()) ? null : current);
        return this.databaseExecutor.supply(() -> this.repository.release(lease));
    }

    public void forget(PlayerLease lease) {
        this.activeLeases.computeIfPresent(lease.playerUuid(), (ignored, current) ->
                current.lease().sessionToken().equals(lease.sessionToken()) ? null : current);
    }

    private void attemptAcquire(PlayerLease lease, long deadlineNanos, CompletableFuture<PlayerLease> future) {
        if (future.isDone()) {
            return;
        }
        if (System.nanoTime() >= deadlineNanos) {
            future.completeExceptionally(new LeaseTimeoutException(lease.playerUuid()));
            return;
        }
        this.databaseExecutor.supply(() -> this.repository.tryAcquire(lease)).whenComplete((result, throwable) -> {
            if (throwable != null) {
                boolean retryable = isRetryableDatabaseFailure(throwable);
                if (System.nanoTime() < deadlineNanos && retryable) {
                    this.acquireRetryCount.incrementAndGet();
                    scheduleRetry(lease, deadlineNanos, future);
                } else if (retryable) {
                    future.completeExceptionally(new LeaseTimeoutException(lease.playerUuid(), throwable));
                } else {
                    future.completeExceptionally(throwable);
                }
                return;
            }
            if (result == LeaseAcquireResult.ACQUIRED) {
                if (System.nanoTime() >= deadlineNanos) {
                    this.databaseExecutor.supply(() -> this.repository.release(lease))
                            .whenComplete((ignored, releaseFailure) -> {
                                LeaseTimeoutException timeout = new LeaseTimeoutException(lease.playerUuid());
                                if (releaseFailure != null) {
                                    timeout.addSuppressed(releaseFailure);
                                }
                                future.completeExceptionally(timeout);
                            });
                    return;
                }
                this.activeLeases.put(lease.playerUuid(), new ActiveLease(lease, LeaseState.LOADING));
                future.complete(lease);
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                future.completeExceptionally(new LeaseTimeoutException(lease.playerUuid()));
                return;
            }
            this.acquireRetryCount.incrementAndGet();
            scheduleRetry(lease, deadlineNanos, future);
        });
    }

    private void scheduleRetry(PlayerLease lease, long deadlineNanos, CompletableFuture<PlayerLease> future) {
        try {
            this.timer.schedule(
                    () -> attemptAcquire(lease, deadlineNanos, future),
                    RETRY_DELAY_MS,
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException exception) {
            future.completeExceptionally(exception);
        }
    }

    private static boolean isRetryableDatabaseFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLTimeoutException || current instanceof SQLTransientException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("40001"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void heartbeat() {
        for (ActiveLease activeLease : this.activeLeases.values()) {
            this.databaseExecutor.supply(() -> {
                // A queued heartbeat must not extend a lease after quit/failure deliberately forgot it.
                if (this.activeLeases.get(activeLease.lease().playerUuid()) != activeLease) {
                    return true;
                }
                return this.repository.renew(activeLease.lease(), activeLease.state());
            })
                    .whenComplete((renewed, throwable) -> {
                        if (throwable != null) {
                            this.warningLogger.accept("Could not renew lease for " + activeLease.lease().playerUuid()
                                    + ": " + throwable.getMessage());
                            return;
                        }
                        if (!renewed) {
                            this.activeLeases.remove(activeLease.lease().playerUuid(), activeLease);
                            this.warningLogger.accept("Lost ownership lease for player " + activeLease.lease().playerUuid());
                        }
                    });
        }
    }

    @Override
    public void close() {
        this.timer.shutdownNow();
        try {
            this.timer.awaitTermination(Duration.ofSeconds(1L).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ActiveLease {
        private final PlayerLease lease;
        private volatile LeaseState state;

        private ActiveLease(PlayerLease lease, LeaseState state) {
            this.lease = lease;
            this.state = state;
        }

        private PlayerLease lease() {
            return this.lease;
        }

        private LeaseState state() {
            return this.state;
        }

        private void state(LeaseState state) {
            this.state = state;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ActiveLease that && this.lease.equals(that.lease);
        }

        @Override
        public int hashCode() {
            return this.lease.hashCode();
        }
    }
}
