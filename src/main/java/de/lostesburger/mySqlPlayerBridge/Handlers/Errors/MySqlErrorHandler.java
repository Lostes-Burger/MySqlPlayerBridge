package de.lostesburger.mySqlPlayerBridge.Handlers.Errors;

import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.Utils.FileUtils;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.logging.Level;

public class MySqlErrorHandler {
    private String message = null;

    public MySqlErrorHandler(){}

    public void hasPlayerData(Player player){
        this.message = "An error occurred while trying to check for Player data!\n" +
                "Player-Name:"+player.getName()+"\n"+
                "Player-UUID:"+player.getUniqueId()+"\n"+
                " \n"+
                "This error prevents the Player from being processed by the Plugin.\n"+
                "The player is not allowed to join, if no check succeed!"
        ;

        PlayerManager.syncFailedKick(player);

        this.callError();
    }

    public void savePlayerData(Player player, HashMap<String, Object> dataMap){
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss");
        String filename = player.getUniqueId().toString()+" "+dateFormat.format(new Date());

        this.message =
                "An error occurred while trying to save a Player data!\n" +
                "Player-Name:"+player.getName()+"\n"+
                "Player-UUID:"+player.getUniqueId()+"\n"+
                " \n"+
                "Player data was collected. The data follows below and\n"+
                "will be saved in a file in plugins/MySqlPlayerBridge/"+filename+"\n"+ dataMap.toString()
        ;

        this.callError();
        FileUtils.saveMapToFile(filename, dataMap);
        PlayerManager.syncFailedKick(player);
    }

    public void onInitialize(){
        this.message = "An error occurred while trying to initialize the Database Connection.\n"+
                "For more information -> MySQL Error below.";
        this.callError();
    }

    public void onManagerInitialize(){
        this.message = "An error occurred while trying to initialize the MySQL Manager\n"+
                "For more information -> Error below.";
        this.callError();
    }

    public void onTableCreate(){
        this.message = "An error occurred while trying to create the MySQL Tables!\n"+
                "For more information -> MySQL Error below.";
        this.callError();
    }

    public void getPlayerData(Player player){
        this.message =
                "An error occurred while trying to get a Player's data!\n" +
                        "Player-Name:"+player.getName()+"\n"+
                        "Player-UUID:"+player.getUniqueId()+"\n"
        ;

        this.callError();
        PlayerManager.syncFailedKick(player);
    }

    private void callError(){
        String error =
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! \n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    " \n" +
                    "       MySqlPlayerBridge Mysql Error\n" +
                    "      caused by an MySQL exception\n" +
                    " \n" +
                    this.message+"\n"+
                    " \n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! \n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n";

        Main.getInstance().getLogger().log(Level.SEVERE, ChatColor.RED+error);
        System.out.println(error);
    }

}
