package de.lostesburger.mySqlPlayerBridge.Platform;

import org.bukkit.entity.Player;

public interface PlatformScheduler {
    boolean runForPlayer(Player player, Runnable task, Runnable retired);

    default boolean runForPlayer(Player player, Runnable task) {
        return runForPlayer(player, task, null);
    }

    boolean runForPlayerLater(Player player, Runnable task, Runnable retired, long delayTicks);

    default boolean runForPlayerLater(Player player, Runnable task, long delayTicks) {
        return runForPlayerLater(player, task, null, delayTicks);
    }

    void runGlobal(Runnable task);

    TaskHandle runGlobalLater(Runnable task, long delayTicks);

    TaskHandle runGlobalRepeating(Runnable task, long initialDelayTicks, long periodTicks);

    interface TaskHandle {
        void cancel();
    }
}
