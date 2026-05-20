package de.lostesburger.mySqlPlayerBridge.Utils;

import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class BridgeScheduler {
    private BridgeScheduler() {
    }

    public static void runAsync(Runnable task) {
        Plugin plugin = Main.getInstance();
        if (Main.IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public static void runGlobal(Runnable task) {
        Plugin plugin = Main.getInstance();
        if (Main.IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    public static boolean runEntity(Player player, Runnable task) {
        return runEntity(player, task, null);
    }

    public static boolean runEntity(Player player, Runnable task, Runnable retired) {
        Plugin plugin = Main.getInstance();
        if (Main.IS_FOLIA) {
            return player.getScheduler().run(plugin, scheduledTask -> task.run(), retired) != null;
        }
        Bukkit.getScheduler().runTask(plugin, task);
        return true;
    }

    public static void runRegion(Location location, Runnable task) {
        Plugin plugin = Main.getInstance();
        if (Main.IS_FOLIA) {
            Bukkit.getRegionScheduler().execute(plugin, location, task);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
