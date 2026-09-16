package de.lostesburger.mySqlPlayerBridge.Platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public final class PaperFoliaPlatformScheduler implements PlatformScheduler {
    private final Plugin plugin;
    private final boolean folia;

    public PaperFoliaPlatformScheduler(Plugin plugin, boolean folia) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.folia = folia;
    }

    @Override
    public boolean runForPlayer(Player player, Runnable task, Runnable retired) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        if (this.folia) {
            Runnable retiredTask = retired == null ? () -> { } : retired;
            return player.getScheduler().run(this.plugin, ignored -> task.run(), retiredTask) != null;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!player.isOnline()) {
                if (retired != null) {
                    retired.run();
                }
                return;
            }
            task.run();
        });
        return true;
    }

    @Override
    public boolean runForPlayerLater(Player player, Runnable task, Runnable retired, long delayTicks) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        long delay = Math.max(1L, delayTicks);
        if (this.folia) {
            Runnable retiredTask = retired == null ? () -> { } : retired;
            return player.getScheduler().runDelayed(
                    this.plugin, ignored -> task.run(), retiredTask, delay) != null;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (!player.isOnline()) {
                if (retired != null) {
                    retired.run();
                }
                return;
            }
            task.run();
        }, delay);
        return true;
    }

    @Override
    public void runGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (this.folia) {
            Bukkit.getGlobalRegionScheduler().execute(this.plugin, task);
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, task);
    }

    @Override
    public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        Objects.requireNonNull(task, "task");
        long delay = Math.max(1L, delayTicks);
        if (this.folia) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask =
                    Bukkit.getGlobalRegionScheduler().runDelayed(
                            this.plugin,
                            ignored -> task.run(),
                            delay
                    );
            return scheduledTask::cancel;
        }
        org.bukkit.scheduler.BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(
                this.plugin,
                task,
                delay
        );
        return bukkitTask::cancel;
    }

    @Override
    public TaskHandle runGlobalRepeating(Runnable task, long initialDelayTicks, long periodTicks) {
        Objects.requireNonNull(task, "task");
        if (this.folia) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask =
                    Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                            this.plugin,
                            ignored -> task.run(),
                            Math.max(1L, initialDelayTicks),
                            Math.max(1L, periodTicks)
                    );
            return scheduledTask::cancel;
        }
        org.bukkit.scheduler.BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(
                this.plugin,
                task,
                Math.max(0L, initialDelayTicks),
                Math.max(1L, periodTicks)
        );
        return bukkitTask::cancel;
    }
}
