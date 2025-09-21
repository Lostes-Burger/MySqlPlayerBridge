package de.lostesburger.mySqlPlayerBridge.Exceptions;

import org.bukkit.entity.Player;

public class NoPlayerDataException extends Exception{
    public NoPlayerDataException(Player player){
        super("No player data found for player: "+player.getName());
    }
}
