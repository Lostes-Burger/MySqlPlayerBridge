package de.lostesburger.mySqlPlayerBridge.Handlers.Migration;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class MySqlMigrationHandler implements Listener {
    private final MySqlManager mySqlManager;
    public boolean RUNNING_MIGRATION;
    public Migration migration;

    public MySqlMigrationHandler(){
        mySqlManager = Main.mySqlConnectionHandler.getManager();
        try {
            RUNNING_MIGRATION = mySqlManager.tableExists(Main.TABLE_NAME);
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
        if(RUNNING_MIGRATION){
            Main.getInstance().getLogger().warning("[Migration detected] [Database structure changed] The database structure has changed! The server will now begin running the migration. All player connections will be terminated and future incoming connections refused until the migration is done!");
            Bukkit.getServer().getPluginManager().registerEvents(this, Main.getInstance());
            Bukkit.getOnlinePlayers().forEach(player -> {
                player.kickPlayer("§c[MySqlPlayerBridge] Server is running database migration -> Try again later!");
            });

            this.startMigration();
        }
    }

    public void runAfterCheck(Runnable runnable) {
        final Scheduler.Task[] taskHolder = new Scheduler.Task[1];

        taskHolder[0] = Scheduler.runTimerAsync(() -> {
            if (!RUNNING_MIGRATION) {
                runnable.run();
                taskHolder[0].cancel();
            }
        }, 20, 20, Main.getInstance());
    }


    private void startMigration(){
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
            Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Staring] Starting database migration now! Data to migrate: "+max);
            Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Backup] If any problems should occur the old data will be saved. The mysql table will be renamed to"+Main.TABLE_NAME+"_backup.");


            for (Map<String, Object> entry : entries){
                counter++;

                String uuidString = (String) entry.get("uuid");
                Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] ["+counter+"/"+max+"] Running migration for player: "+uuidString);

                UUID uuid = UUID.fromString(uuidString);
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

        migration = new Migration(true, MigrationType.LegacyDatabase);
    }

    @EventHandler
    private void terminateJoin(PlayerJoinEvent event){
        if(RUNNING_MIGRATION){
            event.getPlayer().kickPlayer("§c[MySqlPlayerBridge] Server is running database migration -> Try again later!");
        }
    }
}
