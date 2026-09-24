package de.lostesburger.mySqlPlayerBridge.Sync;

import de.lostesburger.mySqlPlayerBridge.Platform.PlatformScheduler;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Owns the completion of both entity application and persistence, including scheduler retirement. */
public final class PlayerEditExecution {
    private PlayerEditExecution() { }

    public static CompletableFuture<Void> execute(PlatformScheduler scheduler, Player player,
            Runnable requireEditable, Function<Player, CompletableFuture<Void>> edit, SaveAction save) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable persist = () -> {
            try {
                requireEditable.run();
                save.run().whenComplete((ignored, failure) -> complete(result, failure));
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        };
        schedule(scheduler, player, () -> {
            try {
                requireEditable.run();
                CompletableFuture<Void> applied = edit.apply(player);
                if (applied.isDone()) {
                    applied.join();
                    // Capture and enqueue synchronously before quit or autosave can capture a later state.
                    persist.run();
                } else {
                    applied.whenComplete((ignored, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else schedule(scheduler, player, persist, result);
                    });
                }
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        }, result);
        return result;
    }

    private static void schedule(PlatformScheduler scheduler, Player player, Runnable task, CompletableFuture<Void> result) {
        try {
            Runnable retired = () -> result.completeExceptionally(new PlayerRetiredException(player.getUniqueId()));
            if (!scheduler.runForPlayer(player, task, retired)) retired.run();
        } catch (Exception exception) {
            result.completeExceptionally(exception);
        }
    }

    private static void complete(CompletableFuture<Void> result, Throwable failure) {
        if (failure == null) result.complete(null);
        else result.completeExceptionally(failure);
    }

    @FunctionalInterface
    public interface SaveAction {
        CompletableFuture<Void> run() throws Exception;
    }
}
