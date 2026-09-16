package de.lostesburger.mySqlPlayerBridge.Sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class RecoverySnapshotStore {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path recoveryDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public RecoverySnapshotStore(Path pluginDataDirectory) {
        this.recoveryDirectory = pluginDataDirectory.resolve("recovery");
    }

    public Path save(PlayerSnapshot snapshot, String reason, Throwable throwable) throws IOException {
        Path playerDirectory = this.recoveryDirectory.resolve(snapshot.playerUuid().toString());
        Files.createDirectories(playerDirectory);
        String filename = FILE_TIME.format(Instant.now()) + '-' + UUID.randomUUID() + ".json";
        Path target = playerDirectory.resolve(filename);

        Map<String, Object> modules = new LinkedHashMap<>();
        for (CapturedModule<?> module : snapshot.modules()) {
            modules.put(module.moduleId(), module.dataForRecovery());
        }
        RecoveryEnvelope envelope = new RecoveryEnvelope(
                snapshot.playerUuid(),
                snapshot.playerName(),
                snapshot.capturedAt().toString(),
                Instant.now().toString(),
                reason,
                throwable == null ? null : throwable.getClass().getName(),
                throwable == null ? null : throwable.getMessage(),
                throwable == null ? null : stackTrace(throwable),
                modules
        );
        Files.writeString(
                target,
                this.gson.toJson(envelope),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        return target;
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private record RecoveryEnvelope(
            UUID playerUuid,
            String playerName,
            String capturedAt,
            String recoveryWrittenAt,
            String reason,
            String exceptionType,
            String exceptionMessage,
            String stackTrace,
            Map<String, Object> modules
    ) {
    }
}
