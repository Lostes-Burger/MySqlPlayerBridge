package de.lostesburger.mySqlPlayerBridge.Sync.Lease;

import java.util.UUID;

public class LeaseTimeoutException extends Exception {
    public LeaseTimeoutException(UUID playerUuid) {
        super("Timed out while waiting for player lease: " + playerUuid);
    }

    public LeaseTimeoutException(UUID playerUuid, Throwable cause) {
        super("Timed out while waiting for player lease: " + playerUuid, cause);
    }
}
