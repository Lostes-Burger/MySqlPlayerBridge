package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Location;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class LocationDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public LocationDataManager(){
        this.enabled = Main.modulesManager.syncLocation;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_LOCATION)){
                new MySqlErrorHandler().logSyncError("Location", "table-exists", Main.TABLE_NAME_LOCATION, null,
                        new RuntimeException("Location mysql table is missing!"), Map.of("table", Main.TABLE_NAME_LOCATION), false);
                throw new RuntimeException("Location mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Location", "table-exists", Main.TABLE_NAME_LOCATION, null,
                    e, Map.of("table", Main.TABLE_NAME_LOCATION), false);
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runEntity(player, () -> {
                    if(!this.enabled) return;
                    Location location = player.getLocation();
                    String uuid = player.getUniqueId().toString();
                    String world = Objects.requireNonNull(location.getWorld()).getName();
                    double x = location.getX();
                    double y = location.getY();
                    double z = location.getZ();
                    float yaw = location.getYaw();
                    float pitch = location.getPitch();
                    BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, world, x, y, z, yaw, pitch));
                });
            } else {
                Scheduler.runAsync(() -> {
                    this.save(player);
                }, Main.getInstance());
            }
        }else {
            this.save(player);
        }

    }

    public void saveManual(UUID uuid, String world, double x, double y, double z, float yaw, float pitch, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runAsync(() -> this.insertToMySql(uuid.toString(), world, x, y, z, yaw, pitch));
            } else {
                Scheduler.runAsync(() -> {
                    this.insertToMySql(uuid.toString(), world, x, y, z, yaw, pitch);
                }, Main.getInstance());
            }
        }else {
            this.insertToMySql(uuid.toString(), world, x, y, z, yaw, pitch);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;

        Location location = player.getLocation();
        String world = Objects.requireNonNull(location.getWorld()).getName();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        float yaw = location.getYaw();
        float pitch = location.getPitch();

        this.insertToMySql(player.getUniqueId().toString(), world, x, y, z, yaw, pitch);
    }

    private void insertToMySql(String uuid, String world, double x, double y, double z, float yaw, float pitch){
        HashMap<String, Object> map = new HashMap<>();
        map.put("world", world);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("yaw", yaw);
        map.put("pitch", pitch);

        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_LOCATION,
                    Map.of("uuid", uuid),
                    map
            );
        } catch (MySqlError e) {
            MySqlErrorHandler errorHandler = new MySqlErrorHandler();
            String errorId = errorHandler.logSyncError("Location", "save", Main.TABLE_NAME_LOCATION, null,
                    e, Map.of("uuid", uuid), false);
            HashMap<String, Object> data = new HashMap<>(map);
            data.put("uuid", uuid);
            errorHandler.saveSyncData(errorId, "Location", "save", Main.TABLE_NAME_LOCATION, null, data);
        }
    }

    public CompletableFuture<Void> applyPlayer(Player player){
        CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        Runnable loadTask = () -> {
            if(!this.enabled){
                completionFuture.complete(null);
                return;
            }

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_LOCATION,
                        Map.of("uuid", playerUuid.toString())
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Location", "load", Main.TABLE_NAME_LOCATION, player,
                        e, Map.of("uuid", playerUuid.toString()), true);
                completionFuture.completeExceptionally(e);
                return;
            }
            if(entry == null || entry.isEmpty()){
                completionFuture.complete(null);
                return;
            }


            World world = Bukkit.getWorld((String) entry.get("world"));
            if(world == null){
                if(!Main.SUPPRESS_WARNINGS) Main.getInstance().getLogger().log(Level.WARNING, "[Location sync] [World missing] Could not apply location data to "+playerName+" ("+playerUuid+") the latest current world could not be found! (unloaded or missing)");
                completionFuture.complete(null);
                return;
            }
            Location location = new Location(world,
                    (Double) entry.get("x"),
                    (Double) entry.get("y"),
                    (Double) entry.get("z"),
                    (Float) entry.get("yaw"),
                    (Float) entry.get("pitch")
            );

            if (Main.IS_FOLIA){
                boolean scheduled = BridgeScheduler.runEntity(player, () -> {
                    try {
                        Method teleportAsync = player.getClass().getMethod("teleportAsync", Location.class);
                        CompletableFuture<Boolean> teleportFuture = (CompletableFuture<Boolean>) teleportAsync.invoke(player, location);

                        teleportFuture.orTimeout(4500L, TimeUnit.MILLISECONDS).whenComplete((success, throwable) -> {
                            if (throwable != null) {
                                completionFuture.completeExceptionally(throwable);
                                return;
                            }
                            if (!Boolean.TRUE.equals(success)) {
                                Bukkit.getLogger().warning("Failed to teleport player! Player: " + playerName);
                            }
                            completionFuture.complete(null);
                        });
                    } catch (NoSuchMethodException e) {
                        completionFuture.completeExceptionally(e);
                    } catch (Exception e) {
                        MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                        String errorId = errorHandler.logSyncError("Location", "teleport", Main.TABLE_NAME_LOCATION, player,
                                e, Map.of("uuid", playerUuid.toString()), true);
                        HashMap<String, Object> data = new HashMap<>(entry);
                        data.put("uuid", playerUuid.toString());
                        errorHandler.saveSyncData(errorId, "Location", "teleport", Main.TABLE_NAME_LOCATION, player, data);
                        completionFuture.completeExceptionally(e);
                    }
                }, () -> completionFuture.complete(null));
                if (!scheduled) {
                    completionFuture.complete(null);
                }
            }else {
                Scheduler.run(() -> {
                    try {
                        player.teleport(location);
                    } catch (Exception e) {
                        MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                        String errorId = errorHandler.logSyncError("Location", "teleport", Main.TABLE_NAME_LOCATION, player,
                                e, Map.of("uuid", playerUuid.toString()), true);
                        HashMap<String, Object> data = new HashMap<>(entry);
                        data.put("uuid", playerUuid.toString());
                        errorHandler.saveSyncData(errorId, "Location", "teleport", Main.TABLE_NAME_LOCATION, player, data);
                        completionFuture.completeExceptionally(e);
                        return;
                    }
                    completionFuture.complete(null);
                }, Main.getInstance());
            }

        };
        if (Main.IS_FOLIA) {
            BridgeScheduler.runAsync(loadTask);
        } else {
            Scheduler.runAsync(loadTask, Main.getInstance());
        }
        return completionFuture;
    }
}
