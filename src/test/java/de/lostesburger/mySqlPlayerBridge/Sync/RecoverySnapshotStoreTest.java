package de.lostesburger.mySqlPlayerBridge.Sync;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoverySnapshotStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesModulePayloadsIndependently() throws Exception {
        TestModule module = new TestModule();
        TestSnapshot payload = new TestSnapshot("serialized-data", 42);
        PlayerSnapshot snapshot = new PlayerSnapshot(
                UUID.randomUUID(),
                "TestPlayer",
                Instant.parse("2026-09-16T10:00:00Z"),
                List.of(new CapturedModule<>(module, payload))
        );

        Path file = new RecoverySnapshotStore(this.temporaryDirectory)
                .save(snapshot, "test", new IllegalStateException("failure"));
        String json = Files.readString(file);

        assertTrue(json.contains("\"test-module\""));
        assertTrue(json.contains("serialized-data"));
        assertTrue(json.contains("\"number\": 42"));
        assertTrue(json.contains("IllegalStateException"));
    }

    private record TestSnapshot(String value, int number) implements ModuleSnapshot {
    }

    private static final class TestModule implements SyncModule<TestSnapshot> {
        @Override
        public String id() {
            return "test-module";
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public TestSnapshot capture(Player player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(Connection connection, UUID playerUuid, TestSnapshot data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TestSnapshot> load(Connection connection, UUID playerUuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> apply(Player player, TestSnapshot data) {
            throw new UnsupportedOperationException();
        }
    }
}
