package de.lostesburger.mySqlPlayerBridge.Managers.Player;

import de.lostesburger.mySqlPlayerBridge.Database.DatabaseException;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class PlayerManager {


    public static void sendCreatedDataMessage(Player player){
        runForPlayer(player, () -> player.sendMessage(Chat.getMessage("created-data")));
    }

    public static void sendDataLoadedMessage(Player player){
        runForPlayer(player, () -> player.sendMessage(Chat.getMessage("sync-success")));
    }

    public static void syncFailedKick(Player player){
        runForPlayer(player, () -> {
            String msg = Chat.getMessage("sync-failed");
            player.sendMessage(msg);
            player.kickPlayer(msg);
        });

    }

    public static void syncTimeoutKick(Player player){
        runForPlayer(player, () -> {
            String msg = Chat.getMessage("sync-timeout");
            player.sendMessage(msg);
            player.kickPlayer(msg);
        });
    }

    public static void crossVersionDenyKick(Player player){
        runForPlayer(player, () -> {
            String msg = Chat.getMessage("cross-version-deny-kick");
            player.sendMessage(msg);
            player.kickPlayer(msg);
        });
    }

    public static void registerPlayer(Player player){
        updatePlayerIndex(player, true);
    }

    public static void registerPlayer(UUID player_uuid){
        updatePlayerIndex(player_uuid, "", false);
    }

    public static void updatePlayerIndex(Player player, boolean online){
        updatePlayerIndex(player.getUniqueId(), player.getName(), online);
    }

    private static void updatePlayerIndex(UUID player_uuid, String playerName, boolean online){
        long timestamp = Instant.now().toEpochMilli();
        String name = playerName == null ? "" : playerName;
        Main.mySqlConnectionHandler.getDatabaseExecutor().run(() -> {
            try {
                Main.mySqlConnectionHandler.getManager().setOrUpdateEntry(
                        Main.TABLE_NAME_PLAYER_INDEX,
                        Map.of("uuid", player_uuid.toString()),
                        Map.of(
                                "player_name", name,
                                "timestamp", String.valueOf(timestamp),
                                "online", online,
                                "server_id", ""
                        )
                );
            } catch (DatabaseException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void runForPlayer(Player player, Runnable task) {
        if (Main.platformScheduler != null) {
            Main.platformScheduler.runForPlayer(player, task);
        }
    }
}
