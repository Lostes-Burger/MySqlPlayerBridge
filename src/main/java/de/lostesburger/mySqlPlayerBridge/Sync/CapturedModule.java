package de.lostesburger.mySqlPlayerBridge.Sync;

import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

public final class CapturedModule<T extends ModuleSnapshot> {
    private final SyncModule<T> module;
    private final T data;

    public CapturedModule(SyncModule<T> module, T data) {
        this.module = Objects.requireNonNull(module, "module");
        this.data = Objects.requireNonNull(data, "data");
    }

    public String moduleId() {
        return this.module.id();
    }

    public ModuleSnapshot dataForRecovery() {
        return this.data;
    }

    public void save(Connection connection, UUID playerUuid) throws Exception {
        this.module.save(connection, playerUuid, this.data);
    }
}
