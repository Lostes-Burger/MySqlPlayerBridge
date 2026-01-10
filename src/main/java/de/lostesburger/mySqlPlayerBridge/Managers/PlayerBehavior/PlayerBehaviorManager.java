package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBehavior;

import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

import java.util.HashSet;
import java.util.Set;

public class PlayerBehaviorManager implements Listener {

    public PlayerBehaviorManager(){
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
    }

    private Set<Player> lockedPlayers = new HashSet<>();

    public void lockPlayer(Player player) {
        lockedPlayers.add(player);
    }

    public void unlockPlayer(Player player) {
        lockedPlayers.remove(player);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (lockedPlayers.contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (lockedPlayers.contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        if (lockedPlayers.contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (lockedPlayers.contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (lockedPlayers.contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
