package de.lostesburger.mySqlPlayerBridge.Database;

import de.lostesburger.mySqlPlayerBridge.Sync.Lease.LeaseAcquireResult;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.LeaseState;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.PlayerLease;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.PlayerLeaseRepository;
import de.lostesburger.mySqlPlayerBridge.Managers.Vault.VaultManager;
import de.lostesburger.mySqlPlayerBridge.Sync.Economy.EconomySnapshot;
import de.lostesburger.mySqlPlayerBridge.Sync.Economy.EconomySyncModule;
import net.milkbowl.vault.economy.Economy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in integration test for both MySQL and MariaDB. Set MPB_TEST_DB_HOST,
 * MPB_TEST_DB_NAME and MPB_TEST_DB_USER to enable it. The test creates only
 * uniquely named temporary tables inside that explicitly supplied database.
 */
class PooledDatabaseIntegrationTest {
    @Test
    void pooledTransactionsUpsertsAndLeasesWorkAgainstRealDatabase() throws Exception {
        String host = System.getenv("MPB_TEST_DB_HOST");
        String database = System.getenv("MPB_TEST_DB_NAME");
        String user = System.getenv("MPB_TEST_DB_USER");
        assumeTrue(notBlank(host) && notBlank(database) && notBlank(user),
                "Set MPB_TEST_DB_HOST, MPB_TEST_DB_NAME and MPB_TEST_DB_USER to run database integration tests");

        int port = parsePort(System.getenv("MPB_TEST_DB_PORT"));
        String password = System.getenv().getOrDefault("MPB_TEST_DB_PASSWORD", "");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String dataTable = "mpb_it_data_" + suffix;
        String leaseTable = "mpb_it_lease_" + suffix;
        String migrationTable = "mpb_it_migrations_" + suffix;
        String legacyTable = "mpb_it_legacy_" + suffix;
        String economyTable = "mpb_it_economy_" + suffix;

        DatabaseConfig config = new DatabaseConfig(
                host, port, database, user, password,
                2, 3_000L, 1_000L, 0L, 0L,
                3_000, 5_000, 5
        );

        try (PooledSqlManager manager = new PooledSqlManager(config)) {
            try {
                createTables(manager, dataTable, leaseTable, migrationTable, legacyTable, economyTable);
                verifyUpsert(manager, dataTable);
                verifyRollback(manager, dataTable);
                verifyLeaseOwnership(manager, leaseTable);
                verifySchemaMigration(manager, migrationTable, legacyTable);
                verifyEconomyPersistence(manager, economyTable);
            } finally {
                manager.executeUpdate("DROP TABLE IF EXISTS " + PooledSqlManager.quoted(dataTable));
                manager.executeUpdate("DROP TABLE IF EXISTS " + PooledSqlManager.quoted(leaseTable));
                manager.executeUpdate("DROP TABLE IF EXISTS " + PooledSqlManager.quoted(migrationTable));
                manager.executeUpdate("DROP TABLE IF EXISTS " + PooledSqlManager.quoted(legacyTable));
                manager.executeUpdate("DROP TABLE IF EXISTS " + PooledSqlManager.quoted(economyTable));
                dropBackupTables(manager, legacyTable + "_pre_unique_");
            }
        }
    }

    private static void createTables(
            PooledSqlManager manager,
            String dataTable,
            String leaseTable,
            String migrationTable,
            String legacyTable,
            String economyTable
    )
            throws DatabaseException {
        manager.executeUpdate("CREATE TABLE " + PooledSqlManager.quoted(dataTable)
                + " (`uuid` VARCHAR(36) NOT NULL PRIMARY KEY, `value` INT NOT NULL) ENGINE=InnoDB");
        manager.executeUpdate("CREATE TABLE " + PooledSqlManager.quoted(leaseTable)
                + " (`player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY,"
                + " `owner_instance_uuid` VARCHAR(36) NOT NULL,"
                + " `session_token` VARCHAR(36) NOT NULL,"
                + " `state` VARCHAR(16) NOT NULL,"
                + " `lease_until` DATETIME(3) NOT NULL,"
                + " `updated_at` DATETIME(3) NOT NULL) ENGINE=InnoDB");
        manager.executeUpdate("CREATE TABLE " + PooledSqlManager.quoted(migrationTable)
                + " (`version` INT NOT NULL PRIMARY KEY, `description` VARCHAR(255) NOT NULL,"
                + " `applied_at` DATETIME(3) NOT NULL) ENGINE=InnoDB");
        manager.executeUpdate("CREATE TABLE " + PooledSqlManager.quoted(legacyTable)
                + " (`uuid` VARCHAR(36), `value` INT) ENGINE=MyISAM");
        manager.executeUpdate("CREATE TABLE " + PooledSqlManager.quoted(economyTable)
                + " (`uuid` VARCHAR(36) NOT NULL PRIMARY KEY, `money` DOUBLE) ENGINE=InnoDB");
    }

    private static void verifyUpsert(PooledSqlManager manager, String table) throws DatabaseException {
        String uuid = UUID.randomUUID().toString();
        manager.setOrUpdateEntry(table, Map.of("uuid", uuid), Map.of("value", 1));
        manager.setOrUpdateEntry(table, Map.of("uuid", uuid), Map.of("value", 2));

        Map<String, Object> row = manager.getEntry(table, Map.of("uuid", uuid));
        assertEquals(2, ((Number) row.get("value")).intValue());
    }

    private static void verifyRollback(PooledSqlManager manager, String table) throws DatabaseException {
        String uuid = UUID.randomUUID().toString();
        assertThrows(DatabaseException.class, () -> manager.inTransaction(connection -> {
            manager.setOrUpdateEntry(connection, table, Map.of("uuid", uuid), Map.of("value", 7));
            throw new IllegalStateException("force rollback");
        }));
        assertNull(manager.getEntry(table, Map.of("uuid", uuid)));
    }

    private static void verifyLeaseOwnership(PooledSqlManager manager, String table) throws DatabaseException {
        PlayerLeaseRepository repository = new PlayerLeaseRepository(manager, table, 10);
        UUID playerUuid = UUID.randomUUID();
        PlayerLease owner = new PlayerLease(playerUuid, UUID.randomUUID(), UUID.randomUUID());
        PlayerLease foreignSession = new PlayerLease(playerUuid, UUID.randomUUID(), UUID.randomUUID());

        assertEquals(LeaseAcquireResult.ACQUIRED, repository.tryAcquire(owner));
        assertEquals(LeaseAcquireResult.BUSY, repository.tryAcquire(foreignSession));
        assertFalse(repository.renew(foreignSession, LeaseState.ONLINE));
        assertFalse(repository.release(foreignSession));
        assertTrue(repository.renew(owner, LeaseState.ONLINE));
        assertTrue(repository.release(owner));
        assertFalse(repository.hasUnexpiredLease(playerUuid));

        assertEquals(LeaseAcquireResult.ACQUIRED, repository.tryAcquire(owner));
        manager.inTransaction(connection -> {
            manager.executeUpdate(connection,
                    "UPDATE " + PooledSqlManager.quoted(table)
                            + " SET `lease_until` = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3))"
                            + " WHERE `player_uuid` = ?",
                    playerUuid.toString());
            return null;
        });
        assertEquals(LeaseAcquireResult.ACQUIRED, repository.tryAcquire(foreignSession));
        assertFalse(repository.release(owner));
        assertTrue(repository.release(foreignSession));
    }

    private static void verifySchemaMigration(
            PooledSqlManager manager,
            String migrationTable,
            String legacyTable
    ) throws DatabaseException {
        String uuid = UUID.randomUUID().toString();
        manager.inTransaction(connection -> {
            String sql = "INSERT INTO " + PooledSqlManager.quoted(legacyTable)
                    + " (`uuid`, `value`) VALUES (?, ?), (?, ?), (NULL, ?)";
            try (PreparedStatement statement = manager.prepare(connection, sql)) {
                statement.setString(1, uuid);
                statement.setInt(2, 1);
                statement.setString(3, uuid);
                statement.setInt(4, 2);
                statement.setInt(5, 9);
                statement.executeUpdate();
            }
            return null;
        });

        DatabaseSchemaMigrator migrator = new DatabaseSchemaMigrator(manager, migrationTable, ignored -> { });
        migrator.migrate(List.of(new DatabaseSchemaMigrator.UniqueKeyTable(
                legacyTable, "uuid", "VARCHAR(36)")));

        List<Map<String, Object>> rows = manager.getAllEntries(legacyTable);
        assertEquals(1, rows.size());
        assertEquals(uuid, String.valueOf(rows.getFirst().get("uuid")));
        assertEquals(2, ((Number) rows.getFirst().get("value")).intValue());
        manager.setOrUpdateEntry(legacyTable, Map.of("uuid", uuid), Map.of("value", 3));
        assertEquals(1, manager.getAllEntries(legacyTable).size());
        assertEquals("InnoDB", tableEngine(manager, legacyTable));

        assertThrows(DatabaseException.class, () -> manager.inTransaction(connection -> {
            manager.executeUpdate(connection,
                    "INSERT INTO " + PooledSqlManager.quoted(legacyTable)
                            + " (`uuid`, `value`) VALUES (?, ?)", uuid, 4);
            return null;
        }));

        // A restarted server must see the recorded migration and leave the table untouched.
        migrator.migrate(List.of(new DatabaseSchemaMigrator.UniqueKeyTable(
                legacyTable, "uuid", "VARCHAR(36)")));
        assertEquals(1, manager.getAllEntries(legacyTable).size());
    }

    private static void verifyEconomyPersistence(PooledSqlManager manager, String table) throws Exception {
        EconomySyncModule module = new EconomySyncModule(
                manager,
                new VaultManager(testEconomyProvider()),
                table,
                true
        );
        UUID playerUuid = UUID.randomUUID();

        manager.inTransaction(connection -> {
            module.save(connection, playerUuid, new EconomySnapshot(42.75d));
            return null;
        });
        EconomySnapshot loaded = manager.inTransaction(connection ->
                module.load(connection, playerUuid).orElseThrow());
        assertEquals(42.75d, loaded.balance());

        assertThrows(DatabaseException.class, () -> manager.inTransaction(connection -> {
            module.save(connection, playerUuid, new EconomySnapshot(99.5d));
            throw new IllegalStateException("force economy rollback");
        }));
        EconomySnapshot afterRollback = manager.inTransaction(connection ->
                module.load(connection, playerUuid).orElseThrow());
        assertEquals(42.75d, afterRollback.balance());
    }

    private static Economy testEconomyProvider() {
        return (Economy) Proxy.newProxyInstance(
                Economy.class.getClassLoader(),
                new Class<?>[]{Economy.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getName" -> "IntegrationTestEconomy";
                    case "fractionalDigits" -> 2;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "IntegrationTestEconomy";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return 0.0d;
    }

    private static void dropBackupTables(PooledSqlManager manager, String prefix) throws DatabaseException {
        List<String> backupTables = new ArrayList<>();
        try (Connection connection = manager.getConnection();
             PreparedStatement statement = manager.prepare(connection,
                     "SELECT `table_name` FROM information_schema.tables"
                             + " WHERE table_schema = DATABASE() AND table_name LIKE ?")) {
            statement.setString(1, prefix + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    backupTables.add(resultSet.getString("table_name"));
                }
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Could not clean integration-test backup tables", exception);
        }
        for (String backupTable : backupTables) {
            manager.executeUpdate("DROP TABLE IF EXISTS " + PooledSqlManager.quoted(backupTable));
        }
    }

    private static String tableEngine(PooledSqlManager manager, String table) throws DatabaseException {
        try (Connection connection = manager.getConnection();
             PreparedStatement statement = manager.prepare(connection,
                     "SELECT `engine` FROM information_schema.tables"
                             + " WHERE table_schema = DATABASE() AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString("engine");
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Could not inspect integration-test table engine", exception);
        }
    }

    private static int parsePort(String value) {
        return value == null || value.isBlank() ? 3306 : Integer.parseInt(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
