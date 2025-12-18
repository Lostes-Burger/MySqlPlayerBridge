package de.lostesburger.mySqlPlayerBridge.Managers.SyncManagers.HotbarSelectionDataManager;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
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
                throw new RuntimeException("Selected hotbar slot mysql table is missing!");
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

    private void save(Player player){
        if(!this.enabled) return;
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_SELECTED_HOTBAR_SLOT,
                    Map.of("uuid", player.getUniqueId().toString()),
                    Map.of("slot", player.getInventory().getHeldItemSlot())
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_SELECTED_HOTBAR_SLOT,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;
            int slot = (Integer) entry.get("slot");

            Scheduler.run(() -> {
                this.setHotbarSlot(player, slot);
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
