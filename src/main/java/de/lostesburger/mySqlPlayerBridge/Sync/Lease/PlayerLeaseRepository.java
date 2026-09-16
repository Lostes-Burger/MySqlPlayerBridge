package de.lostesburger.mySqlPlayerBridge.Sync.Lease;

import de.lostesburger.mySqlPlayerBridge.Database.DatabaseException;
import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class PlayerLeaseRepository {
    private final PooledSqlManager sqlManager;
    private final String table;
    private final int leaseDurationSeconds;

    public PlayerLeaseRepository(PooledSqlManager sqlManager, String table, int leaseDurationSeconds) {
        if (leaseDurationSeconds < 2) {
            throw new IllegalArgumentException("Lease duration must be at least 2 seconds");
        }
        this.sqlManager = sqlManager;
        this.table = PooledSqlManager.quoted(table);
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    public LeaseAcquireResult tryAcquire(PlayerLease requestedLease) throws DatabaseException {
        try {
            return this.sqlManager.inTransaction(connection -> tryAcquire(connection, requestedLease));
        } catch (DatabaseException exception) {
            if (isTransientLockConflict(exception)) {
                return LeaseAcquireResult.BUSY;
            }
            throw exception;
        }
    }

    int leaseDurationSeconds() {
        return this.leaseDurationSeconds;
    }

    private LeaseAcquireResult tryAcquire(Connection connection, PlayerLease requestedLease) throws SQLException {
        String selectSql = "SELECT `owner_instance_uuid`, `session_token`, "
                + "(`lease_until` <= CURRENT_TIMESTAMP(3)) AS `expired` FROM " + this.table
                + " WHERE `player_uuid` = ? FOR UPDATE";

        try (PreparedStatement statement = this.sqlManager.prepare(connection, selectSql)) {
            statement.setString(1, requestedLease.playerUuid().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    try {
                        insert(connection, requestedLease, LeaseState.LOADING);
                        return LeaseAcquireResult.ACQUIRED;
                    } catch (SQLException exception) {
                        if (isDuplicateKey(exception)) {
                            return LeaseAcquireResult.BUSY;
                        }
                        throw exception;
                    }
                }

                boolean sameSession = requestedLease.ownerInstanceUuid().toString().equals(resultSet.getString("owner_instance_uuid"))
                        && requestedLease.sessionToken().toString().equals(resultSet.getString("session_token"));
                boolean expired = resultSet.getBoolean("expired");
                if (!sameSession && !expired) {
                    return LeaseAcquireResult.BUSY;
                }
            }
        }

        updateOwner(connection, requestedLease, LeaseState.LOADING);
        return LeaseAcquireResult.ACQUIRED;
    }

    public boolean renew(PlayerLease lease, LeaseState state) throws DatabaseException {
        try (Connection connection = this.sqlManager.getConnection()) {
            return renew(connection, lease, state);
        } catch (SQLException exception) {
            throw new DatabaseException("Could not renew player lease for " + lease.playerUuid(), exception);
        }
    }

    public boolean renew(Connection connection, PlayerLease lease, LeaseState state) throws SQLException {
        String sql = "UPDATE " + this.table + " SET `state` = ?, "
                + "`lease_until` = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)), "
                + "`updated_at` = CURRENT_TIMESTAMP(3) "
                + "WHERE `player_uuid` = ? AND `owner_instance_uuid` = ? AND `session_token` = ?";
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, state.name());
            statement.setInt(2, this.leaseDurationSeconds);
            statement.setString(3, lease.playerUuid().toString());
            statement.setString(4, lease.ownerInstanceUuid().toString());
            statement.setString(5, lease.sessionToken().toString());
            return statement.executeUpdate() == 1;
        }
    }

    public boolean release(PlayerLease lease) throws DatabaseException {
        try (Connection connection = this.sqlManager.getConnection()) {
            return release(connection, lease);
        } catch (SQLException exception) {
            throw new DatabaseException("Could not release player lease for " + lease.playerUuid(), exception);
        }
    }

    public boolean release(Connection connection, PlayerLease lease) throws SQLException {
        String sql = "DELETE FROM " + this.table
                + " WHERE `player_uuid` = ? AND `owner_instance_uuid` = ? AND `session_token` = ?";
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, lease.playerUuid().toString());
            statement.setString(2, lease.ownerInstanceUuid().toString());
            statement.setString(3, lease.sessionToken().toString());
            return statement.executeUpdate() == 1;
        }
    }

    public boolean owns(Connection connection, PlayerLease lease, boolean lockRow) throws SQLException {
        String sql = "SELECT 1 FROM " + this.table
                + " WHERE `player_uuid` = ? AND `owner_instance_uuid` = ? AND `session_token` = ?"
                + " AND `lease_until` > CURRENT_TIMESTAMP(3)" + (lockRow ? " FOR UPDATE" : "");
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, lease.playerUuid().toString());
            statement.setString(2, lease.ownerInstanceUuid().toString());
            statement.setString(3, lease.sessionToken().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public boolean hasUnexpiredLease(java.util.UUID playerUuid) throws DatabaseException {
        String sql = "SELECT 1 FROM " + this.table
                + " WHERE `player_uuid` = ? AND `lease_until` > CURRENT_TIMESTAMP(3) LIMIT 1";
        try (Connection connection = this.sqlManager.getConnection();
             PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Could not inspect player lease for " + playerUuid, exception);
        }
    }

    public boolean hasAnyUnexpiredLease() throws DatabaseException {
        String sql = "SELECT 1 FROM " + this.table
                + " WHERE `lease_until` > CURRENT_TIMESTAMP(3) LIMIT 1";
        try (Connection connection = this.sqlManager.getConnection();
             PreparedStatement statement = this.sqlManager.prepare(connection, sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        } catch (SQLException exception) {
            throw new DatabaseException("Could not inspect active player leases", exception);
        }
    }

    private void insert(Connection connection, PlayerLease lease, LeaseState state) throws SQLException {
        String sql = "INSERT INTO " + this.table
                + " (`player_uuid`, `owner_instance_uuid`, `session_token`, `state`, `lease_until`, `updated_at`) "
                + "VALUES (?, ?, ?, ?, TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)), CURRENT_TIMESTAMP(3))";
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, lease.playerUuid().toString());
            statement.setString(2, lease.ownerInstanceUuid().toString());
            statement.setString(3, lease.sessionToken().toString());
            statement.setString(4, state.name());
            statement.setInt(5, this.leaseDurationSeconds);
            statement.executeUpdate();
        }
    }

    private void updateOwner(Connection connection, PlayerLease lease, LeaseState state) throws SQLException {
        String sql = "UPDATE " + this.table + " SET `owner_instance_uuid` = ?, `session_token` = ?, `state` = ?, "
                + "`lease_until` = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)), `updated_at` = CURRENT_TIMESTAMP(3) "
                + "WHERE `player_uuid` = ?";
        try (PreparedStatement statement = this.sqlManager.prepare(connection, sql)) {
            statement.setString(1, lease.ownerInstanceUuid().toString());
            statement.setString(2, lease.sessionToken().toString());
            statement.setString(3, state.name());
            statement.setInt(4, this.leaseDurationSeconds);
            statement.setString(5, lease.playerUuid().toString());
            statement.executeUpdate();
        }
    }

    private static boolean isDuplicateKey(SQLException exception) {
        return "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 1062;
    }

    private static boolean isTransientLockConflict(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && ("40001".equals(sqlException.getSQLState())
                    || sqlException.getErrorCode() == 1213
                    || sqlException.getErrorCode() == 1205)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
