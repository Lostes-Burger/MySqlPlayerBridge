package de.lostesburger.mySqlPlayerBridge.Managers.SyncManagers.SaveStutsDataManager;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.CC;
import org.bukkit.entity.Player;

import java.util.Map;

public class SaveStutsDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public SaveStutsDataManager(){
        this.enabled = true;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_SAVE_STATUS)){
                throw new RuntimeException("Saturation mysql table is missing!");
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }

    public String getStatus(Player player){
        if(!this.enabled) return null;
        try {
            Map<String, Object> entry;
            entry = mySqlManager.getEntry(Main.TABLE_SAVE_STATUS,
                    Map.of("uuid", player.getUniqueId().toString())
            );

            if(entry == null){
                savePlayer(player, "wait", false);
                return "OK";
            }

            return (String) entry.get("status");
        } catch (MySqlError e) {
            player.kickPlayer(CC.colorize("&c 遇到问题请联系管理员!"));
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, String status, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.save(player, status);
            }, Main.getInstance());
        }else {
            this.save(player, status);
        }


    }

    private void save(Player player, String status){
        if(!this.enabled) return;
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_SAVE_STATUS,
                    Map.of("uuid", player.getUniqueId().toString()),
                    Map.of("status", status)
            );
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }



}
