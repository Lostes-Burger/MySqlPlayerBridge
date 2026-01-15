package de.lostesburger.mySqlPlayerBridge;

import de.craftcore.craftcore.global.minecraftVersion.Minecraft;
import de.craftcore.craftcore.paper.configuration.lostesburger.BukkitYMLConfig;
import de.lostesburger.mySqlPlayerBridge.Handlers.MySqlConnection.MySqlConnectionHandler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Migration.MySqlMigrationHandler;
import de.lostesburger.mySqlPlayerBridge.Managers.Command.CommandManager;
import de.lostesburger.mySqlPlayerBridge.Managers.ModulesManager.ModulesManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Player.PlayerManager;
import de.lostesburger.mySqlPlayerBridge.Managers.PlayerBridge.PlayerBridgeManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import de.lostesburger.mySqlPlayerBridge.Managers.Vault.VaultManager;
import de.lostesburger.mySqlPlayerBridge.Serialization.SerializationType;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import de.lostesburger.mySqlPlayerBridge.Utils.Checks.DatabaseConfigCheck;
import de.lostesburger.mySqlPlayerBridge.Serialization.NBTSerialization.NBTSerializer;
import de.craftcore.craftcore.paper.githubUpdate.GitHubUpdateCheckerHandler;
import de.lostesburger.mySqlPlayerBridge.Utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import de.craftcore.craftcore.global.scheduler.Scheduler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

public final class Main extends JavaPlugin {

    public static ArrayList<Scheduler.Task> schedulers = new ArrayList<Scheduler.Task>();
    public static FileConfiguration config;
    public static FileConfiguration mysqlConf;
    public static FileConfiguration messages;

    public static String serverType = "Unknown";
    private static Plugin instance;
    public static String version = "3.7.0";
    public static String PLUGIN_NAME = "MySqlPlayerBridge";
    public static String PREFIX;
    public static String LANGUAGE;

    public static VaultManager vaultManager;
    public static ModulesManager modulesManager;
    public static PlayerManager playerManager;
    public static PlayerBridgeManager playerBridgeManager;
    public static CommandManager commandManager;
    public static SyncManager syncManager;
    public static MySqlMigrationHandler mySqlMigrationHandler;

    public static MySqlConnectionHandler mySqlConnectionHandler;

    public static NBTSerializer nbtSerializer = null;
    public static boolean SUPPRESS_WARNINGS = false;

    public static String TABLE_NAME = "player_data";
    public static String TABLE_NAME_REGISTERED_PLAYERS;
    public static String TABLE_NAME_MIGRATION;

    public static String TABLE_NAME_EFFECTS;
    public static String TABLE_NAME_ADVANCEMENTS;
    public static String TABLE_NAME_STATS;
    public static String TABLE_NAME_SELECTED_HOTBAR_SLOT;
    public static String TABLE_NAME_SATURATION;
    public static String TABLE_NAME_LOCATION;
    public static String TABLE_NAME_EXP;
    public static String TABLE_NAME_GAMEMODE;
    public static String TABLE_NAME_INVENTORY;
    public static String TABLE_NAME_ARMOR;
    public static String TABLE_NAME_ENDERCHEST;
    public static String TABLE_NAME_HEALTH;
    public static String TABLE_NAME_MONEY;


    public static SerializationType serializationType = SerializationType.NBT_API;

    public static boolean DEBUG = false;

    @Override
    public void onEnable() {
        instance = this;

        this.getLogger().log(Level.WARNING, "Starting "+PLUGIN_NAME+" plugin v" + version);        serverType = Bukkit.getServer().getVersion();
        this.getLogger().log(Level.INFO, "Detected server type: " + serverType);

        if (Minecraft.isFolia()) {
            this.getLogger().warning("Server is running Folia, a software supported by this plugin");
            this.getLogger().warning("Unknown errors in Folia itself can occur (including major security flaws)");
        }

        /**
         * Config file(s)
         */
        this.getLogger().log(Level.INFO, "Loading/Creating configuration ...");
        BukkitYMLConfig ymlConfig = new BukkitYMLConfig(this, "config.yml");
        config = ymlConfig.getConfig();
        PREFIX = config.getString("prefix");
        LANGUAGE = config.getString("settings.language");
        SUPPRESS_WARNINGS = config.getBoolean("settings.suppress-warnings", false);
        config.set("version", version);
        try {
            Main.config.save(new File(Main.getInstance().getDataFolder(), "config.yml"));
        } catch (IOException ignored) {
        }

        BukkitYMLConfig ymlConfigMySQL = new BukkitYMLConfig(this, "mysql.yml");
        mysqlConf = ymlConfigMySQL.getConfig();
        TABLE_NAME = mysqlConf.getString("main-table-name");
        TABLE_NAME_REGISTERED_PLAYERS = "mpb_registered_players";
        TABLE_NAME_MIGRATION = "mpb_migration";

        TABLE_NAME_EFFECTS = TABLE_NAME + "_potion_effects";
        TABLE_NAME_ADVANCEMENTS = TABLE_NAME + "_advancements";
        TABLE_NAME_STATS = TABLE_NAME + "_stats";
        TABLE_NAME_SELECTED_HOTBAR_SLOT = TABLE_NAME  + "_selected_hotbar_slot";
        TABLE_NAME_SATURATION = TABLE_NAME + "_saturation";
        TABLE_NAME_LOCATION = TABLE_NAME + "_location";
        TABLE_NAME_EXP = TABLE_NAME + "_exp";
        TABLE_NAME_GAMEMODE = TABLE_NAME + "_gamemode";
        TABLE_NAME_INVENTORY = TABLE_NAME + "_inventory";
        TABLE_NAME_ARMOR = TABLE_NAME + "_armor";
        TABLE_NAME_ENDERCHEST = TABLE_NAME + "_enderchest";
        TABLE_NAME_HEALTH = TABLE_NAME + "_health";
        TABLE_NAME_MONEY = TABLE_NAME + "_money";

        BukkitYMLConfig ymlConfigMessages = new BukkitYMLConfig(this, "lang/"+LANGUAGE+".yml");
        messages = ymlConfigMessages.getConfig();

        this.getLogger().log(Level.INFO, "checking for configuration changes ...");

        String msg = "configuration changes found! Please visit the config.yml. Make sure you don't have to change settings to resume error free usage";
        if (ymlConfig.getAccessor().hasChanges()) {
            this.getLogger().log(Level.WARNING, msg);
            Scheduler.runLaterAsync(() -> {
                this.getLogger().log(Level.WARNING, msg);
                Bukkit.broadcastMessage(PREFIX + msg);
            }, 5 * 20, this);
        } else {
            this.getLogger().log(Level.INFO, "No configuration changes in config.yml found.");
        }

        String msg2 = "configuration changes found! Please visit the lang/"+LANGUAGE+".yml. Make sure you don't have to change settings to resume error free usage";
        if (ymlConfigMessages.getAccessor().hasChanges()) {
            this.getLogger().log(Level.WARNING, msg2);
            Scheduler.runLaterAsync(() -> {
                this.getLogger().log(Level.WARNING, msg2);
                Bukkit.broadcastMessage(PREFIX + msg2);
            }, 5 * 20, this);
        } else {
            this.getLogger().log(Level.INFO, "No configuration changes in lang/"+LANGUAGE+".yml found.");
        }

        /**
         * Checks
         */
        this.getLogger().log(Level.INFO, "Checking for updates ...");
        // New GitHubUpdateChecker with 403 rate-limit failsafe
        new GitHubUpdateCheckerHandler(this, version, "https://github.com/Lostes-Burger/MySqlPlayerBridge", PREFIX, 30*60);

        this.getLogger().log(Level.INFO, "Checking database Configuration...");
        if (!new DatabaseConfigCheck(mysqlConf).isSetup()) {
            Bukkit.broadcastMessage(Chat.getMessage("no-database-config-error"));
            return;
        }

        /**
         * Modules
         */
        modulesManager = new ModulesManager();


        /**
         * NBT-API
         */
        if (!Utils.isPluginEnabled("NBTAPI")) {
            Scheduler.Task task = Scheduler.runTimerAsync(() -> {
                getLogger().warning("NBTAPI is not loaded make sure its installed!");
            }, 60, 60, this);

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
        } else {
            tryInitNBTSerializer();
        }


        /**
         * VaultAPI
         */
        if (modulesManager.syncVaultEconomy) {
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
         * SyncModules
         */
        syncManager = new SyncManager();

        /**
         * Migration
         */
        this.getLogger().log(Level.INFO, "Checking for migrations...");
        mySqlMigrationHandler = new MySqlMigrationHandler();


        /**
         * Managers
         */
        mySqlMigrationHandler.runAfterCheck(() -> {
            playerManager = new PlayerManager();
            playerBridgeManager = new PlayerBridgeManager();
            commandManager = new CommandManager();
        });
    }

    public static Plugin getInstance(){return instance;}

    @Override
    public void onDisable() {
        this.getLogger().log(Level.WARNING, "Stopping "+PLUGIN_NAME+" plugin v"+version);
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