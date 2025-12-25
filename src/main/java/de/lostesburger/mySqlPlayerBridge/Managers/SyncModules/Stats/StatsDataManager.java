package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Stats;


import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

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

    public void savePlayer(Player player, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.save(player);
            }, Main.getInstance());
        }else {
            this.save(player);
        }

    }

    public void saveManual(UUID uuid, String serializedStats, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), serializedStats);
            }, Main.getInstance());
        }else {
            this.insertToMySql(uuid.toString(), serializedStats);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serialized = SyncManager.statsSerializer.serialize(player);
        this.insertToMySql(player.getUniqueId().toString(), serialized);
    }

    private void insertToMySql(String uuid, String serializedStats){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_STATS,
                    Map.of("uuid", uuid),
                    Map.of("stats", serializedStats)
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_STATS,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;

            String serialized = (String) entry.get("stats");
            SyncManager.statsSerializer.deserialize(serialized, player, true);
        }, Main.getInstance());

    }
}
