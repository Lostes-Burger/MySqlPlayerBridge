package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Hotbar;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.entity.Player;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
            if (Main.IS_FOLIA) {
                BridgeScheduler.runEntity(player, () -> {
                    if(!this.enabled) return;
                    String uuid = player.getUniqueId().toString();
                    int slot = player.getInventory().getHeldItemSlot();
                    BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, slot));
                });
            } else {
                Scheduler.runAsync(() -> {
                    this.save(player);
                }, Main.getInstance());
            }
        }else {
            this.save(player);
        }
    }

    public void saveManual(String uuid, int slot, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, slot));
            } else {
                Scheduler.runAsync(() -> {
                    this.insertToMySql(uuid, slot);
                }, Main.getInstance());
            }
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

    public CompletableFuture<Void> applyPlayer(Player player){
        CompletableFuture<Void> future = new CompletableFuture<>();
        String playerUuid = player.getUniqueId().toString();
        Runnable loadTask = () -> {
            if(!this.enabled){
                future.complete(null);
                return;
            }

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_SELECTED_HOTBAR_SLOT,
                        Map.of("uuid", playerUuid)
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("HotbarSlot", "load", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, player,
                        e, Map.of("uuid", playerUuid), true);
                future.completeExceptionally(e);
                return;
            }
            if(entry == null || entry.isEmpty()){
                future.complete(null);
                return;
            }
            int slot = (Integer) entry.get("slot");

            Runnable applyTask = () -> {
                try {
                    this.setHotbarSlot(player, slot);
                } catch (RuntimeException e) {
                    new MySqlErrorHandler().logSyncError("HotbarSlot", "apply", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, player,
                            e, Map.of("uuid", playerUuid, "slot", slot), true);
                    future.completeExceptionally(e);
                    return;
                }
                future.complete(null);
            };

            if (Main.IS_FOLIA) {
                boolean scheduled = BridgeScheduler.runEntity(player, applyTask, () -> future.complete(null));
                if (!scheduled) {
                    future.complete(null);
                }
            } else {
                Scheduler.run(applyTask, Main.getInstance());
            }

        };
        if (Main.IS_FOLIA) {
            BridgeScheduler.runAsync(loadTask);
        } else {
            Scheduler.runAsync(loadTask, Main.getInstance());
        }
        return future;
    }

    private void setHotbarSlot(Player player, int slot) {
        if (slot >= 0 && slot < 9) {
            player.getInventory().setHeldItemSlot(slot);
        } else {
            throw new RuntimeException("Invalid hotbar slot");
        }
    }

}
