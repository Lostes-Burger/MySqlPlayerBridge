package de.lostesburger.mySqlPlayerBridge.Managers.Edit;

import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** A validated change shared by single-player and wildcard edits. */
public record EditChange(String type, Map<String, Object> values) {
    public EditChange {
        values = Map.copyOf(values);
    }

    public static EditChange parse(String type, String[] args) {
        if (type.equals("location")) {
            if (args.length != 6 && args.length != 8) throw new IllegalArgumentException("location <world> <x> <y> <z> [yaw pitch]");
            Map<String, Object> values = new HashMap<>();
            values.put("world", args[2]);
            values.put("x", finite(args[3]));
            values.put("y", finite(args[4]));
            values.put("z", finite(args[5]));
            if (args.length == 8) {
                values.put("yaw", finiteFloat(args[6]));
                values.put("pitch", finiteFloat(args[7]));
            }
            return new EditChange(type, values);
        }
        if (args.length != 3) throw new IllegalArgumentException(type + " <value>");
        String input = args[2];
        Object value = switch (type) {
            case "exp" -> range(finiteFloat(input), 0, 1);
            case "exp_level" -> range(Integer.parseInt(input), 0, Integer.MAX_VALUE);
            case "food_level" -> range(Integer.parseInt(input), 0, 20);
            case "saturation" -> range(finiteFloat(input), 0, 20);
            case "health", "money" -> range(finite(input), 0, Double.MAX_VALUE);
            case "health_max", "health_scale" -> range(finite(input), Double.MIN_VALUE, Double.MAX_VALUE);
            case "health_scaled" -> {
                if (!input.equalsIgnoreCase("true") && !input.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("true / false");
                }
                yield Boolean.parseBoolean(input);
            }
            case "gamemode" -> GameMode.valueOf(input.toUpperCase(java.util.Locale.ROOT)).name();
            default -> throw new IllegalArgumentException("Unknown data type: " + type);
        };
        return new EditChange(type, Map.of(column(type), value));
    }

    public String moduleId() {
        return switch (type) {
            case "exp", "exp_level" -> "experience";
            case "money" -> "economy";
            case "health", "health_max", "health_scaled", "health_scale" -> "health";
            case "saturation", "food_level" -> "saturation";
            default -> type;
        };
    }

    public String table() {
        return tableFor(type);
    }

    public static String tableFor(String type) {
        return switch (type) {
            case "exp", "exp_level" -> Main.TABLE_NAME_EXP;
            case "health", "health_max", "health_scaled", "health_scale" -> Main.TABLE_NAME_HEALTH;
            case "saturation", "food_level" -> Main.TABLE_NAME_SATURATION;
            case "gamemode" -> Main.TABLE_NAME_GAMEMODE;
            case "location" -> Main.TABLE_NAME_LOCATION;
            case "money" -> Main.TABLE_NAME_MONEY;
            case "inventory" -> Main.TABLE_NAME_INVENTORY;
            case "armor" -> Main.TABLE_NAME_ARMOR;
            case "enderchest" -> Main.TABLE_NAME_ENDERCHEST;
            default -> throw new IllegalArgumentException("Unknown data type: " + type);
        };
    }

    public static String column(String type) {
        return type.equals("health_max") ? "max_health" : type;
    }

    /** Called inside the offline lease transaction, so validation sees the row being changed. */
    public Map<String, Object> offlineValues(Map<String, Object> current, boolean wildcard) {
        Map<String, Object> update = new HashMap<>(values);
        if (current == null) current = Map.of();
        if (type.equals("health")) {
            double maximum = number(current, "max_health", 20);
            double health = number(values, "health", 0);
            if (!wildcard && health > maximum) throw new IllegalArgumentException("health > max (" + maximum + ")");
            update.put("health", Math.min(health, maximum));
        }
        if (type.equals("health_max")) {
            Double currentHealth = current.get("health") instanceof Number number ? number.doubleValue() : null;
            double maximum = number(values, "max_health", 20);
            if (currentHealth != null && !wildcard && currentHealth > maximum) {
                throw new IllegalArgumentException("max < health (" + currentHealth + ")");
            }
            if (currentHealth != null && currentHealth > maximum) update.put("health", maximum);
        }
        if (type.equals("health_scale")) update.put("health_scaled", true);
        if (type.equals("location") && !update.containsKey("yaw")) {
            update.put("yaw", number(current, "yaw", 0));
            update.put("pitch", number(current, "pitch", 0));
        }
        // A first edit must not create a partial module row that the next join cannot decode.
        Map<String, Object> defaults = switch (moduleId()) {
            case "experience" -> Map.of("exp", 0f, "exp_level", 0);
            case "health" -> Map.of("health", 20d, "max_health", 20d, "health_scaled", false, "health_scale", 20d);
            case "saturation" -> Map.of("saturation", 5f, "food_level", 20);
            default -> Map.of();
        };
        for (var entry : defaults.entrySet()) {
            if (current.get(entry.getKey()) == null) update.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return update;
    }

    /** Must run on the target's entity scheduler. */
    public CompletableFuture<Void> apply(Player player, boolean wildcard) {
        Object value = values.get(column(type));
        switch (type) {
            case "exp" -> player.setExp(((Number) value).floatValue());
            case "exp_level" -> player.setLevel(((Number) value).intValue());
            case "health" -> {
                double health = ((Number) value).doubleValue();
                double maximum = player.getMaxHealth();
                if (!wildcard && health > maximum) throw new IllegalArgumentException("health > max (" + maximum + ")");
                player.setHealth(Math.min(health, maximum));
            }
            case "health_max" -> {
                double maximum = ((Number) value).doubleValue();
                if (!wildcard && player.getHealth() > maximum) throw new IllegalArgumentException("max < health (" + player.getHealth() + ")");
                var attribute = player.getAttribute(Attribute.MAX_HEALTH);
                if (attribute == null) throw new IllegalStateException("Missing max health attribute");
                attribute.setBaseValue(maximum);
                if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
            }
            case "health_scaled" -> player.setHealthScaled((Boolean) value);
            case "health_scale" -> {
                player.setHealthScale(((Number) value).doubleValue());
                player.setHealthScaled(true);
            }
            case "saturation" -> player.setSaturation(((Number) value).floatValue());
            case "food_level" -> player.setFoodLevel(((Number) value).intValue());
            case "gamemode" -> player.setGameMode(GameMode.valueOf((String) value));
            case "money" -> Main.vaultManager.setBalance(player, ((Number) value).doubleValue());
            case "location" -> {
                var world = Bukkit.getWorld((String) values.get("world"));
                if (world == null) throw new IllegalArgumentException("Unknown world: " + values.get("world"));
                Location old = player.getLocation();
                Location destination = new Location(world, number(values, "x", 0), number(values, "y", 0),
                        number(values, "z", 0), (float) number(values, "yaw", old.getYaw()),
                        (float) number(values, "pitch", old.getPitch()));
                return player.teleportAsync(destination).thenAccept(success -> {
                    if (!success) throw new IllegalStateException("Teleport was cancelled");
                });
            }
            default -> throw new IllegalArgumentException("Unsupported live edit: " + type);
        }
        return CompletableFuture.completedFuture(null);
    }

    private static double number(Map<String, Object> values, String key, double fallback) {
        return values.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    private static double finite(String input) {
        double value = Double.parseDouble(input);
        if (!Double.isFinite(value)) throw new IllegalArgumentException(input);
        return value;
    }

    private static float finiteFloat(String input) {
        float value = Float.parseFloat(input);
        if (!Float.isFinite(value)) throw new IllegalArgumentException(input);
        return value;
    }

    private static <T extends Number> T range(T value, double min, double max) {
        if (value.doubleValue() < min || value.doubleValue() > max) throw new IllegalArgumentException(value.toString());
        return value;
    }
}
