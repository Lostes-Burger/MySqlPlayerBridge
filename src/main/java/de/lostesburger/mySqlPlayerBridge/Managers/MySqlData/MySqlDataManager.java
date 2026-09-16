package de.lostesburger.mySqlPlayerBridge.Managers.MySqlData;

import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility facade for commands and event blockers. Player persistence is
 * implemented by the snapshot/lease pipeline in {@link Main#playerSyncService}.
 */
public final class MySqlDataManager {
    private final ConcurrentHashMap<UUID, UUID> joinSyncSessions = new ConcurrentHashMap<>();

    public CompletableFuture<Void> savePlayerDataAsync(Player player) {
        if (isJoinSyncLocked(player.getUniqueId())
                || Main.playerSyncService == null
                || !Main.playerSyncService.hasActiveLease(player.getUniqueId())) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Cannot save player without an active local lease: " + player.getUniqueId()));
        }
        return Main.playerSyncService.captureAndSaveOnline(player);
    }

    public void markJoinSyncPending(UUID playerUuid, UUID localSession) {
        this.joinSyncSessions.put(playerUuid, localSession);
    }

    public void clearJoinSyncPending(UUID playerUuid, UUID localSession) {
        if (localSession != null) {
            this.joinSyncSessions.remove(playerUuid, localSession);
        }
    }

    public boolean isJoinSyncLocked(UUID playerUuid) {
        return this.joinSyncSessions.containsKey(playerUuid);
    }

    public CompletableFuture<Void> saveAllOnlinePlayersAsync() {
        java.util.List<CompletableFuture<Void>> saves = new java.util.ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isJoinSyncLocked(player.getUniqueId())
                    || Main.playerSyncService == null
                    || !Main.playerSyncService.hasActiveLease(player.getUniqueId())) {
                continue;
            }
            saves.add(savePlayerDataAsync(player));
        }
        return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
    }
}
