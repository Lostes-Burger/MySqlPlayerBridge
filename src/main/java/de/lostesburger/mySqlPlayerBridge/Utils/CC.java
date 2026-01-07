package de.lostesburger.mySqlPlayerBridge.Utils;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CC {

    private CC() {}

    public static String colorize(String s) {
        return s == null ? " " : ChatColor.translateAlternateColorCodes((char)'&', (String)s);
    }

    public static String strip(String s) {
        return s == null ? " " : ChatColor.stripColor((String)s);
    }

    public static String[] colorize(String[] lines) {
        return CC.colorize(Arrays.asList((String[])lines.clone())).toArray(new String[0]);
    }

    public static List<String> colorize(List<String> s) {
        ArrayList<String> colorized = new ArrayList<String>();
        s.forEach(st -> colorized.add(CC.colorize(st)));
        return colorized;
    }

    public static TextComponent msgHoverClick(String msg, String hover, String click, ClickEvent.Action clickAction) {
        TextComponent tc = new TextComponent(CC.colorize(msg));
        tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(CC.colorize(hover)).create()));
        tc.setClickEvent(new ClickEvent(clickAction, click));
        return tc;
    }

    public static String toUppercaseFirstChar(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

}
