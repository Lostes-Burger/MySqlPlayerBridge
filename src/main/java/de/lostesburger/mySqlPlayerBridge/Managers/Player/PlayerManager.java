package de.lostesburger.mySqlPlayerBridge.Managers.Player;

import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.CC;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import de.lostesburger.mySqlPlayerBridge.Utils.Log;
import org.bukkit.entity.Player;

public class PlayerManager {


    public static void sendCreatedDataMessage(Player player){
        Log.info(CC.colorize(Chat.getMessage("created-data")));
    }

    public static void sendDataLoadedMessage(Player player){
        Log.info(CC.colorize(Chat.getMessage("sync-success")));
    }

    public static void syncFailedKick(Player player){
        Scheduler.run(() -> {
            String msg = Chat.getMessage("sync-failed");
            player.sendMessage(msg);
            player.kickPlayer(msg);
        }, Main.getInstance());

    }
}
