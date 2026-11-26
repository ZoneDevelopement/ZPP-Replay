package dev.nandi0813.zppreplay.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nandi0813.zppreplay.storage.ReplayStore.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetentionTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void ownerLimitsKeepTheNewestEntriesAndDeleteEmptyOnes() {
        Entry newest = entry("e1", List.of(A, B));
        Entry middle = entry("e2", List.of(A, B));
        Entry oldest = entry("e3", List.of(A));
        Entry foreign = entry("e4", List.of(C));

        Set<String> deleted = ReplayStore.retain(List.of(newest, middle, oldest, foreign), Map.of(A, 1, B, 2));

        assertEquals(Set.of("e3"), deleted);
        assertEquals(List.of(A, B), newest.owners());
        assertEquals(List.of(B), middle.owners());
        assertEquals(List.of(), oldest.owners());
        assertEquals(List.of(C), foreign.owners());
    }

    @Test
    void nothingIsDeletedWhileEveryOwnerIsWithinLimit() {
        Entry newest = entry("e1", List.of(A));
        Entry oldest = entry("e2", List.of(A));

        Set<String> deleted = ReplayStore.retain(List.of(newest, oldest), Map.of(A, 2));

        assertTrue(deleted.isEmpty());
        assertEquals(List.of(A), oldest.owners());
    }

    private static Entry entry(String id, List<UUID> owners) {
        return new Entry(id, "match", "ladder", "DUEL", "arena", 0L, 0, List.of(), List.of(), new ArrayList<>(owners));
    }
}
