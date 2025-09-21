package de.lostesburger.mySqlPlayerBridge.Commands.ClearCommand;

import de.lostesburger.corelib.CommandManager.ServerCommand;
import de.lostesburger.corelib.MySQL.MySqlError;
import de.lostesburger.corelib.MySQL.MySqlManager;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClearCommand implements ServerCommand {
    @Override
    public void execute(CommandSender commandSender, String[] strings) {
        if(!commandSender.hasPermission(Main.config.getString("settings.admin-permission"))){
            commandSender.sendMessage(Chat.getMessage("permission-error"));
            return;
        }

        if(strings.length != 1){
            commandSender.sendMessage(Chat.getMessage("clear-wrong-usage"));
            return;
        }

        String target = strings[0];


        MySqlManager manager = Main.mySqlConnectionHandler.getManager();

        if(target.equalsIgnoreCase("*")){
            List<Map<String, Object>> datas;
            try {
                datas = manager.getAllEntries(Main.TABLE_NAME);
                datas.forEach(data -> {
                    try {
                        manager.deleteEntry(Main.TABLE_NAME, "uuid", data.get("uuid"));
                        Bukkit.getLogger().info("§cDeleted player: "+data.get("uuid"));
                    } catch (MySqlError e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
        }else {
            OfflinePlayer player = Bukkit.getOfflinePlayer(target);
            if(player == null){
                commandSender.sendMessage(Chat.getMessage("clear-player-not-found"));
                return;
            }
            try {
                manager.deleteEntry(Main.TABLE_NAME, "uuid", player.getUniqueId());
                Bukkit.getLogger().info("§cDeleted player: "+player.getUniqueId());
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender commandSender, String[] strings) {
        List<String> options = new ArrayList<String>();
        Bukkit.getOnlinePlayers().forEach(player -> {
            options.add(player.getName());
        });
        options.add("*");

        return options;
    }
}
