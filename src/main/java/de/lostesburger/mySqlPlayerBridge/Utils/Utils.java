package de.lostesburger.mySqlPlayerBridge.Utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class Utils {
    public static boolean isPluginEnabled(String pluginName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }
}
