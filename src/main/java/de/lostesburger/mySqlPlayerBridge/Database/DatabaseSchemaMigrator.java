package de.lostesburger.mySqlPlayerBridge.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Idempotent schema migrations for installations created by older releases. */
public final class DatabaseSchemaMigrator {
    private static final int UNIQUE_KEYS_VERSION = 1;
    private static final int MIGRATION_LOCK_WAIT_SECONDS = 60;

    private final PooledSqlManager sqlManager;
    private final String migrationTable;
    private final Consumer<String> logger;

    public DatabaseSchemaMigrator(
            PooledSqlManager sqlManager,
            String migrationTable,
            Consumer<String> logger
    ) {
        this.sqlManager = sqlManager;
        this.migrationTable = migrationTable;
        this.logger = logger;
    }

    public void migrate(List<UniqueKeyTable> tables) throws DatabaseException {
        try (Connection connection = this.sqlManager.getConnection()) {
            acquireSchemaLock(connection);
            try {
                for (UniqueKeyTable table : tables) {
                    ensureUniqueKey(connection, table);
                }
                if (!isApplied(connection, UNIQUE_KEYS_VERSION)) {
                    this.sqlManager.setOrUpdateEntry(
                            connection,
                            this.migrationTable,
                            Map.of("version", UNIQUE_KEYS_VERSION),
                            Map.of(
                                    "description", "Unique player/module keys and InnoDB tables",
                                    "applied_at", Timestamp.from(Instant.now())
                            )
                    );
                }
            } finally {
                releaseSchemaLock(connection);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Could not run schema migrations", exception);
        }
    }

    private boolean isApplied(Connection connection, int version) throws SQLException {
        Map<String, Object> row = this.sqlManager.getEntry(
                connection, this.migrationTable, Map.of("version", version));
        return row != null && !row.isEmpty();
    }

    private void ensureUniqueKey(Connection connection, UniqueKeyTable table) throws SQLException {
        boolean unique = hasSingleColumnUniqueKey(connection, table.table(), table.keyColumn());
        if (unique
                && isColumnNotNull(connection, table.table(), table.keyColumn())
                && isInnoDb(connection, table.table())) {
            return;
        }

        KeyCounts counts = keyCounts(connection, table.table(), table.keyColumn());
        if (counts.nullKeys() == 0L && counts.totalRows() == counts.distinctKeys()) {
            StringBuilder alter = new StringBuilder("ALTER TABLE ")
                    .append(PooledSqlManager.quoted(table.table()))
                    .append(" MODIFY ")
                    .append(PooledSqlManager.quoted(table.keyColumn()))
                    .append(' ')
                    .append(table.keyDefinition())
                    .append(" NOT NULL");
            if (!unique) {
                alter.append(", ADD UNIQUE INDEX `mpb_key_unique` (")
                        .append(PooledSqlManager.quoted(table.keyColumn()))
                        .append(')');
            }
            alter.append(", ENGINE=InnoDB");
            this.sqlManager.executeUpdate(connection, alter.toString());
            return;
        }

        rebuildWithBackup(connection, table, unique, counts);
    }

    private void acquireSchemaLock(Connection connection) throws SQLException {
        String lockName = "mpb-schema-" + this.migrationTable;
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setQueryTimeout(MIGRATION_LOCK_WAIT_SECONDS + 5);
            statement.setString(1, lockName);
            statement.setInt(2, MIGRATION_LOCK_WAIT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new SQLException("Timed out acquiring schema migration lock " + lockName);
                }
            }
        }
    }

    private void releaseSchemaLock(Connection connection) {
        String lockName = "mpb-schema-" + this.migrationTable;
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            statement.executeQuery();
        } catch (SQLException exception) {
            this.logger.accept("Could not explicitly release schema migration lock " + lockName
                    + "; aborting its pooled connection: " + exception.getMessage());
            try {
                connection.abort(Runnable::run);
            } catch (SQLException abortFailure) {
                this.logger.accept("Could not abort connection holding schema migration lock " + lockName
                        + ": " + abortFailure.getMessage());
            }
        }
    }

    private void rebuildWithBackup(
            Connection connection,
            UniqueKeyTable table,
            boolean alreadyUnique,
            KeyCounts counts
    ) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String temporaryTable = "mpb_tmp_" + suffix;
        String backupTable = backupName(table.table(), suffix);
        List<String> columns = columns(connection, table.table());

        this.logger.accept("Table " + table.table() + " contains " + counts.nullKeys()
                + " null keys and " + (counts.totalRows() - counts.distinctKeys() - counts.nullKeys())
                + " duplicate rows. The complete old table will be retained as " + backupTable + '.');

        this.sqlManager.executeUpdate(connection, "CREATE TABLE " + PooledSqlManager.quoted(temporaryTable)
                + " LIKE " + PooledSqlManager.quoted(table.table()));

        StringBuilder insert = new StringBuilder("INSERT INTO ")
                .append(PooledSqlManager.quoted(temporaryTable)).append(" (");
        StringBuilder select = new StringBuilder(" SELECT ");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                insert.append(", ");
                select.append(", ");
            }
            String column = columns.get(index);
            insert.append(PooledSqlManager.quoted(column));
            if (column.equalsIgnoreCase(table.keyColumn())) {
                select.append(PooledSqlManager.quoted(column));
            } else {
                select.append("MAX(").append(PooledSqlManager.quoted(column)).append(")");
            }
        }
        insert.append(')').append(select)
                .append(" FROM ").append(PooledSqlManager.quoted(table.table()))
                .append(" WHERE ").append(PooledSqlManager.quoted(table.keyColumn())).append(" IS NOT NULL")
                .append(" GROUP BY ").append(PooledSqlManager.quoted(table.keyColumn()));
        this.sqlManager.executeUpdate(connection, insert.toString());

        StringBuilder alter = new StringBuilder("ALTER TABLE ")
                .append(PooledSqlManager.quoted(temporaryTable))
                .append(" MODIFY ").append(PooledSqlManager.quoted(table.keyColumn()))
                .append(' ').append(table.keyDefinition()).append(" NOT NULL");
        if (!alreadyUnique) {
            alter.append(", ADD UNIQUE INDEX `mpb_key_unique` (")
                    .append(PooledSqlManager.quoted(table.keyColumn())).append(')');
        }
        alter.append(", ENGINE=InnoDB");
        this.sqlManager.executeUpdate(connection, alter.toString());

        this.sqlManager.executeUpdate(connection, "RENAME TABLE "
                + PooledSqlManager.quoted(table.table()) + " TO " + PooledSqlManager.quoted(backupTable)
                + ", " + PooledSqlManager.quoted(temporaryTable) + " TO " + PooledSqlManager.quoted(table.table()));
    }

    private boolean hasSingleColumnUniqueKey(Connection connection, String table, String keyColumn) throws SQLException {
        String sql = "SELECT `index_name`, COUNT(*) AS `column_count`, "
                + "MAX(CASE WHEN `column_name` = ? THEN 1 ELSE 0 END) AS `contains_key` "
                + "FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? "
                + "AND non_unique = 0 GROUP BY `index_name` HAVING `column_count` = 1 AND `contains_key` = 1";
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, keyColumn);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean isColumnNotNull(Connection connection, String table, String keyColumn) throws SQLException {
        String sql = "SELECT `is_nullable` FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, table);
            statement.setString(2, keyColumn);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && "NO".equalsIgnoreCase(resultSet.getString("is_nullable"));
            }
        }
    }

    private boolean isInnoDb(Connection connection, String table) throws SQLException {
        String sql = "SELECT `engine` FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = ?";
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && "InnoDB".equalsIgnoreCase(resultSet.getString("engine"));
            }
        }
    }

    private KeyCounts keyCounts(Connection connection, String table, String keyColumn) throws SQLException {
        String quotedKey = PooledSqlManager.quoted(keyColumn);
        String sql = "SELECT COUNT(*) AS `total_rows`, COUNT(DISTINCT " + quotedKey + ") AS `distinct_keys`, "
                + "COALESCE(SUM(" + quotedKey + " IS NULL), 0) AS `null_keys` FROM "
                + PooledSqlManager.quoted(table);
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return new KeyCounts(
                    resultSet.getLong("total_rows"),
                    resultSet.getLong("distinct_keys"),
                    resultSet.getLong("null_keys")
            );
        }
    }

    private List<String> columns(Connection connection, String table) throws SQLException {
        String sql = "SELECT * FROM " + PooledSqlManager.quoted(table) + " LIMIT 0";
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            List<String> result = new ArrayList<>(metadata.getColumnCount());
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                result.add(metadata.getColumnLabel(index));
            }
            return result;
        }
    }

    private static String backupName(String table, String suffix) {
        String postfix = "_pre_unique_" + suffix;
        int maximumBaseLength = 64 - postfix.length();
        String base = table.length() > maximumBaseLength ? table.substring(0, maximumBaseLength) : table;
        return base + postfix;
    }

    public record UniqueKeyTable(String table, String keyColumn, String keyDefinition) {
        public UniqueKeyTable {
            PooledSqlManager.validateIdentifier(table);
            PooledSqlManager.validateIdentifier(keyColumn);
            if (!keyDefinition.matches("[A-Z]+(?:\\([0-9]+\\))?")) {
                throw new IllegalArgumentException("Unsafe key definition: " + keyDefinition);
            }
        }
    }

    private record KeyCounts(long totalRows, long distinctKeys, long nullKeys) {
    }
}
