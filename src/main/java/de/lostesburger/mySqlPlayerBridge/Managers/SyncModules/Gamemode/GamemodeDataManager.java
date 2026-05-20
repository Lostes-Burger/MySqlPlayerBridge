package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Gamemode;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Handlers.Errors.MySqlErrorHandler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.BridgeScheduler;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class GamemodeDataManager {
    private final boolean enabled;
    private final MySqlManager mySqlManager;

    public GamemodeDataManager(){
        this.enabled = Main.modulesManager.syncGamemode;
        this.mySqlManager = Main.mySqlConnectionHandler.getManager();

        try {
            if(!this.mySqlManager.tableExists(Main.TABLE_NAME_GAMEMODE)){
                new MySqlErrorHandler().logSyncError("Gamemode", "table-exists", Main.TABLE_NAME_GAMEMODE, null,
                        new RuntimeException("Gamemode mysql table is missing!"), Map.of("table", Main.TABLE_NAME_GAMEMODE), false);
                throw new RuntimeException("Gamemode mysql table is missing!");
            }
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Gamemode", "table-exists", Main.TABLE_NAME_GAMEMODE, null,
                    e, Map.of("table", Main.TABLE_NAME_GAMEMODE), false);
            throw new RuntimeException(e);
        }
    }

    public void savePlayer(Player player, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runEntity(player, () -> {
                    if(!this.enabled) return;
                    String uuid = player.getUniqueId().toString();
                    String gameMode = player.getGameMode().toString();
                    BridgeScheduler.runAsync(() -> this.insertToMySql(uuid, gameMode));
                });
            } else {
                Scheduler.runAsync(() -> {
                    this.save(player);
                }, Main.getInstance());
            }
        }else {
            this.save(player);
        }

    }

    public void saveManual(UUID uuid, String gameMode, boolean async){
        if(async){
            if (Main.IS_FOLIA) {
                BridgeScheduler.runAsync(() -> this.insertToMySql(uuid.toString(), gameMode));
            } else {
                Scheduler.runAsync(() -> {
                    this.insertToMySql(uuid.toString(), gameMode);
                }, Main.getInstance());
            }
        }else {
            this.insertToMySql(uuid.toString(), gameMode);
        }
    }

    private void save(Player player){
        if(!this.enabled) return;
        this.insertToMySql(player.getUniqueId().toString(), player.getGameMode().toString());
    }

    private void insertToMySql(String uuid, String gameMode){
        try {
            mySqlManager.setOrUpdateEntry(
                    Main.TABLE_NAME_GAMEMODE,
                    Map.of("uuid", uuid),
                    Map.of("gamemode", gameMode)
            );
        } catch (MySqlError e) {
            new MySqlErrorHandler().logSyncError("Gamemode", "save", Main.TABLE_NAME_GAMEMODE, null,
                    e, Map.of("uuid", uuid), false);
        }
    }

    public CompletableFuture<Void> applyPlayer(Player player){
        CompletableFuture<Void> future = new CompletableFuture<>();
        String playerUuid = player.getUniqueId().toString();
        Runnable loadTask = () -> {
            if(!this.enabled){
                future.complete(null);
                return;
            }

            Map<String, Object> entry;
            try {
                entry = mySqlManager.getEntry(Main.TABLE_NAME_GAMEMODE,
                        Map.of("uuid", playerUuid)
                );
            } catch (MySqlError e) {
                new MySqlErrorHandler().logSyncError("Gamemode", "load", Main.TABLE_NAME_GAMEMODE, player,
                        e, Map.of("uuid", playerUuid), true);
                future.completeExceptionally(e);
                return;
            }
            if(entry == null || entry.isEmpty()){
                future.complete(null);
                return;
            }

            Runnable applyTask = () -> {
                try {
                    player.setGameMode(GameMode.valueOf(String.valueOf(entry.get("gamemode"))));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    return;
                }
                future.complete(null);
            };

            if (Main.IS_FOLIA) {
                boolean scheduled = BridgeScheduler.runEntity(player, applyTask, () -> future.complete(null));
                if (!scheduled) {
                    future.complete(null);
                }
            } else {
                Scheduler.run(applyTask, Main.getInstance());
            }

        };
        if (Main.IS_FOLIA) {
            BridgeScheduler.runAsync(loadTask);
        } else {
            Scheduler.runAsync(loadTask, Main.getInstance());
        }
        return future;
    }
}
