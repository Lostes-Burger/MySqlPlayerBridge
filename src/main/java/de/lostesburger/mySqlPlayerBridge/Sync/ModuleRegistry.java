package de.lostesburger.mySqlPlayerBridge.Sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ModuleRegistry {
    private final List<SyncModule<?>> modules = new ArrayList<>();
    private final Set<String> moduleIds = new HashSet<>();

    public void register(SyncModule<?> module) {
        if (!this.moduleIds.add(module.id())) {
            throw new IllegalArgumentException("Duplicate sync module id: " + module.id());
        }
        this.modules.add(module);
    }

    public List<SyncModule<?>> enabledModules() {
        List<SyncModule<?>> enabled = new ArrayList<>();
        for (SyncModule<?> module : this.modules) {
            if (module.enabled()) {
                enabled.add(module);
            }
        }
        return Collections.unmodifiableList(enabled);
    }
}
