package de.lostesburger.mySqlPlayerBridge.Managers.Edit;

import de.lostesburger.mySqlPlayerBridge.Main;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** Tab completion only reads memory; JDBC refreshes run on the database executor. */
public final class EditSuggestions {
    private volatile Map<String, Target> targets = Map.of();
    private volatile long indexRefresh;
    private final AtomicBoolean loadingIndex = new AtomicBoolean();
    private final Map<String, Values> values = new LinkedHashMap<>(16, .75f, true);
    private static final long REFRESH_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    private final Supplier<CompletableFuture<List<Map<String, Object>>>> indexLoader;
    private final BiFunction<UUID, String, CompletableFuture<Map<String, Object>>> valueLoader;
    private final LongSupplier clock;

    public EditSuggestions() {
        this(() -> Main.mySqlConnectionHandler.getDatabaseExecutor().supply(() ->
                        Main.mySqlConnectionHandler.getManager().getAllEntries(Main.TABLE_NAME_PLAYER_INDEX)),
                (uuid, table) -> Main.mySqlConnectionHandler.getDatabaseExecutor().supply(() ->
                        Main.mySqlConnectionHandler.getManager().getEntry(table, Map.of("uuid", uuid.toString()))),
                System::nanoTime);
    }

    EditSuggestions(Supplier<CompletableFuture<List<Map<String, Object>>>> indexLoader,
            BiFunction<UUID, String, CompletableFuture<Map<String, Object>>> valueLoader, LongSupplier clock) {
        this.indexLoader = indexLoader;
        this.valueLoader = valueLoader;
        this.clock = clock;
        refreshIndex();
    }

    public List<String> names() {
        refreshIndex();
        return targets.values().stream().map(Target::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public void refreshIndex() {
        long now = clock.getAsLong();
        if ((indexRefresh != 0 && now - indexRefresh < REFRESH_NANOS) || !loadingIndex.compareAndSet(false, true)) return;
        indexRefresh = now;
        indexLoader.get().thenApply(rows -> {
            Map<String, Target> loaded = new HashMap<>();
            for (Map<String, Object> row : rows) {
                if (row.get("player_name") instanceof String name && row.get("uuid") instanceof String uuid) {
                    loaded.put(name.toLowerCase(Locale.ROOT), new Target(UUID.fromString(uuid), name));
                }
            }
            return Map.copyOf(loaded);
        }).whenComplete((loaded, failure) -> {
            if (failure == null) targets = loaded;
            loadingIndex.set(false);
        });
    }

    public Map<String, Object> values(String playerName, String type) {
        refreshIndex();
        Target target = targets.get(playerName.toLowerCase(Locale.ROOT));
        if (target == null) return Map.of();
        String table = EditChange.tableFor(type);
        String key = target.uuid() + ":" + table;
        synchronized (values) {
            Values cached = values.get(key);
            long now = clock.getAsLong();
            if (cached == null) {
                cached = new Values();
                if (values.size() >= 256) values.remove(values.keySet().iterator().next());
                values.put(key, cached);
            }
            if (!cached.loading && (cached.refreshed == 0 || now - cached.refreshed >= REFRESH_NANOS)) {
                cached.loading = true;
                cached.refreshed = now;
                Values entry = cached;
                valueLoader.apply(target.uuid(), table)
                        .whenComplete((row, failure) -> {
                            synchronized (values) {
                                if (failure == null) entry.row = row == null ? Map.of() : new HashMap<>(row);
                                entry.loading = false;
                            }
                        });
            }
            return new HashMap<>(cached.row);
        }
    }

    private record Target(UUID uuid, String name) { }
    private static final class Values {
        Map<String, Object> row = Map.of();
        long refreshed;
        boolean loading;
    }
}
