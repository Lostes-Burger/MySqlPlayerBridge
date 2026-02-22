package de.lostesburger.mySqlPlayerBridge.Handlers.GitHubUpdateCheck;

import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Checks.GitHubUpdateCheck.GitHubUpdateCheck;
import de.lostesburger.mySqlPlayerBridge.Utils.Checks.GitHubUpdateCheck.GitHubUpdateCheckResult;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.craftcore.craftcore.paper.chat.colorutils.ColorUtils;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
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
                Player player = event.getPlayer();
                if (player.hasPermission("mpb.notify") || "Lostes_Burger".equals(player.getName())) {

                    Component updateMessage = Component.text(Main.PREFIX+"§c"+this.message)
                            .clickEvent(ClickEvent.openUrl("https://github.com/Lostes-Burger/MySqlPlayerBridge/releases/latest"));

                    Component downloadMessage = Component.text(Chat.getMessage("download-update"))
                            .clickEvent(ClickEvent.openUrl("https://github.com/Lostes-Burger/MySqlPlayerBridge/releases/latest"));


                    ((Audience) player).sendMessage(updateMessage);
                    ((Audience) player).sendMessage(downloadMessage);
                    event.getPlayer().sendMessage(ColorUtils.toColor(this.prefix + "§c" + this.message));
                }
            }, 120, this.plugin);


        }
    }
}
