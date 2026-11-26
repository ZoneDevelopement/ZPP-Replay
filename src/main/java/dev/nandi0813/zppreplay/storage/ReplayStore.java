package dev.nandi0813.zppreplay.storage;

import dev.nandi0813.zppreplay.format.ReplayWriter;
import dev.nandi0813.zppreplay.record.RecordProfiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Replay files in {@code <dataFolder>/replays/} plus the {@code index.json} that lists them. Files and the index are
 * written on one writer thread; index reads and id generation are synchronized and do no IO.
 */
public final class ReplayStore {

    private static final String INDEX = "index.json";
    private static final String EXTENSION = ".zrp";
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int ID_LENGTH = 8;
    private static final long CLOSE_WAIT_SECONDS = 10;

    /** One replay in the index. The owners list is changed by retention. */
    public record Entry(String id, String matchId, String ladder, String type, String arena, long startedAt,
                        int durationTicks, List<UUID> participants, List<String> names, List<UUID> owners) {
    }

    private final Path folder;
    private final Path indexFile;
    private final Logger logger;
    private final ExecutorService writer;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> reserved = new HashSet<>();
    private final RecordProfiler profiler;

    public ReplayStore(Path dataFolder, Logger logger, RecordProfiler profiler) {
        this.folder = dataFolder.resolve("replays");
        this.indexFile = folder.resolve(INDEX);
        this.logger = logger;
        this.profiler = profiler;
        this.writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "zpp-replay-writer");
            thread.setDaemon(true);
            return thread;
        });
        load();
    }

    /** Synchronous, called once from onEnable. A missing index is empty; an unreadable one is replaced by an empty one. */
    private void load() {
        try {
            Files.createDirectories(folder);
            if (!Files.exists(indexFile)) return;
            JSONArray array = new JSONObject(Files.readString(indexFile)).getJSONArray("replays");
            for (int i = 0; i < array.length(); i++) {
                Entry entry = fromJson(array.getJSONObject(i));
                entries.put(entry.id(), entry);
            }
        } catch (IOException | RuntimeException e) {
            entries.clear();
            logger.warning("Could not read the replay index, starting empty: " + e.getMessage());
        }
    }

    /** A new id of {@code [a-z0-9]{8}} that is neither saved nor handed out before. Main thread safe. */
    public synchronized String newId() {
        String id;
        do {
            id = randomId();
        } while (entries.containsKey(id) || !reserved.add(id));
        return id;
    }

    /** Queues the write on the writer thread: file, index entry, retention, index. */
    public void save(ReplayWriter.Header header, byte[] rawBody, Entry entry, Map<UUID, Integer> ownerLimits) {
        writer.execute(() -> write(header, rawBody, entry, ownerLimits));
    }

    public synchronized Entry get(String id) {
        return entries.get(id);
    }

    /** Replays the player took part in, newest first. */
    public synchronized List<Entry> forPlayer(UUID player) {
        return entries.values().stream()
                .filter(entry -> entry.participants().contains(player))
                .sorted(Comparator.comparingLong(Entry::startedAt).reversed())
                .toList();
    }

    /** The replay file bytes, read on the writer thread. Fails for an unknown id. */
    public CompletableFuture<byte[]> read(String id) {
        return CompletableFuture.supplyAsync(() -> {
            if (get(id) == null) throw new NoSuchElementException("Unknown replay " + id);
            try {
                return Files.readAllBytes(folder.resolve(id + EXTENSION));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, writer);
    }

    /** Finishes queued writes, waiting up to 10 seconds. */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("Replay writes did not finish within " + CLOSE_WAIT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Removes {@code owner} from every entry beyond that owner's limit, counting from the newest entry. Changes the
     * owners lists in place.
     *
     * @return ids of the entries whose owners became empty; the caller deletes them
     */
    static Set<String> retain(List<Entry> newestFirst, Map<UUID, Integer> limits) {
        Map<UUID, Integer> seen = new LinkedHashMap<>();
        Set<String> emptied = new HashSet<>();
        for (Entry entry : newestFirst) {
            Iterator<UUID> owners = entry.owners().iterator();
            while (owners.hasNext()) {
                UUID owner = owners.next();
                Integer limit = limits.get(owner);
                if (limit == null) continue;
                int count = seen.merge(owner, 1, Integer::sum);
                if (count <= limit) continue;
                owners.remove();
                if (entry.owners().isEmpty()) emptied.add(entry.id());
            }
        }
        return emptied;
    }

    private void write(ReplayWriter.Header header, byte[] rawBody, Entry entry, Map<UUID, Integer> ownerLimits) {
        try {
            byte[] file = ReplayWriter.file(header, rawBody);
            long started = profiler.enabled() ? System.nanoTime() : 0L;
            writeAtomically(folder.resolve(entry.id() + EXTENSION), file);
            if (profiler.enabled()) profiler.saved(entry.id(), file.length, System.nanoTime() - started);
            List<String> deleted = new ArrayList<>();
            byte[] index;
            synchronized (this) {
                entries.put(entry.id(), entry);
                List<Entry> newestFirst = new ArrayList<>(entries.values());
                newestFirst.sort(Comparator.comparingLong(Entry::startedAt).reversed());
                for (String id : retain(newestFirst, ownerLimits)) {
                    entries.remove(id);
                    deleted.add(id);
                }
                index = indexBytes();
            }
            for (String id : deleted) Files.deleteIfExists(folder.resolve(id + EXTENSION));
            writeAtomically(indexFile, index);
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not save replay " + entry.id() + ": " + e.getMessage());
        }
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Called with the lock held. */
    private byte[] indexBytes() {
        JSONArray array = new JSONArray();
        for (Entry entry : entries.values()) array.put(toJson(entry));
        return new JSONObject().put("replays", array).toString(2).getBytes(StandardCharsets.UTF_8);
    }

    private String randomId() {
        char[] id = new char[ID_LENGTH];
        for (int i = 0; i < ID_LENGTH; i++) id[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(id);
    }

    private static JSONObject toJson(Entry entry) {
        return new JSONObject()
                .put("id", entry.id())
                .put("matchId", entry.matchId())
                .put("ladder", entry.ladder())
                .put("type", entry.type())
                .put("arena", entry.arena())
                .put("startedAt", entry.startedAt())
                .put("durationTicks", entry.durationTicks())
                .put("participants", uuidArray(entry.participants()))
                .put("names", new JSONArray(entry.names()))
                .put("owners", uuidArray(entry.owners()));
    }

    private static Entry fromJson(JSONObject json) {
        return new Entry(json.getString("id"), json.getString("matchId"), json.getString("ladder"),
                json.getString("type"), json.getString("arena"), json.getLong("startedAt"),
                json.getInt("durationTicks"), uuids(json.getJSONArray("participants")),
                strings(json.getJSONArray("names")), uuids(json.getJSONArray("owners")));
    }

    private static JSONArray uuidArray(List<UUID> uuids) {
        JSONArray array = new JSONArray();
        for (UUID uuid : uuids) array.put(uuid.toString());
        return array;
    }

    private static List<UUID> uuids(JSONArray array) {
        List<UUID> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(UUID.fromString(array.getString(i)));
        return result;
    }

    private static List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }
}
