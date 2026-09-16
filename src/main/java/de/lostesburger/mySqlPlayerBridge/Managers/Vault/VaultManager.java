package de.lostesburger.mySqlPlayerBridge.Managers.Vault;

import de.lostesburger.mySqlPlayerBridge.Main;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.logging.Level;

public final class VaultManager {
    private final Economy economy;

    public VaultManager(){
        RegisteredServiceProvider<Economy> registration = Main.getInstance().getServer()
                .getServicesManager().getRegistration(Economy.class);
        Economy provider = registration == null ? null : registration.getProvider();
        if(provider == null || !provider.isEnabled()){
            Main.getInstance().getLogger().log(Level.SEVERE,
                    "No enabled Vault economy provider was found. Install Vault and an economy provider or disable sync.vaultEconomy.");
            throw new IllegalStateException("No enabled Vault economy provider found");
        }
        this.economy = provider;
    }

    /** Primarily useful for embedding and deterministic provider tests. */
    public VaultManager(Economy economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
        if (!this.economy.isEnabled()) {
            throw new IllegalArgumentException("Vault economy provider is disabled: " + this.economy.getName());
        }
    }

    public double getBalance(Player player) {
        Objects.requireNonNull(player, "player");
        requireProviderEnabled();
        double balance = this.economy.getBalance(player);
        if (!Double.isFinite(balance)) {
            throw new EconomyTransactionException(
                    "Economy provider " + providerName() + " returned a non-finite balance for "
                            + player.getUniqueId());
        }
        return normalize(balance);
    }

    public void setBalance(Player player, double targetBalance) {
        Objects.requireNonNull(player, "player");
        requireProviderEnabled();
        if (!Double.isFinite(targetBalance)) {
            throw new IllegalArgumentException("Economy balance must be finite: " + targetBalance);
        }

        double normalizedTarget = normalize(targetBalance);
        double currentBalance = getBalance(player);
        double difference = normalize(normalizedTarget - currentBalance);
        if (difference == 0.0d) {
            return;
        }

        EconomyResponse response = difference > 0.0d
                ? this.economy.depositPlayer(player, difference)
                : this.economy.withdrawPlayer(player, -difference);
        if (response == null || !response.transactionSuccess()) {
            String providerMessage = response == null ? "no response" : response.errorMessage;
            throw new EconomyTransactionException(
                    "Economy provider " + providerName() + " could not set balance for "
                            + player.getUniqueId() + " to " + normalizedTarget + ": " + providerMessage);
        }
    }

    public String providerName() {
        String name = this.economy.getName();
        return name == null || name.isBlank() ? this.economy.getClass().getName() : name;
    }

    private void requireProviderEnabled() {
        if (!this.economy.isEnabled()) {
            throw new EconomyTransactionException("Economy provider is disabled: " + providerName());
        }
    }

    private double normalize(double amount) {
        int fractionalDigits = this.economy.fractionalDigits();
        if (fractionalDigits < 0) {
            return amount == -0.0d ? 0.0d : amount;
        }
        int scale = Math.min(fractionalDigits, 16);
        double normalized = BigDecimal.valueOf(amount).setScale(scale, RoundingMode.HALF_UP).doubleValue();
        return normalized == -0.0d ? 0.0d : normalized;
    }
}
