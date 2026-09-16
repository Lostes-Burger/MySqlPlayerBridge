package de.lostesburger.mySqlPlayerBridge.Database;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

public record DatabaseConfig(
        String host,
        int port,
        String database,
        String username,
        String password,
        int maximumPoolSize,
        long connectionTimeoutMs,
        long validationTimeoutMs,
        long keepaliveTimeMs,
        long maxLifetimeMs,
        int connectTimeoutMs,
        int socketTimeoutMs,
        int queryTimeoutSeconds
) {
    public DatabaseConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Database port must be between 1 and 65535");
        }
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("Database pool maximum-size must be at least 1");
        }
        if (connectionTimeoutMs < 250L) {
            throw new IllegalArgumentException("Database connection-timeout-ms must be at least 250");
        }
        if (validationTimeoutMs < 250L || validationTimeoutMs >= connectionTimeoutMs) {
            throw new IllegalArgumentException("Database validation-timeout-ms must be at least 250 and lower than connection-timeout-ms");
        }
        if (keepaliveTimeMs != 0L && keepaliveTimeMs < 30_000L) {
            throw new IllegalArgumentException("Database keepalive-time-ms must be 0 or at least 30000");
        }
        if (maxLifetimeMs != 0L && maxLifetimeMs < 30_000L) {
            throw new IllegalArgumentException("Database max-lifetime-ms must be 0 or at least 30000");
        }
        if (keepaliveTimeMs != 0L && maxLifetimeMs != 0L && keepaliveTimeMs >= maxLifetimeMs) {
            throw new IllegalArgumentException("Database keepalive-time-ms must be lower than max-lifetime-ms");
        }
        if (connectTimeoutMs < 1 || socketTimeoutMs < 1 || queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Database connect, socket and query timeouts must be positive");
        }
    }

    public static DatabaseConfig from(FileConfiguration config) {
        return new DatabaseConfig(
                config.getString("host", ""),
                config.getInt("port", 3306),
                config.getString("database", ""),
                config.getString("user", ""),
                config.getString("password", ""),
                config.getInt("pool.maximum-size", 4),
                config.getLong("pool.connection-timeout-ms", 3_000L),
                config.getLong("pool.validation-timeout-ms", 1_000L),
                config.getLong("pool.keepalive-time-ms", 120_000L),
                config.getLong("pool.max-lifetime-ms", 1_500_000L),
                config.getInt("timeouts.connect-ms", 3_000),
                config.getInt("timeouts.socket-ms", 5_000),
                config.getInt("timeouts.query-seconds", 5)
        );
    }

    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ':' + port + '/' + database
                + "?connectTimeout=" + connectTimeoutMs
                + "&socketTimeout=" + socketTimeoutMs
                + "&tcpKeepAlive=true";
    }
}
