package de.lostesburger.mySqlPlayerBridge.Utils.Checks;

import de.lostesburger.corelib.Chat.ColorUtils.ColorUtils;
import de.lostesburger.corelib.PluginSmiths.License.PluginSmithsAPI;
import de.lostesburger.corelib.Scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class UpdateCheck implements Listener {
    private boolean updateAvailable = false;
    private String latestVersion;
    private String currentVer;
    private String prefix;
    private String pluginName;

    public UpdateCheck(JavaPlugin plugin, String currentVersion, String pluginName, String prefix) {
        Scheduler.Task task = Scheduler.runTimerAsync(() -> {
            String latestVersion = PluginSmithsAPI.getLatestPluginVersion(pluginName);
            this.latestVersion = latestVersion;
            this.currentVer = currentVersion;
            this.prefix = prefix;
            this.pluginName = pluginName;
            if (!latestVersion.equalsIgnoreCase(currentVersion)) {
                this.updateAvailable = true;
                plugin.getLogger().log(Level.WARNING, "A new version of this plugin is available! Current version: " + currentVersion + " Latest version: " + latestVersion);
            }

        }, 100L, 60*60,plugin);

        Main.schedulers.add(task);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.updateAvailable) {
            Scheduler.runLaterAsync(() -> {
                if (event.getPlayer().hasPermission("mpb.notify") || event.getPlayer().getName() == "Lostes_Burger") {
                    event.getPlayer().sendMessage(ColorUtils.toColor(this.prefix + "§c" + this.pluginName + " update available. §7Current version: " + this.currentVer + " §7Latest version: " + this.latestVersion));
                }
            }, 120, Main.getInstance());


        }
    }
}
