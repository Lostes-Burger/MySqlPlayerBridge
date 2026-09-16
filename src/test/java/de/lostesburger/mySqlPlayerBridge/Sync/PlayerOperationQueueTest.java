package de.lostesburger.mySqlPlayerBridge.Sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerOperationQueueTest {
    @Test
    void operationsForOnePlayerCannotOvertake() throws Exception {
        PlayerOperationQueue queue = new PlayerOperationQueue();
        UUID playerUuid = UUID.randomUUID();
        CompletableFuture<Void> firstGate = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean();

        CompletableFuture<Void> first = queue.enqueue(playerUuid, () -> firstGate);
        CompletableFuture<Void> second = queue.enqueue(playerUuid, () -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture(null);
        });

        assertFalse(secondStarted.get());
        firstGate.complete(null);
        CompletableFuture.allOf(first, second).get(1L, TimeUnit.SECONDS);
        assertTrue(secondStarted.get());
    }

    @Test
    void failedOperationDoesNotBlockFollowingOperation() throws Exception {
        PlayerOperationQueue queue = new PlayerOperationQueue();
        UUID playerUuid = UUID.randomUUID();
        AtomicBoolean secondStarted = new AtomicBoolean();

        CompletableFuture<Void> first = queue.enqueue(playerUuid,
                () -> CompletableFuture.failedFuture(new IllegalStateException("expected")));
        CompletableFuture<Void> second = queue.enqueue(playerUuid, () -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(first.isCompletedExceptionally());
        second.get(1L, TimeUnit.SECONDS);
        assertTrue(secondStarted.get());
    }

    @Test
    void differentPlayersCanRunConcurrently() {
        PlayerOperationQueue queue = new PlayerOperationQueue();
        CompletableFuture<Void> firstGate = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean();

        queue.enqueue(UUID.randomUUID(), () -> firstGate);
        queue.enqueue(UUID.randomUUID(), () -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(secondStarted.get());
        firstGate.complete(null);
    }

    @Test
    void invalidOperationCannotLeaveQueueBlocked() throws Exception {
        PlayerOperationQueue queue = new PlayerOperationQueue();
        UUID playerUuid = UUID.randomUUID();
        AtomicBoolean secondStarted = new AtomicBoolean();

        CompletableFuture<Void> invalid = queue.enqueue(playerUuid, () -> null);
        CompletableFuture<Void> second = queue.enqueue(playerUuid, () -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(invalid.isCompletedExceptionally());
        second.get(1L, TimeUnit.SECONDS);
        assertTrue(secondStarted.get());
    }
}
