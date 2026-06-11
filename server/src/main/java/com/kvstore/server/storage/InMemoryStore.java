package com.kvstore.server.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kvstore.server.config.NodeConfig;
import com.kvstore.server.storage.WriteAheadLog.Entry;
import com.kvstore.server.storage.WriteAheadLog.Operation;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory key-value store backed by a WAL and periodic snapshots.
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Load {@code snapshot.json} (if it exists) into the in-memory map.</li>
 *   <li>Replay {@code wal.log} on top of the snapshot.</li>
 * </ol>
 *
 * <p>Writes are first appended to the WAL, then applied to the map.
 */
@Component
public class InMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStore.class);
    static final String SNAPSHOT_FILE = "snapshot.json";

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private final NodeConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WriteAheadLog wal;
    private Path snapshotPath;

    public InMemoryStore(NodeConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() throws IOException {
        String dataDir = config.getDataDir();
        Files.createDirectories(Paths.get(dataDir));
        snapshotPath = Paths.get(dataDir, SNAPSHOT_FILE);
        wal = new WriteAheadLog(dataDir);

        loadSnapshot();
        replayWal();

        log.info("InMemoryStore initialised with {} keys (node={})", store.size(), config.getNodeId());
    }

    @PreDestroy
    public void shutdown() throws IOException {
        wal.close();
    }

    // --- public API ---

    /**
     * Store a key-value pair. Appends to WAL first.
     */
    public void put(String key, String value) throws IOException {
        wal.logPut(key, value);
        store.put(key, value);
    }

    /**
     * Retrieve a value, or {@code null} if absent.
     */
    public String get(String key) {
        return store.get(key);
    }

    /**
     * Delete a key. Appends to WAL first.
     *
     * @return {@code true} if the key existed
     */
    public boolean delete(String key) throws IOException {
        wal.logDelete(key);
        return store.remove(key) != null;
    }

    /**
     * @return a read-only snapshot of all current entries
     */
    public Map<String, String> getAll() {
        return Map.copyOf(store);
    }

    /**
     * @return the current set of keys
     */
    public Set<String> keySet() {
        return store.keySet();
    }

    /**
     * Write the current in-memory state to {@code snapshot.json} and compact the WAL.
     */
    public synchronized void snapshot() throws IOException {
        // Write to a temp file first, then atomically rename
        Path tmp = snapshotPath.resolveSibling(SNAPSHOT_FILE + ".tmp");
        objectMapper.writeValue(tmp.toFile(), store);
        Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Snapshot written ({} keys)", store.size());

        // Compact the WAL: drop everything that is already in the snapshot
        wal.compact(java.time.Instant.now());
    }

    // --- private helpers ---

    private void loadSnapshot() throws IOException {
        if (!Files.exists(snapshotPath)) {
            log.info("No snapshot found at {}", snapshotPath);
            return;
        }
        try {
            Map<String, String> loaded = objectMapper.readValue(snapshotPath.toFile(),
                    new TypeReference<Map<String, String>>() {});
            store.putAll(loaded);
            log.info("Loaded {} keys from snapshot {}", loaded.size(), snapshotPath);
        } catch (IOException e) {
            log.warn("Failed to load snapshot, starting with empty store: {}", e.getMessage());
        }
    }

    private void replayWal() throws IOException {
        java.util.List<Entry> entries = wal.readAll();
        int applied = 0;
        for (Entry entry : entries) {
            if (entry.operation() == Operation.PUT) {
                store.put(entry.key(), entry.value());
                applied++;
            } else if (entry.operation() == Operation.DELETE) {
                store.remove(entry.key());
                applied++;
            }
        }
        log.info("Replayed {} WAL entries", applied);
    }
}
