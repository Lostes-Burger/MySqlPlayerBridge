package de.lostesburger.mySqlPlayerBridge.Sync.Lease;

import java.util.Objects;
import java.util.UUID;

public record PlayerLease(
        UUID playerUuid,
        UUID ownerInstanceUuid,
        UUID sessionToken
) {
    public PlayerLease {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(ownerInstanceUuid, "ownerInstanceUuid");
        Objects.requireNonNull(sessionToken, "sessionToken");
    }
}
