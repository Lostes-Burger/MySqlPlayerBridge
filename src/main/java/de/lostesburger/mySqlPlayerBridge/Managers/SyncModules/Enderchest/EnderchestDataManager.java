package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Enderchest;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NBTSerializationException;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
            Scheduler.runAsync(() -> {
                this.save(player);
            }, Main.getInstance());
        }else {
            this.save(player);
        }

    }

    public void saveManual(UUID uuid, String serializedEnderchest, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), serializedEnderchest);
            }, Main.getInstance());
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

    public void applyPlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_ENDERCHEST,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Enderchest", "load", Main.TABLE_NAME_ENDERCHEST, player,
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
                    player.getEnderChest().setContents(Main.nbtSerializer.deserialize(String.valueOf(entry.get("enderchest"))));
                } catch (Exception e) {
                    MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                    String errorId = errorHandler.logSyncError("Enderchest", "deserialize", Main.TABLE_NAME_ENDERCHEST, player,
                            e, Map.of("uuid", player.getUniqueId().toString()), true);
                    HashMap<String, Object> data = new HashMap<>(entry);
                    data.put("uuid", player.getUniqueId().toString());
                    errorHandler.saveSyncData(errorId, "Enderchest", "deserialize", Main.TABLE_NAME_ENDERCHEST, player, data);
                }
            }, Main.getInstance());

        }, Main.getInstance());

    }
}
