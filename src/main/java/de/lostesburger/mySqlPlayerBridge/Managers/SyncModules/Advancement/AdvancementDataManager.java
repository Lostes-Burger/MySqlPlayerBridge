package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Advancement;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
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
                new MySqlErrorHandler().logSyncError("Advancement", "table-exists", Main.TABLE_NAME_ADVANCEMENTS, null,
                        new RuntimeException("Advancements mysql table is missing!"), Map.of("table", Main.TABLE_NAME_ADVANCEMENTS), false);
                throw new RuntimeException("Advancements mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Advancement", "table-exists", Main.TABLE_NAME_ADVANCEMENTS, null,
                    e, Map.of("table", Main.TABLE_NAME_ADVANCEMENTS), false);
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

    public void insertToMySql(String uuid, String serializedAdvancements, boolean async){
        if(async) {
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid, serializedAdvancements);
            }, Main.getInstance());
        } else {
            this.insertToMySql(uuid, serializedAdvancements);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serialized = SyncManager.advancementSerializer.serialize(player);
        this.insertToMySql(player.getUniqueId().toString(), serialized);
    }

    private void insertToMySql(String uuid, String serializedAdvancements){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_ADVANCEMENTS,
                    Map.of("uuid", uuid),
                    Map.of("advancements", serializedAdvancements)
            );
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Advancement", "save", Main.TABLE_NAME_ADVANCEMENTS, null,
                    e, Map.of("uuid", uuid), false);
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
                new MySqlErrorHandler().logSyncError("Advancement", "load", Main.TABLE_NAME_ADVANCEMENTS, player,
                        e, Map.of("uuid", player.getUniqueId().toString()), true);
                return;
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;

            String serialized = (String) entry.get("advancements");
            SyncManager.advancementSerializer.deserialize(serialized, player, true);
        }, Main.getInstance());

    }
}
