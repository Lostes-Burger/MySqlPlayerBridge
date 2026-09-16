package de.lostesburger.mySqlPlayerBridge.Commands.SubCommands.Sync;

import de.craftcore.craftcore.paper.command.commandmanager.ServerCommand;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.MySqlData.MySqlDataManager;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class SyncSubCommand implements ServerCommand {
    @Override
    public void execute(CommandSender commandSender, String[] strings) {
        if(!commandSender.hasPermission(Main.config.getString("settings.admin-permission"))){
            commandSender.sendMessage(Chat.getMessage("permission-error"));
            return;
        }

        if(strings.length != 1){
            commandSender.sendMessage(Chat.getMessage("manual-sync-wrong-usage"));
            return;
        }

        String targetName = strings[0];

        MySqlDataManager mySqlDataManager = Main.mySqlConnectionHandler.getMySqlDataManager();

        CompletableFuture<Void> save;
        if(targetName.equalsIgnoreCase("*")){
            save = mySqlDataManager.saveAllOnlinePlayersAsync();
        }else {
            Player target = Bukkit.getPlayer(targetName);
            if(target == null){
                commandSender.sendMessage(Chat.getMessage("manual-sync-player-not-found"));
                return;
            }
            save = mySqlDataManager.savePlayerDataAsync(target);
        }
        save.whenComplete((ignored, throwable) -> {
            if(throwable == null){
                sendMessage(commandSender, Chat.getMessage("manual-sync-success"));
                return;
            }
            Main.getInstance().getLogger().log(Level.WARNING, "Manual player synchronization failed", throwable);
            sendMessage(commandSender, Chat.getMessage("manual-sync-failed"));
        });
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

    private void sendMessage(CommandSender sender, String message){
        if(sender instanceof Player player){
            Main.platformScheduler.runForPlayer(player, () -> sender.sendMessage(message));
            return;
        }
        Main.platformScheduler.runGlobal(() -> sender.sendMessage(message));
    }
}
