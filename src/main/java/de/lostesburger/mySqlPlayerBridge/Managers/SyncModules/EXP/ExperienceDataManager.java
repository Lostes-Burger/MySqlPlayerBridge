package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.EXP;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ExperienceDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public ExperienceDataManager(){
        this.enabled = Main.modulesManager.syncExp;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_EXP)){
                new MySqlErrorHandler().logSyncError("Experience", "table-exists", Main.TABLE_NAME_EXP, null,
                        new RuntimeException("Experience mysql table is missing!"), Map.of("table", Main.TABLE_NAME_EXP), false);
                throw new RuntimeException("Experience mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Experience", "table-exists", Main.TABLE_NAME_EXP, null,
                    e, Map.of("table", Main.TABLE_NAME_EXP), false);
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

    public void saveManual(UUID uuid, float exp, int explevel, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), exp, explevel);
            }, Main.getInstance());
        }else {
            this.insertToMySql(uuid.toString(), exp, explevel);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        this.insertToMySql(player.getUniqueId().toString(), player.getExp(), player.getLevel());
    }

    private void insertToMySql(String uuid, float exp, int explevel){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_EXP,
                    Map.of("uuid", uuid),
                    Map.of(
                            "exp", exp,
                            "exp_level", explevel
                    )
            );
        } catch (MySqlError e) {
            MySqlErrorHandler errorHandler = new MySqlErrorHandler();
            String errorId = errorHandler.logSyncError("Experience", "save", Main.TABLE_NAME_EXP, null,
                    e, Map.of("uuid", uuid), false);
            HashMap<String, Object> data = new HashMap<>();
            data.put("uuid", uuid);
            data.put("exp", exp);
            data.put("exp_level", explevel);
            errorHandler.saveSyncData(errorId, "Experience", "save", Main.TABLE_NAME_EXP, null, data);
        }
    }

    public void applyPlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_EXP,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Experience", "load", Main.TABLE_NAME_EXP, player,
                        e, Map.of("uuid", player.getUniqueId().toString()), true);
                return;
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;

            Scheduler.run(() -> {
                try {
                    player.setLevel((Integer) entry.get("exp_level"));
                    player.setExp((Float) entry.get("exp"));
                } catch (Exception e) {
                    MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                    String errorId = errorHandler.logSyncError("Experience", "apply", Main.TABLE_NAME_EXP, player,
                            e, Map.of("uuid", player.getUniqueId().toString()), true);
                    HashMap<String, Object> data = new HashMap<>(entry);
                    data.put("uuid", player.getUniqueId().toString());
                    errorHandler.saveSyncData(errorId, "Experience", "apply", Main.TABLE_NAME_EXP, player, data);
                }
            }, Main.getInstance());


        }, Main.getInstance());

    }
}
