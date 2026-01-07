package de.lostesburger.mySqlPlayerBridge.Utils;

import org.bukkit.Bukkit;

public class Log {

    public static void info(String message){
        Bukkit.getLogger().info(Chat.msg(message));
    }

    public static void warn(String message){
        Bukkit.getLogger().warning(Chat.msg(message));
    }

    public static void error(String message){
        Bukkit.getLogger().severe(Chat.msg(message));
    }

}
