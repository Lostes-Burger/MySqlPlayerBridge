package de.lostesburger.mySqlPlayerBridge.Handlers.Migration;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class MySqlMigrationHandler implements Listener {
    private final MySqlManager mySqlManager;
    public boolean RUNNING_MIGRATION;

    public MySqlMigrationHandler(){
        mySqlManager = Main.mySqlConnectionHandler.getManager();
        try {
            RUNNING_MIGRATION = mySqlManager.tableExists(Main.TABLE_NAME);
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
        if(RUNNING_MIGRATION){
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
                    time[0]++;
                    Main.getInstance().getLogger().warning("[Database Migration] [Waiting] Another Server is currently handling the migration process. This server will wait until the migration is finished! Waited for "+ time[0]*5 +"seconds");
                    this.RUNNING_MIGRATION = this.isRunningMigration();

                    if(!this.RUNNING_MIGRATION){
                        Main.getInstance().getLogger().warning("[Database Migration] [Migration done] Migration finished by other server. Waited for "+ time[0]*5 +"seconds. Starting plugin now...");
                        taskHolder[0].cancel();
                    }

                }, 5*20, 5*20, Main.getInstance());
            }else {
                Main.getInstance().getLogger().warning("[Database Migration] [Passed conditions] All conditions passed! This Server will now handle the database migration. DO NOT SHUT DOWN WHILE MIGRATING!");
                this.startMigration();
            }


        }
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
        return (boolean) entry.get("running_migration");
    }
    public void runAfterCheck(Runnable runnable) {
        final Scheduler.Task[] taskHolder = new Scheduler.Task[1];

        taskHolder[0] = Scheduler.runTimerAsync(() -> {
            if (!RUNNING_MIGRATION) {
                Scheduler.run(() -> {
                    HandlerList.unregisterAll(this);
                }, Main.getInstance());
                runnable.run();
                taskHolder[0].cancel();
            }
        }, 20, 20, Main.getInstance());
    }


    private void startMigration(){
        long timestamp = Instant.now().toEpochMilli();
        try {
            mySqlManager.setOrUpdateEntry(Main.TABLE_NAME_MIGRATION, Map.of("migration", "migration"), Map.of("running_migration", true, "timestamp", timestamp));
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }

        List<Map<String, Object>> entries;
        try {
            entries = mySqlManager.getAllEntries(Main.TABLE_NAME);
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        } finally {
            RUNNING_MIGRATION = false;
        }
        Scheduler.runAsync(() -> {
            int counter = 0;
            int max = entries.size();
            Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Starting] Starting database migration now! Data to migrate: "+max);
            Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Backup] If any problems should occur the old data will be saved. The mysql table will be renamed to"+Main.TABLE_NAME+"_backup.");


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

                SyncManager.inventoryDataManager.saveManual(uuid, inventory, true);
                SyncManager.armorDataManager.saveManual(uuid, armor, true);
                SyncManager.enderchestDataManager.saveManual(uuid, enderchest, true);
                SyncManager.experienceDataManager.saveManual(uuid, exp, exp_level, true);
                SyncManager.moneyDataManager.saveManual(uuid, money, true);
                SyncManager.locationDataManager.saveManual(uuid, world, x, y, z, yaw, pitch, true);
                SyncManager.gamemodeDataManager.saveManual(uuid, gamemode, true);

                PlayerManager.registerPlayer(uuid);
                Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] ["+counter+"/"+max+"] Completed migration for player: "+uuidString);
            }
        }, Main.getInstance());

        String new_name = Main.TABLE_NAME+"_backup_"+UUID.randomUUID().toString().replaceAll("-", "_");
        String renameQuery = "RENAME TABLE `" + Main.TABLE_NAME + "` TO `" + new_name + "`";
        try {
            mySqlManager.getMySQL().queryUpdate(renameQuery);
            Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Backup completed] The Main table got renamed to "+Main.TABLE_NAME+"_backup.");
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }

        Main.getInstance().getLogger().warning("[Database Migration] [Completed] Migration completed successfully! Players are now able to join.");
        try {
            mySqlManager.setOrUpdateEntry(Main.TABLE_NAME_MIGRATION, Map.of("running_migration", "migration"), Map.of("running_migration", false));
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }

    }

    @EventHandler
    private void terminateJoin(PlayerJoinEvent event){
        if(RUNNING_MIGRATION){
            event.getPlayer().kickPlayer("§c[MySqlPlayerBridge] Server is running database migration -> Try again later!");
        }
    }
}
