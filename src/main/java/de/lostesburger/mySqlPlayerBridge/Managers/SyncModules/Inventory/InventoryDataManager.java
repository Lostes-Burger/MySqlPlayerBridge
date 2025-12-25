package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Inventory;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NBTSerializationException;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.entity.Player;

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
                throw new RuntimeException("Inventory mysql table is missing!");
            }
        } catch (MySqlError e) {
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
            serializedInventory = Main.nbtSerializer.serialize(player.getInventory().getContents());
            if(Main.DEBUG){ System.out.println("Inv: "+serializedInventory); }

        } catch (Exception e) {
            throw new RuntimeException(e);
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
            throw new RuntimeException(e);
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
                throw new RuntimeException(e);
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;

            Scheduler.run(() -> {
                try {
                    if(Main.nbtSerializer == null){
                        throw new NBTSerializationException("nbtserializer not loaded", null);
                    }
                    player.getInventory().setContents(Main.nbtSerializer.deserialize(String.valueOf(entry.get("inventory"))));
                } catch (Exception e) {
                    player.kickPlayer(Chat.getMessage("sync-failed"));
                    throw new RuntimeException(e);
                }
            }, Main.getInstance());

        }, Main.getInstance());

    }
}
