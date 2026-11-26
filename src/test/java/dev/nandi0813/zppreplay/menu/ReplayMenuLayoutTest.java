package dev.nandi0813.zppreplay.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ReplayMenuLayoutTest {

    @Test
    void entriesFillTheInnerSevenByFourArea() {
        assertEquals(10, ReplayMenu.slot(0));
        assertEquals(16, ReplayMenu.slot(6));
        assertEquals(19, ReplayMenu.slot(7));
        assertEquals(43, ReplayMenu.slot(27));
    }

    @Test
    void indexIsTheInverseOfSlot() {
        for (int i = 0; i < 28; i++) {
            assertEquals(i, ReplayMenu.index(ReplayMenu.slot(i)), "index " + i);
        }
    }

    @Test
    void borderAndBottomBarAreNotEntries() {
        assertEquals(-1, ReplayMenu.index(0));
        assertEquals(-1, ReplayMenu.index(9));
        assertEquals(-1, ReplayMenu.index(17));
        assertEquals(-1, ReplayMenu.index(47));
        assertEquals(-1, ReplayMenu.index(49));
    }

    @Test
    void pagesHoldTwentyEightEntries() {
        assertEquals(1, ReplayMenu.pages(0));
        assertEquals(1, ReplayMenu.pages(28));
        assertEquals(2, ReplayMenu.pages(29));
    }
}
