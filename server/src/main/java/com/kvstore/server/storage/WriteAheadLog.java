package com.kvstore.server.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-Ahead Log (WAL) for durability.
 *
 * <p>Format of each log line:
 * <pre>TIMESTAMP | OPERATION | KEY | VALUE</pre>
 * where VALUE is empty for DELETE operations.
 *
 * <p>The WAL is appended to synchronously before any write/delete is applied
 * to the in-memory store. On startup, the WAL is replayed to restore state.
 */
public class WriteAheadLog implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WriteAheadLog.class);

    public enum Operation {
        PUT, DELETE
    }

    public record Entry(Instant timestamp, Operation operation, String key, String value) {}

    private final Path walPath;
    private BufferedWriter writer;
    private final Clock clock;

    public WriteAheadLog(String dataDir) throws IOException {
        this(dataDir, Clock.systemUTC());
    }

    /** Package-visible constructor for testing with a controllable clock. */
    WriteAheadLog(String dataDir, Clock clock) throws IOException {
        this.clock = clock;
        Path dir = Paths.get(dataDir);
        Files.createDirectories(dir);
        this.walPath = dir.resolve("wal.log");
        this.writer = Files.newBufferedWriter(walPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Append a PUT entry to the WAL.
     */
    public synchronized void logPut(String key, String value) throws IOException {
        writeLine(Operation.PUT, key, value == null ? "" : value);
    }

    /**
     * Append a DELETE entry to the WAL.
     */
    public synchronized void logDelete(String key) throws IOException {
        writeLine(Operation.DELETE, key, "");
    }

    /**
     * Read all entries from the WAL (used during startup replay).
     */
    public List<Entry> readAll() throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(walPath)) {
            return entries;
        }
        try (BufferedReader reader = Files.newBufferedReader(walPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    entries.add(parseLine(line));
                } catch (Exception e) {
                    log.warn("Skipping malformed WAL line: {}", line, e);
                }
            }
        }
        return entries;
    }

    /**
     * Compact the WAL by replacing it with only the entries after the
     * snapshot timestamp. Called after a successful snapshot write.
     *
     * @param snapshotTime entries at or before this time are discarded
     */
    public synchronized void compact(Instant snapshotTime) throws IOException {
        List<Entry> entries = readAll();
        List<Entry> remaining = entries.stream()
                .filter(e -> e.timestamp().isAfter(snapshotTime))
                .toList();

        writer.close();
        Files.deleteIfExists(walPath);
        writer = Files.newBufferedWriter(walPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        for (Entry e : remaining) {
            writeLine(e.operation(), e.key(), e.value() != null ? e.value() : "");
        }
        writer.flush();
        log.info("WAL compacted: {} entries retained after {}", remaining.size(), snapshotTime);
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }

    // --- internals ---

    private void writeLine(Operation op, String key, String value) throws IOException {
        String line = clock.instant() + " | " + op.name() + " | " + escape(key) + " | " + escape(value);
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private Entry parseLine(String line) {
        // Format: TIMESTAMP | OPERATION | KEY | VALUE
        // Split with limit 4 so trailing empty value is preserved as an empty string
        String[] parts = line.split(" \\| ", 4);
        if (parts.length < 4) {
            throw new IllegalArgumentException("Expected 4 fields, got " + parts.length);
        }
        Instant ts = Instant.parse(parts[0].trim());
        Operation op = Operation.valueOf(parts[1].trim());
        String key = unescape(parts[2].trim());
        String value = unescape(parts[3]);
        return new Entry(ts, op, key, value);
    }

    private static String escape(String s) {
        // Escape pipe characters and newlines to keep the format safe
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        // Process character by character to correctly handle sequences like \\n
        // (a literal backslash followed by 'n') without converting them to a newline.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '\\' -> { sb.append('\\'); i++; }
                    case '|'  -> { sb.append('|');  i++; }
                    case 'n'  -> { sb.append('\n'); i++; }
                    case 'r'  -> { sb.append('\r'); i++; }
                    default   -> sb.append(c); // preserve unrecognised escape as-is
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
