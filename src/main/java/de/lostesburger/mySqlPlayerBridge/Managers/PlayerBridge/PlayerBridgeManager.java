package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.MySqlData.MySqlDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.NoEntryProtection.NoEntryProtection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
public class PlayerBridgeManager implements Listener {
    private static final long JOIN_SYNC_TIMEOUT_MS = MySqlDataManager.SYNC_WAIT_TIMEOUT_MS;
    private final MySqlDataManager mySqlDataManager;

    public PlayerBridgeManager(){
        this.mySqlDataManager = Main.mySqlConnectionHandler.getMySqlDataManager();
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
        new JoinSyncEventBlocker(this.mySqlDataManager);
        this.startAutoSyncTask();
        this.startPlayerIndexCleanupTask();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event){
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        PlayerManager.updatePlayerIndex(player, true);
        this.mySqlDataManager.markJoinSyncPending(playerUuid);

        Scheduler.runAsync(() -> {
            MySqlDataManager.SyncAcquireResult acquireResult = this.mySqlDataManager.acquireSyncLock(playerUuid, true, JOIN_SYNC_TIMEOUT_MS);
            if(acquireResult != MySqlDataManager.SyncAcquireResult.ACQUIRED){
                this.mySqlDataManager.clearJoinSyncPending(playerUuid);
                this.handleJoinSyncAcquireFailure(player, acquireResult);
                return;
            }

            boolean asyncJoinSync = false;
            try {
                if(this.mySqlDataManager.hasData(player)){
                    asyncJoinSync = true;
                    CompletableFuture<Void> syncFuture = this.mySqlDataManager.applyDataToPlayer(player);
                    syncFuture
                            .orTimeout(JOIN_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .whenComplete((ignored, throwable) -> {
                                try {
                                    if(throwable == null){
                                        PlayerManager.sendDataLoadedMessage(player);
                                        return;
                                    }
                                    if(this.isTimeoutThrowable(throwable)){
                                        Main.getInstance().getLogger().log(Level.WARNING, "Join sync timeout for player " + player.getName() + " (" + playerUuid + ") pending modules: " + this.mySqlDataManager.getPendingApplyModules(playerUuid), throwable);
                                        PlayerManager.syncTimeoutKick(player);
                                        return;
                                    }
                                    Main.getInstance().getLogger().log(Level.WARNING, "Join sync failed for player " + player.getName() + " (" + playerUuid + ")", throwable);
                                    PlayerManager.syncFailedKick(player);
                                } finally {
                                    this.mySqlDataManager.releaseSyncLock(playerUuid);
                                }
                            });
                    return;
                }

                if(NoEntryProtection.isTriggered(player)){
                    return;
                }
                PlayerManager.registerPlayer(player);
                this.mySqlDataManager.savePlayerData(player, false);
                PlayerManager.sendCreatedDataMessage(player);
            } catch (RuntimeException e) {
                Main.getInstance().getLogger().log(Level.WARNING, "Join sync failed for player " + player.getName() + " (" + playerUuid + ")", e);
                PlayerManager.syncFailedKick(player);
            } finally {
                if(!asyncJoinSync){
                    this.mySqlDataManager.releaseSyncLock(playerUuid);
                }
            }
        }, Main.getInstance());
    }

    private boolean isTimeoutThrowable(Throwable throwable){
        Throwable current = throwable;
        while (current != null){
            if(current instanceof TimeoutException){
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event){
        Player player = event.getPlayer();
        PlayerManager.updatePlayerIndex(player, false);
        this.mySqlDataManager.savePlayerData(player, false);
    }

    private void handleJoinSyncAcquireFailure(Player player, MySqlDataManager.SyncAcquireResult acquireResult){
        if(acquireResult == MySqlDataManager.SyncAcquireResult.TIMEOUT){
            Main.getInstance().getLogger().warning("Join sync lock wait timeout for player " + player.getName() + " (" + player.getUniqueId() + ")");
            PlayerManager.syncTimeoutKick(player);
            return;
        }
        Main.getInstance().getLogger().warning("Join sync lock wait interrupted for player " + player.getName() + " (" + player.getUniqueId() + ")");
        PlayerManager.syncFailedKick(player);
    }

    private void startAutoSyncTask(){
        assert this.mySqlDataManager != null;
        Scheduler.Task task = Scheduler.runTimerAsync(() -> {

            Main.mySqlConnectionHandler.getMySqlDataManager().saveAllOnlinePlayersAsync();

        }, Main.modulesManager.syncTaskDelay, Main.modulesManager.syncTaskDelay, Main.getInstance());
        Main.schedulers.add(task);
    }

    private void startPlayerIndexCleanupTask(){
        long interval = 10L * 60L * 20L;
        Scheduler.Task task = Scheduler.runTimerAsync(() -> {
            Scheduler.run(() -> {
                List<PlayerSnapshot> onlinePlayers = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()){
                    onlinePlayers.add(new PlayerSnapshot(player.getUniqueId(), player.getName()));
                }
                Scheduler.runAsync(() -> runPlayerIndexCleanup(onlinePlayers), Main.getInstance());
            }, Main.getInstance());
        }, interval, interval, Main.getInstance());
        Main.schedulers.add(task);
    }

    private void runPlayerIndexCleanup(List<PlayerSnapshot> onlinePlayers){
        long now = System.currentTimeMillis();
        long staleThreshold = now - (10L * 60L * 60L * 1000L);

        try {
            MySqlManager manager = Main.mySqlConnectionHandler.getManager();

            for (PlayerSnapshot snapshot : onlinePlayers){
                manager.setOrUpdateEntry(
                        Main.TABLE_NAME_PLAYER_INDEX,
                        Map.of("uuid", snapshot.uuid.toString()),
                        Map.of(
                                "player_name", snapshot.name,
                                "timestamp", String.valueOf(now),
                                "online", true,
                                "server_id", ""
                        )
                );
            }

            List<Map<String, Object>> entries = manager.getAllEntries(Main.TABLE_NAME_PLAYER_INDEX);
            for (Map<String, Object> entry : entries){
                Object onlineValue = entry.get("online");
                boolean isOnline = onlineValue instanceof Boolean && (Boolean) onlineValue;
                if(!isOnline){
                    continue;
                }
                long timestamp = parseTimestamp(entry.get("timestamp"));
                if(timestamp > 0 && timestamp < staleThreshold){
                    manager.setOrUpdateEntry(
                            Main.TABLE_NAME_PLAYER_INDEX,
                            Map.of("uuid", String.valueOf(entry.get("uuid"))),
                            Map.of(
                                    "online", false,
                                    "server_id", ""
                            )
                    );
                }
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }

    private long parseTimestamp(Object timestampValue){
        if(timestampValue == null){
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(timestampValue));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static class PlayerSnapshot {
        private final UUID uuid;
        private final String name;

        private PlayerSnapshot(UUID uuid, String name){
            this.uuid = uuid;
            this.name = name;
        }
    }


}
