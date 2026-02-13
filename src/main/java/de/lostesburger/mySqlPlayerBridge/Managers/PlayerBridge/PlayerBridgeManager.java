package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
public class PlayerBridgeManager implements Listener {
    private final MySqlDataManager mySqlDataManager;

    public PlayerBridgeManager(){
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
        this.startAutoSyncTask();
        this.startPlayerIndexCleanupTask();
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

    private void startPlayerIndexCleanupTask(){
        long interval = 10L * 60L * 20L;
        Scheduler.Task task = Scheduler.runTimerAsync(() -> {
            Scheduler.run(() -> {
                List<PlayerSnapshot> onlinePlayers = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()){
                    onlinePlayers.add(new PlayerSnapshot(player.getUniqueId(), player.getName()));
                }
                Scheduler.runAsync(() -> runPlayerIndexCleanup(onlinePlayers), Main.getInstance());
            }, Main.getInstance());
        }, interval, interval, Main.getInstance());
        Main.schedulers.add(task);
    }

    private void runPlayerIndexCleanup(List<PlayerSnapshot> onlinePlayers){
        long now = System.currentTimeMillis();
        long staleThreshold = now - (10L * 60L * 60L * 1000L);

        try {
            MySqlManager manager = Main.mySqlConnectionHandler.getManager();

            for (PlayerSnapshot snapshot : onlinePlayers){
                manager.setOrUpdateEntry(
                        Main.TABLE_NAME_PLAYER_INDEX,
                        Map.of("uuid", snapshot.uuid.toString()),
                        Map.of(
                                "player_name", snapshot.name,
                                "timestamp", String.valueOf(now),
                                "online", true,
                                "server_id", ""
                        )
                );
            }

            List<Map<String, Object>> entries = manager.getAllEntries(Main.TABLE_NAME_PLAYER_INDEX);
            for (Map<String, Object> entry : entries){
                Object onlineValue = entry.get("online");
                boolean isOnline = onlineValue instanceof Boolean && (Boolean) onlineValue;
                if(!isOnline){
                    continue;
                }
                long timestamp = parseTimestamp(entry.get("timestamp"));
                if(timestamp > 0 && timestamp < staleThreshold){
                    manager.setOrUpdateEntry(
                            Main.TABLE_NAME_PLAYER_INDEX,
                            Map.of("uuid", String.valueOf(entry.get("uuid"))),
                            Map.of(
                                    "online", false,
                                    "server_id", ""
                            )
                    );
                }
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }

    private long parseTimestamp(Object timestampValue){
        if(timestampValue == null){
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(timestampValue));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static class PlayerSnapshot {
        private final UUID uuid;
        private final String name;

        private PlayerSnapshot(UUID uuid, String name){
            this.uuid = uuid;
            this.name = name;
        }
    }


}
