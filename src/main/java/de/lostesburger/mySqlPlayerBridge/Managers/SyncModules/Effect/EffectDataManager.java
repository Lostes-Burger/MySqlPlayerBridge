package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Effect;

import de.craftcore.craftcore.global.minecraftVersion.Minecraft;
import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.craftcore.craftcore.global.scheduler.SchedulerException;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.SyncManager;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EffectDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public EffectDataManager(){
        this.enabled = Main.modulesManager.syncEffects;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_EFFECTS)){
                throw new RuntimeException("Potion Effect mysql table is missing!");
            }
        } catch (MySqlError e) {
            throw new RuntimeException(e);
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

    public void saveManual(UUID uuid, String serializedEffects, boolean async){
        if(async){
            Scheduler.runAsync(() -> {
                this.insertToMySql(uuid.toString(), serializedEffects);
            }, Main.getInstance());
        }else {
            this.insertToMySql(uuid.toString(), serializedEffects);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        String serialized = SyncManager.potionSerializer.serialize(player);
        this.insertToMySql(player.getUniqueId().toString(), serialized);
    }

    private void insertToMySql(String uuid, String serializedEffects){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_EFFECTS,
                    Map.of("uuid", uuid),
                    Map.of("effects", serializedEffects)
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
                entry = mySqlManager.getEntry(Main.TABLE_NAME_EFFECTS,
                        Map.of("uuid", player.getUniqueId().toString())
                );
            } catch (MySqlError e) {
                throw new RuntimeException(e);
            }
            if(entry == null) return;
            if(entry.isEmpty()) return;
            String serialized = (String) entry.get("effects");

            List<PotionEffect> effects = SyncManager.potionSerializer.deserialize(serialized);

            if(!Minecraft.isFolia()){
                Scheduler.run(() -> {
                    player.addPotionEffects(effects);
                }, Main.getInstance());
            }else {
                try {
                    Scheduler.runRegionalScheduler(() -> {
                        player.addPotionEffects(effects);
                    }, Main.getInstance(), player.getLocation());
                } catch (SchedulerException e) {
                    throw new RuntimeException(e);
                }
            }

        }, Main.getInstance());

    }
}
