package de.lostesburger.mySqlPlayerBridge.Sync.Lease;

public final class LeaseLostException extends Exception {
    public LeaseLostException(PlayerLease lease) {
        super("Player lease is no longer owned: " + lease.playerUuid() + " / " + lease.sessionToken());
    }
}
