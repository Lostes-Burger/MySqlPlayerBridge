package de.lostesburger.mySqlPlayerBridge.Managers.StatsDataManager;


import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;

import java.util.Map;

public class StatsDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public StatsDataManager() {
        this.enabled = Main.modulesManager.syncAdvancements;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_STATS)){
                throw new RuntimeException("Statistics mysql table is missing!");
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;
            String serialized = Main.statsSerializer.serialize(player);
            try {
                mySqlManager.setOrUpdateEntry(
                        Main.TABLE_NAME_STATS,
                        Map.of("uuid", player.getUniqueId().toString()),
                        Map.of("stats", serialized)
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
        }, Main.getInstance());
    }

    public void applyPlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_STATS,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;

            String serialized = (String) entry.get("stats");
            Main.statsSerializer.deserialize(serialized, player, true);
        }, Main.getInstance());

    }
}
