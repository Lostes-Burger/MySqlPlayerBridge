package de.lostesburger.mySqlPlayerBridge.Sync;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record LoadedPlayerSnapshot(
        UUID playerUuid,
        List<LoadedModule<?>> modules
) {
    public LoadedPlayerSnapshot {
        Objects.requireNonNull(playerUuid, "playerUuid");
        modules = List.copyOf(modules);
    }
}
