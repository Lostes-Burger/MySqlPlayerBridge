package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.MySqlData.MySqlDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.PlayerBehavior.PlayerBehaviorManager;
import de.lostesburger.mySqlPlayerBridge.NoEntryProtection.NoEntryProtection;
import de.lostesburger.mySqlPlayerBridge.Utils.CC;
import de.lostesburger.mySqlPlayerBridge.Utils.Log;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
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
        Main.playerBehaviorManager.lockPlayer(player);

        waitForDataLoad(player, () -> {
            if (this.mySqlDataManager.hasData(player)) {

                Log.info(Main.saveStutsDataManager.getStatus(player));
                int i = 0;
                while (!Main.saveStutsDataManager.getStatus(player).equals("OK")) {
                    try {
                        Thread.sleep(10);
                        i++;
                        if (i > 300) {
                            Log.info("超时，玩家被踢出");
                            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                                player.kickPlayer(CC.colorize("&c加载异常"));
                            });
                            return;

                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                this.mySqlDataManager.applyDataToPlayer(player);
                Main.playerManager.sendDataLoadedMessage(player);
                Main.saveStutsDataManager.savePlayer(player, "wait", true);
                Main.playerBehaviorManager.unlockPlayer(player);

                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);

            } else {
                if (NoEntryProtection.isTriggered(player)) return;
                this.mySqlDataManager.savePlayerData(player, true);
                Main.playerManager.sendCreatedDataMessage(player);
                Main.saveStutsDataManager.savePlayer(player, "wait", true);

            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent event){
        Player player = event.getPlayer();

        Scheduler.runAsync(() -> {
            Log.info("Player " + player.getName() + " left the bridge");
            this.mySqlDataManager.savePlayerData(player, false);
            Main.saveStutsDataManager.savePlayer(player, "OK", false);
        }, Main.getInstance());

    }

    private void startAutoSyncTask(){
        assert this.mySqlDataManager != null;
        Scheduler.Task task = Scheduler.runTimerAsync(() -> {

            Main.mySqlConnectionHandler.getMySqlDataManager().saveAllOnlinePlayersAsync();

        }, Main.modulesManager.syncTaskDelay, Main.modulesManager.syncTaskDelay, Main.getInstance());
        Main.schedulers.add(task);
    }


    private void waitForDataLoad(Player player, Runnable onDataLoaded) {
        Scheduler.runAsync(() -> {
            while (!this.mySqlDataManager.hasData(player)) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            onDataLoaded.run();
        }, Main.getInstance());
    }



}
