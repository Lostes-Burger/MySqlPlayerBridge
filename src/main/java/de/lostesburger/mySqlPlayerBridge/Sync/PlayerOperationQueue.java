package de.lostesburger.mySqlPlayerBridge.Sync;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Serializes asynchronous operations for one player without blocking a thread. */
public final class PlayerOperationQueue {
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public <T> CompletableFuture<T> enqueue(UUID playerUuid, Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<T> result = new CompletableFuture<>();
        final CompletableFuture<Void> previous;
        synchronized (this.tails) {
            previous = this.tails.put(playerUuid, gate);
        }

        CompletableFuture<Void> ready = previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.handle((value, throwable) -> null);
        ready.thenCompose(unused -> start(operation, result)).whenComplete((value, throwable) -> {
            gate.complete(null);
            this.tails.remove(playerUuid, gate);
        });
        return result;
    }

    public CompletableFuture<Void> tail(UUID playerUuid) {
        return this.tails.getOrDefault(playerUuid, CompletableFuture.completedFuture(null));
    }

    private static <T> CompletableFuture<Void> start(
            Supplier<CompletableFuture<T>> operation,
            CompletableFuture<T> result
    ) {
        final CompletableFuture<T> started;
        try {
            started = operation.get();
        } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
            return CompletableFuture.completedFuture(null);
        }
        if (started == null) {
            result.completeExceptionally(new NullPointerException("Queued operation returned no future"));
            return CompletableFuture.completedFuture(null);
        }
        return started.handle((value, throwable) -> {
            if (throwable == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(throwable);
            }
            return null;
        });
    }
}
