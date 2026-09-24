package de.lostesburger.mySqlPlayerBridge.Sync;

import de.lostesburger.mySqlPlayerBridge.Platform.PlatformScheduler;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class PlayerEditExecutionTest {
    private final UUID uuid = UUID.randomUUID();
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> "TestPlayer";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    private final TestScheduler scheduler = new TestScheduler();

    @Test
    void successWaitsForDatabaseCommit() {
        List<String> events = new ArrayList<>();
        CompletableFuture<Void> database = new CompletableFuture<>();
        var result = PlayerEditExecution.execute(scheduler, player, () -> {}, target -> {
            events.add("apply");
            return CompletableFuture.completedFuture(null);
        }, () -> {
            events.add("save");
            return database;
        });
        assertTrue(events.isEmpty());
        scheduler.next();
        assertEquals(List.of("apply", "save"), events);
        assertFalse(result.isDone());
        database.complete(null);
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    @Test
    void saveFailureIsReportedInsteadOfSuccess() {
        CompletableFuture<Void> database = new CompletableFuture<>();
        var result = PlayerEditExecution.execute(scheduler, player, () -> {}, target -> CompletableFuture.completedFuture(null), () -> database);
        scheduler.next();
        RuntimeException error = new RuntimeException("Database unavailable");
        database.completeExceptionally(error);
        assertSame(error, assertThrows(CompletionException.class, result::join).getCause());
    }

    @Test
    void disconnectBeforeApplicationCompletesWithFailureAndDoesNotApply() {
        var result = PlayerEditExecution.execute(scheduler, player, () -> fail("Must not validate"),
                target -> { fail("Must not apply"); return null; }, () -> { fail("Must not save"); return null; });
        scheduler.retire();
        assertInstanceOf(PlayerRetiredException.class, assertThrows(CompletionException.class, result::join).getCause());
    }

    @Test
    void rejectedSchedulingCompletesWithFailure() {
        scheduler.accept = false;
        var result = PlayerEditExecution.execute(scheduler, player, () -> {},
                target -> CompletableFuture.completedFuture(null), () -> { fail("Must not save"); return null; });
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void teleportMustFinishAndReturnToEntityContextBeforeSaving() {
        CompletableFuture<Void> teleport = new CompletableFuture<>();
        List<String> events = new ArrayList<>();
        var result = PlayerEditExecution.execute(scheduler, player, () -> {}, target -> teleport, () -> {
            events.add("save");
            return CompletableFuture.completedFuture(null);
        });
        scheduler.next();
        assertTrue(events.isEmpty());
        teleport.complete(null);
        assertTrue(events.isEmpty());
        assertFalse(result.isDone());
        scheduler.next();
        assertEquals(List.of("save"), events);
        assertTrue(result.isDone());
    }

    @Test
    void cancelledTeleportDoesNotSave() {
        CompletableFuture<Void> teleport = new CompletableFuture<>();
        var result = PlayerEditExecution.execute(scheduler, player, () -> {}, target -> teleport,
                () -> { fail("Must not save"); return null; });
        scheduler.next();
        teleport.completeExceptionally(new IllegalStateException("Cancelled"));
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void changedSessionIsCheckedAgainAfterTeleport() {
        CompletableFuture<Void> teleport = new CompletableFuture<>();
        boolean[] active = {true};
        var result = PlayerEditExecution.execute(scheduler, player, () -> {
            if (!active[0]) throw new IllegalStateException("Session changed");
        }, target -> teleport, () -> { fail("Must not save"); return null; });
        scheduler.next();
        active[0] = false;
        teleport.complete(null);
        scheduler.next();
        assertTrue(result.isCompletedExceptionally());
    }

    private static final class TestScheduler implements PlatformScheduler {
        private final ArrayDeque<Runnable[]> tasks = new ArrayDeque<>();
        boolean accept = true;
        void next() { tasks.remove()[0].run(); }
        void retire() { tasks.remove()[1].run(); }
        @Override public boolean runForPlayer(Player player, Runnable task, Runnable retired) {
            if (accept) tasks.add(new Runnable[]{task, retired});
            return accept;
        }
        @Override public boolean runForPlayerLater(Player player, Runnable task, Runnable retired, long delay) { throw new UnsupportedOperationException(); }
        @Override public void runGlobal(Runnable task) { throw new UnsupportedOperationException(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delay) { throw new UnsupportedOperationException(); }
        @Override public TaskHandle runGlobalRepeating(Runnable task, long delay, long period) { throw new UnsupportedOperationException(); }
    }
}
