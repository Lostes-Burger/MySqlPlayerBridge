package de.lostesburger.mySqlPlayerBridge.Managers.Command;

import de.lostesburger.mySqlPlayerBridge.Commands.MPBCommand.MPBCommand;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.plugin.java.JavaPlugin;

public class CommandManager {
    private final de.lostesburger.corelib.CommandManager.GlobalCommand.CommandManager commandManager;

    public CommandManager(){
        commandManager = new de.lostesburger.corelib.CommandManager.GlobalCommand.CommandManager((JavaPlugin) Main.getInstance());

        String rootCommand = Main.config.getString("settings.command-prefix");
        commandManager.addCommand(rootCommand, new MPBCommand());
    }

    public de.lostesburger.corelib.CommandManager.GlobalCommand.CommandManager getCommandManager() { return commandManager; }
}
