package de.lostesburger.mySqlPlayerBridge.Sync;

import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;
import de.lostesburger.mySqlPlayerBridge.Exceptions.CrossVersionItemSyncException;
import de.lostesburger.mySqlPlayerBridge.Exceptions.NBTSerializationException;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Serialization.Serialization.AdvancementSerializer;
import de.lostesburger.mySqlPlayerBridge.Serialization.Serialization.PotionSerializer;
import de.lostesburger.mySqlPlayerBridge.Serialization.Serialization.StatsSerializer;
import de.lostesburger.mySqlPlayerBridge.Sync.Economy.EconomySyncModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Creates the built-in modules for the snapshot pipeline.
 */
public final class StandardModuleRegistryFactory {
    private StandardModuleRegistryFactory() {
    }

    public static ModuleRegistry create(PooledSqlManager sqlManager) {
        ModuleRegistry registry = new ModuleRegistry();
        PotionSerializer potionSerializer = new PotionSerializer();
        AdvancementSerializer advancementSerializer = new AdvancementSerializer();
        StatsSerializer statsSerializer = new StatsSerializer();

        registry.register(module(sqlManager, "inventory", Main.TABLE_NAME_INVENTORY,
                Main.modulesManager.syncInventory,
                player -> new TextSnapshot(serializeItems(player.getInventory().getContents())),
                data -> Map.of("inventory", data.value()),
                row -> new TextSnapshot(text(row, "inventory")),
                (player, data) -> itemApply(() -> player.getInventory().setContents(deserializeItems(data.value())),
                        "Inventory contains items not supported by this server version")));

        registry.register(module(sqlManager, "armor", Main.TABLE_NAME_ARMOR,
                Main.modulesManager.syncArmorSlots,
                player -> new TextSnapshot(serializeItems(player.getInventory().getArmorContents())),
                data -> Map.of("armor", data.value()),
                row -> new TextSnapshot(text(row, "armor")),
                (player, data) -> itemApply(() -> player.getInventory().setArmorContents(deserializeItems(data.value())),
                        "Armor contains items not supported by this server version")));

        registry.register(module(sqlManager, "enderchest", Main.TABLE_NAME_ENDERCHEST,
                Main.modulesManager.syncEnderChest,
                player -> new TextSnapshot(serializeItems(player.getEnderChest().getContents())),
                data -> Map.of("enderchest", data.value()),
                row -> new TextSnapshot(text(row, "enderchest")),
                (player, data) -> itemApply(() -> player.getEnderChest().setContents(deserializeItems(data.value())),
                        "Ender chest contains items not supported by this server version")));

        registry.register(module(sqlManager, "location", Main.TABLE_NAME_LOCATION,
                Main.modulesManager.syncLocation,
                player -> {
                    Location location = player.getLocation();
                    World world = location.getWorld();
                    if (world == null) {
                        throw new IllegalStateException("Player location has no world");
                    }
                    return new LocationSnapshot(world.getName(), location.getX(), location.getY(), location.getZ(),
                            location.getYaw(), location.getPitch());
                },
                data -> Map.of(
                        "world", data.world(), "x", data.x(), "y", data.y(), "z", data.z(),
                        "yaw", data.yaw(), "pitch", data.pitch()),
                row -> new LocationSnapshot(text(row, "world"), number(row, "x").doubleValue(),
                        number(row, "y").doubleValue(), number(row, "z").doubleValue(),
                        number(row, "yaw").floatValue(), number(row, "pitch").floatValue()),
                StandardModuleRegistryFactory::applyLocation));

        registry.register(module(sqlManager, "experience", Main.TABLE_NAME_EXP,
                Main.modulesManager.syncExp,
                player -> new ExperienceSnapshot(player.getExp(), player.getLevel()),
                data -> Map.of("exp", data.progress(), "exp_level", data.level()),
                row -> new ExperienceSnapshot(number(row, "exp").floatValue(), number(row, "exp_level").intValue()),
                (player, data) -> completed(() -> {
                    player.setLevel(data.level());
                    player.setExp(data.progress());
                })));

        registry.register(module(sqlManager, "health", Main.TABLE_NAME_HEALTH,
                Main.modulesManager.syncHealth,
                player -> {
                    AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHealth == null) {
                        throw new IllegalStateException("Player has no max-health attribute");
                    }
                    return new HealthSnapshot(player.getHealth(), maxHealth.getBaseValue(), player.isHealthScaled(),
                            player.getHealthScale());
                },
                data -> Map.of("health", data.health(), "max_health", data.maxHealth(),
                        "health_scaled", data.scaled(), "health_scale", data.scale()),
                row -> new HealthSnapshot(number(row, "health").doubleValue(),
                        number(row, "max_health").doubleValue(), bool(row, "health_scaled"),
                        number(row, "health_scale").doubleValue()),
                (player, data) -> completed(() -> {
                    AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHealth == null) {
                        throw new IllegalStateException("Player has no max-health attribute");
                    }
                    maxHealth.setBaseValue(data.maxHealth());
                    player.setHealth(Math.min(data.health(), player.getMaxHealth()));
                    player.setHealthScaled(data.scaled());
                    if (data.scaled()) {
                        player.setHealthScale(data.scale());
                    }
                })));

        registry.register(module(sqlManager, "gamemode", Main.TABLE_NAME_GAMEMODE,
                Main.modulesManager.syncGamemode,
                player -> new TextSnapshot(player.getGameMode().name()),
                data -> Map.of("gamemode", data.value()),
                row -> new TextSnapshot(text(row, "gamemode")),
                (player, data) -> completed(() -> player.setGameMode(GameMode.valueOf(data.value())))));

        registry.register(module(sqlManager, "effects", Main.TABLE_NAME_EFFECTS,
                Main.modulesManager.syncEffects,
                player -> new TextSnapshot(potionSerializer.serialize(player)),
                data -> Map.of("effects", data.value()),
                row -> new TextSnapshot(text(row, "effects")),
                (player, data) -> {
                    List<PotionEffect> effects = potionSerializer.deserialize(data.value());
                    return completed(() -> player.addPotionEffects(effects));
                }));

        registry.register(module(sqlManager, "advancements", Main.TABLE_NAME_ADVANCEMENTS,
                Main.modulesManager.syncAdvancements,
                player -> new TextSnapshot(advancementSerializer.serialize(player)),
                data -> Map.of("advancements", data.value()),
                row -> new TextSnapshot(text(row, "advancements")),
                (player, data) -> completed(() -> advancementSerializer.deserialize(data.value(), player, true))));

        registry.register(module(sqlManager, "stats", Main.TABLE_NAME_STATS,
                Main.modulesManager.syncStats,
                player -> new TextSnapshot(statsSerializer.serialize(player)),
                data -> Map.of("stats", data.value()),
                row -> new TextSnapshot(text(row, "stats")),
                (player, data) -> completed(() -> statsSerializer.deserialize(data.value(), player, true))));

        registry.register(module(sqlManager, "hotbar", Main.TABLE_NAME_SELECTED_HOTBAR_SLOT,
                Main.modulesManager.syncSelectedHotbarSlot,
                player -> new HotbarSnapshot(player.getInventory().getHeldItemSlot()),
                data -> Map.of("slot", data.slot()),
                row -> new HotbarSnapshot(number(row, "slot").intValue()),
                (player, data) -> completed(() -> {
                    if (data.slot() < 0 || data.slot() > 8) {
                        throw new IllegalArgumentException("Invalid hotbar slot: " + data.slot());
                    }
                    player.getInventory().setHeldItemSlot(data.slot());
                })));

        registry.register(module(sqlManager, "saturation", Main.TABLE_NAME_SATURATION,
                Main.modulesManager.syncSaturation,
                player -> new SaturationSnapshot(player.getSaturation(), player.getFoodLevel()),
                data -> Map.of("saturation", data.saturation(), "food_level", data.foodLevel()),
                row -> new SaturationSnapshot(number(row, "saturation").floatValue(),
                        number(row, "food_level").intValue()),
                (player, data) -> completed(() -> {
                    player.setFoodLevel(data.foodLevel());
                    player.setSaturation(data.saturation());
                })));

        registry.register(new EconomySyncModule(
                sqlManager,
                Main.vaultManager,
                Main.TABLE_NAME_MONEY,
                Main.modulesManager.syncVaultEconomy
        ));

        return registry;
    }

    private static CompletableFuture<Void> applyLocation(Player player, LocationSnapshot data) {
        World world = Bukkit.getWorld(data.world());
        if (world == null) {
            if (!Main.SUPPRESS_WARNINGS) {
                Main.getInstance().getLogger().log(Level.WARNING,
                        "[Location sync] World ''{0}'' is missing; location was not applied to {1}",
                        new Object[]{data.world(), player.getUniqueId()});
            }
            return CompletableFuture.completedFuture(null);
        }
        Location location = new Location(world, data.x(), data.y(), data.z(), data.yaw(), data.pitch());
        return player.teleportAsync(location).thenCompose(success -> success
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException("Teleport returned false")));
    }

    private static String serializeItems(org.bukkit.inventory.ItemStack[] items) throws Exception {
        if (Main.nbtSerializer == null) {
            throw new NBTSerializationException("NBT serializer is not loaded", null);
        }
        return Main.nbtSerializer.serialize(items);
    }

    private static org.bukkit.inventory.ItemStack[] deserializeItems(String value) throws Exception {
        if (Main.nbtSerializer == null) {
            throw new NBTSerializationException("NBT serializer is not loaded", null);
        }
        return Main.nbtSerializer.deserialize(value);
    }

    private static CompletableFuture<Void> itemApply(ThrowingRunnable action, String crossVersionMessage) {
        try {
            action.run();
            return CompletableFuture.completedFuture(null);
        } catch (Exception exception) {
            if (Main.modulesManager.crossVersionDenyJoinOnItemSyncError) {
                return CompletableFuture.failedFuture(new CrossVersionItemSyncException(crossVersionMessage, exception));
            }
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static CompletableFuture<Void> completed(ThrowingRunnable action) throws Exception {
        action.run();
        return CompletableFuture.completedFuture(null);
    }

    private static String text(Map<String, Object> row, String column) {
        Object value = required(row, column);
        return String.valueOf(value);
    }

    private static Number number(Map<String, Object> row, String column) {
        Object value = required(row, column);
        if (value instanceof Number number) {
            return number;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Column " + column + " is not numeric: " + value, exception);
        }
    }

    private static boolean bool(Map<String, Object> row, String column) {
        Object value = required(row, column);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Object required(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            throw new IllegalArgumentException("Required database column is null: " + column);
        }
        return value;
    }

    private static <T extends ModuleSnapshot> SyncModule<T> module(
            PooledSqlManager sqlManager,
            String id,
            String table,
            boolean enabled,
            Capture<T> capture,
            Encoder<T> encoder,
            Decoder<T> decoder,
            Applier<T> applier
    ) {
        return new DatabaseBackedModule<>(sqlManager, id, table, enabled, capture, encoder, decoder, applier);
    }

    private record DatabaseBackedModule<T extends ModuleSnapshot>(
            PooledSqlManager sqlManager,
            String id,
            String table,
            boolean enabled,
            Capture<T> captureFunction,
            Encoder<T> encoder,
            Decoder<T> decoder,
            Applier<T> applier
    ) implements SyncModule<T> {
        @Override
        public T capture(Player player) throws Exception {
            return this.captureFunction.capture(player);
        }

        @Override
        public void save(Connection connection, UUID playerUuid, T data) throws Exception {
            this.sqlManager.setOrUpdateEntry(connection, this.table, Map.of("uuid", playerUuid.toString()),
                    this.encoder.encode(data));
        }

        @Override
        public Optional<T> load(Connection connection, UUID playerUuid) throws Exception {
            Map<String, Object> row = this.sqlManager.getEntry(connection, this.table,
                    Map.of("uuid", playerUuid.toString()));
            return row == null || row.isEmpty() ? Optional.empty() : Optional.of(this.decoder.decode(row));
        }

        @Override
        public CompletableFuture<Void> apply(Player player, T data) throws Exception {
            return this.applier.apply(player, data);
        }
    }

    private record TextSnapshot(String value) implements ModuleSnapshot {
    }

    private record LocationSnapshot(String world, double x, double y, double z, float yaw, float pitch)
            implements ModuleSnapshot {
    }

    private record ExperienceSnapshot(float progress, int level) implements ModuleSnapshot {
    }

    private record HealthSnapshot(double health, double maxHealth, boolean scaled, double scale)
            implements ModuleSnapshot {
    }

    private record HotbarSnapshot(int slot) implements ModuleSnapshot {
    }

    private record SaturationSnapshot(float saturation, int foodLevel) implements ModuleSnapshot {
    }

    @FunctionalInterface
    private interface Capture<T extends ModuleSnapshot> {
        T capture(Player player) throws Exception;
    }

    @FunctionalInterface
    private interface Encoder<T extends ModuleSnapshot> {
        Map<String, Object> encode(T data) throws Exception;
    }

    @FunctionalInterface
    private interface Decoder<T extends ModuleSnapshot> {
        T decode(Map<String, Object> row) throws Exception;
    }

    @FunctionalInterface
    private interface Applier<T extends ModuleSnapshot> {
        CompletableFuture<Void> apply(Player player, T data) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
