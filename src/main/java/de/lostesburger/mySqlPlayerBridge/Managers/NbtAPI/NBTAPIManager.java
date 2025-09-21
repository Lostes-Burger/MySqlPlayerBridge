package de.lostesburger.mySqlPlayerBridge.Managers.NbtAPI;

import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class NBTAPIManager {

    private static final String PLUGIN_FILENAME = "ItemNBTAPI.jar";
    private static final String PLUGIN_NAME = "ItemNBTAPI";
    private static final String MODRINTH_STATIC_URL_PREFIX = "https://cdn.modrinth.com/data/nfGCP9fk/versions/zINGb3iE/item-nbt-api-plugin-";
    private final File pluginsFolder = new File("plugins");

    public void checkAndUpdate() {
        try {
            String installed = getInstalledVersion();
            String latest = fetchLatestVersionFromGitHub();
            Main.getInstance().getLogger().info("[NBTAPIManager] Installed: " + installed + ", Latest-available: " + latest);

            if (installed == null || !installed.equals(latest)) {
                Main.getInstance().getLogger().info("[NBTAPIManager] Loading latest version " + latest + "...");
                uninstallOldVersion();
                String downloadUrl = MODRINTH_STATIC_URL_PREFIX + latest + ".jar";
                downloadPlugin(downloadUrl);
                loadPlugin();
                Main.getInstance().getLogger().info("[NBTAPIManager] NBT-API installed/updated.");
            } else {
                Main.getInstance().getLogger().info("[NBTAPIManager] NBT-API is running the newest version.");
            }
        } catch (Exception e) {
            Main.getInstance().getLogger().severe("[NBTAPIManager] Error while loading NBT-API:");
            e.printStackTrace();
        }
    }

    private void uninstallOldVersion() {
        File file = new File(pluginsFolder, PLUGIN_FILENAME);
        if (file.exists()) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (plugin != null) {
                Bukkit.getPluginManager().disablePlugin(plugin);
            }
            file.delete();
        }
    }

    private String getInstalledVersion() {
        File jarFile = new File(pluginsFolder, PLUGIN_FILENAME);
        if (!jarFile.exists()) return null;
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("version:")) {
                        return line.split(":", 2)[1].trim();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String fetchLatestVersionFromGitHub() throws IOException {
        URL url = new URL("https://api.github.com/repos/tr7zw/Item-NBT-API/releases/latest");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestProperty("Accept", "application/vnd.github.v3+json");

        if (con.getResponseCode() != 200) {
            throw new IOException("GitHub API returned code: " + con.getResponseCode());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
        StringBuilder json = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            json.append(line);
        }
        reader.close();

        // Beispiel: "tag_name":"2.15.1"
        String regex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(json.toString());
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IOException("tag_name nicht gefunden in GitHub JSON");
    }


    private void downloadPlugin(String url) throws IOException {
        File target = new File(pluginsFolder, PLUGIN_FILENAME);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        Main.getInstance().getLogger().severe("[NBTAPIManager] Downloaded plugin from: "+url);
    }

    private void loadPlugin() {
        try {
            File pluginFile = new File(pluginsFolder, PLUGIN_FILENAME);
            Plugin plugin = Bukkit.getPluginManager().loadPlugin(pluginFile);
            Bukkit.getPluginManager().enablePlugin(plugin);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
