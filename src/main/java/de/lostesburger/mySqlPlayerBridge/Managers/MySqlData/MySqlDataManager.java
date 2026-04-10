package de.lostesburger.mySqlPlayerBridge.Managers.MySqlData;


import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


public class MySqlDataManager {
    public static final long SYNC_WAIT_TIMEOUT_MS = 5000L;
    public final MySqlManager mySqlManager;
    public final boolean DEBUG = false;
    private final Set<UUID> activeSyncPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> joinSyncPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Object> syncMonitors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> pendingApplyModules = new ConcurrentHashMap<>();

    public MySqlDataManager(MySqlManager manager){
        mySqlManager = manager;
    }

    public boolean hasData(Player player){
        UUID uuid = player.getUniqueId();
        try {
            if(Main.DEBUG){
                System.out.println("Checking if player has data! Player: "+player.getName());
            }
            return this.mySqlManager.entryExists(Main.TABLE_NAME_PLAYER_INDEX, Map.of("uuid", uuid.toString()));
        } catch (MySqlError e) {
            new MySqlErrorHandler().hasPlayerData(player);
            throw new RuntimeException(e);
        }
    }
    
    public void savePlayerData(Player player, boolean async){
        if(!this.hasData(player) && Main.config.getBoolean("settings.no-entry-protection")){ return; }

        SyncManager.inventoryDataManager.savePlayer(player, async);
        SyncManager.armorDataManager.savePlayer(player, async);
        SyncManager.enderchestDataManager.savePlayer(player, async);
        SyncManager.locationDataManager.savePlayer(player, async);
        SyncManager.experienceDataManager.savePlayer(player, async);
        SyncManager.healthDataManager.savePlayer(player, async);
        SyncManager.gamemodeDataManager.savePlayer(player, async);
        SyncManager.moneyDataManager.savePlayer(player, async);
        SyncManager.effectDataManager.savePlayer(player, async);
        SyncManager.advancementDataManager.savePlayer(player, async);
        SyncManager.statsDataManager.savePlayer(player, async);
        SyncManager.hotbarSlotSelectionDataManager.savePlayer(player, async);
        SyncManager.saturationDataManager.savePlayer(player, async);
    }


    public boolean checkDatabaseConnection(){ return Main.mySqlConnectionHandler.getMySQL().isConnectionAlive(); }

    public CompletableFuture<Void> applyDataToPlayer(Player player){
        if(Main.DEBUG){
            System.out.println("attempting to applyDataToPlayer player: "+player.getName());
        }

        UUID playerUuid = player.getUniqueId();
        Set<String> pending = ConcurrentHashMap.newKeySet();
        pending.addAll(List.of(
                "inventory", "armor", "enderchest", "location", "exp", "health", "gamemode",
                "money", "effects", "advancements", "stats", "hotbar", "saturation"
        ));
        this.pendingApplyModules.put(playerUuid, pending);

        List<CompletableFuture<Void>> futures = List.of(
                trackApplyFuture(playerUuid, "inventory", SyncManager.inventoryDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "armor", SyncManager.armorDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "enderchest", SyncManager.enderchestDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "location", SyncManager.locationDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "exp", SyncManager.experienceDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "health", SyncManager.healthDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "gamemode", SyncManager.gamemodeDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "money", SyncManager.moneyDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "effects", SyncManager.effectDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "advancements", SyncManager.advancementDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "stats", SyncManager.statsDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "hotbar", SyncManager.hotbarSlotSelectionDataManager.applyPlayer(player)),
                trackApplyFuture(playerUuid, "saturation", SyncManager.saturationDataManager.applyPlayer(player))
        );
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((result, throwable) -> this.pendingApplyModules.remove(playerUuid));
    }

    public void markJoinSyncPending(UUID playerUuid){
        this.joinSyncPlayers.add(playerUuid);
    }

    public void clearJoinSyncPending(UUID playerUuid){
        this.joinSyncPlayers.remove(playerUuid);
    }

    public boolean isJoinSyncLocked(UUID playerUuid){
        return this.joinSyncPlayers.contains(playerUuid);
    }

    public SyncAcquireResult acquireSyncLock(UUID playerUuid, boolean joinSync, long timeoutMs){
        Object monitor = this.syncMonitors.computeIfAbsent(playerUuid, key -> new Object());
        long waitUntil = System.currentTimeMillis() + Math.max(0L, timeoutMs);

        synchronized (monitor){
            while (this.activeSyncPlayers.contains(playerUuid)){
                long remainingMs = waitUntil - System.currentTimeMillis();
                if(remainingMs <= 0){
                    return SyncAcquireResult.TIMEOUT;
                }
                try {
                    monitor.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return SyncAcquireResult.INTERRUPTED;
                }
            }

            this.activeSyncPlayers.add(playerUuid);
            if(joinSync){
                this.joinSyncPlayers.add(playerUuid);
            }
            return SyncAcquireResult.ACQUIRED;
        }
    }

    public void releaseSyncLock(UUID playerUuid){
        Object monitor = this.syncMonitors.computeIfAbsent(playerUuid, key -> new Object());
        synchronized (monitor){
            this.activeSyncPlayers.remove(playerUuid);
            this.joinSyncPlayers.remove(playerUuid);
            monitor.notifyAll();
        }
    }

    public SyncExecutionResult runPreEditSync(Player player, long timeoutMs){
        SyncAcquireResult acquireResult = this.acquireSyncLock(player.getUniqueId(), false, timeoutMs);
        if(acquireResult == SyncAcquireResult.TIMEOUT){
            return SyncExecutionResult.TIMEOUT;
        }
        if(acquireResult == SyncAcquireResult.INTERRUPTED){
            return SyncExecutionResult.FAILED;
        }

        try {
            this.savePlayerData(player, false);
            return SyncExecutionResult.SUCCESS;
        } catch (RuntimeException e) {
            return SyncExecutionResult.FAILED;
        } finally {
            this.releaseSyncLock(player.getUniqueId());
        }
    }

    public Set<String> getPendingApplyModules(UUID playerUuid){
        Set<String> pending = this.pendingApplyModules.get(playerUuid);
        if(pending == null){
            return Set.of();
        }
        return new HashSet<>(pending);
    }

    private CompletableFuture<Void> trackApplyFuture(UUID playerUuid, String moduleName, CompletableFuture<Void> future){
        return future.whenComplete((result, throwable) -> {
            Set<String> pending = this.pendingApplyModules.get(playerUuid);
            if(pending != null){
                pending.remove(moduleName);
            }
        });
    }

    public enum SyncAcquireResult {
        ACQUIRED,
        TIMEOUT,
        INTERRUPTED
    }

    public enum SyncExecutionResult {
        SUCCESS,
        TIMEOUT,
        FAILED
    }

    public void saveAllOnlinePlayers(){
        for (Player player : Bukkit.getOnlinePlayers()){
            this.savePlayerData(player, false);
        }
    }

    public void saveAllOnlinePlayersAsync(){
        for (Player player : Bukkit.getOnlinePlayers()){
            Scheduler.runAsync(() -> { this.savePlayerData(player, true);}, Main.getInstance());
        }
    }
}
