package dev.nandi0813.zppreplay.record;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    void picksTheHighestValidLimit() {
        assertEquals(12, RecordingManager.limitFor(
                List.of("zppreplay.limit.5", "zppreplay.limit.12", "zppreplay.limit.3"), 10));
    }

    @Test
    void ignoresInvalidAndOutOfRangeLimits() {
        assertEquals(10, RecordingManager.limitFor(List.of("zppreplay.limit.0", "zppreplay.limit.1001",
                "zppreplay.limit.abc", "zppreplay.limit.", "zppreplay.limit.-5", "zppreplay.limit.99999",
                "zppreplay.record"), 10));
    }

    @Test
    void acceptsTheEdges() {
        assertEquals(1000, RecordingManager.limitFor(List.of("zppreplay.limit.1000"), 10));
        assertEquals(1, RecordingManager.limitFor(List.of("zppreplay.limit.1"), 10));
    }

    @Test
    void fallsBackWithoutLimitPermissions() {
        assertEquals(7, RecordingManager.limitFor(List.of(), 7));
    }
}
