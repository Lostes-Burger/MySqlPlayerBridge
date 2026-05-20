package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Inventory;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NBTSerializationException;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class InventoryDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public InventoryDataManager(){
        this.enabled = Main.modulesManager.syncInventory;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_INVENTORY)){
                new MySqlErrorHandler().logSyncError("Inventory", "table-exists", Main.TABLE_NAME_INVENTORY, null,
                        new RuntimeException("Inventory mysql table is missing!"), Map.of("table", Main.TABLE_NAME_INVENTORY), false);
                throw new RuntimeException("Inventory mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Inventory", "table-exists", Main.TABLE_NAME_INVENTORY, null,
                    e, Map.of("table", Main.TABLE_NAME_INVENTORY), false);
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runEntity(player, () -> {
                    if(!this.enabled) return;
                    String serializedInventory;
                    try {
                        if(Main.nbtSerializer == null){
                            throw new NBTSerializationException("nbtserializer not loaded on serialize", null);
                        }
                        serializedInventory = Main.nbtSerializer.serialize(player.getInventory().getContents());
                        if(Main.DEBUG){ System.out.println("Inv: "+serializedInventory); }
                    } catch (Exception e) {
                        new MySqlErrorHandler().logSyncError("Inventory", "serialize", Main.TABLE_NAME_INVENTORY, player,
                                e, Map.of("uuid", player.getUniqueId().toString()), false);
                        return;
                    }
                    String uuid = player.getUniqueId().toString();
                    BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, serializedInventory));
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

    public void saveManual(UUID uuid, String serializedInventory, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runAsync(() -> this.insertToMySql(uuid.toString(), serializedInventory));
            } else {
                Scheduler.runAsync(() -> {
                    this.insertToMySql(uuid.toString(), serializedInventory);
                }, Main.getInstance());
            }
        }else {
            this.insertToMySql(uuid.toString(), serializedInventory);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serializedInventory;
        try {
            if(Main.nbtSerializer == null){
                throw new NBTSerializationException("nbtserializer not loaded on serialize", null);
            }
            serializedInventory = Main.nbtSerializer.serialize(player.getInventory().getContents());
            if(Main.DEBUG){ System.out.println("Inv: "+serializedInventory); }

        } catch (Exception e) {
            new MySqlErrorHandler().logSyncError("Inventory", "serialize", Main.TABLE_NAME_INVENTORY, player,
                    e, Map.of("uuid", player.getUniqueId().toString()), false);
            return;
        }
        this.insertToMySql(player.getUniqueId().toString(), serializedInventory);
    }

    private void insertToMySql(String uuid, String serializedInventory){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_INVENTORY,
                    Map.of("uuid", uuid),
                    Map.of("inventory", serializedInventory)
            );
        } catch (MySqlError e) {
            MySqlErrorHandler errorHandler = new MySqlErrorHandler();
            String errorId = errorHandler.logSyncError("Inventory", "save", Main.TABLE_NAME_INVENTORY, null,
                    e, Map.of("uuid", uuid), false);
            HashMap<String, Object> data = new HashMap<>();
            data.put("uuid", uuid);
            data.put("inventory", serializedInventory);
            errorHandler.saveSyncData(errorId, "Inventory", "save", Main.TABLE_NAME_INVENTORY, null, data);
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_INVENTORY,
                        Map.of("uuid", playerUuid)
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Inventory", "load", Main.TABLE_NAME_INVENTORY, player,
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
                    player.getInventory().setContents(Main.nbtSerializer.deserialize(String.valueOf(entry.get("inventory"))));
                } catch (Exception e) {
                    MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                    String errorId = errorHandler.logSyncError("Inventory", "deserialize", Main.TABLE_NAME_INVENTORY, player,
                            e, Map.of("uuid", playerUuid), true);
                    HashMap<String, Object> data = new HashMap<>(entry);
                    data.put("uuid", playerUuid);
                    errorHandler.saveSyncData(errorId, "Inventory", "deserialize", Main.TABLE_NAME_INVENTORY, player, data);
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
