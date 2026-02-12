package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.MySqlData.MySqlDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.NoEntryProtection.NoEntryProtection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerBridgeManager implements Listener {
    private final MySqlDataManager mySqlDataManager;

    public PlayerBridgeManager(){
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
        this.startAutoSyncTask();
        this.mySqlDataManager = Main.mySqlConnectionHandler.getMySqlDataManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event){
        Player player = event.getPlayer();
        PlayerManager.updatePlayerIndex(player, true);

        Scheduler.runAsync(() -> {
            if(this.mySqlDataManager.hasData(player)){
                this.mySqlDataManager.applyDataToPlayer(player);
                PlayerManager.sendDataLoadedMessage(player);
            }else {
                if(NoEntryProtection.isTriggered(player)) return;
                PlayerManager.registerPlayer(player);
                this.mySqlDataManager.savePlayerData(player, true);
                PlayerManager.sendCreatedDataMessage(player);
            }
        }, Main.getInstance());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event){
        Player player = event.getPlayer();
        PlayerManager.updatePlayerIndex(player, false);
        this.mySqlDataManager.savePlayerData(player, false);
    }

    private void startAutoSyncTask(){
        assert this.mySqlDataManager != null;
        Scheduler.Task task = Scheduler.runTimerAsync(() -> {

            Main.mySqlConnectionHandler.getMySqlDataManager().saveAllOnlinePlayersAsync();

        }, Main.modulesManager.syncTaskDelay, Main.modulesManager.syncTaskDelay, Main.getInstance());
        Main.schedulers.add(task);
    }


}
