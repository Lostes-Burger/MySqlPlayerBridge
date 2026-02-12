package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Hotbar;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;
import java.util.Map;

public class HotbarSlotSelectionDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public HotbarSlotSelectionDataManager(){
        this.enabled = Main.modulesManager.syncSelectedHotbarSlot;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_SELECTED_HOTBAR_SLOT)){
                new MySqlErrorHandler().logSyncError("HotbarSlot", "table-exists", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, null,
                        new RuntimeException("Selected hotbar slot mysql table is missing!"), Map.of("table", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT), false);
                throw new RuntimeException("Selected hotbar slot mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("HotbarSlot", "table-exists", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, null,
                    e, Map.of("table", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT), false);
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

    public void saveManual(String uuid, int slot, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid, slot);
            }, Main.getInstance());
        }else {
            this.insertToMySql(uuid, slot);
        }
    }


    private void save(Player player){
        if(!this.enabled) return;
        this.insertToMySql(player.getUniqueId().toString(), player.getInventory().getHeldItemSlot());
    }

    private void insertToMySql(String uuid, int slot){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_SELECTED_HOTBAR_SLOT,
                    Map.of("uuid", uuid),
                    Map.of("slot", slot)
            );
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("HotbarSlot", "save", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, null,
                    e, Map.of("uuid", uuid), false);
        }
    }

    public void applyPlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_SELECTED_HOTBAR_SLOT,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("HotbarSlot", "load", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, player,
                        e, Map.of("uuid", player.getUniqueId().toString()), true);
                return;
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;
            int slot = (Integer) entry.get("slot");

            Scheduler.run(() -> {
                try {
                    this.setHotbarSlot(player, slot);
                } catch (RuntimeException e) {
                    new MySqlErrorHandler().logSyncError("HotbarSlot", "apply", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, player,
                            e, Map.of("uuid", player.getUniqueId().toString(), "slot", slot), true);
                }
            }, Main.getInstance());

        }, Main.getInstance());

    }

    private void setHotbarSlot(Player player, int slot) {
        if (slot >= 0 && slot < 9) {
            player.getInventory().setHeldItemSlot(slot);
        } else {
            throw new RuntimeException("Invalid hotbar slot");
        }
    }

}
