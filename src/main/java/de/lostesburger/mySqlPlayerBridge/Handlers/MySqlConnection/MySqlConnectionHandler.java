package de.lostesburger.mySqlPlayerBridge.Handlers.MySqlConnection;

import de.lostesburger.mySqlPlayerBridge.Database.DatabaseConfig;
import de.lostesburger.mySqlPlayerBridge.Database.DatabaseException;
import de.lostesburger.mySqlPlayerBridge.Database.DatabaseExecutor;
import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.MySqlData.MySqlDataManager;


public class MySqlConnectionHandler implements AutoCloseable {
    private final PooledSqlManager sqlManager;
    private final DatabaseExecutor databaseExecutor;
    private final MySqlDataManager mySqlDataManager;

    public MySqlConnectionHandler(DatabaseConfig databaseConfig) {
        PooledSqlManager createdManager = null;
        DatabaseExecutor createdExecutor = null;
        try {
            createdManager = new PooledSqlManager(databaseConfig);
            createdExecutor = new DatabaseExecutor(databaseConfig.maximumPoolSize());
            createTables(createdManager);
            this.sqlManager = createdManager;
            this.databaseExecutor = createdExecutor;
            this.mySqlDataManager = new MySqlDataManager();
        } catch (Exception exception) {
            if (createdExecutor != null) {
                createdExecutor.close();
            }
            if (createdManager != null) {
                createdManager.close();
            }
            new MySqlErrorHandler().onInitialize();
            throw new RuntimeException("Could not initialize pooled database access", exception);
        }
    }

    private void createTables(PooledSqlManager manager) throws DatabaseException {
        createTable(manager, Main.TABLE_NAME_PLAYER_INDEX,
                "`uuid` VARCHAR(36) NOT NULL PRIMARY KEY, " +
                        "`player_name` VARCHAR(32) NOT NULL DEFAULT '', " +
                        "`timestamp` TEXT, " +
                        "`online` BOOLEAN NOT NULL DEFAULT FALSE, " +
                        "`server_id` TEXT");
        createTable(manager, Main.TABLE_NAME_MIGRATION,
                "`migration` VARCHAR(64) NOT NULL PRIMARY KEY, " +
                        "`running_migration` BOOLEAN NOT NULL DEFAULT FALSE, " +
                        "`timestamp` TEXT");
        createTable(manager, Main.TABLE_NAME_SCHEMA_MIGRATIONS,
                "`version` INT NOT NULL PRIMARY KEY, " +
                        "`description` VARCHAR(255) NOT NULL, " +
                        "`applied_at` DATETIME(3) NOT NULL");
        createTable(manager, Main.TABLE_NAME_PLAYER_LOCKS,
                "`player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY, " +
                        "`owner_instance_uuid` VARCHAR(36) NOT NULL, " +
                        "`session_token` VARCHAR(36) NOT NULL, " +
                        "`state` VARCHAR(16) NOT NULL, " +
                        "`lease_until` DATETIME(3) NOT NULL, " +
                        "`updated_at` DATETIME(3) NOT NULL");

        createUuidTable(manager, Main.TABLE_NAME_EFFECTS, "`effects` LONGTEXT");
        createUuidTable(manager, Main.TABLE_NAME_ADVANCEMENTS, "`advancements` LONGTEXT");
        createUuidTable(manager, Main.TABLE_NAME_STATS, "`stats` LONGTEXT");
        createUuidTable(manager, Main.TABLE_NAME_SELECTED_HOTBAR_SLOT, "`slot` INT");
        createUuidTable(manager, Main.TABLE_NAME_SATURATION, "`saturation` FLOAT, `food_level` INT");
        createUuidTable(manager, Main.TABLE_NAME_LOCATION,
                "`world` TEXT, `x` DOUBLE, `y` DOUBLE, `z` DOUBLE, `yaw` FLOAT, `pitch` FLOAT");
        createUuidTable(manager, Main.TABLE_NAME_EXP, "`exp_level` INT, `exp` FLOAT");
        createUuidTable(manager, Main.TABLE_NAME_GAMEMODE, "`gamemode` TEXT");
        createUuidTable(manager, Main.TABLE_NAME_INVENTORY, "`inventory` LONGTEXT");
        createUuidTable(manager, Main.TABLE_NAME_ARMOR, "`armor` LONGTEXT");
        createUuidTable(manager, Main.TABLE_NAME_ENDERCHEST, "`enderchest` LONGTEXT");
        createUuidTable(manager, Main.TABLE_NAME_HEALTH,
                "`health` DOUBLE, `max_health` DOUBLE, `health_scaled` BOOLEAN, `health_scale` DOUBLE");
        createUuidTable(manager, Main.TABLE_NAME_MONEY, "`money` DOUBLE");

    }

    private void createUuidTable(PooledSqlManager manager, String table, String moduleColumns) throws DatabaseException {
        createTable(manager, table, "`uuid` VARCHAR(36) NOT NULL PRIMARY KEY, " + moduleColumns);
    }

    private void createTable(PooledSqlManager manager, String table, String columns) throws DatabaseException {
        manager.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + PooledSqlManager.quoted(table)
                        + " (" + columns + ") ENGINE=InnoDB"
        );
    }

    public PooledSqlManager getManager() {
        return this.sqlManager;
    }

    public DatabaseExecutor getDatabaseExecutor() {
        return this.databaseExecutor;
    }

    public MySqlDataManager getMySqlDataManager() {
        return this.mySqlDataManager;
    }

    @Override
    public void close() {
        this.databaseExecutor.close();
        this.sqlManager.close();
    }
}
