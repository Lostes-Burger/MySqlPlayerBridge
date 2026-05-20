package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Advancement;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AdvancementDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public AdvancementDataManager() {
        this.enabled = Main.modulesManager.syncAdvancements;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_ADVANCEMENTS)){
                new MySqlErrorHandler().logSyncError("Advancement", "table-exists", Main.TABLE_NAME_ADVANCEMENTS, null,
                        new RuntimeException("Advancements mysql table is missing!"), Map.of("table", Main.TABLE_NAME_ADVANCEMENTS), false);
                throw new RuntimeException("Advancements mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Advancement", "table-exists", Main.TABLE_NAME_ADVANCEMENTS, null,
                    e, Map.of("table", Main.TABLE_NAME_ADVANCEMENTS), false);
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async){
        if(async) {
            if (Main.IS_FOLIA) {
                BridgeScheduler.runEntity(player, () -> {
                    if(!this.enabled) return;
                    String uuid = player.getUniqueId().toString();
                    String serialized = SyncManager.advancementSerializer.serialize(player);
                    BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, serialized));
                });
            } else {
                Scheduler.runAsync(() -> {
                    this.save(player);
                }, Main.getInstance());
            }
        } else {
            this.save(player);
        }
    }

    public void insertToMySql(String uuid, String serializedAdvancements, boolean async){
        if(async) {
            if (Main.IS_FOLIA) {
                BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, serializedAdvancements));
            } else {
                Scheduler.runAsync(() -> {
                    this.insertToMySql(uuid, serializedAdvancements);
                }, Main.getInstance());
            }
        } else {
            this.insertToMySql(uuid, serializedAdvancements);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serialized = SyncManager.advancementSerializer.serialize(player);
        this.insertToMySql(player.getUniqueId().toString(), serialized);
    }

    private void insertToMySql(String uuid, String serializedAdvancements){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_ADVANCEMENTS,
                    Map.of("uuid", uuid),
                    Map.of("advancements", serializedAdvancements)
            );
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Advancement", "save", Main.TABLE_NAME_ADVANCEMENTS, null,
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_ADVANCEMENTS,
                        Map.of("uuid", playerUuid)
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Advancement", "load", Main.TABLE_NAME_ADVANCEMENTS, player,
                        e, Map.of("uuid", playerUuid), true);
                future.completeExceptionally(e);
                return;
            }
            if(entry == null || entry.isEmpty()){
                future.complete(null);
                return;
            }

            String serialized = (String) entry.get("advancements");
            Runnable applyTask = () -> {
                try {
                    SyncManager.advancementSerializer.deserialize(serialized, player, true);
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
