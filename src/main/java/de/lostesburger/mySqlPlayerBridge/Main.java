package de.lostesburger.mySqlPlayerBridge;

import de.lostesburger.corelib.Config.BukkitYMLConfig;
import de.lostesburger.corelib.NMS.Minecraft;
import de.lostesburger.corelib.NMS.Version;
import de.lostesburger.corelib.PluginSmiths.Utils.PluginSmithsUpdateCheck;
import de.lostesburger.corelib.Scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.MySqlConnection.MySqlConnectionHandler;
import de.lostesburger.mySqlPlayerBridge.Managers.Command.CommandManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Modules.ModulesManager;
import de.lostesburger.mySqlPlayerBridge.Managers.NbtAPI.NBTAPIManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge.PlayerBridgeManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Vault.VaultManager;
import de.lostesburger.mySqlPlayerBridge.Serialization.SerializationType;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import de.lostesburger.mySqlPlayerBridge.Utils.Checks.DatabaseConfigCheck;
import de.lostesburger.mySqlPlayerBridge.Serialization.NBTSerialization.NBTSerializer;
import de.lostesburger.mySqlPlayerBridge.Utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

public final class Main extends JavaPlugin {

    public static ArrayList<Scheduler.Task> schedulers = new ArrayList<Scheduler.Task>();
    public static FileConfiguration config;
    public static FileConfiguration mysqlConf;
    public static FileConfiguration messages;


    public static Version McVer;
    public static String serverType = "Unknown";
    private static Plugin instance;
    public static String version = "3.2";
    public static String pluginName = "MySqlPlayerBridge";
    public static String prefix;

    public static VaultManager vaultManager;
    public static ModulesManager modulesManager;
    public static PlayerManager playerManager;
    public static PlayerBridgeManager playerBridgeManager;
    public static CommandManager commandManager;
    public static MySqlConnectionHandler mySqlConnectionHandler;
    public static NBTSerializer nbtSerializer = null;

    public static String TABLE_NAME = "player_data";
    public static SerializationType serializationType = SerializationType.NBT_API;

    public static boolean DEBUG = false;

    @Override
    public void onEnable() {
        instance = this;

        this.getLogger().log(Level.WARNING, "Starting MySqlPlayerBridge plugin v"+version);
        serverType = Bukkit.getServer().getVersion();
        this.getLogger().log(Level.INFO, "Detected server type: "+serverType);

        McVer = Minecraft.getVersion();
        if(McVer != Version.v1_21){
            this.getLogger().log(Level.SEVERE, "Plugin is using Paper API 1.21 -> downgrading not fully supported (may cause unknown bugs)");
        }
        if(Minecraft.isFolia()){
            this.getLogger().warning("Server is running Folia, a software supported by this plugin");
            this.getLogger().warning("Unknown errors in folia itself can occur (including major security flaws)");
        }

        /**
         * Config file(s)
         */
        this.getLogger().log(Level.INFO, "Loading/Creating configuration ...");
        BukkitYMLConfig ymlConfig = new BukkitYMLConfig(this, "config.yml");
        config = ymlConfig.getConfig();
        prefix = config.getString("prefix");
        config.set("version", version);
        try { Main.config.save(new File(Main.getInstance().getDataFolder(), "config.yml")); } catch (IOException ignored) {}

        BukkitYMLConfig ymlConfigMySQL = new BukkitYMLConfig(this, "mysql.yml");
        mysqlConf = ymlConfigMySQL.getConfig();
        TABLE_NAME = mysqlConf.getString("main-table-name");

        BukkitYMLConfig ymlConfigMessages = new BukkitYMLConfig(this, "messages.yml");
        messages = ymlConfigMessages.getConfig();

        this.getLogger().log(Level.INFO, "checking for configuration changes ...");

        String msg = "configuration changes found! Please visit the config.yml. Make sure you don't have to change settings to resume error free usage";
        if(ymlConfig.getAccessor().hasChanges()){
            this.getLogger().log(Level.WARNING, msg);
            Scheduler.runLaterAsync(()-> {
                this.getLogger().log(Level.WARNING, msg);
                Bukkit.broadcastMessage(prefix+msg);
            }, 5*20, this);
        }else {this.getLogger().log(Level.INFO, "No configuration changes in config.yml found."); }

        String msg2 = "configuration changes found! Please visit the messages.yml. Make sure you don't have to change settings to resume error free usage";
        if(ymlConfigMessages.getAccessor().hasChanges()){
            this.getLogger().log(Level.WARNING, msg2);
            Scheduler.runLaterAsync(()-> {
                this.getLogger().log(Level.WARNING, msg2);
                Bukkit.broadcastMessage(prefix+msg2);
            }, 5*20, this);
        }else {this.getLogger().log(Level.INFO, "No configuration changes in messages.yml found."); }

        /**
         * Checks
         */
        this.getLogger().log(Level.INFO, "Checking for updates ...");
        new PluginSmithsUpdateCheck(this, version, pluginName, prefix);
        this.getLogger().log(Level.INFO, "Checking database Configuration...");

        if(!new DatabaseConfigCheck(mysqlConf).isSetup()) {
            Bukkit.broadcastMessage(Chat.getMessage("no-database-config-error"));
            return;
        }

        /**
         * Modules
         */
        modulesManager= new ModulesManager();


        /**
         * NBT-API
         */
        if (!Utils.isPluginEnabled("NBTAPI")) {
            Scheduler.Task task = Scheduler.runTimerAsync(() -> {
                getLogger().warning("NBTAPI is not loaded make sure its installed!");
            }, 60, 60 , this);

            getLogger().warning("Listening for plugin enable event...");

            Bukkit.getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onPluginEnable(PluginEnableEvent event) {
                    if (event.getPlugin().getName().equalsIgnoreCase("NBTAPI")) {
                        task.cancel();
                        getLogger().info("NBTAPI loaded, initialisiere NBTSerializer...");
                        tryInitNBTSerializer();
                        HandlerList.unregisterAll(this);
                    }
                }
            }, this);
        }else { tryInitNBTSerializer(); }


        /**
         * VaultAPI
         */
        if(modulesManager.syncVaultEconomy){
            this.getLogger().log(Level.INFO, "Loading Vault API Module...");
            vaultManager = new VaultManager();
            this.getLogger().log(Level.INFO, "Loaded Vault API Module");
        }

        /**
         * Database
         */
        mySqlConnectionHandler = new MySqlConnectionHandler(
                mysqlConf.getString("host"),
                mysqlConf.getInt("port"),
                mysqlConf.getString("database"),
                mysqlConf.getString("user"),
                mysqlConf.getString("password")
        );

        /**
         * Other Managers
         */
        playerManager = new PlayerManager();
        playerBridgeManager = new PlayerBridgeManager();
        commandManager = new CommandManager();
    }



    public static Plugin getInstance(){return instance;}

    @Override
    public void onDisable() {
        this.getLogger().log(Level.WARNING, "Stopping MySqlPlayerBridge plugin v"+version);
        if(mySqlConnectionHandler != null){
            mySqlConnectionHandler.getMySqlDataManager().saveAllOnlinePlayers();
        }

        this.getLogger().log(Level.INFO, "Closing MySql connection...");
        if(mySqlConnectionHandler != null) {
            mySqlConnectionHandler.getMySQL().closeConnection();
        }

        this.getLogger().log(Level.INFO, "Stopping running scheduler tasks...");
        schedulers.forEach(Scheduler.Task::cancel);
    }

    private void tryInitNBTSerializer() {
        try {
            nbtSerializer = new NBTSerializer();
            getLogger().info("NBTSerializer initialized successfully");
        } catch (Exception e) {
            getLogger().severe("NBTSerializer could not be initialized:");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }
}
