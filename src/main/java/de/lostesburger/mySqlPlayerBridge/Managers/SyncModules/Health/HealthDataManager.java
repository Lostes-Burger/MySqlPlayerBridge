package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Health;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HealthDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public HealthDataManager() {
        this.enabled = Main.modulesManager.syncHealth;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if (!this.mySqlManager.tableExists(Main.TABLE_NAME_HEALTH)) {
                new MySqlErrorHandler().logSyncError("Health", "table-exists", Main.TABLE_NAME_HEALTH, null,
                        new RuntimeException("Health mysql table is missing!"), Map.of("table", Main.TABLE_NAME_HEALTH), false);
                throw new RuntimeException("Health mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Health", "table-exists", Main.TABLE_NAME_HEALTH, null,
                    e, Map.of("table", Main.TABLE_NAME_HEALTH), false);
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async) {
        if (async) {
            Scheduler.runAsync(() -> {
                this.save(player);
            }, Main.getInstance());
        } else {
            this.save(player);
        }
    }

    public void saveManual(UUID uuid, double health, double health_max, boolean health_scaled, double health_scale, boolean async) {
        if (async) {
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), health, health_max, health_scaled, health_scale);
            }, Main.getInstance());
        } else {
            this.insertToMySql(uuid.toString(), health, health_max, health_scaled, health_scale);
        }
    }

    private void save(Player player) {
        if (!this.enabled) return;
        double maxHp = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        this.insertToMySql(
                player.getUniqueId().toString(),
                player.getHealth(),
                maxHp,
                player.isHealthScaled(),
                player.getHealthScale()
        );
    }

    private void insertToMySql(String uuid, double health, double health_max, boolean health_scaled, double health_scale) {
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_HEALTH,
                    Map.of("uuid", uuid),
                    Map.of(
                            "health", health,
                            "max_health", health_max,
                            "health_scaled", health_scaled,
                            "health_scale", health_scale
                    )
            );
        } catch (MySqlError e) {
            MySqlErrorHandler errorHandler = new MySqlErrorHandler();
            String errorId = errorHandler.logSyncError("Health", "save", Main.TABLE_NAME_HEALTH, null,
                    e, Map.of("uuid", uuid), false);
            HashMap<String, Object> data = new HashMap<>();
            data.put("uuid", uuid);
            data.put("health", health);
            data.put("max_health", health_max);
            data.put("health_scaled", health_scaled);
            data.put("health_scale", health_scale);
            errorHandler.saveSyncData(errorId, "Health", "save", Main.TABLE_NAME_HEALTH, null, data);
        }
    }

    public void applyPlayer(Player player) {
        Scheduler.runAsync(() -> {
            if (!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_HEALTH,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Health", "load", Main.TABLE_NAME_HEALTH, player,
                        e, Map.of("uuid", player.getUniqueId().toString()), true);
                return;
            }
            if (entry == null) return;
            if (entry.isEmpty()) return;

            double max_health = (Double) entry.get("max_health");
            double health = (Double) entry.get("health");
            boolean scaled = (boolean) entry.get("health_scaled");
            double scale = (Double) entry.get("health_scale");

            Scheduler.run(() -> {
                try {
                    if(health > player.getMaxHealth()){
                        player.setHealth(player.getMaxHealth());
                    }else {
                        player.setHealth(health);
                    }
                    if(scaled) {
                        player.setHealthScaled(true);
                        player.setHealthScale(scale);
                    }
                } catch (Exception e) {
                    MySqlErrorHandler errorHandler = new MySqlErrorHandler();
                    String errorId = errorHandler.logSyncError("Health", "apply", Main.TABLE_NAME_HEALTH, player,
                            e, Map.of("uuid", player.getUniqueId().toString()), true);
                    HashMap<String, Object> data = new HashMap<>(entry);
                    data.put("uuid", player.getUniqueId().toString());
                    errorHandler.saveSyncData(errorId, "Health", "apply", Main.TABLE_NAME_HEALTH, player, data);
                }
            }, Main.getInstance());

        }, Main.getInstance());
    }
}
