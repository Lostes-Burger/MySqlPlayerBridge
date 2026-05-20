package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Effect;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EffectDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public EffectDataManager(){
        this.enabled = Main.modulesManager.syncEffects;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_EFFECTS)){
                new MySqlErrorHandler().logSyncError("Effect", "table-exists", Main.TABLE_NAME_EFFECTS, null,
                        new RuntimeException("Potion Effect mysql table is missing!"), Map.of("table", Main.TABLE_NAME_EFFECTS), false);
                throw new RuntimeException("Potion Effect mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Effect", "table-exists", Main.TABLE_NAME_EFFECTS, null,
                    e, Map.of("table", Main.TABLE_NAME_EFFECTS), false);
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runEntity(player, () -> {
                    if(!this.enabled) return;
                    String uuid = player.getUniqueId().toString();
                    String serialized = SyncManager.potionSerializer.serialize(player);
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

    public void saveManual(UUID uuid, String serializedEffects, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runAsync(() -> this.insertToMySql(uuid.toString(), serializedEffects));
            } else {
                Scheduler.runAsync(() -> {
                    this.insertToMySql(uuid.toString(), serializedEffects);
                }, Main.getInstance());
            }
        }else {
            this.insertToMySql(uuid.toString(), serializedEffects);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serialized = SyncManager.potionSerializer.serialize(player);
        this.insertToMySql(player.getUniqueId().toString(), serialized);
    }

    private void insertToMySql(String uuid, String serializedEffects){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_EFFECTS,
                    Map.of("uuid", uuid),
                    Map.of("effects", serializedEffects)
            );
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Effect", "save", Main.TABLE_NAME_EFFECTS, null,
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_EFFECTS,
                        Map.of("uuid", playerUuid)
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Effect", "load", Main.TABLE_NAME_EFFECTS, player,
                        e, Map.of("uuid", playerUuid), true);
                future.completeExceptionally(e);
                return;
            }
            if(entry == null || entry.isEmpty()){
                future.complete(null);
                return;
            }
            String serialized = (String) entry.get("effects");
            List<PotionEffect> effects;
            try {
                effects = SyncManager.potionSerializer.deserialize(serialized);
            } catch (Exception e) {
                future.completeExceptionally(e);
                return;
            }

            Runnable applyTask = () -> {
                try {
                    player.addPotionEffects(effects);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    return;
                }
                future.complete(null);
            };

            if(!Main.IS_FOLIA){
                Scheduler.run(() -> {
                    applyTask.run();
                }, Main.getInstance());
            }else {
                boolean scheduled = BridgeScheduler.runEntity(player, applyTask, () -> future.complete(null));
                if (!scheduled) {
                    future.complete(null);
                }
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
