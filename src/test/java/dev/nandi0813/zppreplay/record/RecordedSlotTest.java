package dev.nandi0813.zppreplay.record;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RecordedSlotTest {

    @Test
    void heldHotbarSlotIsMainHandAndOtherHotbarSlotsAreNotRecorded() {
        assertEquals(0, Recording.recordedSlot(3, 3));
        assertEquals(-1, Recording.recordedSlot(3, 4));
        assertEquals(-1, Recording.recordedSlot(3, 20));
    }

    @Test
    void armorAndOffHandIndexesMapToRecordedSlots() {
        assertEquals(1, Recording.recordedSlot(0, 40));
        assertEquals(2, Recording.recordedSlot(0, 36));
        assertEquals(3, Recording.recordedSlot(0, 37));
        assertEquals(4, Recording.recordedSlot(0, 38));
        assertEquals(5, Recording.recordedSlot(0, 39));
        assertEquals(-1, Recording.recordedSlot(0, 41));
    }
}
