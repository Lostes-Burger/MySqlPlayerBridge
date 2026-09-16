package de.lostesburger.mySqlPlayerBridge.Commands.SubCommands.Clear;

import de.craftcore.craftcore.paper.command.commandmanager.ServerCommand;
import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClearSubCommand implements ServerCommand {
    @Override
    public void execute(CommandSender commandSender, String[] strings) {
        if (!commandSender.hasPermission(Main.config.getString("settings.admin-permission"))) {
            commandSender.sendMessage(Chat.getMessage("permission-error"));
            return;
        }
        if (strings.length != 1) {
            commandSender.sendMessage(Chat.getMessage("clear-wrong-usage"));
            return;
        }

        String target = strings[0];
        Main.mySqlConnectionHandler.getDatabaseExecutor().run(() -> clear(target, commandSender))
                .exceptionally(throwable -> {
                    Main.getInstance().getLogger().warning("Clear command failed: " + throwable.getMessage());
                    return null;
                });
    }

    private void clear(String target, CommandSender sender) throws Exception {
        PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
        String uuid = target.equals("*") ? null : findUuidByName(manager, target);
        if (!target.equals("*") && uuid == null) {
            sendMessage(sender, Chat.getMessage("clear-player-not-found"));
            return;
        }
        boolean activeLease = uuid == null
                ? Main.playerLeaseCoordinator.hasAnyDatabaseLease()
                : Main.playerLeaseCoordinator.hasDatabaseLease(java.util.UUID.fromString(uuid));
        if (activeLease) {
            sendMessage(sender, Chat.getMessage("edit-player-online-other-server"));
            return;
        }

        manager.inTransaction(connection -> {
            for (String table : playerDataTables()) {
                if (uuid == null) {
                    manager.deleteAll(connection, table);
                } else {
                    manager.deleteEntry(connection, table, Map.of("uuid", uuid));
                }
            }
            return null;
        });
        Main.getInstance().getLogger().info(uuid == null
                ? "Cleared all player bridge data"
                : "Cleared player bridge data for " + uuid);
    }

    private String findUuidByName(PooledSqlManager manager, String target) throws Exception {
        for (Map<String, Object> entry : manager.getAllEntries(Main.TABLE_NAME_PLAYER_INDEX)) {
            if (target.equalsIgnoreCase(String.valueOf(entry.get("player_name")))) {
                return String.valueOf(entry.get("uuid"));
            }
        }
        return null;
    }

    private List<String> playerDataTables() {
        return List.of(
                Main.TABLE_NAME_INVENTORY,
                Main.TABLE_NAME_ARMOR,
                Main.TABLE_NAME_ENDERCHEST,
                Main.TABLE_NAME_LOCATION,
                Main.TABLE_NAME_EXP,
                Main.TABLE_NAME_HEALTH,
                Main.TABLE_NAME_GAMEMODE,
                Main.TABLE_NAME_MONEY,
                Main.TABLE_NAME_EFFECTS,
                Main.TABLE_NAME_ADVANCEMENTS,
                Main.TABLE_NAME_STATS,
                Main.TABLE_NAME_SELECTED_HOTBAR_SLOT,
                Main.TABLE_NAME_SATURATION,
                Main.TABLE_NAME_PLAYER_INDEX
        );
    }

    private void sendMessage(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            Main.platformScheduler.runForPlayer(player, () -> sender.sendMessage(message));
        } else {
            Main.platformScheduler.runGlobal(() -> sender.sendMessage(message));
        }
    }

    @Override
    public List<String> tabComplete(CommandSender commandSender, String[] strings) {
        List<String> options = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
        options.add("*");
        return options;
    }
}
