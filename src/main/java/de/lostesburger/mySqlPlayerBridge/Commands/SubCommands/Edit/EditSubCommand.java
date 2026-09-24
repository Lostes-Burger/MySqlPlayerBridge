package de.lostesburger.mySqlPlayerBridge.Commands.SubCommands.Edit;

import de.craftcore.craftcore.paper.command.commandmanager.ServerCommand;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.Edit.EditChange;
import de.lostesburger.mySqlPlayerBridge.Managers.Edit.EditGuiManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Edit.EditOperations;
import de.lostesburger.mySqlPlayerBridge.Managers.Edit.EditSuggestions;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class EditSubCommand implements ServerCommand {
    private static final Set<String> INVENTORY_TYPES = Set.of("inventory", "armor", "enderchest");
    private static final Set<String> ALL_TYPES = Set.of("inventory", "armor", "enderchest", "exp", "exp_level",
            "health", "health_max", "health_scaled", "health_scale", "saturation", "food_level", "gamemode", "location", "money");
    private final EditGuiManager gui = EditGuiManager.getInstance();
    private final EditSuggestions suggestions = new EditSuggestions();

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Main.config.getString("settings.admin-permission"))) {
            sender.sendMessage(Chat.getMessage("permission-error"));
            return;
        }
        if (args.length < 2 || !ALL_TYPES.contains(args[1].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(usage("<player/*> <data_type> <values>"));
            return;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        boolean wildcard = args[0].equals("*");
        if (type.equals("money") && !Main.modulesManager.syncVaultEconomy) {
            sender.sendMessage(Chat.getMessage("edit-vault-disabled"));
            return;
        }
        if (INVENTORY_TYPES.contains(type)) {
            if (wildcard) {
                sender.sendMessage(Chat.getMessage("edit-target-required-player"));
            } else if (args.length != 2) {
                sender.sendMessage(usage("<player> " + type));
            } else if (!(sender instanceof Player)) {
                sender.sendMessage(Chat.getMessage("edit-console-no-gui"));
            } else {
                openInventory((Player) sender, args[0], type);
            }
            return;
        }
        final EditChange change;
        try {
            change = EditChange.parse(type, args);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", String.valueOf(exception.getMessage())));
            return;
        }
        if (wildcard) {
            editAll(sender, change);
        } else {
            resolve(args[0]).thenCompose(target -> EditOperations.edit(target.uuid(), change, false))
                    .whenComplete((ignored, failure) -> report(sender, failure));
        }
    }

    private CompletableFuture<Target> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return CompletableFuture.completedFuture(new Target(online.getUniqueId(), online.getName()));
        return Main.mySqlConnectionHandler.getDatabaseExecutor().supply(() -> {
            for (Map<String, Object> row : Main.mySqlConnectionHandler.getManager().getAllEntries(Main.TABLE_NAME_PLAYER_INDEX)) {
                if (name.equalsIgnoreCase(String.valueOf(row.get("player_name")))) {
                    return new Target(UUID.fromString(String.valueOf(row.get("uuid"))), String.valueOf(row.get("player_name")));
                }
            }
            throw new PlayerNotFoundException();
        });
    }

    private void editAll(CommandSender sender, EditChange change) {
        // Include new local players even if their first index write is still pending.
        Map<UUID, Target> locals = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            locals.put(player.getUniqueId(), new Target(player.getUniqueId(), player.getName()));
        }
        Main.mySqlConnectionHandler.getDatabaseExecutor().supply(() -> {
            Map<UUID, Target> targets = new LinkedHashMap<>(locals);
            for (Map<String, Object> row : Main.mySqlConnectionHandler.getManager().getAllEntries(Main.TABLE_NAME_PLAYER_INDEX)) {
                UUID uuid = UUID.fromString(String.valueOf(row.get("uuid")));
                targets.putIfAbsent(uuid, new Target(uuid, String.valueOf(row.get("player_name"))));
            }
            return new ArrayList<>(targets.values());
        }).thenCompose(targets -> {
            CompletableFuture<BatchResult> batch = CompletableFuture.completedFuture(new BatchResult(0, 0, 0));
            // Bound the number of outstanding database tasks even for large player indexes.
            for (Target target : targets) {
                batch = batch.thenCompose(result -> EditOperations.edit(target.uuid(), change, true)
                        .handle((ignored, failure) -> {
                            if (failure == null) return new BatchResult(result.saved() + 1, result.busy(), result.failed());
                            if (EditOperations.isBusy(failure)) return new BatchResult(result.saved(), result.busy() + 1, result.failed());
                            Main.getInstance().getLogger().log(Level.WARNING, "Wildcard edit failed for " + target.name(), failure);
                            return new BatchResult(result.saved(), result.busy(), result.failed() + 1);
                        }));
            }
            return batch;
        }).whenComplete((result, failure) -> {
            if (failure != null) {
                report(sender, failure);
                return;
            }
            EditOperations.send(sender, EditOperations.message("edit-batch-result",
                            "§7Saved: {saved}; skipped (active on another server): {busy}; failed: {failed}.")
                    .replace("{saved}", String.valueOf(result.saved()))
                    .replace("{busy}", String.valueOf(result.busy()))
                    .replace("{failed}", String.valueOf(result.failed())));
        });
    }

    private void openInventory(Player admin, String name, String type) {
        resolve(name).thenCompose(target -> {
            Player player = Bukkit.getPlayer(target.uuid());
            if (player != null) {
                CompletableFuture<Void> opened = new CompletableFuture<>();
                boolean scheduled = Main.platformScheduler.runForPlayer(player, () -> {
                    try {
                        if (!Main.playerSyncService.hasActiveLease(target.uuid())
                                || Main.mySqlConnectionHandler.getMySqlDataManager().isJoinSyncLocked(target.uuid())) {
                            throw new IllegalStateException("Player is still synchronizing");
                        }
                        String serialized = switch (type) {
                            case "inventory" -> Main.nbtSerializer.serialize(player.getInventory().getContents());
                            case "armor" -> Main.nbtSerializer.serialize(player.getInventory().getArmorContents());
                            case "enderchest" -> Main.nbtSerializer.serialize(player.getEnderChest().getContents());
                            default -> throw new IllegalArgumentException(type);
                        };
                        Main.platformScheduler.runForPlayer(admin, () -> gui.openInventoryEditor(admin, target.uuid(), target.name(), type, serialized));
                        opened.complete(null);
                    } catch (Exception exception) {
                        opened.completeExceptionally(exception);
                    }
                }, () -> opened.completeExceptionally(new IllegalStateException("Player disconnected before opening editor")));
                if (!scheduled) opened.completeExceptionally(new IllegalStateException("Player disconnected before opening editor"));
                return opened;
            }
            return Main.mySqlConnectionHandler.getDatabaseExecutor().run(() -> {
                if (Main.playerLeaseCoordinator.hasDatabaseLease(target.uuid())) throw new EditOperations.PlayerBusyException();
                Map<String, Object> row = Main.mySqlConnectionHandler.getManager()
                        .getEntry(EditChange.tableFor(type), Map.of("uuid", target.uuid().toString()));
                String serialized = row == null || row.get(type) == null ? null : String.valueOf(row.get(type));
                Main.platformScheduler.runForPlayer(admin, () -> gui.openInventoryEditor(admin, target.uuid(), target.name(), type, serialized));
            });
        }).whenComplete((ignored, failure) -> {
            if (failure != null) report(admin, failure);
        });
    }

    private void report(CommandSender sender, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PlayerNotFoundException) {
                EditOperations.send(sender, Chat.getMessage("edit-player-not-found"));
                return;
            }
        }
        EditOperations.report(sender, failure);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Main.config.getString("settings.admin-permission"))) return List.of();
        if (args.length == 1) {
            Set<String> names = new LinkedHashSet<>();
            names.add("*");
            names.addAll(suggestions.names());
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return filter(names, args[0]);
        }
        if (args.length < 2) return List.of();
        if (args.length == 2) {
            // Start warming the values while the administrator selects a data type.
            if (!args[0].equals("*")) {
                for (String type : List.of("exp", "health", "saturation", "gamemode", "location", "money")) {
                    suggestions.values(args[0], type);
                }
            }
            Set<String> types = new LinkedHashSet<>(ALL_TYPES);
            if (args[0].equals("*")) types.removeAll(INVENTORY_TYPES);
            if (!Main.modulesManager.syncVaultEconomy) types.remove("money");
            return filter(types, args[1]);
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        if (!ALL_TYPES.contains(type) || INVENTORY_TYPES.contains(type)) return List.of();
        Map<String, Object> row = args[0].equals("*") ? Map.of() : suggestions.values(args[0], type);
        Set<String> options = new LinkedHashSet<>();
        String column = EditChange.column(type);
        if (type.equals("location")) {
            String[] columns = {"world", "x", "y", "z", "yaw", "pitch"};
            int index = args.length - 3;
            if (index < columns.length) {
                column = columns[index];
                Object value = row.get(column);
                options.add(value == null ? "<" + column + ">" : String.valueOf(value));
            }
        } else if (args.length == 3) {
            Object value = row.get(column);
            if (value != null) options.add(type.equals("health_scaled") && value instanceof Number n
                    ? String.valueOf(n.intValue() != 0) : String.valueOf(value));
            if (type.equals("gamemode")) for (GameMode mode : GameMode.values()) options.add(mode.name());
            if (type.equals("health_scaled")) options.addAll(List.of("true", "false"));
        }
        return filter(options, args[args.length - 1]);
    }

    private List<String> filter(Collection<String> values, String prefix) {
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    private String usage(String usage) {
        return Chat.getMessage("edit-usage").replace("{command}", Main.config.getString("settings.command-prefix"))
                .replace("{usage}", usage);
    }

    private record Target(UUID uuid, String name) { }
    private record BatchResult(int saved, int busy, int failed) { }
    private static final class PlayerNotFoundException extends IllegalStateException { }
}
