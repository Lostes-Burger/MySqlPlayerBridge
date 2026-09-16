package de.lostesburger.mySqlPlayerBridge.Database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

public final class PooledSqlManager implements AutoCloseable {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final HikariDataSource dataSource;
    private final int queryTimeoutSeconds;

    public PooledSqlManager(DatabaseConfig databaseConfig) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("MySqlPlayerBridge-Pool");
        hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
        hikariConfig.setJdbcUrl(databaseConfig.jdbcUrl());
        hikariConfig.setUsername(databaseConfig.username());
        hikariConfig.setPassword(databaseConfig.password());
        hikariConfig.setMaximumPoolSize(databaseConfig.maximumPoolSize());
        hikariConfig.setConnectionTimeout(databaseConfig.connectionTimeoutMs());
        hikariConfig.setValidationTimeout(databaseConfig.validationTimeoutMs());
        hikariConfig.setKeepaliveTime(databaseConfig.keepaliveTimeMs());
        hikariConfig.setMaxLifetime(databaseConfig.maxLifetimeMs());
        hikariConfig.setAutoCommit(true);
        hikariConfig.setInitializationFailTimeout(1L);
        this.queryTimeoutSeconds = databaseConfig.queryTimeoutSeconds();
        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws SQLException {
        return this.dataSource.getConnection();
    }

    public boolean isConnectionAlive() {
        try (Connection connection = getConnection()) {
            return connection.isValid(Math.max(1, this.queryTimeoutSeconds));
        } catch (SQLException exception) {
            return false;
        }
    }

    public boolean tableExists(String table) throws DatabaseException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ? LIMIT 1";
        try (Connection connection = getConnection();
             PreparedStatement statement = prepare(connection, sql)) {
            statement.setString(1, validateIdentifier(table));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Could not check whether table exists: " + table, exception);
        }
    }

    public boolean entryExists(String table, Map<String, Object> conditions) throws DatabaseException {
        Objects.requireNonNull(conditions, "conditions");
        String sql = "SELECT 1 FROM " + quoted(table) + whereClause(conditions) + " LIMIT 1";
        try (Connection connection = getConnection();
             PreparedStatement statement = prepare(connection, sql)) {
            bind(statement, new ArrayList<>(conditions.values()), 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Could not check entry in table " + table, exception);
        }
    }

    public Map<String, Object> getEntry(String table, Map<String, Object> conditions) throws DatabaseException {
        Objects.requireNonNull(conditions, "conditions");
        try (Connection connection = getConnection()) {
            return getEntry(connection, table, conditions);
        } catch (SQLException exception) {
            throw new DatabaseException("Could not load entry from table " + table, exception);
        }
    }

    public Map<String, Object> getEntry(
            Connection connection,
            String table,
            Map<String, Object> conditions
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(conditions, "conditions");
        String sql = "SELECT * FROM " + quoted(table) + whereClause(conditions) + " LIMIT 1";
        try (PreparedStatement statement = prepare(connection, sql)) {
            bind(statement, new ArrayList<>(conditions.values()), 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRow(resultSet) : null;
            }
        }
    }

    public List<Map<String, Object>> getAllEntries(String table) throws DatabaseException {
        String sql = "SELECT * FROM " + quoted(table);
        try (Connection connection = getConnection();
             PreparedStatement statement = prepare(connection, sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<Map<String, Object>> entries = new ArrayList<>();
            while (resultSet.next()) {
                entries.add(readRow(resultSet));
            }
            return entries;
        } catch (SQLException exception) {
            throw new DatabaseException("Could not load entries from table " + table, exception);
        }
    }

    public void setOrUpdateEntry(String table, Map<String, Object> identifyingKey, Map<String, Object> values) throws DatabaseException {
        Objects.requireNonNull(identifyingKey, "identifyingKey");
        Objects.requireNonNull(values, "values");
        if (identifyingKey.isEmpty()) {
            throw new IllegalArgumentException("At least one identifying key is required");
        }
        if (values.isEmpty()) {
            return;
        }

        inTransaction(connection -> {
            setOrUpdateEntry(connection, table, identifyingKey, values);
            return null;
        });
    }

    public void setOrUpdateEntry(
            Connection connection,
            String table,
            Map<String, Object> identifyingKey,
            Map<String, Object> values
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(identifyingKey, "identifyingKey");
        Objects.requireNonNull(values, "values");
        if (identifyingKey.isEmpty()) {
            throw new IllegalArgumentException("At least one identifying key is required");
        }
        if (values.isEmpty()) {
            return;
        }

        Map<String, Object> combined = new LinkedHashMap<>(identifyingKey);
        combined.putAll(values);

        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        StringJoiner updates = new StringJoiner(", ");
        for (String column : combined.keySet()) {
            columns.add(quoted(column));
            placeholders.add("?");
        }
        for (String column : values.keySet()) {
            String quotedColumn = quoted(column);
            updates.add(quotedColumn + " = VALUES(" + quotedColumn + ")");
        }

        String sql = "INSERT INTO " + quoted(table) + " (" + columns + ") VALUES (" + placeholders + ")"
                + " ON DUPLICATE KEY UPDATE " + updates;
        try (PreparedStatement statement = prepare(connection, sql)) {
            bind(statement, new ArrayList<>(combined.values()), 1);
            statement.executeUpdate();
        }
    }

    public void deleteEntry(String table, String keyColumn, Object keyValue) throws DatabaseException {
        deleteEntry(table, Map.of(keyColumn, keyValue));
    }

    public void deleteEntry(String table, Map<String, Object> conditions) throws DatabaseException {
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("Refusing to delete without conditions");
        }
        try (Connection connection = getConnection()) {
            deleteEntry(connection, table, conditions);
        } catch (SQLException exception) {
            throw new DatabaseException("Could not delete entry from table " + table, exception);
        }
    }

    public int deleteEntry(Connection connection, String table, Map<String, Object> conditions) throws SQLException {
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("Refusing to delete without conditions");
        }
        String sql = "DELETE FROM " + quoted(table) + whereClause(conditions);
        try (PreparedStatement statement = prepare(connection, sql)) {
            bind(statement, new ArrayList<>(conditions.values()), 1);
            return statement.executeUpdate();
        }
    }

    public int deleteAll(Connection connection, String table) throws SQLException {
        return executeUpdate(connection, "DELETE FROM " + quoted(table));
    }

    public void executeUpdate(String sql) throws DatabaseException {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(this.queryTimeoutSeconds);
            statement.executeUpdate(sql);
        } catch (SQLException exception) {
            throw new DatabaseException("Could not execute database update", exception);
        }
    }

    public int executeUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    public <T> T inTransaction(TransactionWork<T> work) throws DatabaseException {
        try (Connection connection = getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof DatabaseException databaseException) {
                    throw databaseException;
                }
                throw new DatabaseException("Database transaction failed", exception);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Could not obtain database connection", exception);
        }
    }

    public PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(this.queryTimeoutSeconds);
        return statement;
    }

    public int queryTimeoutSeconds() {
        return this.queryTimeoutSeconds;
    }

    private static String whereClause(Map<String, Object> conditions) {
        if (conditions.isEmpty()) {
            return "";
        }
        StringJoiner where = new StringJoiner(" AND ", " WHERE ", "");
        for (String column : conditions.keySet()) {
            where.add(quoted(column) + " = ?");
        }
        return where.toString();
    }

    private static int bind(PreparedStatement statement, List<Object> values, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object value : values) {
            statement.setObject(index++, value);
        }
        return index;
    }

    private static Map<String, Object> readRow(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        Map<String, Object> row = new HashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
        }
        return row;
    }

    public static String quoted(String identifier) {
        return '`' + validateIdentifier(identifier) + '`';
    }

    public static String validateIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return identifier;
    }

    @Override
    public void close() {
        this.dataSource.close();
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection connection) throws Exception;
    }
}
