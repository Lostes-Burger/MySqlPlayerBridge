# Temporäre Sync-Notizen

## Was geändert wurde
- Join-Sync nutzt jetzt einen In-Memory-Lock pro Spieler (kein DB-Lock), inklusive 5-Sekunden-Wartefenster.
- Während aktivem Join-Sync werden relevante Spieler-Events in einer eigenen Klasse geblockt (`JoinSyncEventBlocker`).
- Vor jedem Admin-GUI-Edit (`inventory`, `armor`, `enderchest`) wird für lokal online Targets ein Pre-Sync in die DB erzwungen.
- Wenn Pre-Sync fehlschlägt oder in den Lock-Timeout läuft, wird der Edit abgebrochen.
- Join-Sync und Edit-Pre-Sync nutzen dieselbe Lock-Mechanik, damit keine überlappenden kritischen Syncs passieren.

## Warum
- Verhindert Dupes/Item-Verluste während Join-Sync.
- Verhindert, dass GUI-Edits mit veralteten Daten starten.
- Erzwingt klares Fehlerverhalten bei hängenden oder fehlerhaften Syncs statt Weiterarbeiten mit alten Daten.
