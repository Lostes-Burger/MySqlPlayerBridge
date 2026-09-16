package de.lostesburger.mySqlPlayerBridge.Commands.SubCommands.Edit;

import de.lostesburger.mySqlPlayerBridge.Database.DatabaseException;
import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;
import de.craftcore.craftcore.paper.command.commandmanager.ServerCommand;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.Edit.EditGuiManager;
import de.lostesburger.mySqlPlayerBridge.Sync.SnapshotException;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class EditSubCommand implements ServerCommand {
    private static final Set<String> PLAYER_ONLY_TYPES = Set.of("inventory", "armor", "enderchest");
    private static final Set<String> ALL_TYPES = Set.of(
            "inventory", "armor", "enderchest",
            "exp", "exp_level",
            "health", "health_max", "health_scaled", "health_scale",
            "saturation", "food_level",
            "gamemode", "location", "money"
    );
    private static final Set<String> WILDCARD_TYPES = Set.of(
            "exp", "exp_level",
            "health", "health_max", "health_scaled", "health_scale",
            "saturation", "food_level",
            "gamemode", "location", "money"
    );

    private final EditGuiManager editGuiManager = EditGuiManager.getInstance();

    @Override
    public void execute(CommandSender commandSender, String[] strings) {
        if(!commandSender.hasPermission(Main.config.getString("settings.admin-permission"))){
            commandSender.sendMessage(Chat.getMessage("permission-error"));
            return;
        }

        if(strings.length < 2){
            commandSender.sendMessage(buildUsageMessage("<player/*> <data_type> <values>"));
            return;
        }

        String targetArg = strings[0];
        String typeArg = normalize(strings[1]);
        boolean wildcard = targetArg.equals("*");

        if(!ALL_TYPES.contains(typeArg)){
            commandSender.sendMessage(buildUsageMessage("<player/*> <data_type> <values>"));
            return;
        }

        if(typeArg.equals("money") && !Main.modulesManager.syncVaultEconomy){
            commandSender.sendMessage(Chat.getMessage("edit-vault-disabled"));
            return;
        }

        if(wildcard && PLAYER_ONLY_TYPES.contains(typeArg)){
            commandSender.sendMessage(Chat.getMessage("edit-target-required-player"));
            return;
        }

        Player onlineTarget = null;
        if(!wildcard){
            onlineTarget = findOnlinePlayer(targetArg);
            if(onlineTarget != null && (Main.playerSyncService == null
                    || !Main.playerSyncService.hasActiveLease(onlineTarget.getUniqueId())
                    || Main.mySqlConnectionHandler.getMySqlDataManager()
                    .isJoinSyncLocked(onlineTarget.getUniqueId()))){
                commandSender.sendMessage(Chat.getMessage("edit-pre-sync-failed")
                        .replace("{player}", onlineTarget.getName()));
                return;
            }
        }

        if(PLAYER_ONLY_TYPES.contains(typeArg)){
            if(strings.length != 2){
                commandSender.sendMessage(buildUsageMessage("<player> "+typeArg));
                return;
            }
            if(!(commandSender instanceof Player)){
                commandSender.sendMessage(Chat.getMessage("edit-console-no-gui"));
                return;
            }
            handleInventoryEdit((Player) commandSender, targetArg, onlineTarget, typeArg);
            return;
        }

        switch (typeArg){
            case "exp" -> handleExpEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "exp_level" -> handleExpLevelEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "health" -> handleHealthEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "health_max" -> handleHealthMaxEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "health_scaled" -> handleHealthScaledEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "health_scale" -> handleHealthScaleEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "saturation" -> handleSaturationEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "food_level" -> handleFoodLevelEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "gamemode" -> handleGamemodeEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "location" -> handleLocationEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            case "money" -> handleMoneyEdit(commandSender, targetArg, onlineTarget, wildcard, strings);
            default -> commandSender.sendMessage(Chat.getMessage("edit-wrong-usage"));
        }
    }

    @Override
    public List<String> tabComplete(CommandSender commandSender, String[] strings) {
        if(!commandSender.hasPermission(Main.config.getString("settings.admin-permission"))){ return List.of(); }

        if(strings.length == 1){
            return filterByPrefix(getTargetSuggestions(), strings[0]);
        }

        if(strings.length == 2){
            String target = strings[0];
            Set<String> types = target.equals("*") ? WILDCARD_TYPES : ALL_TYPES;
            if(!Main.modulesManager.syncVaultEconomy){
                Set<String> filtered = new HashSet<>(types);
                filtered.remove("money");
                types = filtered;
            }
            return filterByPrefix(types, strings[1]);
        }

        if(strings[0].equals("*")){
            return List.of();
        }

        return getValueSuggestions(strings);
    }

    private void handleInventoryEdit(Player admin, String targetArg, Player onlineTarget, String typeArg){
        if(onlineTarget != null && onlineTarget.isOnline()){
            openLiveInventoryEditor(admin, onlineTarget, typeArg);
            return;
        }

        runAsyncTask(() -> {
            TargetInfo targetInfo = resolveTarget(targetArg, null, admin);
            if(targetInfo == null) return;

            PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
            String table = getTableForInventoryType(typeArg);
            String column = getColumnForInventoryType(typeArg);

            String serialized = null;
            try {
                Map<String, Object> entry = manager.getEntry(table, Map.of("uuid", targetInfo.uuid));
                if(entry != null && !entry.isEmpty()){
                    Object value = entry.get(column);
                    if(value != null){
                        serialized = String.valueOf(value);
                    }
                }
            } catch (DatabaseException e) {
                sendMessage(admin, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }

            String finalSerialized = serialized;
            Main.platformScheduler.runForPlayer(admin, () ->
                    editGuiManager.openInventoryEditor(admin, UUID.fromString(targetInfo.uuid),
                            targetInfo.name, typeArg, finalSerialized));
        });
    }

    private void openLiveInventoryEditor(Player admin, Player target, String type){
        Main.platformScheduler.runForPlayer(target, () -> {
            if(Main.nbtSerializer == null){
                sendMessage(admin, Chat.getMessage("edit-invalid-value").replace("{reason}", "NBT serializer missing"));
                return;
            }

            try {
                String serialized = switch (type) {
                    case "inventory" -> Main.nbtSerializer.serialize(target.getInventory().getContents());
                    case "armor" -> Main.nbtSerializer.serialize(target.getInventory().getArmorContents());
                    case "enderchest" -> Main.nbtSerializer.serialize(target.getEnderChest().getContents());
                    default -> throw new IllegalArgumentException("Unsupported inventory editor type: " + type);
                };
                Main.platformScheduler.runForPlayer(admin, () -> editGuiManager.openInventoryEditor(
                        admin, target.getUniqueId(), target.getName(), type, serialized));
            } catch (Exception exception) {
                Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                        "Could not capture live inventory for editing: " + target.getUniqueId(), exception);
                sendMessage(admin, Chat.getMessage("edit-invalid-value").replace("{reason}", "serialize"));
            }
        });
    }

    private void handleExpEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> exp <exp>"));
            return;
        }
        Float exp = parseFloat(strings[2]);
        if(exp == null){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        if(exp < 0 || exp > 1){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", "exp range 0-1"));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_EXP, Map.of("exp", exp), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_EXP, targetInfo.uuid, Map.of("exp", exp), sender);
            applyExp(onlineTarget, exp, null);
        });
    }

    private void handleExpLevelEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> exp_level <level>"));
            return;
        }
        Integer level = parseInt(strings[2]);
        if(level == null || level < 0){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_EXP, Map.of("exp_level", level), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_EXP, targetInfo.uuid, Map.of("exp_level", level), sender);
            applyExp(onlineTarget, null, level);
        });
    }

    private void handleHealthEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> health <value>"));
            return;
        }
        Double health = parseDouble(strings[2]);
        if(health == null || health < 0){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateHealthForAll(health, sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;

            Double maxHealth = getHealthMax(targetInfo.uuid);
            if(maxHealth != null && health > maxHealth){
                sendMessage(sender, Chat.getMessage("edit-invalid-value").replace("{reason}", "health > max ("+maxHealth+")"));
                return;
            }
            updateSingleEntry(Main.TABLE_NAME_HEALTH, targetInfo.uuid, Map.of("health", health), sender);
            applyHealth(onlineTarget, health, null, null, null);
        });
    }

    private void handleHealthMaxEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> health_max <value>"));
            return;
        }
        Double maxHealth = parseDouble(strings[2]);
        if(maxHealth == null || maxHealth <= 0){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateHealthMaxForAll(maxHealth, sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;

            Double currentHealth = getHealthValue(targetInfo.uuid);
            if(currentHealth != null && currentHealth > maxHealth){
                sendMessage(sender, Chat.getMessage("edit-invalid-value").replace("{reason}", "max < health ("+currentHealth+")"));
                return;
            }
            updateSingleEntry(Main.TABLE_NAME_HEALTH, targetInfo.uuid, Map.of("max_health", maxHealth), sender);
            applyHealth(onlineTarget, null, maxHealth, null, null);
        });
    }

    private void handleHealthScaledEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> health_scaled <true|false>"));
            return;
        }
        Boolean scaled = parseBoolean(strings[2]);
        if(scaled == null){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_HEALTH, Map.of("health_scaled", scaled), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_HEALTH, targetInfo.uuid, Map.of("health_scaled", scaled), sender);
            applyHealth(onlineTarget, null, null, scaled, null);
        });
    }

    private void handleHealthScaleEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> health_scale <value>"));
            return;
        }
        Double scale = parseDouble(strings[2]);
        if(scale == null || scale <= 0){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_HEALTH, Map.of("health_scale", scale), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_HEALTH, targetInfo.uuid, Map.of("health_scale", scale), sender);
            applyHealth(onlineTarget, null, null, null, scale);
        });
    }

    private void handleSaturationEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> saturation <value>"));
            return;
        }
        Float saturation = parseFloat(strings[2]);
        if(saturation == null || saturation < 0 || saturation > 20){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_SATURATION, Map.of("saturation", saturation), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_SATURATION, targetInfo.uuid, Map.of("saturation", saturation), sender);
            applySaturation(onlineTarget, saturation, null);
        });
    }

    private void handleFoodLevelEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> food_level <value>"));
            return;
        }
        Integer food = parseInt(strings[2]);
        if(food == null || food < 0 || food > 20){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_SATURATION, Map.of("food_level", food), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_SATURATION, targetInfo.uuid, Map.of("food_level", food), sender);
            applySaturation(onlineTarget, null, food);
        });
    }

    private void handleGamemodeEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> gamemode <gamemode>"));
            return;
        }
        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(strings[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        String gamemodeString = gameMode.name();
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_GAMEMODE, Map.of("gamemode", gamemodeString), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_GAMEMODE, targetInfo.uuid, Map.of("gamemode", gamemodeString), sender);
            applyGamemode(onlineTarget, gameMode);
        });
    }

    private void handleLocationEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length < 6 || strings.length > 8){
            sender.sendMessage(buildUsageMessage("<player/*> location <world_name> <x> <y> <z> [yaw] [pitch]"));
            return;
        }

        String worldName = strings[2];
        Double x = parseDouble(strings[3]);
        Double y = parseDouble(strings[4]);
        Double z = parseDouble(strings[5]);
        if(x == null || y == null || z == null){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", "location"));
            return;
        }

        Float yaw = null;
        Float pitch = null;
        if(strings.length == 8){
            yaw = parseFloat(strings[6]);
            pitch = parseFloat(strings[7]);
            if(yaw == null || pitch == null){
                sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", "yaw/pitch"));
                return;
            }
        }

        String finalWorldName = worldName;
        Float finalYaw = yaw;
        Float finalPitch = pitch;
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateLocationForAll(finalWorldName, x, y, z, finalYaw, finalPitch, sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;

            Map<String, Object> update = new HashMap<>();
            update.put("world", finalWorldName);
            update.put("x", x);
            update.put("y", y);
            update.put("z", z);

            if(finalYaw != null && finalPitch != null){
                update.put("yaw", finalYaw);
                update.put("pitch", finalPitch);
            } else {
                Map<String, Object> entry = getEntry(Main.TABLE_NAME_LOCATION, targetInfo.uuid);
                if(entry == null || entry.isEmpty()){
                    update.put("yaw", 0f);
                    update.put("pitch", 0f);
                }
            }

            updateSingleEntry(Main.TABLE_NAME_LOCATION, targetInfo.uuid, update, sender);
            applyLocation(onlineTarget, finalWorldName, x, y, z, finalYaw, finalPitch);
        });
    }

    private void handleMoneyEdit(CommandSender sender, String targetArg, Player onlineTarget, boolean wildcard, String[] strings){
        if(strings.length != 3){
            sender.sendMessage(buildUsageMessage("<player/*> money <value>"));
            return;
        }
        Double money = parseDouble(strings[2]);
        if(money == null){
            sender.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", strings[2]));
            return;
        }
        runAsyncEdit(wildcard, sender, () -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_MONEY, Map.of("money", money), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            if(onlineTarget != null){
                applyMoney(onlineTarget, money, sender);
                return;
            }
            updateSingleEntry(Main.TABLE_NAME_MONEY, targetInfo.uuid, Map.of("money", money), sender);
        });
    }

    private void updateAllEntries(String table, Map<String, Object> update, CommandSender sender){
        PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(table);
        } catch (DatabaseException e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        for (Map<String, Object> entry : entries){
            Object uuid = entry.get("uuid");
            if(uuid == null) continue;
            try {
                manager.setOrUpdateEntry(table, Map.of("uuid", String.valueOf(uuid)), update);
            } catch (DatabaseException e) {
                sendMessage(sender, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }
        }
        sendMessage(sender, Chat.getMessage("edit-success-all"));
    }

    private void updateSingleEntry(String table, String uuid, Map<String, Object> update, CommandSender sender){
        PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
        try {
            manager.setOrUpdateEntry(table, Map.of("uuid", uuid), update);
        } catch (DatabaseException e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        sendMessage(sender, Chat.getMessage("edit-success"));
    }

    private void updateHealthForAll(double health, CommandSender sender){
        PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(Main.TABLE_NAME_HEALTH);
        } catch (DatabaseException e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        for (Map<String, Object> entry : entries){
            String uuid = String.valueOf(entry.get("uuid"));
            Double maxHealth = entry.get("max_health") instanceof Number number ? number.doubleValue() : null;
            double newHealth = maxHealth == null ? health : Math.min(health, maxHealth);
            try {
                manager.setOrUpdateEntry(Main.TABLE_NAME_HEALTH, Map.of("uuid", uuid), Map.of("health", newHealth));
            } catch (DatabaseException e) {
                sendMessage(sender, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }
        }
        sendMessage(sender, Chat.getMessage("edit-success-all"));
    }

    private void updateHealthMaxForAll(double maxHealth, CommandSender sender){
        PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(Main.TABLE_NAME_HEALTH);
        } catch (DatabaseException e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        for (Map<String, Object> entry : entries){
            String uuid = String.valueOf(entry.get("uuid"));
            Double currentHealth = entry.get("health") instanceof Number number ? number.doubleValue() : null;
            Map<String, Object> update = new HashMap<>();
            update.put("max_health", maxHealth);
            if(currentHealth != null && currentHealth > maxHealth){
                update.put("health", maxHealth);
            }
            try {
                manager.setOrUpdateEntry(Main.TABLE_NAME_HEALTH, Map.of("uuid", uuid), update);
            } catch (DatabaseException e) {
                sendMessage(sender, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }
        }
        sendMessage(sender, Chat.getMessage("edit-success-all"));
    }

    private void updateLocationForAll(String world, double x, double y, double z, Float yaw, Float pitch, CommandSender sender){
        PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(Main.TABLE_NAME_LOCATION);
        } catch (DatabaseException e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        Map<String, Object> update = new HashMap<>();
        update.put("world", world);
        update.put("x", x);
        update.put("y", y);
        update.put("z", z);
        if(yaw != null && pitch != null){
            update.put("yaw", yaw);
            update.put("pitch", pitch);
        }
        for (Map<String, Object> entry : entries){
            Object uuid = entry.get("uuid");
            if(uuid == null) continue;
            try {
                manager.setOrUpdateEntry(Main.TABLE_NAME_LOCATION, Map.of("uuid", String.valueOf(uuid)), update);
            } catch (DatabaseException e) {
                sendMessage(sender, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }
        }
        sendMessage(sender, Chat.getMessage("edit-success-all"));
    }

    private void applyExp(Player onlineTarget, Float exp, Integer level){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        runForPlayerAndSave(onlineTarget, () -> {
            if(exp != null){
                onlineTarget.setExp(exp);
            }
            if(level != null){
                onlineTarget.setLevel(level);
            }
        });
    }

    private void applyHealth(Player onlineTarget, Double health, Double maxHealth, Boolean scaled, Double scale){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        runForPlayerAndSave(onlineTarget, () -> {
            if(maxHealth != null && onlineTarget.getAttribute(Attribute.MAX_HEALTH) != null){
                onlineTarget.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHealth);
            }
            if(health != null){
                double limit = onlineTarget.getMaxHealth();
                onlineTarget.setHealth(Math.min(health, limit));
            }
            if(scaled != null){
                onlineTarget.setHealthScaled(scaled);
            }
            if(scale != null){
                if(!onlineTarget.isHealthScaled()){
                    onlineTarget.setHealthScaled(true);
                }
                onlineTarget.setHealthScale(scale);
            }
        });
    }

    private void applySaturation(Player onlineTarget, Float saturation, Integer foodLevel){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        runForPlayerAndSave(onlineTarget, () -> {
            if(saturation != null){
                onlineTarget.setSaturation(saturation);
            }
            if(foodLevel != null){
                onlineTarget.setFoodLevel(foodLevel);
            }
        });
    }

    private void applyGamemode(Player onlineTarget, GameMode gamemode){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        runForPlayerAndSave(onlineTarget, () -> onlineTarget.setGameMode(gamemode));
    }

    private void applyLocation(Player onlineTarget, String worldName, double x, double y, double z, Float yaw, Float pitch){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        runForPlayer(onlineTarget, () -> {
            World world = Bukkit.getWorld(worldName);
            if(world == null) return;
            float useYaw = yaw != null ? yaw : onlineTarget.getLocation().getYaw();
            float usePitch = pitch != null ? pitch : onlineTarget.getLocation().getPitch();
            Location location = new Location(world, x, y, z, useYaw, usePitch);
            onlineTarget.teleportAsync(location).whenComplete((teleported, throwable) -> {
                if(throwable != null || !Boolean.TRUE.equals(teleported)){
                    Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                            "Could not apply edited location for " + onlineTarget.getUniqueId(), throwable);
                    return;
                }
                saveLivePlayer(onlineTarget);
            });
        });
    }

    private void applyMoney(Player onlineTarget, double money, CommandSender sender){
        if(!onlineTarget.isOnline() || Main.vaultManager == null){
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            return;
        }
        boolean scheduled = Main.platformScheduler.runForPlayer(onlineTarget, () -> {
            try {
                Main.vaultManager.setBalance(onlineTarget, money);
                Main.playerSyncService.saveOnline(Main.playerSyncService.capture(onlineTarget))
                        .whenComplete((ignored, throwable) -> {
                            if(throwable != null){
                                Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                                        "Could not persist edited economy balance for "
                                                + onlineTarget.getUniqueId(), throwable);
                            }
                            sendMessage(sender, Chat.getMessage(throwable == null
                                    ? "edit-success"
                                    : "edit-db-error"));
                        });
            } catch (Exception exception) {
                Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                        "Could not apply edited economy balance for " + onlineTarget.getUniqueId(), exception);
                sendMessage(sender, Chat.getMessage("edit-db-error"));
            }
        }, () -> sendMessage(sender, Chat.getMessage("edit-db-error")));
        if(!scheduled){
            sendMessage(sender, Chat.getMessage("edit-db-error"));
        }
    }

    private void runForPlayer(Player player, Runnable task){
        Main.platformScheduler.runForPlayer(player, task);
    }

    private void runForPlayerAndSave(Player player, Runnable task){
        Main.platformScheduler.runForPlayer(player, () -> {
            task.run();
            saveLivePlayer(player);
        });
    }

    private void saveLivePlayer(Player player){
        if(Main.playerSyncService == null || !Main.playerSyncService.hasActiveLease(player.getUniqueId())){
            Main.getInstance().getLogger().warning("Edited player has no active local lease: " + player.getUniqueId());
            return;
        }
        try {
            Main.playerSyncService.saveOnline(Main.playerSyncService.capture(player))
                    .exceptionally(throwable -> {
                        Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                                "Could not persist admin edit for " + player.getUniqueId(), throwable);
                        return null;
                    });
        } catch (SnapshotException exception) {
            Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                    "Could not capture admin edit for " + player.getUniqueId(), exception);
        }
    }

    private void runAsyncTask(Runnable task){
        Main.mySqlConnectionHandler.getDatabaseExecutor().run(task::run)
                .exceptionally(throwable -> {
                    Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                            "Admin database operation failed", throwable);
                    return null;
                });
    }

    private void runAsyncEdit(boolean wildcard, CommandSender sender, Runnable task){
        runAsyncTask(() -> {
            if(wildcard){
                try {
                    if(Main.playerLeaseCoordinator.hasAnyDatabaseLease()){
                        sendMessage(sender, Chat.getMessage("edit-player-online-other-server"));
                        return;
                    }
                } catch (DatabaseException exception) {
                    sendMessage(sender, Chat.getMessage("edit-db-error"));
                    throw new RuntimeException(exception);
                }
            }
            task.run();
        });
    }

    private Double getHealthMax(String uuid){
        Map<String, Object> entry = getEntry(Main.TABLE_NAME_HEALTH, uuid);
        if(entry == null || entry.isEmpty()){
            return null;
        }
        Object value = entry.get("max_health");
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double getHealthValue(String uuid){
        Map<String, Object> entry = getEntry(Main.TABLE_NAME_HEALTH, uuid);
        if(entry == null || entry.isEmpty()){
            return null;
        }
        Object value = entry.get("health");
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Map<String, Object> getEntry(String table, String uuid){
        PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
        try {
            return manager.getEntry(table, Map.of("uuid", uuid));
        } catch (DatabaseException e) {
            return null;
        }
    }

    private TargetInfo resolveTarget(String targetName, Player onlineTarget, CommandSender sender){
        if(onlineTarget != null){
            return new TargetInfo(onlineTarget.getUniqueId().toString(), onlineTarget.getName(), true);
        }

        PooledSqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(Main.TABLE_NAME_PLAYER_INDEX);
        } catch (DatabaseException e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }

        for (Map<String, Object> entry : entries){
            String name = String.valueOf(entry.get("player_name"));
            if(name.equalsIgnoreCase(targetName)){
                String uuid = String.valueOf(entry.get("uuid"));
                try {
                    if(Main.playerLeaseCoordinator.hasDatabaseLease(UUID.fromString(uuid))){
                        sendMessage(sender, Chat.getMessage("edit-player-online-other-server"));
                        return null;
                    }
                } catch (DatabaseException exception) {
                    sendMessage(sender, Chat.getMessage("edit-db-error"));
                    throw new RuntimeException(exception);
                }
                return new TargetInfo(uuid, name, false);
            }
        }

        sendMessage(sender, Chat.getMessage("edit-player-not-found"));
        return null;
    }

    private String commandNameOrIndex(String targetArg, Player onlineTarget){
        if(onlineTarget != null){
            return onlineTarget.getName();
        }
        return targetArg;
    }

    private List<String> getTargetSuggestions(){
        List<String> suggestions = new ArrayList<>();
        suggestions.add("*");

        for (Player player : Bukkit.getOnlinePlayers()){
            if(!suggestions.contains(player.getName())){
                suggestions.add(player.getName());
            }
        }

        return suggestions;
    }

    private List<String> getValueSuggestions(String[] strings){
        String type = normalize(strings[1]);
        int argIndex = strings.length - 1;
        if (type.equals("gamemode") && argIndex == 2) {
            List<String> options = new ArrayList<>();
            for (GameMode mode : GameMode.values()) {
                options.add(mode.name());
            }
            return options;
        }
        if (type.equals("health_scaled") && argIndex == 2) {
            return List.of("true", "false");
        }
        if (type.equals("location")) {
            return placeholderLocation(argIndex);
        }
        return List.of();
    }

    private List<String> placeholderLocation(int argIndex){
        return switch (argIndex) {
            case 2 -> List.of("<world_name>");
            case 3 -> List.of("<x>");
            case 4 -> List.of("<y>");
            case 5 -> List.of("<z>");
            case 6 -> List.of("<yaw>");
            case 7 -> List.of("<pitch>");
            default -> List.of();
        };
    }

    private List<String> filterByPrefix(Set<String> options, String prefix){
        List<String> result = new ArrayList<>();
        for (String option : options){
            if(prefix == null || prefix.isEmpty() || option.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))){
                result.add(option);
            }
        }
        return result;
    }

    private List<String> filterByPrefix(List<String> options, String prefix){
        List<String> result = new ArrayList<>();
        for (String option : options){
            if(prefix == null || prefix.isEmpty() || option.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))){
                result.add(option);
            }
        }
        return result;
    }

    private Player findOnlinePlayer(String name){
        for (Player player : Bukkit.getOnlinePlayers()){
            if(player.getName().equalsIgnoreCase(name)){
                return player;
            }
        }
        return null;
    }

    private String normalize(String value){
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private Float parseFloat(String value){
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String value){
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value){
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBoolean(String value){
        if(value == null) return null;
        if(value.equalsIgnoreCase("true")) return true;
        if(value.equalsIgnoreCase("false")) return false;
        return null;
    }

    private String getTableForInventoryType(String type){
        return switch (type) {
            case "inventory" -> Main.TABLE_NAME_INVENTORY;
            case "armor" -> Main.TABLE_NAME_ARMOR;
            case "enderchest" -> Main.TABLE_NAME_ENDERCHEST;
            default -> "";
        };
    }

    private String getColumnForInventoryType(String type){
        return switch (type) {
            case "inventory" -> "inventory";
            case "armor" -> "armor";
            case "enderchest" -> "enderchest";
            default -> "";
        };
    }

    private void sendMessage(CommandSender sender, String message){
        if(sender instanceof Player player){
            Main.platformScheduler.runForPlayer(player, () -> sender.sendMessage(message));
            return;
        }
        Main.platformScheduler.runGlobal(() -> sender.sendMessage(message));
    }

    private String buildUsageMessage(String usage){
        String command = Main.config.getString("settings.command-prefix");
        return Chat.getMessage("edit-usage")
                .replace("{command}", command)
                .replace("{usage}", usage);
    }

    private static class TargetInfo {
        private final String uuid;
        private final String name;
        private final boolean onlineLocal;

        private TargetInfo(String uuid, String name, boolean onlineLocal){
            this.uuid = uuid;
            this.name = name;
            this.onlineLocal = onlineLocal;
        }
    }
}
