package de.lostesburger.mySqlPlayerBridge.Sync.Economy;

import de.lostesburger.mySqlPlayerBridge.Database.PooledSqlManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Vault.VaultManager;
import de.lostesburger.mySqlPlayerBridge.Sync.SyncModule;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Vault access is confined to capture/apply in the player's execution context.
 * Only immutable balance data crosses into the database transaction.
 */
public final class EconomySyncModule implements SyncModule<EconomySnapshot> {
    private final PooledSqlManager sqlManager;
    private final VaultManager vaultManager;
    private final String table;
    private final boolean enabled;

    public EconomySyncModule(
            PooledSqlManager sqlManager,
            VaultManager vaultManager,
            String table,
            boolean enabled
    ) {
        this.sqlManager = Objects.requireNonNull(sqlManager, "sqlManager");
        this.table = PooledSqlManager.validateIdentifier(table);
        this.enabled = enabled;
        this.vaultManager = enabled
                ? Objects.requireNonNull(vaultManager, "vaultManager")
                : vaultManager;
    }

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public boolean enabled() {
        return this.enabled;
    }

    @Override
    public EconomySnapshot capture(Player player) {
        requireEnabled();
        return new EconomySnapshot(this.vaultManager.getBalance(player));
    }

    @Override
    public void save(Connection connection, UUID playerUuid, EconomySnapshot data) throws Exception {
        requireEnabled();
        this.sqlManager.setOrUpdateEntry(
                connection,
                this.table,
                Map.of("uuid", playerUuid.toString()),
                Map.of("money", data.balance())
        );
    }

    @Override
    public Optional<EconomySnapshot> load(Connection connection, UUID playerUuid) throws Exception {
        requireEnabled();
        Map<String, Object> row = this.sqlManager.getEntry(
                connection,
                this.table,
                Map.of("uuid", playerUuid.toString())
        );
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        Object value = row.get("money");
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Economy balance is not numeric for " + playerUuid + ": " + value);
        }
        return Optional.of(new EconomySnapshot(number.doubleValue()));
    }

    @Override
    public CompletableFuture<Void> apply(Player player, EconomySnapshot data) {
        requireEnabled();
        this.vaultManager.setBalance(player, data.balance());
        return CompletableFuture.completedFuture(null);
    }

    private void requireEnabled() {
        if (!this.enabled || this.vaultManager == null) {
            throw new IllegalStateException("Economy sync module is disabled");
        }
    }
}
