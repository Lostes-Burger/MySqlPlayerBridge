package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Money;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public class MoneyDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public MoneyDataManager(){
        this.enabled = Main.modulesManager.syncVaultEconomy;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_MONEY)){
                throw new RuntimeException("Money mysql table is missing!");
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }

        if(this.enabled){
            if(Main.vaultManager == null){
                throw new RuntimeException("Vault manager not loaded!");
            }
        }

    }

    public void savePlayer(Player player, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.save(player);
            }, Main.getInstance());
        }else {
            this.save(player);
        }

    }

    public void saveManual(UUID uuid, double money, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), money);
            }, Main.getInstance());
        }else {
            this.insertToMySql(uuid.toString(), money);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        double money = Main.vaultManager.getBalance(player);
        this.insertToMySql(player.getUniqueId().toString(), money);
    }

    private void insertToMySql(String uuid, double money){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_MONEY,
                    Map.of("uuid", uuid),
                    Map.of("money", money)
            );
        } catch (MySqlError e) {
            throw new RuntimeException(e);
        }
    }

    public void applyPlayer(Player player){
        Scheduler.runAsync(() -> {
            if(!this.enabled) return;

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_MONEY,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;

            Main.vaultManager.setBalance(player, (Double) entry.get("money"));

        }, Main.getInstance());
    }
}
