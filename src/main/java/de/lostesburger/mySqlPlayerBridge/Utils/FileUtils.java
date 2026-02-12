package de.lostesburger.mySqlPlayerBridge.Utils;

import de.lostesburger.mySqlPlayerBridge.Main;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;

public class FileUtils {

    public static void saveMapToFile(String fileName, HashMap<String, Object> data) {
        if (!fileName.endsWith(".json")) {
            fileName += ".json";
        }

        File file = new File(Main.getInstance().getDataFolder(), fileName);
        writeMapToFile(file, data);
    }

    public static void saveMapToFile(String subDir, String fileName, HashMap<String, Object> data) {
        if (!fileName.endsWith(".json")) {
            fileName += ".json";
        }
        File baseDir = new File(Main.getInstance().getDataFolder(), subDir);
        File file = new File(baseDir, fileName);
        writeMapToFile(file, data);
    }

    private static void writeMapToFile(File file, HashMap<String, Object> data) {
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        try (FileWriter writer = new FileWriter(file)) {
            writer.write("{\n");
            int i = 0;
            for (var entry : data.entrySet()) {
                String key = escapeJson(entry.getKey());
                String value = escapeJson(String.valueOf(entry.getValue()));
                writer.write("  \"" + key + "\": \"" + value + "\"");
                if (++i < data.size()) {
                    writer.write(",");
                }
                writer.write("\n");
            }
            writer.write("}");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
