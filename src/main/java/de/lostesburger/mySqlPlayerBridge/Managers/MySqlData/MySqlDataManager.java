package de.lostesburger.mySqlPlayerBridge.Managers.MySqlData;


import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;


public class MySqlDataManager {
    public final MySqlManager mySqlManager;
    public final boolean DEBUG = false;

    public MySqlDataManager(MySqlManager manager){
        mySqlManager = manager;
    }

    public boolean hasData(Player player){
        UUID uuid = player.getUniqueId();
        try {
            if(Main.DEBUG){
                System.out.println("Checking if player has data! Player: "+player.getName());
            }
            return this.mySqlManager.entryExists(Main.TABLE_NAME_PLAYER_INDEX, Map.of("uuid", uuid.toString()));
        } catch (MySqlError e) {
            new MySqlErrorHandler().hasPlayerData(player);
            throw new RuntimeException(e);
        }
    }
    
    public void savePlayerData(Player player, boolean async){
        if(!this.hasData(player) && Main.config.getBoolean("settings.no-entry-protection")){ return; }

        SyncManager.inventoryDataManager.savePlayer(player, async);
        SyncManager.armorDataManager.savePlayer(player, async);
        SyncManager.enderchestDataManager.savePlayer(player, async);
        SyncManager.locationDataManager.savePlayer(player, async);
        SyncManager.experienceDataManager.savePlayer(player, async);
        SyncManager.healthDataManager.savePlayer(player, async);
        SyncManager.gamemodeDataManager.savePlayer(player, async);
        SyncManager.moneyDataManager.savePlayer(player, async);
        SyncManager.effectDataManager.savePlayer(player, async);
        SyncManager.advancementDataManager.savePlayer(player, async);
        SyncManager.statsDataManager.savePlayer(player, async);
        SyncManager.hotbarSlotSelectionDataManager.savePlayer(player, async);
        SyncManager.saturationDataManager.savePlayer(player, async);
    }


    public boolean checkDatabaseConnection(){ return Main.mySqlConnectionHandler.getMySQL().isConnectionAlive(); }

    public void applyDataToPlayer(Player player){
        if(Main.DEBUG){
            System.out.println("attempting to applyDataToPlayer player: "+player.getName());
        }

        SyncManager.inventoryDataManager.applyPlayer(player);
        SyncManager.armorDataManager.applyPlayer(player);
        SyncManager.enderchestDataManager.applyPlayer(player);
        SyncManager.locationDataManager.applyPlayer(player);
        SyncManager.experienceDataManager.applyPlayer(player);
        SyncManager.healthDataManager.applyPlayer(player);
        SyncManager.gamemodeDataManager.applyPlayer(player);
        SyncManager.moneyDataManager.applyPlayer(player);
        SyncManager.effectDataManager.applyPlayer(player);
        SyncManager.advancementDataManager.applyPlayer(player);
        SyncManager.statsDataManager.applyPlayer(player);
        SyncManager.hotbarSlotSelectionDataManager.applyPlayer(player);
        SyncManager.saturationDataManager.applyPlayer(player);
    }

    public void saveAllOnlinePlayers(){
        for (Player player : Bukkit.getOnlinePlayers()){
            this.savePlayerData(player, false);
        }
    }

    public void saveAllOnlinePlayersAsync(){
        for (Player player : Bukkit.getOnlinePlayers()){
            Scheduler.runAsync(() -> { this.savePlayerData(player, true);}, Main.getInstance());
        }
    }
}
