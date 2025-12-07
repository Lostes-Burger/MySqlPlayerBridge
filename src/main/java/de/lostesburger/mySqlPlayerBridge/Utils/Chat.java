package de.lostesburger.mySqlPlayerBridge.Utils;


import de.craftcore.craftcore.paper.chat.colorutils.ColorUtils;
import de.lostesburger.mySqlPlayerBridge.Main;

public class Chat {
    public static String msg(String message){
        return ColorUtils.toColor(Main.PREFIX+message);
    }
    public static String getMessage(String messageKey){
        return msg(Main.messages.getString(messageKey));
    }
    public static String getMessageWithoutPrefix(String messageKey){
        return Main.messages.getString(messageKey);
    }
}
