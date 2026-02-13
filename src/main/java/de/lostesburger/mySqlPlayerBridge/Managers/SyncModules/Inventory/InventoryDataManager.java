package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Inventory;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NBTSerializationException;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
            Scheduler.runAsync(() -> {
                this.save(player);
            }, Main.getInstance());
        }else {
            this.save(player);
        }

    }

    public void saveManual(UUID uuid, String serializedInventory, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), serializedInventory);
            }, Main.getInstance());
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
            ItemStack[] storage = player.getInventory().getContents();
            int storageSize = Math.min(storage.length, 36);
            ItemStack[] withOffhand = new ItemStack[storageSize + 1];
            System.arraycopy(storage, 0, withOffhand, 0, storageSize);
            withOffhand[withOffhand.length - 1] = player.getInventory().getItemInOffHand();
            serializedInventory = Main.nbtSerializer.serialize(withOffhand);
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

    public void applyPlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_INVENTORY,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Inventory", "load", Main.TABLE_NAME_INVENTORY, player,
                        e, Map.of("uuid", player.getUniqueId().toString()), true);
                return;
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;

            Scheduler.run(() -> {
                try {
                    if(Main.nbtSerializer == null){
                        throw new NBTSerializationException("nbtserializer not loaded", null);
                    }
                    ItemStack[] data = Main.nbtSerializer.deserialize(String.valueOf(entry.get("inventory")));
                    ItemStack[] storage = new ItemStack[36];
                    int copyLen = data.length >= 37 ? 36 : Math.min(36, data.length);
                    System.arraycopy(data, 0, storage, 0, copyLen);
                    player.getInventory().setContents(storage);
                    if(data.length >= 37){
                        player.getInventory().setItemInOffHand(data[data.length - 1]);
                    }
                } catch (Exception e) {
                    MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                    String errorId = errorHandler.logSyncError("Inventory", "deserialize", Main.TABLE_NAME_INVENTORY, player,
                            e, Map.of("uuid", player.getUniqueId().toString()), true);
                    HashMap<String, Object> data = new HashMap<>(entry);
                    data.put("uuid", player.getUniqueId().toString());
                    errorHandler.saveSyncData(errorId, "Inventory", "deserialize", Main.TABLE_NAME_INVENTORY, player, data);
                }
            }, Main.getInstance());

        }, Main.getInstance());

    }
}
