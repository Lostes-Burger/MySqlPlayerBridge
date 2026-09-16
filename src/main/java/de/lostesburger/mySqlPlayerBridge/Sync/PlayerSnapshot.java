package de.lostesburger.mySqlPlayerBridge.Sync;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PlayerSnapshot(
        UUID playerUuid,
        String playerName,
        Instant capturedAt,
        List<CapturedModule<?>> modules
) {
    public PlayerSnapshot {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(capturedAt, "capturedAt");
        modules = List.copyOf(modules);
    }
}
