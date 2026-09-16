package de.lostesburger.mySqlPlayerBridge.Sync;

import java.util.UUID;

public final class PlayerRetiredException extends Exception {
    public PlayerRetiredException(UUID playerUuid) {
        super("Player is no longer schedulable: " + playerUuid);
    }
}
