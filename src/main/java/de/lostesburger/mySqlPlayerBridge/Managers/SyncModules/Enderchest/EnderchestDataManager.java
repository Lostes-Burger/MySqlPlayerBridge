package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Enderchest;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Exceptions.CrossVersionItemSyncException;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NBTSerializationException;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EnderchestDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public EnderchestDataManager(){
        this.enabled = Main.modulesManager.syncEnderChest;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_ENDERCHEST)){
                new MySqlErrorHandler().logSyncError("Enderchest", "table-exists", Main.TABLE_NAME_ENDERCHEST, null,
                        new RuntimeException("Enderchest mysql table is missing!"), Map.of("table", Main.TABLE_NAME_ENDERCHEST), false);
                throw new RuntimeException("Enderchest mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Enderchest", "table-exists", Main.TABLE_NAME_ENDERCHEST, null,
                    e, Map.of("table", Main.TABLE_NAME_ENDERCHEST), false);
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runEntity(player, () -> {
                    if(!this.enabled) return;
                    String serializedEnderchest;
                    try {
                        if(Main.nbtSerializer == null){
                            throw new NBTSerializationException("nbtserializer not loaded on serialize", null);
                        }
                        serializedEnderchest = Main.nbtSerializer.serialize(player.getEnderChest().getContents());
                        if(Main.DEBUG){ System.out.println("EC: "+serializedEnderchest); }
                    } catch (Exception e) {
                        new MySqlErrorHandler().logSyncError("Enderchest", "serialize", Main.TABLE_NAME_ENDERCHEST, player,
                                e, Map.of("uuid", player.getUniqueId().toString()), false);
                        return;
                    }
                    String uuid = player.getUniqueId().toString();
                    BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, serializedEnderchest));
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

    public void saveManual(UUID uuid, String serializedEnderchest, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runAsync(() -> this.insertToMySql(uuid.toString(), serializedEnderchest));
            } else {
                Scheduler.runAsync(() -> {
                    this.insertToMySql(uuid.toString(), serializedEnderchest);
                }, Main.getInstance());
            }
        }else {
            this.insertToMySql(uuid.toString(), serializedEnderchest);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serializedEnderchest;
        try {
            if(Main.nbtSerializer == null){
                throw new NBTSerializationException("nbtserializer not loaded on serialize", null);
            }
            serializedEnderchest = Main.nbtSerializer.serialize(player.getEnderChest().getContents());
            if(Main.DEBUG){ System.out.println("EC: "+serializedEnderchest); }

        } catch (Exception e) {
            new MySqlErrorHandler().logSyncError("Enderchest", "serialize", Main.TABLE_NAME_ENDERCHEST, player,
                    e, Map.of("uuid", player.getUniqueId().toString()), false);
            return;
        }

        this.insertToMySql(player.getUniqueId().toString(), serializedEnderchest);
    }

    private void insertToMySql(String uuid, String serializedEnderchest){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_ENDERCHEST,
                    Map.of("uuid", uuid),
                    Map.of("enderchest", serializedEnderchest)
            );
        } catch (MySqlError e) {
            MySqlErrorHandler errorHandler = new MySqlErrorHandler();
            String errorId = errorHandler.logSyncError("Enderchest", "save", Main.TABLE_NAME_ENDERCHEST, null,
                    e, Map.of("uuid", uuid), false);
            HashMap<String, Object> data = new HashMap<>();
            data.put("uuid", uuid);
            data.put("enderchest", serializedEnderchest);
            errorHandler.saveSyncData(errorId, "Enderchest", "save", Main.TABLE_NAME_ENDERCHEST, null, data);
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_ENDERCHEST,
                        Map.of("uuid", playerUuid)
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Enderchest", "load", Main.TABLE_NAME_ENDERCHEST, player,
                        e, Map.of("uuid", playerUuid), true);
                future.completeExceptionally(e);
                return;
            }
            if(entry == null || entry.isEmpty()){
                future.complete(null);
                return;
            }

            Runnable applyTask = () -> {
                try {
                    if(Main.nbtSerializer == null){
                        throw new NBTSerializationException("nbtserializer not loaded", null);
                    }
                    player.getEnderChest().setContents(Main.nbtSerializer.deserialize(String.valueOf(entry.get("enderchest"))));
                } catch (Exception e) {
                    MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                    String errorId = errorHandler.logSyncError("Enderchest", "deserialize", Main.TABLE_NAME_ENDERCHEST, player,
                            e, Map.of("uuid", playerUuid), false);
                    HashMap<String, Object> data = new HashMap<>(entry);
                    data.put("uuid", playerUuid);
                    errorHandler.saveSyncData(errorId, "Enderchest", "deserialize", Main.TABLE_NAME_ENDERCHEST, player, data);
                    if(Main.modulesManager.crossVersionDenyJoinOnItemSyncError){
                        future.completeExceptionally(new CrossVersionItemSyncException("Enderchest contains items not supported by this server version", e));
                    }else {
                        future.completeExceptionally(e);
                    }
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
