package de.lostesburger.mySqlPlayerBridge.Managers.Modules;

import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.configuration.file.FileConfiguration;

public class ModulesManager {
    public String pathKickOnSyncFail;
    public String pathSyncEnderChest;
    public String pathSyncArmorSlots;
    public String pathSyncLocation;
    public String pathSyncGamemode;
    public String pathSyncExp;
    public String pathSyncHealth;
    public String pathSyncSaturation;
    public String pathSyncVaultEconomy;
    public String pathSyncInventory;
    public String pathSyncTaskDelay;

    public boolean kickOnSyncFail;
    public boolean syncEnderChest;
    public boolean syncArmorSlots;
    public boolean syncLocation;
    public boolean syncGamemode;
    public boolean syncExp;
    public boolean syncHealth;
    public boolean syncSaturation;
    public boolean syncInventory;
    public boolean syncVaultEconomy;
    public int syncTaskDelay;


    public ModulesManager(){
        FileConfiguration conf = Main.config;
        this.pathKickOnSyncFail = "settings.kickPlayerOnSyncFail";
        this.pathSyncInventory = "sync.inventory";
        this.pathSyncEnderChest = "sync.enderChest";
        this.pathSyncArmorSlots = "sync.amorSlots";
        this.pathSyncLocation = "sync.location";
        this.pathSyncGamemode = "sync.gamemode";
        this.pathSyncExp = "sync.exp";
        this.pathSyncHealth = "sync.health";
        this.pathSyncSaturation = "sync.saturation";
        this.pathSyncVaultEconomy = "sync.vaultEconomy";
        this.pathSyncTaskDelay = "syncTask.delay";


        this.syncVaultEconomy = conf.getBoolean(this.pathSyncVaultEconomy);
        this.syncEnderChest = conf.getBoolean(this.pathSyncEnderChest);
        this.syncArmorSlots = conf.getBoolean(this.pathSyncArmorSlots);
        this.syncLocation = conf.getBoolean(this.pathSyncLocation);
        this.syncGamemode = conf.getBoolean(this.pathSyncGamemode);
        this.syncExp = conf.getBoolean(this.pathSyncExp);
        this.syncHealth = conf.getBoolean(this.pathSyncHealth);
        this.syncSaturation = conf.getBoolean(this.pathSyncSaturation);
        this.syncInventory = conf.getBoolean(this.pathSyncInventory);
        this.kickOnSyncFail = conf.getBoolean(this.pathKickOnSyncFail);
        this.syncTaskDelay = (conf.getInt(this.pathSyncTaskDelay)*20);

    }
}
