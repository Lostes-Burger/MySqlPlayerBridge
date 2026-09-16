package de.lostesburger.mySqlPlayerBridge.Utils.Checks;


import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class DatabaseConfigCheck implements Listener {
    private final FileConfiguration mysql;
    private final boolean isSetup;

    public DatabaseConfigCheck(FileConfiguration mysqlConf){
        this.mysql = mysqlConf;
        this.isSetup = this.check();

        if(!this.isSetup){
            Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
            Main.platformScheduler.runGlobalRepeating(() -> {
                Bukkit.broadcastMessage(Chat.getMessage("no-database-config-error"));
            }, 40L, 40L);
        }
    }

    private boolean check() {
        return isConfigured(this.mysql.getString("host"))
                && isConfigured(this.mysql.getString("database"))
                && isConfigured(this.mysql.getString("user"));
    }

    private static boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    public boolean isSetup(){return this.isSetup; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event){
        Player player = event.getPlayer();
        player.sendTitle("§cMySqlPlayerBridge Error", Chat.getMessageWithoutPrefix("no-database-config-error"), 0, 120*20, 0);
    }

}
