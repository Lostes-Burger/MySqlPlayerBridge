package de.lostesburger.mySqlPlayerBridge.Commands.MPBCommand;


import de.lostesburger.corelib.CommandManager.ServerCommand;
import de.lostesburger.corelib.CommandManager.SubCommand.SubCommandManager;
import de.lostesburger.corelib.CommandManager.SubCommand.SubCommandManagerException;
import de.lostesburger.corelib.CommandManager.SubCommand.SubCommandManagerExceptionType;
import de.lostesburger.mySqlPlayerBridge.Commands.ClearCommand.ClearCommand;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.command.CommandSender;

import java.util.List;

public class MPBCommand implements ServerCommand {
    private SubCommandManager subCommandManager;
    private String adminPerm;

    public MPBCommand() {
        subCommandManager = new SubCommandManager();
        adminPerm = Main.config.getString("settings.admin-permission");

        subCommandManager.addSubCommand("clear", new ClearCommand());
    }

    @Override
    public void execute(CommandSender commandSender, String[] strings) {
        if(this.adminPerm.isEmpty() || !commandSender.hasPermission(this.adminPerm)){
            commandSender.sendMessage(Chat.getMessage("permission-error"));
            return;
        }

        try {
            subCommandManager.executeIntern(commandSender, strings);
        } catch (SubCommandManagerException e) {
            switch (e.getErrorType()){
                case SubCommandManagerExceptionType.UNKNOWN_SUBCOMMAND -> {
                    commandSender.sendMessage(Chat.getMessage("unknown-subcommand-error").replace("{subcommands}", e.getDetails()));
                    break;
                }
                case SubCommandManagerExceptionType.NO_ARGS_ERROR -> {
                    commandSender.sendMessage(Chat.getMessage("no-subcommand-error").replace("{subcommands}", e.getDetails()));
                    break;
                }
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender commandSender, String[] strings) {
        if(this.adminPerm.isEmpty() || !commandSender.hasPermission(this.adminPerm)){ return List.of(); }
        return subCommandManager.tabCompleteIntern(commandSender, strings);
    }
}
