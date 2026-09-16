package de.lostesburger.mySqlPlayerBridge.Sync;

import org.bukkit.entity.Player;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface SyncModule<T extends ModuleSnapshot> {
    String id();

    boolean enabled();

    T capture(Player player) throws Exception;

    void save(Connection connection, UUID playerUuid, T data) throws Exception;

    Optional<T> load(Connection connection, UUID playerUuid) throws Exception;

    CompletableFuture<Void> apply(Player player, T data) throws Exception;
}
