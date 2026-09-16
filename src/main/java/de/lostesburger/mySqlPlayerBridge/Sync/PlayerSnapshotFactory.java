package de.lostesburger.mySqlPlayerBridge.Sync;

import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class PlayerSnapshotFactory {
    private final ModuleRegistry moduleRegistry;

    public PlayerSnapshotFactory(ModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    public PlayerSnapshot capture(Player player) throws SnapshotException {
        List<CapturedModule<?>> capturedModules = new ArrayList<>();
        for (SyncModule<?> module : this.moduleRegistry.enabledModules()) {
            try {
                capturedModules.add(captureModule(module, player));
            } catch (Exception exception) {
                throw new SnapshotException("Could not capture module " + module.id() + " for " + player.getUniqueId(), exception);
            }
        }
        return new PlayerSnapshot(player.getUniqueId(), player.getName(), Instant.now(), capturedModules);
    }

    private static <T extends ModuleSnapshot> CapturedModule<T> captureModule(SyncModule<T> module, Player player) throws Exception {
        return new CapturedModule<>(module, module.capture(player));
    }
}
