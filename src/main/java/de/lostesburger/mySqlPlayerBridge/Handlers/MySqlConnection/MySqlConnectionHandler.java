package de.lostesburger.mySqlPlayerBridge.Handlers.MySqlConnection;

import de.craftcore.craftcore.global.mysql.MySQL;
import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.MySqlData.MySqlDataManager;


public class MySqlConnectionHandler {
    private final MySQL mySQL;
    private final MySqlManager mySqlManager;
    private final MySqlDataManager mySqlDataManager;

    public MySqlConnectionHandler(String host, int port, String database, String username, String password) {
        try {
            this.mySQL = new MySQL(host, port, username, password, database);
        } catch (Exception e) {
            new MySqlErrorHandler().onInitialize();
            throw new RuntimeException(e);
        }
        try {
            mySqlManager = new MySqlManager(mySQL);
        } catch (MySqlError e) {
            new MySqlErrorHandler().onManagerInitialize();
            throw new RuntimeException(e);
        }

        this.createTables();
        this.mySqlDataManager = new MySqlDataManager(mySqlManager);
    }

    private void createTables() {
        try {
            mySqlManager.createTable(Main.TABLE_NAME_REGISTERED_PLAYERS,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.text("timestamp")
            );
            mySqlManager.createTable(Main.TABLE_NAME_MIGRATION,
                    MySqlManager.ColumnDefinition.text("migration"),
                    MySqlManager.ColumnDefinition.Boolean("running_migration"),
                    MySqlManager.ColumnDefinition.text("timestamp")
            );

            mySqlManager.createTable(Main.TABLE_NAME_EFFECTS,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.longText("effects")
            );
            mySqlManager.createTable(Main.TABLE_NAME_ADVANCEMENTS,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.longText("advancements")
            );
            mySqlManager.createTable(Main.TABLE_NAME_STATS,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.longText("stats")
            );
            mySqlManager.createTable(Main.TABLE_NAME_SELECTED_HOTBAR_SLOT,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.integer("slot")
            );
            mySqlManager.createTable(Main.TABLE_NAME_SATURATION,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.Float("saturation"),
                    MySqlManager.ColumnDefinition.integer("food_level")
            );

            mySqlManager.createTable(Main.TABLE_NAME_LOCATION,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.text("world"),
                    MySqlManager.ColumnDefinition.doubLe("x"),
                    MySqlManager.ColumnDefinition.doubLe("y"),
                    MySqlManager.ColumnDefinition.doubLe("z"),
                    MySqlManager.ColumnDefinition.Float("yaw"),
                    MySqlManager.ColumnDefinition.Float("pitch")
            );

            mySqlManager.createTable(Main.TABLE_NAME_EXP,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.integer("exp_level"),
                    MySqlManager.ColumnDefinition.Float("exp")
            );

            mySqlManager.createTable(Main.TABLE_NAME_GAMEMODE,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.text("gamemode")
            );

            mySqlManager.createTable(Main.TABLE_NAME_INVENTORY,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.longText("inventory")
            );

            mySqlManager.createTable(Main.TABLE_NAME_ARMOR,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.longText("armor")
            );

            mySqlManager.createTable(Main.TABLE_NAME_ENDERCHEST,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.longText("enderchest")
            );

            mySqlManager.createTable(Main.TABLE_NAME_HEALTH,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.doubLe("health"),
                    MySqlManager.ColumnDefinition.doubLe("max_health"),
                    MySqlManager.ColumnDefinition.Boolean("health_scaled"),
                    MySqlManager.ColumnDefinition.doubLe("health_scale")
            );

            mySqlManager.createTable(Main.TABLE_NAME_MONEY,
                    MySqlManager.ColumnDefinition.varchar("uuid", 36),
                    MySqlManager.ColumnDefinition.doubLe("money")
            );
        } catch (MySqlError e) {
            new MySqlErrorHandler().onTableCreate();
            throw new RuntimeException(e);
        }
    }

    public MySqlManager getManager(){ return mySqlManager; }
    public MySQL getMySQL(){ return this.mySQL; }
    public MySqlDataManager getMySqlDataManager(){ return this.mySqlDataManager; }
}
