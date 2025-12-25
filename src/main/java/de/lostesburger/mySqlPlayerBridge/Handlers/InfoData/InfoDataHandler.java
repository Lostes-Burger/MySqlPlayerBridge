package de.lostesburger.mySqlPlayerBridge.Handlers.InfoData;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.json.simple.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class InfoDataHandler {
    private HashMap<String, Object> infos;
    public static final String TABLE_NAME = "info_data";

    public InfoDataHandler() {
        infos = new HashMap<String, Object>();
        this.addGeneralInfos();
    }

    private void addGeneralInfos(){
        this.addInfo("plugin_ver", Main.version);
        this.addInfo("server_ver", Main.serverType);
    }

    public void addInfo(String info_key, Object info_value){ infos.put(info_key, info_value); }
    public void removeInfo(String info_key){ infos.remove(info_key); }

    public void saveData(){
        DateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
        String date = dateFormat.format(new Date());

        JSONObject jsonObject = new JSONObject(this.infos);
        String jsonData = jsonObject.toString();

        try {
            Main.mySqlConnectionHandler.getManager().setOrUpdateEntry(TABLE_NAME,
                    Map.of("date", date),
                    Map.of("data", jsonData)
            );
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }
}
