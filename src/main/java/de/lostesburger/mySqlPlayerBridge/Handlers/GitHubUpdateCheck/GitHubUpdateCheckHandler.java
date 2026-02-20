package de.lostesburger.mySqlPlayerBridge.Handlers.GitHubUpdateCheck;

import de.lostesburger.mySqlPlayerBridge.Utils.Checks.GitHubUpdateCheck.GitHubUpdateCheck;
import de.lostesburger.mySqlPlayerBridge.Utils.Checks.GitHubUpdateCheck.GitHubUpdateCheckResult;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.craftcore.craftcore.paper.chat.colorutils.ColorUtils;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class GitHubUpdateCheckHandler implements Listener {
    private boolean updateAvailable = false;
    private String latestVersion;
    private String currentVer;
    private String repoName;
    private String message;

    private final String prefix;
    private final String repoUrl;
    private final JavaPlugin plugin;

    public GitHubUpdateCheckHandler(JavaPlugin plugin, String currentVersion, String githubUrl, String prefix, int checkInterval) {
        this.repoUrl = githubUrl;
        this.prefix = prefix;
        this.plugin = plugin;

        Scheduler.runTimerAsync(() -> {
            this.message = Chat.getMessageWithoutPrefix("update-available").replace("{latest}", latestVersion).replace("{installed}", currentVersion);
            try {
                GitHubUpdateCheckResult result = new GitHubUpdateCheck(this.repoUrl, currentVersion).checkForUpdate();
                this.updateAvailable = result.isUpdateAvailable();
                this.latestVersion = result.getLatestVersion();
                this.currentVer = result.getInstalledVersion();
                this.repoName = result.getRepoName();

                if(result.isUpdateAvailable()){

                    plugin.getLogger().log(Level.WARNING, this.message);
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "GitHub update check failed.", e);
            }
        }, 100L, checkInterval*20, plugin);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.updateAvailable) {
            Scheduler.runLaterAsync(() -> {
                if (event.getPlayer().hasPermission("mpb.notify") || "Lostes_Burger".equals(event.getPlayer().getName())) {
                    event.getPlayer().sendMessage(ColorUtils.toColor(this.prefix + "§c" + this.message));
                }
            }, 120, this.plugin);


        }
    }
}
