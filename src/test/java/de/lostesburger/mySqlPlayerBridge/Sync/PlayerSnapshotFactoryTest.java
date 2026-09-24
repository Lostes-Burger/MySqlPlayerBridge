package de.lostesburger.mySqlPlayerBridge.Sync;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSnapshotFactoryTest {
    @Test
    void explicitEditCapturesDisabledModuleWithoutEnablingAutomaticSync() throws Exception {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("experience", true));
        registry.register(new TestModule("enderchest", false));
        registry.register(new TestModule("armor", false));
        PlayerSnapshotFactory factory = new PlayerSnapshotFactory(registry);
        UUID uuid = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "TestPlayer";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        assertEquals(List.of("experience", "enderchest"), factory.capture(player, "enderchest")
                .modules().stream().map(CapturedModule::moduleId).toList());
        assertEquals(List.of("experience"), factory.capture(player).modules().stream().map(CapturedModule::moduleId).toList());
        assertEquals(List.of("experience"), factory.capture(player, "experience").modules().stream().map(CapturedModule::moduleId).toList());
        assertThrows(IllegalArgumentException.class, () -> factory.capture(player, "unknown"));
    }

    private record TestData() implements ModuleSnapshot { }
    private record TestModule(String id, boolean enabled) implements SyncModule<TestData> {
        @Override public TestData capture(Player player) { return new TestData(); }
        @Override public void save(Connection connection, UUID uuid, TestData data) { }
        @Override public Optional<TestData> load(Connection connection, UUID uuid) { return Optional.empty(); }
        @Override public CompletableFuture<Void> apply(Player player, TestData data) { return CompletableFuture.completedFuture(null); }
    }
}
