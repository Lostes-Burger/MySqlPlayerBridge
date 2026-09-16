package de.lostesburger.mySqlPlayerBridge.Sync;

import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class LoadedModule<T extends ModuleSnapshot> {
    private final SyncModule<T> module;
    private final T data;

    public LoadedModule(SyncModule<T> module, T data) {
        this.module = Objects.requireNonNull(module, "module");
        this.data = Objects.requireNonNull(data, "data");
    }

    public String moduleId() {
        return this.module.id();
    }

    public CompletableFuture<Void> apply(Player player) throws Exception {
        return this.module.apply(player, this.data);
    }
}
