package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Saturation;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;

import java.util.Map;

public class SaturationDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public SaturationDataManager(){
        this.enabled = Main.modulesManager.syncSaturation;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_SATURATION)){
                throw new RuntimeException("Saturation mysql table is missing!");
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

    public void saveManual(String uuid, float saturation, int foodlevel, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid, saturation, foodlevel);
            }, Main.getInstance());
        }else {
            this.insertToMySql(uuid, saturation, foodlevel);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        this.insertToMySql(player.getUniqueId().toString(), player.getSaturation(), player.getFoodLevel());
    }

    private void insertToMySql(String uuid, float saturation, int foodlevel){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_SATURATION,
                    Map.of("uuid", uuid),
                    Map.of(
                            "saturation", saturation,
                            "food_level", foodlevel
                    )
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_SATURATION,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;
            float saturation = (Float) entry.get("saturation");
            int food_level = (Integer) entry.get("food_level");

            Scheduler.run(() -> {
                player.setSaturation(saturation);
                player.setFoodLevel(food_level);
            }, Main.getInstance());

        }, Main.getInstance());

    }
}
