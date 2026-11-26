package dev.nandi0813.zppreplay.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class RecordProfilerTest {

    private static final String PROPERTY = "zpp.replay.profile";

    @Test
    void disabledOnReleaseEvenWithTheProperty() {
        System.setProperty(PROPERTY, "true");
        try {
            assertFalse(RecordProfiler.create(Logger.getAnonymousLogger(), true).enabled());
        } finally {
            System.clearProperty(PROPERTY);
        }
    }

    @Test
    void enabledOnDevelopmentOnlyWithTheProperty() {
        System.setProperty(PROPERTY, "true");
        try {
            assertTrue(RecordProfiler.create(Logger.getAnonymousLogger(), false).enabled());
        } finally {
            System.clearProperty(PROPERTY);
        }
        assertFalse(RecordProfiler.create(Logger.getAnonymousLogger(), false).enabled());
    }

    @Test
    void nearestRankPercentile() {
        long[] hundred = new long[100];
        long[] twoHundred = new long[200];
        for (int i = 0; i < 200; i++) {
            if (i < 100) hundred[i] = i + 1;
            twoHundred[i] = i + 1;
        }
        assertEquals(99, RecordProfiler.percentile(hundred, 99));
        assertEquals(198, RecordProfiler.percentile(twoHundred, 99));
        assertEquals(100, RecordProfiler.percentile(hundred, 100));
        assertEquals(1, RecordProfiler.percentile(hundred, 1));
    }
}
