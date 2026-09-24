package de.lostesburger.mySqlPlayerBridge.Managers.Edit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EditChangeTest {
    private EditChange change(String type, String value) {
        return EditChange.parse(type, new String[]{"Player", type, value});
    }

    @Test
    void nonFiniteValuesCannotReachDatabaseOrBukkit() {
        for (String type : new String[]{"exp", "health", "health_max", "health_scale", "saturation", "money"}) {
            for (String value : new String[]{"NaN", "Infinity", "-Infinity", "1e999"}) {
                assertThrows(IllegalArgumentException.class, () -> change(type, value), type + " " + value);
            }
        }
    }

    @Test
    void singleHealthEditRejectsTooMuchHealthButWildcardClamps() {
        EditChange change = change("health", "30");
        assertThrows(IllegalArgumentException.class, () -> change.offlineValues(Map.of("max_health", 20d), false));
        assertEquals(20d, change.offlineValues(Map.of("max_health", 20d), true).get("health"));
    }

    @Test
    void wildcardLoweringMaximumAlsoLowersHealth() {
        EditChange change = change("health_max", "10");
        assertThrows(IllegalArgumentException.class, () -> change.offlineValues(Map.of("health", 20d), false));
        Map<String, Object> row = change.offlineValues(Map.of("health", 20d), true);
        assertEquals(10d, row.get("health"));
        assertEquals(10d, row.get("max_health"));
    }

    @Test
    void editingScaleEnablesScalingOfflineToo() {
        assertEquals(true, change("health_scale", "40").offlineValues(Map.of(), false).get("health_scaled"));
    }

    @Test
    void firstEditCreatesCompleteExperienceAndHealthRows() {
        assertEquals(Map.of("exp", 0f, "exp_level", 5), change("exp_level", "5").offlineValues(null, false));
        assertEquals(Map.of("health", 10d, "max_health", 20d, "health_scaled", false, "health_scale", 20d),
                change("health", "10").offlineValues(null, false));
    }

    @Test
    void locationPreservesOrientationUnlessExplicitlyGiven() {
        EditChange change = EditChange.parse("location", new String[]{"Player", "location", "world", "1", "2", "3"});
        Map<String, Object> row = change.offlineValues(Map.of("yaw", 30f, "pitch", 10f), false);
        assertEquals(30d, row.get("yaw"));
        assertEquals(10d, row.get("pitch"));
        assertEquals(0d, change.offlineValues(null, false).get("yaw"));
    }

    @Test
    void ordinaryChangesDoNotOverwriteExistingSiblingValues() {
        assertEquals(Map.of("exp", .5f), change("exp", "0.5").offlineValues(Map.of("exp_level", 30), false));
    }
}
