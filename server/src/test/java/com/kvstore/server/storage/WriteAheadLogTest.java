package com.kvstore.server.storage;

import com.kvstore.server.storage.WriteAheadLog.Entry;
import com.kvstore.server.storage.WriteAheadLog.Operation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WriteAheadLogTest {

    @TempDir
    Path tempDir;

    private WriteAheadLog wal;

    @BeforeEach
    void setUp() throws IOException {
        wal = new WriteAheadLog(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        wal.close();
    }

    @Test
    void emptyWalReturnsNoEntries() throws IOException {
        List<Entry> entries = wal.readAll();
        assertTrue(entries.isEmpty());
    }

    @Test
    void logPutAndReadBack() throws IOException {
        wal.logPut("foo", "bar");

        List<Entry> entries = wal.readAll();
        assertEquals(1, entries.size());

        Entry entry = entries.get(0);
        assertEquals(Operation.PUT, entry.operation());
        assertEquals("foo", entry.key());
        assertEquals("bar", entry.value());
        assertNotNull(entry.timestamp());
    }

    @Test
    void logDeleteAndReadBack() throws IOException {
        wal.logDelete("my-key");

        List<Entry> entries = wal.readAll();
        assertEquals(1, entries.size());

        Entry entry = entries.get(0);
        assertEquals(Operation.DELETE, entry.operation());
        assertEquals("my-key", entry.key());
    }

    @Test
    void multipleEntriesPreserveOrder() throws IOException {
        wal.logPut("a", "1");
        wal.logPut("b", "2");
        wal.logDelete("a");

        List<Entry> entries = wal.readAll();
        assertEquals(3, entries.size());
        assertEquals(Operation.PUT, entries.get(0).operation());
        assertEquals("a", entries.get(0).key());
        assertEquals(Operation.PUT, entries.get(1).operation());
        assertEquals("b", entries.get(1).key());
        assertEquals(Operation.DELETE, entries.get(2).operation());
        assertEquals("a", entries.get(2).key());
    }

    @Test
    void specialCharactersInKeyAndValue() throws IOException {
        String key = "key|with|pipes";
        String value = "value\nwith\nnewlines";

        wal.logPut(key, value);

        List<Entry> entries = wal.readAll();
        assertEquals(1, entries.size());
        assertEquals(key, entries.get(0).key());
        assertEquals(value, entries.get(0).value());
    }

    @Test
    void backslashEscapingIsReversible() throws IOException {
        String key = "key\\with\\backslashes";
        String value = "val\\n\\|literal";

        wal.logPut(key, value);

        List<Entry> entries = wal.readAll();
        assertEquals(1, entries.size());
        assertEquals(key, entries.get(0).key());
        assertEquals(value, entries.get(0).value());
    }

    @Test
    void compactRemovesOldEntries() throws IOException {
        wal.close();

        // Use a controllable clock: two instants separated by 1 second
        Instant t1 = Instant.parse("2000-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2000-01-01T00:00:01Z");
        AtomicReference<Instant> now = new AtomicReference<>(t1);
        Clock controlledClock = Clock.fixed(t1, ZoneOffset.UTC);

        // Write two entries at t1
        WriteAheadLog wal2 = new WriteAheadLog(tempDir.toString(),
                new Clock() {
                    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                    @Override public Clock withZone(java.time.ZoneId z) { return this; }
                    @Override public Instant instant() { return now.get(); }
                });
        wal2.logPut("a", "1");
        wal2.logPut("b", "2");

        // Advance clock to t2 and write one more entry
        now.set(t2);
        wal2.logPut("c", "3");

        // Compact: discard anything at or before t1
        wal2.compact(t1);

        List<Entry> remaining = wal2.readAll();
        assertEquals(1, remaining.size());
        assertEquals("c", remaining.get(0).key());
        wal2.close();
    }

    @Test
    void walSurvivesReopenAfterAppend() throws IOException {
        wal.logPut("persist", "value");
        wal.close();

        // Reopen and verify
        WriteAheadLog wal2 = new WriteAheadLog(tempDir.toString());
        try {
            List<Entry> entries = wal2.readAll();
            assertEquals(1, entries.size());
            assertEquals("persist", entries.get(0).key());
        } finally {
            wal2.close();
        }
    }
}
