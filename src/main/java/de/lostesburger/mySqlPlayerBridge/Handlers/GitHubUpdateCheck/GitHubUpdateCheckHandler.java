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

public class GitHubUpdateCheckHandler implements Listener {
    private static final String DEFAULT_RELEASE_URL = "https://github.com/Lostes-Burger/MySqlPlayerBridge/releases/latest";
    private static final String USERNAME_NOTIFY_OVERRIDE = "Lostes_Burger";

    private volatile boolean updateAvailable = false;
    private volatile String message = "";
    private volatile String releaseUrl = DEFAULT_RELEASE_URL;

    private final String prefix;
    private final String repoUrl;
    private final JavaPlugin plugin;
    private final String currentVersion;

    public GitHubUpdateCheckHandler(JavaPlugin plugin, String currentVersion, String githubUrl, String prefix, int checkInterval) {
        this.repoUrl = githubUrl;
        this.prefix = prefix;
        this.plugin = plugin;
        this.currentVersion = currentVersion;

        Scheduler.runTimerAsync(() -> {
            try {
                GitHubUpdateCheckResult result = new GitHubUpdateCheck(this.repoUrl, this.currentVersion).checkForUpdate();

                if (!result.isSuccess()) {
                    this.updateAvailable = false;
                    String failedTemplate = resolveMessage(
                            "update-check-failed",
                            "GitHub update check failed: {reason}"
                    );
                    this.plugin.getLogger().warning(
                            failedTemplate.replace("{reason}", result.getErrorMessage())
                    );
                    return;
                }

                if (result.getReleaseUrl() != null && !result.getReleaseUrl().isBlank()) {
                    this.releaseUrl = result.getReleaseUrl();
                } else {
                    this.releaseUrl = DEFAULT_RELEASE_URL;
                }

                String latestForMessage = result.getLatestVersion() == null || result.getLatestVersion().isBlank()
                        ? this.currentVersion
                        : result.getLatestVersion();

                String updateMessageKey = result.isPreRelease() ? "update-available-prerelease" : "update-available";
                this.message = resolveMessage(
                        updateMessageKey,
                        "A new version is available. Installed: {installed}, latest: {latest}."
                )
                        .replace("{latest}", latestForMessage)
                        .replace("{installed}", this.currentVersion);
                this.updateAvailable = result.isUpdateAvailable();

                if (this.updateAvailable) {
                    plugin.getLogger().warning(this.message);
                    plugin.getLogger().warning(this.releaseUrl);
                }

            } catch (Throwable throwable) {
                this.updateAvailable = false;
                String failedTemplate = resolveMessage(
                        "update-check-failed",
                        "GitHub update check failed: {reason}"
                );
                String reason = throwable.getMessage() == null || throwable.getMessage().isBlank()
                        ? throwable.getClass().getSimpleName()
                        : throwable.getMessage();
                plugin.getLogger().warning(failedTemplate.replace("{reason}", reason));
            }
        }, 100L, checkInterval*20, plugin);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!this.updateAvailable) {
            return;
        }

        Player player = event.getPlayer();
        if (!canReceiveUpdateNotify(player)) {
            return;
        }

        Scheduler.runLater(() -> {
            if (!player.isOnline()) {
                return;
            }

            Component updateMessage = Component.text(Main.PREFIX + "§c" + this.message)
                    .clickEvent(ClickEvent.openUrl(this.releaseUrl));

            Component downloadMessage = Component.text(Chat.getMessage("download-update"))
                    .clickEvent(ClickEvent.openUrl(this.releaseUrl));

            ((Audience) player).sendMessage(updateMessage);
            ((Audience) player).sendMessage(downloadMessage);
            player.sendMessage(ColorUtils.toColor(this.prefix + "§c" + this.message));
        }, 120L, this.plugin);
    }

    private boolean canReceiveUpdateNotify(Player player) {
        if (USERNAME_NOTIFY_OVERRIDE.equals(player.getName())) {
            return true;
        }

        String notifyPermission = Main.config.getString("settings.admin-permission");
        if (notifyPermission == null || notifyPermission.isBlank()) {
            return false;
        }

        return player.hasPermission(notifyPermission);
    }

    private String resolveMessage(String key, String fallback) {
        String messageValue = Chat.getMessageWithoutPrefix(key);
        if (messageValue == null || messageValue.isBlank()) {
            return fallback;
        }
        return messageValue;
    }
}
