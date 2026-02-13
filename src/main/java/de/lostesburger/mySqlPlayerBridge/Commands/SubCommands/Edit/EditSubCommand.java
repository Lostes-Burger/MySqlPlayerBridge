package de.lostesburger.mySqlPlayerBridge.Commands.SubCommands.Edit;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.craftcore.craftcore.paper.command.commandmanager.ServerCommand;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.Edit.EditGuiManager;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
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
    private static final Set<String> PLAYER_ONLY_TYPES = Set.of("inventory", "armor", "enderchest", "offhand");
    private static final Set<String> ALL_TYPES = Set.of(
            "inventory", "armor", "enderchest", "offhand",
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
        Scheduler.runAsync(() -> {
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, admin);
            if(targetInfo == null) return;

            MySqlManager manager = Main.mySqlConnectionHandler.getManager();
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
            } catch (MySqlError e) {
                sendMessage(admin, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }

            String finalSerialized = serialized;
            Scheduler.run(() -> {
                editGuiManager.openInventoryEditor(admin, UUID.fromString(targetInfo.uuid), targetInfo.name, typeArg, finalSerialized);
            }, Main.getInstance());
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_EXP, Map.of("exp", exp), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_EXP, targetInfo.uuid, Map.of("exp", exp), sender);
            applyExp(onlineTarget, exp, null);
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_EXP, Map.of("exp_level", level), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_EXP, targetInfo.uuid, Map.of("exp_level", level), sender);
            applyExp(onlineTarget, null, level);
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
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
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
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
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_HEALTH, Map.of("health_scaled", scaled), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_HEALTH, targetInfo.uuid, Map.of("health_scaled", scaled), sender);
            applyHealth(onlineTarget, null, null, scaled, null);
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_HEALTH, Map.of("health_scale", scale), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_HEALTH, targetInfo.uuid, Map.of("health_scale", scale), sender);
            applyHealth(onlineTarget, null, null, null, scale);
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_SATURATION, Map.of("saturation", saturation), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_SATURATION, targetInfo.uuid, Map.of("saturation", saturation), sender);
            applySaturation(onlineTarget, saturation, null);
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_SATURATION, Map.of("food_level", food), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_SATURATION, targetInfo.uuid, Map.of("food_level", food), sender);
            applySaturation(onlineTarget, null, food);
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_GAMEMODE, Map.of("gamemode", gamemodeString), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_GAMEMODE, targetInfo.uuid, Map.of("gamemode", gamemodeString), sender);
            applyGamemode(onlineTarget, gameMode);
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
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
        }, Main.getInstance());
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
        Scheduler.runAsync(() -> {
            if(wildcard){
                updateAllEntries(Main.TABLE_NAME_MONEY, Map.of("money", money), sender);
                return;
            }
            TargetInfo targetInfo = resolveTarget(commandNameOrIndex(targetArg, onlineTarget), onlineTarget, sender);
            if(targetInfo == null) return;
            updateSingleEntry(Main.TABLE_NAME_MONEY, targetInfo.uuid, Map.of("money", money), sender);
            applyMoney(onlineTarget, money);
        }, Main.getInstance());
    }

    private void updateAllEntries(String table, Map<String, Object> update, CommandSender sender){
        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(table);
        } catch (MySqlError e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        for (Map<String, Object> entry : entries){
            Object uuid = entry.get("uuid");
            if(uuid == null) continue;
            try {
                manager.setOrUpdateEntry(table, Map.of("uuid", String.valueOf(uuid)), update);
            } catch (MySqlError e) {
                sendMessage(sender, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }
        }
        sendMessage(sender, Chat.getMessage("edit-success-all"));
    }

    private void updateSingleEntry(String table, String uuid, Map<String, Object> update, CommandSender sender){
        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        try {
            manager.setOrUpdateEntry(table, Map.of("uuid", uuid), update);
        } catch (MySqlError e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        sendMessage(sender, Chat.getMessage("edit-success"));
    }

    private void updateHealthForAll(double health, CommandSender sender){
        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(Main.TABLE_NAME_HEALTH);
        } catch (MySqlError e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        for (Map<String, Object> entry : entries){
            String uuid = String.valueOf(entry.get("uuid"));
            Double maxHealth = entry.get("max_health") instanceof Double ? (Double) entry.get("max_health") : null;
            double newHealth = maxHealth == null ? health : Math.min(health, maxHealth);
            try {
                manager.setOrUpdateEntry(Main.TABLE_NAME_HEALTH, Map.of("uuid", uuid), Map.of("health", newHealth));
            } catch (MySqlError e) {
                sendMessage(sender, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }
        }
        sendMessage(sender, Chat.getMessage("edit-success-all"));
    }

    private void updateHealthMaxForAll(double maxHealth, CommandSender sender){
        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(Main.TABLE_NAME_HEALTH);
        } catch (MySqlError e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }
        for (Map<String, Object> entry : entries){
            String uuid = String.valueOf(entry.get("uuid"));
            Double currentHealth = entry.get("health") instanceof Double ? (Double) entry.get("health") : null;
            Map<String, Object> update = new HashMap<>();
            update.put("max_health", maxHealth);
            if(currentHealth != null && currentHealth > maxHealth){
                update.put("health", maxHealth);
            }
            try {
                manager.setOrUpdateEntry(Main.TABLE_NAME_HEALTH, Map.of("uuid", uuid), update);
            } catch (MySqlError e) {
                sendMessage(sender, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }
        }
        sendMessage(sender, Chat.getMessage("edit-success-all"));
    }

    private void updateLocationForAll(String world, double x, double y, double z, Float yaw, Float pitch, CommandSender sender){
        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(Main.TABLE_NAME_LOCATION);
        } catch (MySqlError e) {
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
            } catch (MySqlError e) {
                sendMessage(sender, Chat.getMessage("edit-db-error"));
                throw new RuntimeException(e);
            }
        }
        sendMessage(sender, Chat.getMessage("edit-success-all"));
    }

    private void applyExp(Player onlineTarget, Float exp, Integer level){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        Scheduler.run(() -> {
            if(exp != null){
                onlineTarget.setExp(exp);
            }
            if(level != null){
                onlineTarget.setLevel(level);
            }
        }, Main.getInstance());
    }

    private void applyHealth(Player onlineTarget, Double health, Double maxHealth, Boolean scaled, Double scale){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        Scheduler.run(() -> {
            if(maxHealth != null && onlineTarget.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null){
                onlineTarget.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
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
        }, Main.getInstance());
    }

    private void applySaturation(Player onlineTarget, Float saturation, Integer foodLevel){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        Scheduler.run(() -> {
            if(saturation != null){
                onlineTarget.setSaturation(saturation);
            }
            if(foodLevel != null){
                onlineTarget.setFoodLevel(foodLevel);
            }
        }, Main.getInstance());
    }

    private void applyGamemode(Player onlineTarget, GameMode gamemode){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        Scheduler.run(() -> onlineTarget.setGameMode(gamemode), Main.getInstance());
    }

    private void applyLocation(Player onlineTarget, String worldName, double x, double y, double z, Float yaw, Float pitch){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        Scheduler.run(() -> {
            World world = Bukkit.getWorld(worldName);
            if(world == null) return;
            float useYaw = yaw != null ? yaw : onlineTarget.getLocation().getYaw();
            float usePitch = pitch != null ? pitch : onlineTarget.getLocation().getPitch();
            Location location = new Location(world, x, y, z, useYaw, usePitch);
            onlineTarget.teleport(location);
        }, Main.getInstance());
    }

    private void applyMoney(Player onlineTarget, double money){
        if(onlineTarget == null || !onlineTarget.isOnline()) return;
        if(Main.vaultManager == null) return;
        Scheduler.run(() -> Main.vaultManager.setBalance(onlineTarget, money), Main.getInstance());
    }

    private Double getHealthMax(String uuid){
        Map<String, Object> entry = getEntry(Main.TABLE_NAME_HEALTH, uuid);
        if(entry == null || entry.isEmpty()){
            return null;
        }
        Object value = entry.get("max_health");
        return value instanceof Double ? (Double) value : null;
    }

    private Double getHealthValue(String uuid){
        Map<String, Object> entry = getEntry(Main.TABLE_NAME_HEALTH, uuid);
        if(entry == null || entry.isEmpty()){
            return null;
        }
        Object value = entry.get("health");
        return value instanceof Double ? (Double) value : null;
    }

    private Map<String, Object> getEntry(String table, String uuid){
        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        try {
            return manager.getEntry(table, Map.of("uuid", uuid));
        } catch (MySqlError e) {
            return null;
        }
    }

    private TargetInfo resolveTarget(String targetName, Player onlineTarget, CommandSender sender){
        if(onlineTarget != null){
            return new TargetInfo(onlineTarget.getUniqueId().toString(), onlineTarget.getName(), true);
        }

        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        List<Map<String, Object>> entries;
        try {
            entries = manager.getAllEntries(Main.TABLE_NAME_PLAYER_INDEX);
        } catch (MySqlError e) {
            sendMessage(sender, Chat.getMessage("edit-db-error"));
            throw new RuntimeException(e);
        }

        for (Map<String, Object> entry : entries){
            String name = String.valueOf(entry.get("player_name"));
            if(name.equalsIgnoreCase(targetName)){
                String uuid = String.valueOf(entry.get("uuid"));
                Object online = entry.get("online");
                boolean isOnline = online instanceof Boolean && (Boolean) online;
                if(isOnline){
                    sendMessage(sender, Chat.getMessage("edit-player-online-other-server"));
                    return null;
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

        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        try {
            List<Map<String, Object>> entries = manager.getAllEntries(Main.TABLE_NAME_PLAYER_INDEX);
            for (Map<String, Object> entry : entries){
                String name = String.valueOf(entry.get("player_name"));
                if(name == null || name.isEmpty()) continue;
                suggestions.add(name);
            }
        } catch (MySqlError ignored) {
        }

        for (Player player : Bukkit.getOnlinePlayers()){
            if(!suggestions.contains(player.getName())){
                suggestions.add(player.getName());
            }
        }

        return suggestions;
    }

    private List<String> getValueSuggestions(String[] strings){
        String targetName = strings[0];
        String type = normalize(strings[1]);
        int argIndex = strings.length - 1;

        MySqlManager manager = Main.mySqlConnectionHandler.getManager();
        Map<String, Object> indexEntry = findIndexEntryByName(manager, targetName);
        if(indexEntry == null){
            return List.of();
        }
        String uuid = String.valueOf(indexEntry.get("uuid"));

        try {
            switch (type){
                case "exp" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_EXP, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "exp");
                }
                case "exp_level" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_EXP, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "exp_level");
                }
                case "health" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_HEALTH, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "health");
                }
                case "health_max" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_HEALTH, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "max_health");
                }
                case "health_scaled" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_HEALTH, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "health_scaled");
                }
                case "health_scale" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_HEALTH, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "health_scale");
                }
                case "saturation" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_SATURATION, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "saturation");
                }
                case "food_level" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_SATURATION, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "food_level");
                }
                case "gamemode" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_GAMEMODE, Map.of("uuid", uuid));
                    if(argIndex != 2){
                        return List.of();
                    }
                    return buildGamemodeSuggestions(entry);
                }
                case "money" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_MONEY, Map.of("uuid", uuid));
                    return tabValue(argIndex, 2, entry, "money");
                }
                case "location" -> {
                    Map<String, Object> entry = manager.getEntry(Main.TABLE_NAME_LOCATION, Map.of("uuid", uuid));
                    if(entry == null || entry.isEmpty()){
                        return placeholderLocation(argIndex);
                    }
                    if(argIndex == 2){
                        return List.of(String.valueOf(entry.get("world")));
                    }
                    if(argIndex == 3){
                        return List.of(String.valueOf(entry.get("x")));
                    }
                    if(argIndex == 4){
                        return List.of(String.valueOf(entry.get("y")));
                    }
                    if(argIndex == 5){
                        return List.of(String.valueOf(entry.get("z")));
                    }
                    if(argIndex == 6){
                        return List.of(String.valueOf(entry.get("yaw")));
                    }
                    if(argIndex == 7){
                        return List.of(String.valueOf(entry.get("pitch")));
                    }
                    return List.of();
                }
                default -> {
                    return List.of();
                }
            }
        } catch (MySqlError e) {
            return List.of();
        }
    }

    private Map<String, Object> findIndexEntryByName(MySqlManager manager, String name){
        try {
            List<Map<String, Object>> entries = manager.getAllEntries(Main.TABLE_NAME_PLAYER_INDEX);
            for (Map<String, Object> entry : entries){
                String entryName = String.valueOf(entry.get("player_name"));
                if(entryName.equalsIgnoreCase(name)){
                    return entry;
                }
            }
        } catch (MySqlError ignored) {
        }
        return null;
    }

    private List<String> tabValue(int argIndex, int valueIndex, Map<String, Object> entry, String key){
        if(argIndex != valueIndex){
            return List.of();
        }
        if(entry == null || entry.isEmpty()){
            return List.of();
        }
        Object value = entry.get(key);
        if(value == null){
            return List.of();
        }
        return List.of(String.valueOf(value));
    }

    private List<String> buildGamemodeSuggestions(Map<String, Object> entry){
        List<String> options = new ArrayList<>();
        if(entry != null && !entry.isEmpty()){
            Object current = entry.get("gamemode");
            if(current != null){
                options.add(String.valueOf(current));
            }
        }
        for (GameMode mode : GameMode.values()){
            String name = mode.name();
            if(!options.contains(name)){
                options.add(name);
            }
        }
        return options;
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
            case "offhand" -> Main.TABLE_NAME_INVENTORY;
            default -> "";
        };
    }

    private String getColumnForInventoryType(String type){
        return switch (type) {
            case "inventory" -> "inventory";
            case "armor" -> "armor";
            case "enderchest" -> "enderchest";
            case "offhand" -> "inventory";
            default -> "";
        };
    }

    private void sendMessage(CommandSender sender, String message){
        Scheduler.run(() -> sender.sendMessage(message), Main.getInstance());
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
