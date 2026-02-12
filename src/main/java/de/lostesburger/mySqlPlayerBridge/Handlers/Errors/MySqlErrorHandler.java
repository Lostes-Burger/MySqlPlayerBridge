package de.lostesburger.mySqlPlayerBridge.Handlers.Errors;

import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.Utils.FileUtils;
import org.bukkit.entity.Player;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.io.PrintWriter;
import java.io.StringWriter;

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

        Main.getInstance().getLogger().log(Level.SEVERE, error);
        System.out.println(error);
    }

    public String logSyncError(String module, String action, String table, Player player, Exception exception, Map<String, Object> context, boolean kickPlayer) {
        String errorId = UUID.randomUUID().toString();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date());
        String playerName = player != null ? player.getName() : "unknown";
        String playerUuid = player != null ? player.getUniqueId().toString() : String.valueOf(context != null ? context.get("uuid") : "unknown");

        StringBuilder error = new StringBuilder();
        error.append("MySqlPlayerBridge SyncModule Error\n");
        error.append("Error-ID: ").append(errorId).append("\n");
        error.append("Timestamp: ").append(timestamp).append("\n");
        error.append("Module: ").append(module).append("\n");
        error.append("Action: ").append(action).append("\n");
        error.append("Table: ").append(table).append("\n");
        error.append("Player-Name: ").append(playerName).append("\n");
        error.append("Player-UUID: ").append(playerUuid).append("\n");
        if (exception != null) {
            error.append("Exception: ").append(exception.getClass().getName()).append(": ").append(exception.getMessage()).append("\n");
        }
        if (context != null && !context.isEmpty()) {
            error.append("Context: ").append(context).append("\n");
        }

        Main.getInstance().getLogger().log(Level.SEVERE, error.toString());
        if (exception != null) {
            Main.getInstance().getLogger().log(Level.SEVERE, "SyncModule exception stack trace", exception);
        }

        HashMap<String, Object> logData = new HashMap<>();
        logData.put("error_id", errorId);
        logData.put("timestamp", timestamp);
        logData.put("module", module);
        logData.put("action", action);
        logData.put("table", table);
        logData.put("player_name", playerName);
        logData.put("player_uuid", playerUuid);
        if (exception != null) {
            logData.put("exception_type", exception.getClass().getName());
            logData.put("exception_message", String.valueOf(exception.getMessage()));
            logData.put("stack_trace", getStackTrace(exception));
        }
        if (context != null && !context.isEmpty()) {
            logData.put("context", context.toString());
        }
        FileUtils.saveMapToFile("mysql-sync-error-" + errorId, logData);

        if (kickPlayer && player != null) {
            PlayerManager.syncFailedKick(player);
        }
        return errorId;
    }

    private String getStackTrace(Exception exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }

    public void saveSyncData(String errorId, String module, String action, String table, Player player, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        String playerName = player != null ? player.getName() : "unknown";
        String playerUuid = player != null ? player.getUniqueId().toString() : String.valueOf(data.getOrDefault("uuid", "unknown"));

        HashMap<String, Object> logData = new HashMap<>();
        logData.put("error_id", errorId);
        logData.put("module", module);
        logData.put("action", action);
        logData.put("table", table);
        logData.put("player_name", playerName);
        logData.put("player_uuid", playerUuid);
        logData.put("data", data.toString());

        FileUtils.saveMapToFile("sync-failures", "sync-data-" + errorId, logData);
    }
}
