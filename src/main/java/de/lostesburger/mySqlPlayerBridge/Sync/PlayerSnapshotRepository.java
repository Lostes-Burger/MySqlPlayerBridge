package de.lostesburger.mySqlPlayerBridge.Sync;

import de.lostesburger.mySqlPlayerBridge.Database.DatabaseException;
import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.LeaseLostException;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.LeaseState;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.PlayerLease;
import de.lostesburger.mySqlPlayerBridge.Sync.Lease.PlayerLeaseRepository;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerSnapshotRepository {
    private final PooledSqlManager sqlManager;
    private final PlayerLeaseRepository leaseRepository;
    private final String playerIndexTable;

    public PlayerSnapshotRepository(
            PooledSqlManager sqlManager,
            PlayerLeaseRepository leaseRepository,
            String playerIndexTable
    ) {
        this.sqlManager = sqlManager;
        this.leaseRepository = leaseRepository;
        this.playerIndexTable = playerIndexTable;
    }

    public Optional<LoadedPlayerSnapshot> load(PlayerLease lease, ModuleRegistry moduleRegistry)
            throws DatabaseException {
        return this.sqlManager.inTransaction(connection -> {
            requireLease(connection, lease);
            Map<String, Object> index = this.sqlManager.getEntry(connection, this.playerIndexTable,
                    Map.of("uuid", lease.playerUuid().toString()));
            if (index == null || index.isEmpty()) {
                renewLease(connection, lease, LeaseState.LOADING);
                return Optional.empty();
            }

            List<LoadedModule<?>> loadedModules = new ArrayList<>();
            for (SyncModule<?> module : moduleRegistry.enabledModules()) {
                loadModule(module, connection, lease.playerUuid()).ifPresent(loadedModules::add);
            }
            renewLease(connection, lease, LeaseState.LOADING);
            return Optional.of(new LoadedPlayerSnapshot(lease.playerUuid(), loadedModules));
        });
    }

    public void save(PlayerSnapshot snapshot, PlayerLease lease, boolean online, boolean releaseLease)
            throws DatabaseException {
        this.sqlManager.inTransaction(connection -> {
            requireLease(connection, lease);
            for (CapturedModule<?> module : snapshot.modules()) {
                module.save(connection, snapshot.playerUuid());
            }
            this.sqlManager.setOrUpdateEntry(connection, this.playerIndexTable,
                    Map.of("uuid", snapshot.playerUuid().toString()),
                    Map.of(
                            "player_name", snapshot.playerName(),
                            "timestamp", String.valueOf(Instant.now().toEpochMilli()),
                            "online", online,
                            "server_id", lease.ownerInstanceUuid().toString()
                    ));
            if (releaseLease && !this.leaseRepository.release(connection, lease)) {
                throw new LeaseLostException(lease);
            }
            if (!releaseLease) {
                renewLease(connection, lease, online ? LeaseState.ONLINE : LeaseState.LOADING);
            }
            return null;
        });
    }

    public void setOnline(PlayerLease lease, String playerName, boolean online) throws DatabaseException {
        this.sqlManager.inTransaction(connection -> {
            requireLease(connection, lease);
            this.sqlManager.setOrUpdateEntry(connection, this.playerIndexTable,
                    Map.of("uuid", lease.playerUuid().toString()),
                    Map.of(
                            "player_name", playerName,
                            "timestamp", String.valueOf(Instant.now().toEpochMilli()),
                            "online", online,
                            "server_id", lease.ownerInstanceUuid().toString()
                    ));
            renewLease(connection, lease, LeaseState.ONLINE);
            return null;
        });
    }

    private void requireLease(Connection connection, PlayerLease lease) throws Exception {
        if (!this.leaseRepository.owns(connection, lease, true)) {
            throw new LeaseLostException(lease);
        }
    }

    private void renewLease(Connection connection, PlayerLease lease, LeaseState state) throws Exception {
        if (!this.leaseRepository.renew(connection, lease, state)) {
            throw new LeaseLostException(lease);
        }
    }

    private static <T extends ModuleSnapshot> Optional<LoadedModule<?>> loadModule(
            SyncModule<T> module,
            Connection connection,
            UUID playerUuid
    ) throws Exception {
        return module.load(connection, playerUuid).map(data -> new LoadedModule<>(module, data));
    }
}
