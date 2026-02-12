package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Armor;

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

public class ArmorDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public ArmorDataManager(){
        this.enabled = Main.modulesManager.syncArmorSlots;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_ARMOR)){
                new MySqlErrorHandler().logSyncError("Armor", "table-exists", Main.TABLE_NAME_ARMOR, null,
                        new RuntimeException("Armor mysql table is missing!"), Map.of("table", Main.TABLE_NAME_ARMOR), false);
                throw new RuntimeException("Armor mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Armor", "table-exists", Main.TABLE_NAME_ARMOR, null,
                    e, Map.of("table", Main.TABLE_NAME_ARMOR), false);
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

    public void saveManual(UUID uuid, String serializedArmor, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), serializedArmor);
            }, Main.getInstance());
        }else {
            this.insertToMySql(uuid.toString(), serializedArmor);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serializedArmor;
        try {
            if(Main.nbtSerializer == null){
                throw new NBTSerializationException("nbtserializer not loaded on serialize", null);
            }
            serializedArmor = Main.nbtSerializer.serialize(new ItemStack[]{
                    player.getInventory().getBoots(),
                    player.getInventory().getLeggings(),
                    player.getInventory().getChestplate(),
                    player.getInventory().getHelmet()
            });
            if(Main.DEBUG){ System.out.println("Armor: "+serializedArmor); }

        } catch (Exception e) {
            new MySqlErrorHandler().logSyncError("Armor", "serialize", Main.TABLE_NAME_ARMOR, player,
                    e, Map.of("uuid", player.getUniqueId().toString()), false);
            return;
        }

        this.insertToMySql(player.getUniqueId().toString(), serializedArmor);
    }

    private void insertToMySql(String uuid, String serializedArmor){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_ARMOR,
                    Map.of("uuid", uuid),
                    Map.of("armor", serializedArmor)
            );
        } catch (MySqlError e) {
            MySqlErrorHandler errorHandler = new MySqlErrorHandler();
            String errorId = errorHandler.logSyncError("Armor", "save", Main.TABLE_NAME_ARMOR, null,
                    e, Map.of("uuid", uuid), false);
            HashMap<String, Object> data = new HashMap<>();
            data.put("uuid", uuid);
            data.put("armor", serializedArmor);
            errorHandler.saveSyncData(errorId, "Armor", "save", Main.TABLE_NAME_ARMOR, null, data);
        }
    }

    public void applyPlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_ARMOR,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Armor", "load", Main.TABLE_NAME_ARMOR, player,
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
                    player.getInventory().setArmorContents(Main.nbtSerializer.deserialize(String.valueOf(entry.get("armor"))));
                } catch (Exception e) {
                    MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                    String errorId = errorHandler.logSyncError("Armor", "deserialize", Main.TABLE_NAME_ARMOR, player,
                            e, Map.of("uuid", player.getUniqueId().toString()), true);
                    HashMap<String, Object> data = new HashMap<>(entry);
                    data.put("uuid", player.getUniqueId().toString());
                    errorHandler.saveSyncData(errorId, "Armor", "deserialize", Main.TABLE_NAME_ARMOR, player, data);
                }
            }, Main.getInstance());

        }, Main.getInstance());

    }
}
