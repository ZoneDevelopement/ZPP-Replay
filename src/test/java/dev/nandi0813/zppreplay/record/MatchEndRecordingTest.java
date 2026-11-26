package dev.nandi0813.zppreplay.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nandi0813.api.Enum.MatchType;
import dev.nandi0813.api.Event.Match.BotMatchEndEvent;
import dev.nandi0813.api.Event.Match.MatchEndEvent;
import dev.nandi0813.api.Interface.Match;
import dev.nandi0813.zppreplay.format.ReplayReader;
import dev.nandi0813.zppreplay.storage.ReplayStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatchEndRecordingTest {

    @TempDir Path temp;

    @Test
    void botCompletionSavesReadableBotReplayAtEventTickAndRemovesRecording() throws Exception {
        assertCompletion(new BotMatchEndEvent(match("bot", MatchType.BOT), BotMatchEndEvent.Outcome.HUMAN_WIN, 1, 0, 0), "BOT");
    }

    @Test
    void regularCompletionSavesDuelReplay() throws Exception {
        assertCompletion(new MatchEndEvent(match("duel", MatchType.DUEL)), "DUEL");
    }

    @Test
    void repeatedOrBothCompletionEventsSaveOnlyOnce() throws Exception {
        Match match = match("once", MatchType.BOT);
        withServerTick(42, () -> {
            ReplayStore store = new ReplayStore(temp, Logger.getAnonymousLogger(), RecordProfiler.create(Logger.getAnonymousLogger(), true));
            try {
                RecordingManager manager = manager(store);
                seed(manager, match);
                dispatch(manager, new BotMatchEndEvent(match, BotMatchEndEvent.Outcome.BOT_WIN, 0, 1, 0));
                dispatch(manager, new MatchEndEvent(match));
                dispatch(manager, new BotMatchEndEvent(match, BotMatchEndEvent.Outcome.DRAW, 0, 0, 1));
                assertTrue(recordings(manager).isEmpty());
            } finally {
                store.close();
            }
        });
        assertEquals(1, replayFiles().size());
    }

    private void assertCompletion(org.bukkit.event.Event event, String expectedType) throws Exception {
        Match match = event instanceof MatchEndEvent ? ((MatchEndEvent) event).getMatch() : ((BotMatchEndEvent) event).getMatch();
        withServerTick(42, () -> {
            ReplayStore store = new ReplayStore(temp, Logger.getAnonymousLogger(), RecordProfiler.create(Logger.getAnonymousLogger(), true));
            try {
                RecordingManager manager = manager(store);
                seed(manager, match);
                dispatch(manager, event);
                assertTrue(recordings(manager).isEmpty());
            } finally {
                store.close();
            }
        });
        List<Path> files = replayFiles();
        assertEquals(1, files.size());
        ReplayReader.Replay replay = ReplayReader.read(Files.readAllBytes(files.getFirst()));
        assertEquals(expectedType, replay.header().matchType());
        assertEquals(32, replay.header().durationTicks());
    }

    private RecordingManager manager(ReplayStore store) {
        return new RecordingManager(null, null, store, RecordProfiler.create(Logger.getAnonymousLogger(), true));
    }

    @SuppressWarnings("unchecked")
    private static Map<Match, Recording> recordings(RecordingManager manager) throws Exception {
        Field field = RecordingManager.class.getDeclaredField("recordings");
        field.setAccessible(true);
        return (Map<Match, Recording>) field.get(manager);
    }

    private static void seed(RecordingManager manager, Match match) throws Exception {
        recordings(manager).put(match, new Recording(match, 10, 1000, Map.of(UUID.randomUUID(), 1)));
    }

    private static void dispatch(RecordingManager manager, org.bukkit.event.Event event) throws Exception {
        var method = java.util.Arrays.stream(RecordingManager.class.getMethods())
                .filter(candidate -> candidate.isAnnotationPresent(EventHandler.class))
                .filter(candidate -> candidate.getParameterCount() == 1 && candidate.getParameterTypes()[0] == event.getClass())
                .findFirst().orElseThrow(() -> new AssertionError("No registered listener for " + event.getClass().getSimpleName()));
        method.invoke(manager, event);
    }

    private List<Path> replayFiles() throws Exception {
        Path folder = temp.resolve("replays");
        try (var paths = Files.list(folder)) {
            return paths.filter(path -> path.toString().endsWith(".zrp")).toList();
        }
    }

    private static Match match(String id, MatchType type) {
        World world = proxy(World.class, (method, args) -> defaultValue(method.getReturnType()));
        return proxy(Match.class, (method, args) -> switch (method.getName()) {
            case "getArenaWorld" -> world;
            case "getArenaBounds" -> new BoundingBox(0, 0, 0, 10, 10, 10);
            case "isBuildArena" -> true;
            case "getMatchId" -> id;
            case "getLadderName" -> "classic";
            case "getMatchType" -> type;
            case "getOriginalArenaName" -> "arena";
            case "getArenaName" -> "arena";
            case "getPlayers", "getBotBodies", "getSpectators" -> List.of();
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> type.getSimpleName() + " proxy";
                default -> null;
            };
            return invocation.call(method, args);
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }

    private static void withServerTick(int tick, ThrowingRunnable action) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        Object previous = field.get(null);
        Server server = proxy(Server.class, (method, args) -> method.getName().equals("getCurrentTick") ? tick : defaultValue(method.getReturnType()));
        field.set(null, server);
        try {
            action.run();
        } finally {
            field.set(null, previous);
        }
    }

    @FunctionalInterface private interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    @FunctionalInterface private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
