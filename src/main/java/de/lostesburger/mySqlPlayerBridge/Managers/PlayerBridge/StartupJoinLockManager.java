package de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge;

import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerLoadEvent;

public class StartupJoinLockManager implements Listener {
    private final String blockedMessage;
    private volatile StartupState state = StartupState.STARTING;
    private boolean serverLoaded;
    private boolean bridgeReady;

    public StartupJoinLockManager(){
        this.blockedMessage = Chat.getMessage("startup-join-blocked");
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event){
        if(this.isJoinAllowed() || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED){
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, this.blockedMessage);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event){
        this.markServerLoaded();
    }

    public synchronized void markWaitingForDatabase(){
        if(this.isTerminalState()){
            return;
        }
        this.updateState(StartupState.WAITING_FOR_DATABASE);
    }

    public synchronized void markMigrating(){
        if(this.isTerminalState()){
            return;
        }
        this.updateState(StartupState.MIGRATING);
    }

    public synchronized void markServerLoaded(){
        this.serverLoaded = true;
        this.unlockIfReady();
    }

    public synchronized void markBridgeReady(){
        this.bridgeReady = true;
        this.unlockIfReady();
    }

    public synchronized void markFailed(){
        if(this.state == StartupState.STOPPING){
            return;
        }
        this.updateState(StartupState.FAILED);
    }

    public synchronized void lockForShutdown(){
        this.updateState(StartupState.STOPPING);
    }

    public boolean isJoinAllowed(){
        return this.state == StartupState.READY;
    }

    private void unlockIfReady(){
        if(this.isTerminalState() || !this.serverLoaded || !this.bridgeReady){
            return;
        }
        this.updateState(StartupState.READY);
    }

    private boolean isTerminalState(){
        return this.state == StartupState.FAILED || this.state == StartupState.STOPPING;
    }

    private void updateState(StartupState newState){
        if(this.state == newState){
            return;
        }
        this.state = newState;
        if(newState == StartupState.READY){
            Main.getInstance().getLogger().info("Startup join lock released. Player connections are now allowed.");
            return;
        }
        if(newState == StartupState.FAILED){
            Main.getInstance().getLogger().warning("Startup join lock entered FAILED state. Player connections remain locked.");
            return;
        }
        Main.getInstance().getLogger().info("Startup join lock state changed to " + newState + ".");
    }

    public enum StartupState {
        STARTING,
        WAITING_FOR_DATABASE,
        MIGRATING,
        READY,
        FAILED,
        STOPPING
    }
}
