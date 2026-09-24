package de.lostesburger.mySqlPlayerBridge.Managers.Edit;

import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class EditOperations {
    private EditOperations() { }

    public static CompletableFuture<Void> edit(UUID uuid, EditChange change, boolean wildcard) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return Main.playerSyncService.editOnline(player, change.moduleId(), target -> change.apply(target, wildcard));
        }
        return Main.mySqlConnectionHandler.getDatabaseExecutor().run(() -> {
            var manager = Main.mySqlConnectionHandler.getManager();
            boolean saved = Main.playerLeaseCoordinator.editOffline(uuid, connection -> {
                Map<String, Object> current = manager.getEntry(connection, change.table(), Map.of("uuid", uuid.toString()));
                manager.setOrUpdateEntry(connection, change.table(), Map.of("uuid", uuid.toString()),
                        change.offlineValues(current, wildcard));
                return null;
            });
            if (!saved) throw new PlayerBusyException();
        });
    }

    public static CompletableFuture<Void> saveOfflineInventory(UUID uuid, String type, String serialized) {
        return Main.mySqlConnectionHandler.getDatabaseExecutor().run(() -> {
            boolean saved = Main.playerLeaseCoordinator.editOffline(uuid, connection -> {
                Main.mySqlConnectionHandler.getManager().setOrUpdateEntry(connection, EditChange.tableFor(type),
                        Map.of("uuid", uuid.toString()), Map.of(type, serialized));
                return null;
            });
            if (!saved) throw new PlayerBusyException();
        });
    }

    public static void report(CommandSender sender, Throwable failure) {
        if (failure == null) {
            send(sender, Chat.getMessage("edit-success"));
            return;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PlayerBusyException) {
                send(sender, Chat.getMessage("edit-player-online-other-server"));
                return;
            }
            if (cause instanceof IllegalArgumentException) {
                send(sender, Chat.getMessage("edit-invalid-value").replace("{reason}", String.valueOf(cause.getMessage())));
                return;
            }
        }
        Main.getInstance().getLogger().log(Level.WARNING, "Admin edit failed", failure);
        send(sender, message("edit-save-failed", "§cEdit could not be saved. See console; check the player's current data before retrying."));
    }

    public static String message(String key, String fallback) {
        return Main.messages.contains(key) ? Chat.getMessage(key) : Chat.msg(fallback);
    }

    public static void send(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            Main.platformScheduler.runForPlayer(player, () -> sender.sendMessage(message));
        } else {
            Main.platformScheduler.runGlobal(() -> sender.sendMessage(message));
        }
    }

    public static boolean isBusy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PlayerBusyException) return true;
        }
        return false;
    }

    public static final class PlayerBusyException extends IllegalStateException {
        public PlayerBusyException() {
            super("Player has an active database lease");
        }
    }
}
