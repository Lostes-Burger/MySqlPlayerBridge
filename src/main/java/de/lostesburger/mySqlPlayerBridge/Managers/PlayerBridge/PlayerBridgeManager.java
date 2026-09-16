package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;
import de.lostesburger.mySqlPlayerBridge.Exceptions.CrossVersionItemSyncException;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.MySqlData.MySqlDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.Platform.PlatformScheduler;
import de.lostesburger.mySqlPlayerBridge.Sync.PlayerSnapshot;
import de.lostesburger.mySqlPlayerBridge.Sync.PlayerSyncService;
import de.lostesburger.mySqlPlayerBridge.Sync.SnapshotException;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.LeaseTimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class PlayerBridgeManager implements Listener, AutoCloseable {
    private final MySqlDataManager mySqlDataManager;
    private final PlayerSyncService syncService;
    private final PlatformScheduler platformScheduler;
    private final Map<UUID, UUID> localSessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> skipQuitSessions = new ConcurrentHashMap<>();
    private final List<PlatformScheduler.TaskHandle> tasks = new ArrayList<>();

    public PlayerBridgeManager() {
        this.mySqlDataManager = Main.mySqlConnectionHandler.getMySqlDataManager();
        this.syncService = Main.playerSyncService;
        this.platformScheduler = Main.platformScheduler;
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
        new JoinSyncEventBlocker(this.mySqlDataManager);
        startAutoSyncTask();
        startPlayerIndexCleanupTask();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        UUID localSession = UUID.randomUUID();
        this.skipQuitSessions.remove(playerUuid);
        this.localSessions.put(playerUuid, localSession);
        this.mySqlDataManager.markJoinSyncPending(playerUuid, localSession);

        this.syncService.join(player)
                .whenComplete((outcome, throwable) -> {
                    this.mySqlDataManager.clearJoinSyncPending(playerUuid, localSession);
                    boolean currentSession = localSession.equals(this.localSessions.get(playerUuid));
                    if (!currentSession) {
                        return;
                    }
                    if (throwable == null) {
                        if (outcome == PlayerSyncService.JoinOutcome.LOADED) {
                            PlayerManager.sendDataLoadedMessage(player);
                        } else if (outcome == PlayerSyncService.JoinOutcome.CREATED) {
                            PlayerManager.sendCreatedDataMessage(player);
                        }
                        return;
                    }

                    this.skipQuitSessions.put(playerUuid, localSession);
                    Throwable cause = unwrap(throwable);
                    if (contains(cause, CrossVersionItemSyncException.class)) {
                        Main.getInstance().getLogger().log(Level.WARNING,
                                "Join denied because item data is not supported for " + player.getName()
                                        + " (" + playerUuid + ")", cause);
                        PlayerManager.crossVersionDenyKick(player);
                    } else if (contains(cause, TimeoutException.class)
                            || contains(cause, LeaseTimeoutException.class)) {
                        Main.getInstance().getLogger().log(Level.WARNING,
                                "Join sync timed out for " + player.getName() + " (" + playerUuid + ")", cause);
                        PlayerManager.syncTimeoutKick(player);
                    } else {
                        Main.getInstance().getLogger().log(Level.WARNING,
                                "Join sync failed for " + player.getName() + " (" + playerUuid + ")", cause);
                        PlayerManager.syncFailedKick(player);
                    }
                });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        UUID localSession = this.localSessions.get(playerUuid);

        if (this.mySqlDataManager.isJoinSyncLocked(playerUuid)) {
            this.mySqlDataManager.clearJoinSyncPending(playerUuid, localSession);
            // Put cleanup directly behind the in-flight join. This prevents a
            // rapid reconnect from waiting behind a leaked local join lease.
            this.syncService.releaseWithoutSave(playerUuid).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    Main.getInstance().getLogger().log(Level.WARNING,
                            "Could not release lease after player left during join sync for " + playerUuid,
                            unwrap(throwable));
                }
            });
            forgetLocalSession(playerUuid, localSession);
            return;
        }
        if (localSession != null && this.skipQuitSessions.remove(playerUuid, localSession)) {
            this.syncService.releaseWithoutSave(playerUuid);
            forgetLocalSession(playerUuid, localSession);
            return;
        }
        if (!this.syncService.hasActiveLease(playerUuid)) {
            forgetLocalSession(playerUuid, localSession);
            return;
        }

        final PlayerSnapshot snapshot;
        try {
            // PlayerQuitEvent is in the player's valid context on Paper and Folia.
            snapshot = this.syncService.capture(player);
        } catch (SnapshotException exception) {
            Main.getInstance().getLogger().log(Level.SEVERE,
                    "Could not capture quit snapshot for " + playerUuid + "; the lease will expire",
                    exception);
            this.syncService.allowLeaseToExpire(playerUuid);
            forgetLocalSession(playerUuid, localSession);
            return;
        }

        this.syncService.saveAndRelease(snapshot).whenComplete((ignored, throwable) -> {
            forgetLocalSession(playerUuid, localSession);
            if (throwable != null) {
                Main.getInstance().getLogger().log(Level.SEVERE,
                        "Could not persist quit snapshot for " + playerUuid, unwrap(throwable));
            }
        });
    }

    private void startAutoSyncTask() {
        long interval = Math.max(20L, Main.modulesManager.syncTaskDelay);
        this.tasks.add(this.platformScheduler.runGlobalRepeating(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID playerUuid = player.getUniqueId();
                if (this.mySqlDataManager.isJoinSyncLocked(playerUuid)
                        || !this.syncService.hasActiveLease(playerUuid)) {
                    continue;
                }
                this.syncService.captureAndSaveAuto(player).whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        Main.getInstance().getLogger().log(Level.WARNING,
                                "Automatic player save failed for " + playerUuid, unwrap(throwable));
                    }
                });
            }
        }, interval, interval));
    }

    private void startPlayerIndexCleanupTask() {
        long interval = 10L * 60L * 20L;
        this.tasks.add(this.platformScheduler.runGlobalRepeating(() -> {
            long staleThreshold = System.currentTimeMillis() - Duration.ofHours(10L).toMillis();
            Main.mySqlConnectionHandler.getDatabaseExecutor().run(() ->
                    Main.mySqlConnectionHandler.getManager().inTransaction(connection -> {
                        markStaleIndexEntriesOffline(connection, staleThreshold);
                        return null;
                    })
            ).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    Main.getInstance().getLogger().log(Level.WARNING,
                            "Player index cleanup failed", unwrap(throwable));
                }
            });
        }, interval, interval));
    }

    private void markStaleIndexEntriesOffline(Connection connection, long staleThreshold) throws Exception {
        String sql = "UPDATE " + PooledSqlManager.quoted(Main.TABLE_NAME_PLAYER_INDEX)
                + " SET `online` = FALSE, `server_id` = '' WHERE `online` = TRUE"
                + " AND CAST(`timestamp` AS UNSIGNED) < ?";
        Main.mySqlConnectionHandler.getManager().executeUpdate(connection, sql, staleThreshold);
    }

    public CompletableFuture<Void> shutdown() {
        close();
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (UUID playerUuid : this.localSessions.keySet()) {
            CompletableFuture<Void> save;
            Player paperPlayer = Main.IS_FOLIA ? null : Bukkit.getPlayer(playerUuid);
            if (paperPlayer != null && this.syncService.hasActiveLease(playerUuid)) {
                try {
                    save = this.syncService.saveAndRelease(this.syncService.capture(paperPlayer));
                } catch (SnapshotException exception) {
                    save = this.syncService.saveLatestAndRelease(playerUuid);
                }
            } else {
                save = this.syncService.saveLatestAndRelease(playerUuid);
            }
            saves.add(save.exceptionally(throwable -> {
                Main.getInstance().getLogger().log(Level.SEVERE,
                        "Shutdown save/release failed for " + playerUuid, unwrap(throwable));
                return null;
            }));
        }
        return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
    }

    private void forgetLocalSession(UUID playerUuid, UUID localSession) {
        if (localSession != null) {
            this.localSessions.remove(playerUuid, localSession);
        }
    }

    @Override
    public void close() {
        for (PlatformScheduler.TaskHandle task : this.tasks) {
            task.cancel();
        }
        this.tasks.clear();
    }

    private static boolean contains(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
