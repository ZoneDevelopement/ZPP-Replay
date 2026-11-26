package dev.nandi0813.zppreplay.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nandi0813.zppreplay.format.ReplayReader;
import dev.nandi0813.zppreplay.format.ReplayWriter;
import dev.nandi0813.zppreplay.record.RecordProfiler;
import dev.nandi0813.zppreplay.storage.ReplayStore.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayStoreTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    @Test
    void keepsTheNewestEntriesWithinTheLimitAndDeletesTheRest() throws Exception {
        ReplayStore store = new ReplayStore(dir, logger(new Captured()), RecordProfiler.create(Logger.getAnonymousLogger(), true));
        String[] ids = new String[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = store.newId();
            save(store, ids[i], i + 1);
        }

        // Writes run in order on the single writer thread, so this read returns after the three saves and retention.
        byte[] kept = store.read(ids[2]).get();

        assertNull(store.get(ids[0]));
        assertNotNull(store.get(ids[1]));
        assertNotNull(store.get(ids[2]));
        JSONObject index = new JSONObject(Files.readString(dir.resolve("replays/index.json")));
        assertEquals(2, index.getJSONArray("replays").length());
        assertFalse(Files.exists(dir.resolve("replays/" + ids[0] + ".zrp")));
        assertEquals("match-3", ReplayReader.read(kept).header().matchId());
        store.close();
    }

    @Test
    void playerSeesOwnReplaysNewestFirstAndStrangersSeeNothing() throws Exception {
        ReplayStore store = new ReplayStore(dir, logger(new Captured()), RecordProfiler.create(Logger.getAnonymousLogger(), true));
        String[] ids = new String[2];
        for (int i = 0; i < 2; i++) {
            ids[i] = store.newId();
            save(store, ids[i], i + 1);
        }

        store.read(ids[1]).get(); // waits for both saves

        List<Entry> mine = store.forPlayer(OWNER);
        assertEquals(List.of("match-2", "match-1"), mine.stream().map(Entry::matchId).toList());
        assertEquals(List.of(), store.forPlayer(STRANGER));
        store.close();
    }

    @Test
    void corruptIndexLoadsAsEmptyWithAWarning() throws Exception {
        Files.createDirectories(dir.resolve("replays"));
        Files.writeString(dir.resolve("replays/index.json"), "{not json");
        Captured captured = new Captured();

        ReplayStore store = new ReplayStore(dir, logger(captured), RecordProfiler.create(Logger.getAnonymousLogger(), true));

        assertEquals(List.of(), store.forPlayer(OWNER));
        assertTrue(captured.records.stream().anyMatch(record -> record.getLevel() == Level.WARNING));
        store.close();
    }

    /** One entry owned and participated in by {@link #OWNER}; the owner limit is 2. */
    private static void save(ReplayStore store, String id, int number) throws IOException {
        ReplayWriter writer = new ReplayWriter();
        writer.writeRound(number);
        writer.writeEnd();
        ReplayWriter.Header header = new ReplayWriter.Header(id, "match-" + number, "NoDebuff", "DUEL", "Original",
                "Arena copy", 1_000L * number, 10, false, 1, 1, 1);
        Entry entry = new Entry(id, "match-" + number, "NoDebuff", "DUEL", "Arena copy", 1_000L * number, 10,
                List.of(OWNER), List.of("Steve"), new ArrayList<>(List.of(OWNER)));
        store.save(header, writer.body(), entry, Map.of(OWNER, 2));
    }

    private static Logger logger(Captured captured) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(captured);
        return logger;
    }

    private static final class Captured extends Handler {

        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
