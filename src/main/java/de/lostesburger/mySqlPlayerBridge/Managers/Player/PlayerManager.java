package de.lostesburger.mySqlPlayerBridge.Managers.Player;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.scheduler.Scheduler;

import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class PlayerManager {


    public static void sendCreatedDataMessage(Player player){
        if(Main.IS_FOLIA){
            BridgeScheduler.runEntity(player, () -> player.sendMessage(Chat.getMessage("created-data")));
            return;
        }
        player.sendMessage(Chat.getMessage("created-data"));
    }

    public static void sendDataLoadedMessage(Player player){
        if(Main.IS_FOLIA){
            BridgeScheduler.runEntity(player, () -> player.sendMessage(Chat.getMessage("sync-success")));
            return;
        }
        player.sendMessage(Chat.getMessage("sync-success"));
    }

    public static void syncFailedKick(Player player){
        if(Main.IS_FOLIA){
            BridgeScheduler.runEntity(player, () -> {
                String msg = Chat.getMessage("sync-failed");
                player.sendMessage(msg);
                player.kickPlayer(msg);
            });
            return;
        }
        Scheduler.run(() -> {
            String msg = Chat.getMessage("sync-failed");
            player.sendMessage(msg);
            player.kickPlayer(msg);
        }, Main.getInstance());

    }

    public static void syncTimeoutKick(Player player){
        if(Main.IS_FOLIA){
            BridgeScheduler.runEntity(player, () -> {
                String msg = Chat.getMessage("sync-timeout");
                player.sendMessage(msg);
                player.kickPlayer(msg);
            });
            return;
        }
        Scheduler.run(() -> {
            String msg = Chat.getMessage("sync-timeout");
            player.sendMessage(msg);
            player.kickPlayer(msg);
        }, Main.getInstance());
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
        Runnable task = () -> {
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
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
        };
        if(Main.IS_FOLIA){
            BridgeScheduler.runAsync(task);
        }else {
            Scheduler.runAsync(task, Main.getInstance());
        }
    }
}
