package de.lostesburger.mySqlPlayerBridge.Sync.Economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EconomySnapshotTest {
    @Test
    void acceptsFiniteBalances() {
        assertEquals(-12.5d, new EconomySnapshot(-12.5d).balance());
    }

    @Test
    void rejectsNonFiniteBalances() {
        assertThrows(IllegalArgumentException.class, () -> new EconomySnapshot(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new EconomySnapshot(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new EconomySnapshot(Double.NEGATIVE_INFINITY));
    }
}
