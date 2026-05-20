package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Stats;


import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class StatsDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public StatsDataManager() {
        this.enabled = Main.modulesManager.syncAdvancements;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_STATS)){
                new MySqlErrorHandler().logSyncError("Stats", "table-exists", Main.TABLE_NAME_STATS, null,
                        new RuntimeException("Statistics mysql table is missing!"), Map.of("table", Main.TABLE_NAME_STATS), false);
                throw new RuntimeException("Statistics mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Stats", "table-exists", Main.TABLE_NAME_STATS, null,
                    e, Map.of("table", Main.TABLE_NAME_STATS), false);
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runEntity(player, () -> {
                    if(!this.enabled) return;
                    String uuid = player.getUniqueId().toString();
                    String serialized = SyncManager.statsSerializer.serialize(player);
                    BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, serialized));
                });
            } else {
                Scheduler.runAsync(() -> {
                    this.save(player);
                }, Main.getInstance());
            }
        }else {
            this.save(player);
        }

    }

    public void saveManual(UUID uuid, String serializedStats, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runAsync(() -> this.insertToMySql(uuid.toString(), serializedStats));
            } else {
                Scheduler.runAsync(() -> {
                    this.insertToMySql(uuid.toString(), serializedStats);
                }, Main.getInstance());
            }
        }else {
            this.insertToMySql(uuid.toString(), serializedStats);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serialized = SyncManager.statsSerializer.serialize(player);
        this.insertToMySql(player.getUniqueId().toString(), serialized);
    }

    private void insertToMySql(String uuid, String serializedStats){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_STATS,
                    Map.of("uuid", uuid),
                    Map.of("stats", serializedStats)
            );
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Stats", "save", Main.TABLE_NAME_STATS, null,
                    e, Map.of("uuid", uuid), false);
        }
    }

    public CompletableFuture<Void> applyPlayer(Player player){
        CompletableFuture<Void> future = new CompletableFuture<>();
        String playerUuid = player.getUniqueId().toString();
        Runnable loadTask = () -> {
            if(!this.enabled){
                future.complete(null);
                return;
            }

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_STATS,
                        Map.of("uuid", playerUuid)
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Stats", "load", Main.TABLE_NAME_STATS, player,
                        e, Map.of("uuid", playerUuid), true);
                future.completeExceptionally(e);
                return;
            }
            if(entry == null || entry.isEmpty()){
                future.complete(null);
                return;
            }

            String serialized = (String) entry.get("stats");
            Runnable applyTask = () -> {
                try {
                    SyncManager.statsSerializer.deserialize(serialized, player, true);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    return;
                }
                future.complete(null);
            };

            if (Main.IS_FOLIA) {
                boolean scheduled = BridgeScheduler.runEntity(player, applyTask, () -> future.complete(null));
                if (!scheduled) {
                    future.complete(null);
                }
            } else {
                Scheduler.run(applyTask, Main.getInstance());
            }
        };
        if (Main.IS_FOLIA) {
            BridgeScheduler.runAsync(loadTask);
        } else {
            Scheduler.runAsync(loadTask, Main.getInstance());
        }
        return future;
    }
}
