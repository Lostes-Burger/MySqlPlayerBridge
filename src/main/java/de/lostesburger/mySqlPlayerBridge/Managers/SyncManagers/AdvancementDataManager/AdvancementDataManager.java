package de.lostesburger.mySqlPlayerBridge.Managers.SyncManagers.AdvancementDataManager;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;

import java.util.Map;

public class AdvancementDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public AdvancementDataManager() {
        this.enabled = Main.modulesManager.syncAdvancements;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_ADVANCEMENTS)){
                throw new RuntimeException("Advancements mysql table is missing!");
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async){
        if(async) {
            Scheduler.runAsync(() -> {
                this.save(player);
            }, Main.getInstance());
        } else {
            this.save(player);
        }

    }

    private void save(Player player){
        if(!this.enabled) return;
        String serialized = Main.advancementSerializer.serialize(player);
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_ADVANCEMENTS,
                    Map.of("uuid", player.getUniqueId().toString()),
                    Map.of("advancements", serialized)
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_ADVANCEMENTS,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;

            String serialized = (String) entry.get("advancements");
            Main.advancementSerializer.deserialize(serialized, player, true);
        }, Main.getInstance());

    }
}
