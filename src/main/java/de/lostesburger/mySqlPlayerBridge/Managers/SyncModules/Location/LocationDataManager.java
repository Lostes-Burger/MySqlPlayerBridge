package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Location;

import de.craftcore.craftcore.global.minecraftVersion.Minecraft;
import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.craftcore.craftcore.global.scheduler.SchedulerException;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
            Scheduler.runAsync(() -> {
                this.save(player);
            }, Main.getInstance());
        }else {
            this.save(player);
        }

    }

    public void saveManual(UUID uuid, String world, double x, double y, double z, float yaw, float pitch, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), world, x, y, z, yaw, pitch);
            }, Main.getInstance());
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

    public void applyPlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_LOCATION,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Location", "load", Main.TABLE_NAME_LOCATION, player,
                        e, Map.of("uuid", player.getUniqueId().toString()), true);
                return;
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;


            World world = Bukkit.getWorld((String) entry.get("world"));
            if(world == null){
                if(!Main.SUPPRESS_WARNINGS) Main.getInstance().getLogger().log(Level.WARNING, "[Location sync] [World missing] Could not apply location data to "+player.getName()+" ("+player.getUniqueId()+toString()+") the latest current world could not be found! (unloaded or missing)");
                return;
            }
            Location location = new Location(world,
                    (Double) entry.get("x"),
                    (Double) entry.get("y"),
                    (Double) entry.get("z"),
                    (Float) entry.get("yaw"),
                    (Float) entry.get("pitch")
            );

            if (Minecraft.isFolia()){
                try {
                    Scheduler.runRegionalScheduler(() -> {
                        try {
                            Method teleportAsync = player.getClass().getMethod("teleportAsync", Location.class);
                            CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) teleportAsync.invoke(player, location);

                            future.thenAccept(success -> {
                                if (!success) {
                                    Bukkit.getLogger().warning("Failed to teleport player! Player: " + player.getName());
                                }
                            });
                        } catch (NoSuchMethodException e) {} catch (Exception e) {
                            MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                            String errorId = errorHandler.logSyncError("Location", "teleport", Main.TABLE_NAME_LOCATION, player,
                                    e, Map.of("uuid", player.getUniqueId().toString()), true);
                            HashMap<String, Object> data = new HashMap<>(entry);
                            data.put("uuid", player.getUniqueId().toString());
                            errorHandler.saveSyncData(errorId, "Location", "teleport", Main.TABLE_NAME_LOCATION, player, data);
                        }
                    }, Main.getInstance(), location);
                } catch (SchedulerException e) {
                    MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                    String errorId = errorHandler.logSyncError("Location", "teleport", Main.TABLE_NAME_LOCATION, player,
                            e, Map.of("uuid", player.getUniqueId().toString()), true);
                    HashMap<String, Object> data = new HashMap<>(entry);
                    data.put("uuid", player.getUniqueId().toString());
                    errorHandler.saveSyncData(errorId, "Location", "teleport", Main.TABLE_NAME_LOCATION, player, data);
                }
            }else {
                Scheduler.run(() -> {
                    try {
                        player.teleport(location);
                    } catch (Exception e) {
                        MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                        String errorId = errorHandler.logSyncError("Location", "teleport", Main.TABLE_NAME_LOCATION, player,
                                e, Map.of("uuid", player.getUniqueId().toString()), true);
                        HashMap<String, Object> data = new HashMap<>(entry);
                        data.put("uuid", player.getUniqueId().toString());
                        errorHandler.saveSyncData(errorId, "Location", "teleport", Main.TABLE_NAME_LOCATION, player, data);
                    }
                }, Main.getInstance());
            }

        }, Main.getInstance());

    }
}
