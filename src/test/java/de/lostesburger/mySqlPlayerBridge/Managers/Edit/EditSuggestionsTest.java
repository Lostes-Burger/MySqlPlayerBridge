package de.lostesburger.mySqlPlayerBridge.Managers.Edit;

import de.lostesburger.mySqlPlayerBridge.Main;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class EditSuggestionsTest {
    @Test
    void offlineNamesAppearAfterAsynchronousLoadWithoutWaitingOnTabThread() {
        CompletableFuture<List<Map<String, Object>>> index = new CompletableFuture<>();
        AtomicInteger loads = new AtomicInteger();
        EditSuggestions cache = new EditSuggestions(() -> { loads.incrementAndGet(); return index; },
                (uuid, table) -> CompletableFuture.completedFuture(Map.of()), () -> 1L);
        assertTrue(cache.names().isEmpty());
        assertEquals(1, loads.get());
        index.complete(List.of(Map.of("uuid", UUID.randomUUID().toString(), "player_name", "OfflinePlayer")));
        assertEquals(List.of("OfflinePlayer"), cache.names());
        assertEquals(1, loads.get());
    }

    @Test
    void storedValuesAreCachedCaseInsensitivelyAndRefreshWithoutBlocking() {
        String oldTable = Main.TABLE_NAME_EXP;
        Main.TABLE_NAME_EXP = "test_exp";
        try {
            AtomicLong time = new AtomicLong(1);
            AtomicInteger loads = new AtomicInteger();
            CompletableFuture<Map<String, Object>> initial = new CompletableFuture<>();
            CompletableFuture<Map<String, Object>> refreshed = new CompletableFuture<>();
            String playerUuid = UUID.randomUUID().toString();
            EditSuggestions cache = new EditSuggestions(() -> CompletableFuture.completedFuture(List.of(
                    Map.of("uuid", playerUuid, "player_name", "OfflinePlayer"))),
                    (uuid, table) -> loads.incrementAndGet() == 1 ? initial : refreshed, time::get);
            assertTrue(cache.values("offlineplayer", "exp_level").isEmpty());
            assertTrue(cache.values("OFFLINEPLAYER", "exp_level").isEmpty());
            assertEquals(1, loads.get());
            initial.complete(Map.of("exp_level", 12));
            assertEquals(12, cache.values("OfflinePlayer", "exp_level").get("exp_level"));
            time.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(6));
            assertEquals(12, cache.values("OfflinePlayer", "exp_level").get("exp_level"));
            refreshed.complete(Map.of("exp_level", 24));
            assertEquals(24, cache.values("OfflinePlayer", "exp_level").get("exp_level"));
        } finally {
            Main.TABLE_NAME_EXP = oldTable;
        }
    }
}
