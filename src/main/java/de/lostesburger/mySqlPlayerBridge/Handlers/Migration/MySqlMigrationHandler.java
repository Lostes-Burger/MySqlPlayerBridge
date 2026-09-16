package de.lostesburger.mySqlPlayerBridge.Handlers.Migration;

import de.lostesburger.mySqlPlayerBridge.Database.DatabaseException;
import de.lostesburger.mySqlPlayerBridge.Database.DatabaseSchemaMigrator;
import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;
import de.lostesburger.mySqlPlayerBridge.Main;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class MySqlMigrationHandler {
    private static final long MIGRATION_FAILSAFE_TIMEOUT_MS = 15 * 60 * 1000L;
    private final PooledSqlManager mySqlManager;
    private final CompletableFuture<Void> migrationCompletion = new CompletableFuture<>();
    private volatile boolean runningMigration;

    public MySqlMigrationHandler(){
        mySqlManager = Main.mySqlConnectionHandler.getManager();
        try {
            migrateSchema();
            runningMigration = mySqlManager.tableExists(Main.TABLE_NAME);
        } catch (DatabaseException e) {
            throw new RuntimeException(e);
        }
        if(runningMigration){
            Main.getInstance().getLogger().warning("[Migration detected] [Database structure changed] The database structure has changed! The server will now begin running the migration. All player connections will be terminated and future incoming connections refused until the migration is done!");
            Main.getInstance().getLogger().warning("[Migration detected] [Checking conditions] Checking if other Server is currently handling the migration...");

            if(this.tryClaimMigration() == MigrationClaim.BUSY){
                Main.getInstance().getLogger().warning("[Database Migration] [Failed conditions] Another Server is currently handling the migration process. This server will wait until the migration is finished!");

                waitForOtherMigration(0);
            }else {
                Main.getInstance().getLogger().warning("[Database Migration] [Passed conditions] All conditions passed! This Server will now handle the database migration. DO NOT SHUT DOWN WHILE MIGRATING!");
                this.startMigration();
            }
            return;
        }

        Main.mySqlConnectionHandler.getDatabaseExecutor().run(() -> {
            try {
                this.migrateLegacyPlayerIndexTable();
                this.migrationCompletion.complete(null);
            } catch (RuntimeException e) {
                this.migrationCompletion.completeExceptionally(e);
            }
        });
    }

    private void migrateSchema() throws DatabaseException {
        Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] Checking and normalizing the target schema...");
        DatabaseSchemaMigrator schemaMigrator = new DatabaseSchemaMigrator(
                mySqlManager,
                Main.TABLE_NAME_SCHEMA_MIGRATIONS,
                message -> Main.getInstance().getLogger().warning("[Schema migration] " + message)
        );
        schemaMigrator.migrate(List.of(
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_PLAYER_INDEX, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_MIGRATION, "migration", "VARCHAR(64)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_EFFECTS, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_ADVANCEMENTS, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_STATS, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_SATURATION, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_LOCATION, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_EXP, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_GAMEMODE, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_INVENTORY, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_ARMOR, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_ENDERCHEST, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_HEALTH, "uuid", "VARCHAR(36)"),
                new DatabaseSchemaMigrator.UniqueKeyTable(Main.TABLE_NAME_MONEY, "uuid", "VARCHAR(36)")
        ));
    }

    private void waitForOtherMigration(int attempts) {
        CompletableFuture.delayedExecutor(5L, TimeUnit.SECONDS).execute(() ->
                Main.mySqlConnectionHandler.getDatabaseExecutor().run(() -> {
                    int waitedSeconds = (attempts + 1) * 5;
                    Main.getInstance().getLogger().warning("[Database Migration] [Waiting] Another server is currently handling the migration. Waited " + waitedSeconds + " seconds");
                    if (!mySqlManager.tableExists(Main.TABLE_NAME)) {
                        this.runningMigration = false;
                        Main.getInstance().getLogger().warning("[Database Migration] [Migration done] Migration finished by other server. Starting plugin...");
                        this.migrateLegacyPlayerIndexTable();
                        this.migrationCompletion.complete(null);
                        return;
                    }
                    if (this.tryClaimMigration() == MigrationClaim.BUSY) {
                        waitForOtherMigration(attempts + 1);
                        return;
                    }
                    Main.getInstance().getLogger().warning("[Database Migration] [Takeover] Previous migration lease expired. This server will resume the idempotent migration.");
                    this.runningMigration = true;
                    this.startMigration();
                }).exceptionally(throwable -> {
                    this.migrationCompletion.completeExceptionally(throwable);
                    return null;
                })
        );
    }

    private MigrationClaim tryClaimMigration(){
        try {
            return mySqlManager.inTransaction(connection -> {
                String sql = "SELECT `running_migration`, CAST(`timestamp` AS UNSIGNED) AS `lock_time`, "
                        + "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED) AS `db_now` FROM "
                        + PooledSqlManager.quoted(Main.TABLE_NAME_MIGRATION)
                        + " WHERE `migration` = ? FOR UPDATE";
                boolean busy = false;
                long databaseNow;
                try (PreparedStatement statement = mySqlManager.prepare(connection, sql)) {
                    statement.setString(1, "migration");
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            databaseNow = resultSet.getLong("db_now");
                            long lockTime = resultSet.getLong("lock_time");
                            busy = asBoolean(resultSet.getObject("running_migration"))
                                    && lockTime > 0L
                                    && databaseNow - lockTime <= MIGRATION_FAILSAFE_TIMEOUT_MS;
                        } else {
                            databaseNow = databaseNow(connection);
                        }
                    }
                }
                if (busy) {
                    return MigrationClaim.BUSY;
                }
                mySqlManager.setOrUpdateEntry(
                        connection,
                        Main.TABLE_NAME_MIGRATION,
                        Map.of("migration", "migration"),
                        Map.of("running_migration", true, "timestamp", databaseNow)
                );
                return MigrationClaim.CLAIMED;
            });
        } catch (DatabaseException e) {
            throw new RuntimeException(e);
        }
    }

    private long databaseNow(java.sql.Connection connection) throws Exception {
        try (PreparedStatement statement = mySqlManager.prepare(connection,
                "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)" );
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private void touchMigrationLock() throws DatabaseException {
        mySqlManager.inTransaction(connection -> {
            String sql = "UPDATE " + PooledSqlManager.quoted(Main.TABLE_NAME_MIGRATION)
                    + " SET `timestamp` = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)"
                    + " WHERE `migration` = ? AND `running_migration` = TRUE";
            mySqlManager.executeUpdate(connection, sql, "migration");
            return null;
        });
    }
    public void runWhenMigrationComplete(Runnable runnable, Runnable failureHandler) {
        this.migrationCompletion.whenComplete((ignored, throwable) -> {
            if(throwable != null){
                Main.getInstance().getLogger().log(Level.SEVERE, "[Database Migration] Migration failed. Plugin startup will not continue.", throwable);
                failureHandler.run();
                return;
            }
            Main.platformScheduler.runGlobal(() -> {
                if(Main.getInstance().isEnabled()){
                    runnable.run();
                }
            });
        });
    }


    private void startMigration(){
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
                    UUID.fromString(uuidString);
                    Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] ["+counter+"/"+max+"] Running migration for player: "+uuidString);

                    String inventory = (String) entry.get("inventory");
                    String armor = (String) entry.get("armor");
                    String enderchest = (String) entry.get("enderchest");
                    String gamemode = (String) entry.get("gamemode");
                    int exp_level = asNumber(entry.get("exp_level"), "exp_level").intValue();
                    float exp = asNumber(entry.get("exp"), "exp").floatValue();
                    double money = asNumber(entry.get("money"), "money").doubleValue();

                    String world = (String) entry.get("world");
                    double x = asNumber(entry.get("x"), "x").doubleValue();
                    double y = asNumber(entry.get("y"), "y").doubleValue();
                    double z = asNumber(entry.get("z"), "z").doubleValue();
                    float yaw = asNumber(entry.get("yaw"), "yaw").floatValue();
                    float pitch = asNumber(entry.get("pitch"), "pitch").floatValue();

                    mySqlManager.inTransaction(connection -> {
                        mySqlManager.setOrUpdateEntry(connection, Main.TABLE_NAME_INVENTORY,
                                Map.of("uuid", uuidString), Map.of("inventory", inventory));
                        mySqlManager.setOrUpdateEntry(connection, Main.TABLE_NAME_ARMOR,
                                Map.of("uuid", uuidString), Map.of("armor", armor));
                        mySqlManager.setOrUpdateEntry(connection, Main.TABLE_NAME_ENDERCHEST,
                                Map.of("uuid", uuidString), Map.of("enderchest", enderchest));
                        mySqlManager.setOrUpdateEntry(connection, Main.TABLE_NAME_EXP,
                                Map.of("uuid", uuidString), Map.of("exp", exp, "exp_level", exp_level));
                        mySqlManager.setOrUpdateEntry(connection, Main.TABLE_NAME_MONEY,
                                Map.of("uuid", uuidString), Map.of("money", money));
                        mySqlManager.setOrUpdateEntry(connection, Main.TABLE_NAME_LOCATION,
                                Map.of("uuid", uuidString), Map.of(
                                        "world", world, "x", x, "y", y, "z", z,
                                        "yaw", yaw, "pitch", pitch));
                        mySqlManager.setOrUpdateEntry(connection, Main.TABLE_NAME_GAMEMODE,
                                Map.of("uuid", uuidString), Map.of("gamemode", gamemode));
                        mySqlManager.setOrUpdateEntry(connection, Main.TABLE_NAME_PLAYER_INDEX,
                                Map.of("uuid", uuidString), Map.of(
                                        "player_name", "",
                                        "timestamp", String.valueOf(Instant.now().toEpochMilli()),
                                        "online", false,
                                        "server_id", ""));
                        return null;
                    });
                    this.touchMigrationLock();
                    Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] ["+counter+"/"+max+"] Completed migration for player: "+uuidString);
                }

                String new_name = Main.TABLE_NAME+"_backup_"+UUID.randomUUID().toString().replaceAll("-", "_");
                String renameQuery = "RENAME TABLE `" + Main.TABLE_NAME + "` TO `" + new_name + "`";
                mySqlManager.executeUpdate(renameQuery);
                Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Backup completed] The Main table got renamed to "+new_name+".");

                this.migrateLegacyPlayerIndexTable();
                this.markMigrationStep(2, "Legacy combined player table imported");
                mySqlManager.setOrUpdateEntry(
                        Main.TABLE_NAME_MIGRATION,
                        Map.of("migration", "migration"),
                        Map.of("running_migration", false)
                );
                this.runningMigration = false;
                Main.getInstance().getLogger().warning("[Database Migration] [Completed] Migration completed successfully! Plugin startup will now continue.");
                this.migrationCompletion.complete(null);
            } catch (Exception e) {
                this.migrationCompletion.completeExceptionally(e);
            }
        };
        Main.mySqlConnectionHandler.getDatabaseExecutor().run(migrationTask::run)
                .exceptionally(throwable -> {
                    this.migrationCompletion.completeExceptionally(throwable);
                    return null;
                });
    }

    private void migrateLegacyPlayerIndexTable(){
        try {
            if(!mySqlManager.tableExists(Main.TABLE_NAME_REGISTERED_PLAYERS_LEGACY)){
                return;
            }
        } catch (DatabaseException e) {
            throw new RuntimeException(e);
        }
        Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Legacy Player Registry] Detected legacy player registry in database. Migrating to new player index...");

        List<Map<String, Object>> entries;
        try {
            entries = mySqlManager.getAllEntries(Main.TABLE_NAME_REGISTERED_PLAYERS_LEGACY);
        } catch (DatabaseException e) {
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
            } catch (DatabaseException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            mySqlManager.executeUpdate("DROP TABLE IF EXISTS `" + Main.TABLE_NAME_REGISTERED_PLAYERS_LEGACY + "`");
        } catch (DatabaseException e) {
            throw new RuntimeException(e);
        }

        Main.getInstance().getLogger().log(Level.INFO, "[Database Migration] [Legacy Player Registry] Migration to new player index completed.");
        this.markMigrationStep(3, "Legacy player registry imported");
    }

    private void markMigrationStep(int version, String description) {
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_SCHEMA_MIGRATIONS,
                    Map.of("version", version),
                    Map.of("description", description, "applied_at", Timestamp.from(Instant.now()))
            );
        } catch (DatabaseException exception) {
            throw new RuntimeException("Could not record migration step " + version, exception);
        }
    }

    private static Number asNumber(Object value, String column) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Legacy column is not numeric: " + column + '=' + value, exception);
        }
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private enum MigrationClaim {
        CLAIMED,
        BUSY
    }
}
