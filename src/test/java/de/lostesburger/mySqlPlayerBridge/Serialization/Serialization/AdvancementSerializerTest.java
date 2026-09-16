package de.lostesburger.mySqlPlayerBridge.Serialization.Serialization;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdvancementSerializerTest {

    @Test
    void parsesCustomNamespacedAdvancementKey() {
        NamespacedKey key = AdvancementSerializer.parseKey("vanilla_refresh:biome/snowlands");

        assertNotNull(key);
        assertEquals("vanilla_refresh", key.getNamespace());
        assertEquals("biome/snowlands", key.getKey());
    }

    @Test
    void parsesLegacyUnqualifiedMinecraftKey() {
        NamespacedKey key = AdvancementSerializer.parseKey("story/stone");

        assertNotNull(key);
        assertEquals("minecraft", key.getNamespace());
        assertEquals("story/stone", key.getKey());
    }

    @Test
    void rejectsInvalidKeysWithoutThrowing() {
        assertNull(AdvancementSerializer.parseKey("bad:key:value"));
        assertNull(AdvancementSerializer.parseKey(""));
        assertNull(AdvancementSerializer.parseKey(null));
    }
}
