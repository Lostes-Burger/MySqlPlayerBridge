package de.lostesburger.mySqlPlayerBridge.Commands.MPBCommand;

import de.craftcore.craftcore.paper.command.commandmanager.ServerCommand;
import de.craftcore.craftcore.paper.command.commandmanager.SubCommand.SubCommandManager;
import de.craftcore.craftcore.paper.command.commandmanager.SubCommand.SubCommandManagerException;
import de.craftcore.craftcore.paper.command.commandmanager.SubCommand.SubCommandManagerExceptionType;
import de.lostesburger.mySqlPlayerBridge.Commands.SubCommands.Clear.ClearSubCommand;
import de.lostesburger.mySqlPlayerBridge.Commands.SubCommands.Edit.EditSubCommand;
import de.lostesburger.mySqlPlayerBridge.Commands.SubCommands.Sync.SyncSubCommand;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.awt.*;
import java.util.List;

public class MPBCommand implements ServerCommand {
    private SubCommandManager subCommandManager;
    private String adminPerm;

    public MPBCommand() {
        subCommandManager = new SubCommandManager();
        adminPerm = Main.config.getString("settings.admin-permission");

        subCommandManager.addSubCommand("clear", new ClearSubCommand());
        subCommandManager.addSubCommand("sync", new SyncSubCommand());
        subCommandManager.addSubCommand("edit", new EditSubCommand());
    }

    @Override
    public void execute(CommandSender commandSender, String[] strings) {
        if(strings.length == 0 && commandSender instanceof Player player){
            player.sendMessage(Chat.msg("§9MySql Player Bridge §5v"+Main.version));
            player.sendMessage(Chat.msg(" "));
            player.sendMessage(Chat.msg("&#182CD6S&#1A2ED5y&#1C2FD4n&#1E31D3c &#2234D1p&#2436D0l&#2638CFa&#2739CEy&#293BCDe&#2B3CCCr &#2F40CAd&#3141C9a&#3343C8t&#3545C7a &#3948C4a&#3B4AC3c&#3D4BC2r&#3F4DC1o&#414FC0s&#4350BFs &#4653BDm&#4855BCu&#4A57BBl&#4C58BAt&#4E5AB9i&#505CB8p&#525DB7l&#545FB6e &#595CBAs&#5B5BBCe&#5D59BDr&#5F58BFv&#6256C1e&#6455C3r&#6653C5s &#6B50C8u&#6D4FCAs&#6F4DCCi&#724CCEn&#744AD0g &#7947D3a &#7D44D7s&#8043D9i&#8241DBn&#8440DDg&#863EDFl&#893DE0e &#8D3AE4M&#9038E6y&#9237E8S&#9435EAQ&#9634EBL &#9B31EFd&#9D2FF1a&#A02EF3t&#A22CF5a&#A42BF6b&#A629F8a&#A928FAs&#AB26FCe"));
            player.sendMessage(Chat.msg("&#D61818D&#D71721e&#D8162Av&#D91534e&#DA143Dl&#DB1346o&#DC124Fp&#DE1159e&#DF1062d &#E10E74b&#E20D7Ey &#E40C90L&#E50B99o&#E60AA3s&#E709ACt&#E808B5e&#E907BEs&#EB06C8_&#EC05D1B&#ED04DAu&#EE03E3r&#EF02EDg&#F001F6e&#F100FFr"));
            player.sendMessage(Chat.msg(" "));
            player.sendMessage(Chat.msg("&#18BFD6C&#17BCD7h&#17B8D9e&#16B5DAc&#15B1DBk &#14AADEt&#13A7DFh&#12A3E0i&#11A0E2s &#1099E4p&#0F95E5r&#0E92E7o&#0E8EE8j&#0D8BE9e&#0C87EBc&#0B84ECt &#0A7DEEo&#0979F0u&#0876F1t &#076FF3o&#066BF5n &#0564F7G&#0461F9i&#035DFAt&#025AFBH&#0256FCu&#0153FEb&#004FFF: "));
            Component msg = Component.text(Main.PREFIX+"§chttps://github.com/Lostes-Burger/MySqlPlayerBridge")
                    .clickEvent(ClickEvent.openUrl("https://github.com/Lostes-Burger/MySqlPlayerBridge"));


            ((Audience) player).sendMessage(msg);
        }

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
