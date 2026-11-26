package dev.nandi0813.zppreplay.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ReplayFormatTest {

    @Test
    void varintRoundTrips() throws IOException {
        for (int value : new int[] {0, 127, 128, 16383, 16384, Integer.MAX_VALUE}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ReplayFormat.writeVarInt(new DataOutputStream(bytes), value);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(value, ReplayFormat.readVarInt(in));
            assertEquals(0, in.available());
        }
    }

    @Test
    void angleRoundTripsWithinOneAndHalfDegrees() {
        for (float degrees = -180f; degrees <= 180f; degrees += 0.5f) {
            float back = ReplayFormat.angle(ReplayFormat.angle(degrees));
            float difference = Math.abs(back - degrees);
            difference = Math.min(difference, 360f - difference);
            assertTrue(difference <= 1.5f, "angle " + degrees + " came back as " + back);
        }
    }
}
