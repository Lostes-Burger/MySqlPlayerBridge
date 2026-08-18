package de.lostesburger.mySqlPlayerBridge.Handlers.Migration;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class MySqlMigrationHandler implements Listener {
    private static final long MIGRATION_FAILSAFE_TIMEOUT_MS = 15 * 60 * 1000L;
    private final MySqlManager mySqlManager;
    private final CompletableFuture<Void> migrationCompletion = new CompletableFuture<>();
    private volatile boolean runningMigration;

    public MySqlMigrationHandler(){
        mySqlManager = Main.mySqlConnectionHandler.getManager();
        try {
            runningMigration = mySqlManager.tableExists(Main.TABLE_NAME);
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
        if(runningMigration){
            Main.getInstance().getLogger().warning("[Migration detected] [Database structure changed] The database structure has changed! The server will now begin running the migration. All player connections will be terminated and future incoming connections refused until the migration is done!");
            Main.getInstance().getLogger().warning("[Migration detected] [Checking conditions] Checking if other Server is currently handling the migration...");

            Bukkit.getServer().getPluginManager().registerEvents(this, Main.getInstance());
            Bukkit.getOnlinePlayers().forEach(player -> {
                player.kickPlayer("§c[MySqlPlayerBridge] Running database migration -> Try again later!");
            });

            if(this.isRunningMigration()){
                Main.getInstance().getLogger().warning("[Database Migration] [Failed conditions] Another Server is currently handling the migration process. This server will wait until the migration is finished!");

                final Scheduler.Task[] taskHolder = new Scheduler.Task[1];
                final int[] time = {0};
                taskHolder[0] = Scheduler.runTimerAsync(() -> {
                    try {
                        time[0]++;
                        Main.getInstance().getLogger().warning("[Database Migration] [Waiting] Another Server is currently handling the migration process. This server will wait until the migration is finished! Waited for "+ time[0]*5 +"seconds");
                        this.runningMigration = this.isRunningMigration();

                        if(this.runningMigration){
                            return;
                        }

                        Main.getInstance().getLogger().warning("[Database Migration] [Migration done] Migration finished by other server. Waited for "+ time[0]*5 +"seconds. Starting plugin now...");
                        taskHolder[0].cancel();
                        this.migrateLegacyPlayerIndexTable();
                        this.migrationCompletion.complete(null);
                    } catch (RuntimeException e) {
                        taskHolder[0].cancel();
                        this.migrationCompletion.completeExceptionally(e);
                    }
                }, 5*20, 5*20, Main.getInstance());
            }else {
                Main.getInstance().getLogger().warning("[Database Migration] [Passed conditions] All conditions passed! This Server will now handle the database migration. DO NOT SHUT DOWN WHILE MIGRATING!");
                this.startMigration();
            }
            return;
        }

        BridgeScheduler.runAsync(() -> {
            try {
                this.migrateLegacyPlayerIndexTable();
                this.migrationCompletion.complete(null);
            } catch (RuntimeException e) {
                this.migrationCompletion.completeExceptionally(e);
            }
        });
    }

    private boolean isRunningMigration(){
        Map<String, Object> entry;
        try {
            entry = mySqlManager.getEntry(Main.TABLE_NAME_MIGRATION, Map.of("migration", "migration"));
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }

        if(entry == null) return false;
        if(entry.isEmpty()) return false;

        boolean runningMigration = Boolean.TRUE.equals(entry.get("running_migration"));
        if(!runningMigration) return false;

        long migrationTimestamp = extractMigrationTimestamp(entry);
        long now = Instant.now().toEpochMilli();
        if(migrationTimestamp <= 0L || now - migrationTimestamp > MIGRATION_FAILSAFE_TIMEOUT_MS){
            Main.getInstance().getLogger().warning("[Database Migration] [Failsafe triggered] Migration lock exceeded failsafe timeout. Resetting running_migration to false.");
            this.resetRunningMigrationState();
            return false;
        }

        return true;
    }

    private long extractMigrationTimestamp(Map<String, Object> entry){
        Object rawTimestamp = entry.get("timestamp");
        if(rawTimestamp == null) return -1L;

        if(rawTimestamp instanceof Number number){
            return number.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(rawTimestamp));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private void resetRunningMigrationState(){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_MIGRATION,
                    Map.of("migration", "migration"),
                    Map.of("running_migration", false)
            );
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }
    public void runWhenMigrationComplete(Runnable runnable) {
        this.migrationCompletion.whenComplete((ignored, throwable) -> {
            if(throwable != null){
                Main.getInstance().getLogger().log(Level.SEVERE, "[Database Migration] Migration failed. Plugin startup will not continue.", throwable);
                return;
            }
            BridgeScheduler.runGlobal(() -> {
                if(Main.getInstance().isEnabled()){
                    HandlerList.unregisterAll(this);
                    runnable.run();
                }
            });
        });
    }


    private void startMigration(){
        long timestamp = Instant.now().toEpochMilli();
        try {
            mySqlManager.setOrUpdateEntry(Main.TABLE_NAME_MIGRATION, Map.of("migration", "migration"), Map.of("running_migration", true, "timestamp", timestamp));
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }

        Runnable migrationTask = () -> {
            try {
                List<Map<String, Object>> entries = mySqlManager.getAllEntries(Main.TABLE_NAME);
                int counter = 0;
                int max = entries.size();
                Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Starting] Starting database migration now! Data to migrate: "+max);
                Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Backup] If any problems should occur the old data will be saved. The mysql table will be renamed to "+Main.TABLE_NAME+"_backup.");

                for (Map<String, Object> entry : entries){
                    counter++;

                    String uuidString = (String) entry.get("uuid");
                    UUID uuid = UUID.fromString(uuidString);
                    Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] ["+counter+"/"+max+"] Running migration for player: "+uuidString);

                    String inventory = (String) entry.get("inventory");
                    String armor = (String) entry.get("armor");
                    String enderchest = (String) entry.get("enderchest");
                    String gamemode = (String) entry.get("gamemode");
                    int exp_level = (Integer) entry.get("exp_level");
                    float exp = (Float) entry.get("exp");
                    double money = (Double) entry.get("money");

                    String world = (String) entry.get("world");
                    double x = (Double) entry.get("x");
                    double y = (Double) entry.get("y");
                    double z = (Double) entry.get("z");
                    float yaw = (Float) entry.get("yaw");
                    float pitch = (Float) entry.get("pitch");

                    SyncManager.inventoryDataManager.saveManual(uuid, inventory, false);
                    SyncManager.armorDataManager.saveManual(uuid, armor, false);
                    SyncManager.enderchestDataManager.saveManual(uuid, enderchest, false);
                    SyncManager.experienceDataManager.saveManual(uuid, exp, exp_level, false);
                    SyncManager.moneyDataManager.saveManual(uuid, money, false);
                    SyncManager.locationDataManager.saveManual(uuid, world, x, y, z, yaw, pitch, false);
                    SyncManager.gamemodeDataManager.saveManual(uuid, gamemode, false);

                    this.registerMigratedPlayer(uuid);
                    Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] ["+counter+"/"+max+"] Completed migration for player: "+uuidString);
                }

                String new_name = Main.TABLE_NAME+"_backup_"+UUID.randomUUID().toString().replaceAll("-", "_");
                String renameQuery = "RENAME TABLE `" + Main.TABLE_NAME + "` TO `" + new_name + "`";
                mySqlManager.getMySQL().queryUpdate(renameQuery);
                Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Backup completed] The Main table got renamed to "+new_name+".");

                this.migrateLegacyPlayerIndexTable();
                mySqlManager.setOrUpdateEntry(
                        Main.TABLE_NAME_MIGRATION,
                        Map.of("migration", "migration"),
                        Map.of("running_migration", false)
                );
                this.runningMigration = false;
                Main.getInstance().getLogger().warning("[Database Migration] [Completed] Migration completed successfully! Players are now able to join.");
                this.migrationCompletion.complete(null);
            } catch (Exception e) {
                this.migrationCompletion.completeExceptionally(e);
            }
        };
        BridgeScheduler.runAsync(migrationTask);
    }

    @EventHandler
    private void terminateJoin(PlayerJoinEvent event){
        if(runningMigration){
            event.getPlayer().kickPlayer("§c[MySqlPlayerBridge] Server is running database migration -> Try again later!");
        }
    }

    private void migrateLegacyPlayerIndexTable(){
        try {
            if(!mySqlManager.tableExists(Main.TABLE_NAME_REGISTERED_PLAYERS_LEGACY)){
                return;
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
        Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Legacy Player Registry] Detected legacy player registry in database. Migrating to new player index...");

        List<Map<String, Object>> entries;
        try {
            entries = mySqlManager.getAllEntries(Main.TABLE_NAME_REGISTERED_PLAYERS_LEGACY);
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }

        for (Map<String, Object> entry : entries){
            String uuid = String.valueOf(entry.get("uuid"));
            String timestamp = String.valueOf(entry.get("timestamp"));
            try {
                mySqlManager.setOrUpdateEntry(
                        Main.TABLE_NAME_PLAYER_INDEX,
                        Map.of("uuid", uuid),
                        Map.of(
                                "player_name", "",
                                "timestamp", timestamp,
                                "online", false,
                                "server_id", ""
                        )
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
        }

        try {
            mySqlManager.getMySQL().queryUpdate("DROP TABLE IF EXISTS `" + Main.TABLE_NAME_REGISTERED_PLAYERS_LEGACY + "`");
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }

        Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Legacy Player Registry] Migration to new player index completed.");
    }

    private void registerMigratedPlayer(UUID uuid){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_PLAYER_INDEX,
                    Map.of("uuid", uuid.toString()),
                    Map.of(
                            "player_name", "",
                            "timestamp", String.valueOf(Instant.now().toEpochMilli()),
                            "online", false,
                            "server_id", ""
                    )
            );
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }
}
