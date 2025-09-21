package de.lostesburger.mySqlPlayerBridge.Managers.Vault;

import de.lostesburger.mySqlPlayerBridge.Main;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.logging.Level;

public class VaultManager {
    private static Economy economy;
    public VaultManager(){
        economy = null;
        if(!isVaultAvailable()){
            Main.getInstance().getLogger().log(Level.SEVERE, "Vault API not found. Install Vault Plugin or disable this feature in the config.yml");
            throw new RuntimeException("Vault API not found. Install Vault Plugin or disable this feature in the config.yml");
        }
        this.setup();
    }

    public boolean setup() {
        RegisteredServiceProvider<Economy> rsp = Main.getInstance().getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            this.economy = rsp.getProvider();
        }
        return this.economy != null;
    }
    public double getBalance(Player player) {
        if (this.economy != null) {
            return this.economy.getBalance(player);
        }
        return 0.0;
    }
    public boolean setBalance(Player player, double amount) {
        if (this.economy != null) {
            double currentBalance = this.economy.getBalance(player);
            if (this.economy.has(player, currentBalance)) {
                this.economy.withdrawPlayer(player, currentBalance);
                this.economy.depositPlayer(player, amount);
                return true;
            }
        }
        return false;
    }

    private boolean isVaultAvailable() {
        Plugin vaultPlugin = Main.getInstance().getServer().getPluginManager().getPlugin("Vault");
        return vaultPlugin != null && vaultPlugin.isEnabled();
    }
}

