package de.lostesburger.mySqlPlayerBridge.Sync.Economy;

import de.lostesburger.mySqlPlayerBridge.Sync.ModuleSnapshot;

public record EconomySnapshot(double balance) implements ModuleSnapshot {
    public EconomySnapshot {
        if (!Double.isFinite(balance)) {
            throw new IllegalArgumentException("Economy balance must be finite: " + balance);
        }
    }
}
