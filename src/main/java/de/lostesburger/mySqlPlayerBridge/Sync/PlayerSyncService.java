package de.lostesburger.mySqlPlayerBridge.Sync;

import de.lostesburger.mySqlPlayerBridge.Database.DatabaseExecutor;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.NoEntryProtection.NoEntryProtection;
import de.lostesburger.mySqlPlayerBridge.Platform.PlatformScheduler;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.LeaseLostException;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.PlayerLease;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.PlayerLeaseCoordinator;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.logging.Level;

public final class PlayerSyncService {
    private final ModuleRegistry moduleRegistry;
    private final PlayerSnapshotFactory snapshotFactory;
    private final PlayerSnapshotRepository snapshotRepository;
    private final PlayerLeaseCoordinator leaseCoordinator;
    private final DatabaseExecutor databaseExecutor;
    private final PlatformScheduler platformScheduler;
    private final RecoverySnapshotStore recoverySnapshotStore;
    private final long joinOperationTimeoutSeconds;
    private final int maximumSaveAttempts;
    private final long saveRetryDelayMs;
    private final PlayerOperationQueue operationQueue = new PlayerOperationQueue();
    private final Map<UUID, BoundSnapshot> latestSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, PendingAutoSave> pendingAutoSaves = new ConcurrentHashMap<>();
    private final AtomicLong coalescedAutoSaves = new AtomicLong();

    public PlayerSyncService(
            ModuleRegistry moduleRegistry,
            PlayerSnapshotRepository snapshotRepository,
            PlayerLeaseCoordinator leaseCoordinator,
            DatabaseExecutor databaseExecutor,
            PlatformScheduler platformScheduler,
            long joinOperationTimeoutSeconds,
            int maximumSaveAttempts,
            long saveRetryDelayMs
    ) {
        if (joinOperationTimeoutSeconds < 1L) {
            throw new IllegalArgumentException("Join operation timeout must be positive");
        }
        if (maximumSaveAttempts < 1) {
            throw new IllegalArgumentException("Maximum save attempts must be at least one");
        }
        if (saveRetryDelayMs < 0L) {
            throw new IllegalArgumentException("Save retry delay cannot be negative");
        }
        this.moduleRegistry = moduleRegistry;
        this.snapshotFactory = new PlayerSnapshotFactory(moduleRegistry);
        this.snapshotRepository = snapshotRepository;
        this.leaseCoordinator = leaseCoordinator;
        this.databaseExecutor = databaseExecutor;
        this.platformScheduler = platformScheduler;
        this.joinOperationTimeoutSeconds = joinOperationTimeoutSeconds;
        this.maximumSaveAttempts = maximumSaveAttempts;
        this.saveRetryDelayMs = saveRetryDelayMs;
        this.recoverySnapshotStore = new RecoverySnapshotStore(Main.getInstance().getDataFolder().toPath());
    }

    public CompletableFuture<JoinOutcome> join(Player player) {
        UUID playerUuid = player.getUniqueId();
        return this.operationQueue.enqueue(playerUuid, () -> this.leaseCoordinator.acquire(playerUuid)
                .thenCompose(lease -> load(lease)
                        .thenCompose(loaded -> loaded
                                .map(snapshot -> applyLoaded(player, snapshot, lease)
                                        .thenCompose(ignored -> captureForPlayer(player, lease))
                                        .thenCompose(captured -> ensurePlayerActive(player)
                                                .thenCompose(ignored -> saveLoadedAndMarkOnline(captured, lease)))
                                        .thenApply(ignored -> JoinOutcome.LOADED))
                                .orElseGet(() -> createInitial(player, lease)))
                        .orTimeout(this.joinOperationTimeoutSeconds, TimeUnit.SECONDS)
                        .exceptionallyCompose(throwable -> releaseAfterFailure(lease, throwable))));
    }

    /** Must be called from the player's valid Bukkit/Folia execution context. */
    public PlayerSnapshot capture(Player player) throws SnapshotException {
        PlayerLease lease = this.leaseCoordinator.activeLease(player.getUniqueId());
        if (lease == null) {
            throw new SnapshotException(
                    "Cannot capture player without an active lease: " + player.getUniqueId(),
                    new IllegalStateException("No active player lease")
            );
        }
        PlayerSnapshot snapshot = this.snapshotFactory.capture(player);
        this.latestSnapshots.put(snapshot.playerUuid(), new BoundSnapshot(snapshot, lease));
        return snapshot;
    }

    public CompletableFuture<Void> saveOnline(PlayerSnapshot snapshot) {
        PlayerLease lease = leaseForSnapshot(snapshot);
        if (lease == null) {
            return missingLease(snapshot.playerUuid());
        }
        return save(snapshot, lease, false);
    }

    /** Apply in the entity context, then acknowledge only the committed snapshot. */
    public CompletableFuture<Void> editOnline(Player player, String moduleId,
            java.util.function.Function<Player, CompletableFuture<Void>> edit) {
        PlayerLease lease = this.leaseCoordinator.activeLease(player.getUniqueId());
        if (lease == null) {
            return missingLease(player.getUniqueId());
        }
        return PlayerEditExecution.execute(this.platformScheduler, player,
                () -> requireEditable(player, lease), edit, () -> saveEdited(player, lease, moduleId));
    }

    private CompletableFuture<Void> saveEdited(Player player, PlayerLease lease, String moduleId)
            throws SnapshotException {
        requireEditable(player, lease);
        PlayerSnapshot snapshot = this.snapshotFactory.capture(player, moduleId);
        this.latestSnapshots.put(snapshot.playerUuid(), new BoundSnapshot(snapshot, lease));
        return save(snapshot, lease, false);
    }

    private void requireEditable(Player player, PlayerLease lease) {
        if (!this.leaseCoordinator.isActive(lease) || !player.isOnline()
                || Main.mySqlConnectionHandler.getMySqlDataManager().isJoinSyncLocked(player.getUniqueId())) {
            throw new IllegalStateException("Player session is not ready for editing: " + player.getUniqueId());
        }
    }

    public CompletableFuture<Void> captureAndSaveOnline(Player player) {
        PlayerLease lease = this.leaseCoordinator.activeLease(player.getUniqueId());
        if (lease == null) {
            return missingLease(player.getUniqueId());
        }
        return captureForPlayer(player, lease).thenCompose(snapshot -> save(snapshot, lease, false));
    }

    public CompletableFuture<Void> captureAndSaveAuto(Player player) {
        PlayerLease lease = this.leaseCoordinator.activeLease(player.getUniqueId());
        if (lease == null) {
            return CompletableFuture.completedFuture(null);
        }
        return captureForPlayer(player, lease).thenCompose(snapshot -> saveAuto(snapshot, lease));
    }

    public CompletableFuture<Void> saveAndRelease(PlayerSnapshot snapshot) {
        PlayerLease lease = leaseForSnapshot(snapshot);
        if (lease == null) {
            return missingLease(snapshot.playerUuid());
        }
        return save(snapshot, lease, true);
    }

    public CompletableFuture<Void> releaseWithoutSave(UUID playerUuid) {
        return this.operationQueue.enqueue(playerUuid, () -> {
            PlayerLease lease = this.leaseCoordinator.activeLease(playerUuid);
            if (lease == null) {
                return CompletableFuture.completedFuture(null);
            }
            return this.leaseCoordinator.release(lease).thenApply(ignored -> {
                this.latestSnapshots.computeIfPresent(playerUuid, (ignoredUuid, current) ->
                        current.lease().equals(lease) ? null : current);
                this.pendingAutoSaves.remove(playerUuid);
                return null;
            });
        });
    }

    public CompletableFuture<Void> saveLatestAndRelease(UUID playerUuid) {
        BoundSnapshot latest = this.latestSnapshots.get(playerUuid);
        PlayerLease activeLease = this.leaseCoordinator.activeLease(playerUuid);
        if (latest == null || activeLease == null || !latest.lease().equals(activeLease)) {
            return releaseWithoutSave(playerUuid);
        }
        return save(latest.snapshot(), latest.lease(), true);
    }

    public boolean hasActiveLease(UUID playerUuid) {
        return this.leaseCoordinator.activeLease(playerUuid) != null;
    }

    public long coalescedAutoSaveCount() {
        return this.coalescedAutoSaves.get();
    }

    /** Stops heartbeats without deleting the database row, so the lease expires safely. */
    public void allowLeaseToExpire(UUID playerUuid) {
        PlayerLease lease = this.leaseCoordinator.activeLease(playerUuid);
        if (lease != null) {
            this.leaseCoordinator.forget(lease);
        }
    }

    private CompletableFuture<Optional<LoadedPlayerSnapshot>> load(PlayerLease lease) {
        return this.databaseExecutor.supply(() -> this.snapshotRepository.load(lease, this.moduleRegistry));
    }

    private CompletableFuture<JoinOutcome> createInitial(Player player, PlayerLease lease) {
        if (Main.config.getBoolean("settings.no-entry-protection")) {
            return runForPlayer(player, () -> NoEntryProtection.isTriggered(player))
                    .thenCompose(ignored -> this.leaseCoordinator.release(lease))
                    .thenApply(ignored -> JoinOutcome.NO_ENTRY_PROTECTED);
        }

        return captureForPlayer(player, lease)
                .thenCompose(snapshot -> this.databaseExecutor.run(
                        () -> this.snapshotRepository.save(snapshot, lease, false, false)))
                .thenCompose(ignored -> ensurePlayerActive(player))
                .thenCompose(ignored -> markOnline(lease, player.getName()))
                .thenApply(ignored -> JoinOutcome.CREATED);
    }

    private CompletableFuture<Void> ensurePlayerActive(Player player) {
        return runForPlayer(player, () -> {
            if (!player.isOnline()) {
                throw new PlayerRetiredException(player.getUniqueId());
            }
            return null;
        });
    }

    private CompletableFuture<Void> applyLoaded(
            Player player,
            LoadedPlayerSnapshot snapshot,
            PlayerLease lease
    ) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        boolean scheduled = this.platformScheduler.runForPlayer(player, () -> {
            List<CompletableFuture<Void>> applications = new ArrayList<>();
            try {
                if (!this.leaseCoordinator.isActive(lease)) {
                    throw new LeaseLostException(lease);
                }
                for (LoadedModule<?> module : snapshot.modules()) {
                    applications.add(module.apply(player));
                }
            } catch (Exception exception) {
                completion.completeExceptionally(exception);
                return;
            }
            CompletableFuture.allOf(applications.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, throwable) -> complete(completion, throwable));
        }, () -> completion.completeExceptionally(new PlayerRetiredException(player.getUniqueId())));
        if (!scheduled) {
            completion.completeExceptionally(new PlayerRetiredException(player.getUniqueId()));
        }
        return completion;
    }

    private CompletableFuture<PlayerSnapshot> captureForPlayer(Player player, PlayerLease lease) {
        CompletableFuture<PlayerSnapshot> completion = new CompletableFuture<>();
        boolean scheduled = this.platformScheduler.runForPlayer(player, () -> {
            try {
                if (!this.leaseCoordinator.isActive(lease)) {
                    throw new LeaseLostException(lease);
                }
                completion.complete(capture(player));
            } catch (Exception exception) {
                completion.completeExceptionally(exception);
            }
        }, () -> completion.completeExceptionally(new PlayerRetiredException(player.getUniqueId())));
        if (!scheduled) {
            completion.completeExceptionally(new PlayerRetiredException(player.getUniqueId()));
        }
        return completion;
    }

    private CompletableFuture<Void> markOnline(PlayerLease lease, String playerName) {
        return this.databaseExecutor.run(() -> this.snapshotRepository.setOnline(lease, playerName, true))
                .thenCompose(ignored -> this.leaseCoordinator.markOnline(lease)
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new LeaseLostException(lease)));
    }

    private CompletableFuture<Void> saveLoadedAndMarkOnline(PlayerSnapshot snapshot, PlayerLease lease) {
        return this.databaseExecutor.run(() -> this.snapshotRepository.save(snapshot, lease, true, false))
                .thenCompose(ignored -> this.leaseCoordinator.markOnline(lease)
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new LeaseLostException(lease)));
    }

    private CompletableFuture<Void> save(
            PlayerSnapshot snapshot,
            PlayerLease lease,
            boolean releaseLease
    ) {
        UUID playerUuid = snapshot.playerUuid();
        return this.operationQueue.enqueue(playerUuid, () -> executeSave(snapshot, lease, releaseLease, false));
    }

    private CompletableFuture<Void> saveAuto(PlayerSnapshot snapshot, PlayerLease lease) {
        UUID playerUuid = snapshot.playerUuid();
        PendingAutoSave pending = this.pendingAutoSaves.computeIfAbsent(playerUuid, ignored -> new PendingAutoSave());
        CompletableFuture<Void> completion = new CompletableFuture<>();
        boolean startDrain;
        synchronized (pending) {
            if (pending.latest != null) {
                this.coalescedAutoSaves.incrementAndGet();
            }
            pending.latest = new BoundSnapshot(snapshot, lease);
            pending.waiters.add(completion);
            startDrain = !pending.running;
            if (startDrain) {
                pending.running = true;
            }
        }
        if (startDrain) {
            this.operationQueue.enqueue(playerUuid, () -> drainAutoSaves(pending));
        }
        return completion;
    }

    private CompletableFuture<Void> drainAutoSaves(PendingAutoSave pending) {
        final BoundSnapshot boundSnapshot;
        final List<CompletableFuture<Void>> waiters;
        synchronized (pending) {
            if (pending.latest == null) {
                pending.running = false;
                return CompletableFuture.completedFuture(null);
            }
            boundSnapshot = pending.latest;
            pending.latest = null;
            waiters = new ArrayList<>(pending.waiters);
            pending.waiters.clear();
        }

        return executeSave(boundSnapshot.snapshot(), boundSnapshot.lease(), false, true)
                .handle((ignored, throwable) -> {
            for (CompletableFuture<Void> waiter : waiters) {
                if (throwable == null) {
                    waiter.complete(null);
                } else {
                    waiter.completeExceptionally(unwrap(throwable));
                }
            }
            return null;
                }).thenCompose(ignored -> drainAutoSaves(pending));
    }

    private CompletableFuture<Void> executeSave(
            PlayerSnapshot snapshot,
            PlayerLease lease,
            boolean releaseLease,
            boolean ignoreObsoleteSession
    ) {
        UUID playerUuid = snapshot.playerUuid();
        if (!this.leaseCoordinator.isActive(lease)) {
            if (ignoreObsoleteSession) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(new LeaseLostException(lease));
        }
        return executeSaveAttempt(snapshot, lease, releaseLease, 1).whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                if (releaseLease) {
                    this.leaseCoordinator.forget(lease);
                    this.latestSnapshots.computeIfPresent(playerUuid, (ignoredUuid, current) ->
                            current.lease().equals(lease) ? null : current);
                    this.pendingAutoSaves.remove(playerUuid);
                }
                return;
            }

            Throwable cause = unwrap(throwable);
            writeRecoverySnapshot(snapshot, releaseLease ? "quit-or-shutdown-save" : "online-save", cause);
            if (releaseLease) {
                // Do not delete a failed quit lease. Stop heartbeats and let the DB-clock failsafe expire it.
                this.leaseCoordinator.forget(lease);
                this.latestSnapshots.computeIfPresent(playerUuid, (ignoredUuid, current) ->
                        current.lease().equals(lease) ? null : current);
                this.pendingAutoSaves.remove(playerUuid);
            }
        });
    }

    private CompletableFuture<Void> executeSaveAttempt(
            PlayerSnapshot snapshot,
            PlayerLease lease,
            boolean releaseLease,
            int attempt
    ) {
        CompletableFuture<Boolean> stateFuture = releaseLease
                ? this.leaseCoordinator.markSaving(lease)
                : CompletableFuture.completedFuture(true);
        CompletableFuture<Void> attemptFuture = stateFuture.thenCompose(renewed -> {
            if (!renewed) {
                return CompletableFuture.failedFuture(new LeaseLostException(lease));
            }
            return this.databaseExecutor.run(
                    () -> this.snapshotRepository.save(snapshot, lease, !releaseLease, releaseLease));
        });
        return attemptFuture.exceptionallyCompose(throwable -> {
            Throwable cause = unwrap(throwable);
            if (attempt >= this.maximumSaveAttempts || !isTransientDatabaseFailure(cause)) {
                return CompletableFuture.failedFuture(cause);
            }
            Main.getInstance().getLogger().log(Level.WARNING,
                    "Transient snapshot save failure for " + snapshot.playerUuid()
                            + "; retrying attempt " + (attempt + 1) + '/' + this.maximumSaveAttempts,
                    cause);
            Executor delayed = CompletableFuture.delayedExecutor(this.saveRetryDelayMs, TimeUnit.MILLISECONDS);
            return CompletableFuture.runAsync(() -> { }, delayed)
                    .thenCompose(ignored -> executeSaveAttempt(snapshot, lease, releaseLease, attempt + 1));
        });
    }

    private static boolean isTransientDatabaseFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLTimeoutException || current instanceof SQLTransientException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if ((state != null && (state.startsWith("08") || state.equals("40001")))
                        || sqlException.getErrorCode() == 1205
                        || sqlException.getErrorCode() == 1213) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void writeRecoverySnapshot(PlayerSnapshot snapshot, String reason, Throwable throwable) {
        try {
            java.nio.file.Path path = this.recoverySnapshotStore.save(snapshot, reason, throwable);
            Main.getInstance().getLogger().severe("Player snapshot was written to recovery file " + path);
        } catch (Exception recoveryFailure) {
            throwable.addSuppressed(recoveryFailure);
            Main.getInstance().getLogger().log(Level.SEVERE,
                    "Could not write recovery snapshot for " + snapshot.playerUuid(), recoveryFailure);
        }
    }

    private <T> CompletableFuture<T> runForPlayer(Player player, PlayerCallable<T> callable) {
        CompletableFuture<T> completion = new CompletableFuture<>();
        boolean scheduled = this.platformScheduler.runForPlayer(player, () -> {
            try {
                completion.complete(callable.call());
            } catch (Exception exception) {
                completion.completeExceptionally(exception);
            }
        }, () -> completion.completeExceptionally(new PlayerRetiredException(player.getUniqueId())));
        if (!scheduled) {
            completion.completeExceptionally(new PlayerRetiredException(player.getUniqueId()));
        }
        return completion;
    }

    private CompletableFuture<JoinOutcome> releaseAfterFailure(PlayerLease lease, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        return this.leaseCoordinator.release(lease).handle((ignored, releaseFailure) -> {
            if (releaseFailure != null) {
                cause.addSuppressed(unwrap(releaseFailure));
            }
            throw new CompletionException(cause);
        });
    }

    private static void complete(CompletableFuture<Void> future, Throwable throwable) {
        if (throwable == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(unwrap(throwable));
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private PlayerLease leaseForSnapshot(PlayerSnapshot snapshot) {
        BoundSnapshot latest = this.latestSnapshots.get(snapshot.playerUuid());
        if (latest != null && latest.snapshot() == snapshot) {
            return latest.lease();
        }
        return this.leaseCoordinator.activeLease(snapshot.playerUuid());
    }

    private static CompletableFuture<Void> missingLease(UUID playerUuid) {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "Cannot save player without an active local lease: " + playerUuid));
    }

    public enum JoinOutcome {
        LOADED,
        CREATED,
        NO_ENTRY_PROTECTED
    }

    @FunctionalInterface
    private interface PlayerCallable<T> {
        T call() throws Exception;
    }

    private static final class PendingAutoSave {
        private BoundSnapshot latest;
        private final List<CompletableFuture<Void>> waiters = new ArrayList<>();
        private boolean running;
    }

    private record BoundSnapshot(PlayerSnapshot snapshot, PlayerLease lease) {
    }
}
