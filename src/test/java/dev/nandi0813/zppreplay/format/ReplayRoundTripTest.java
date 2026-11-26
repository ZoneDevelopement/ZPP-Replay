package dev.nandi0813.zppreplay.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nandi0813.zppreplay.format.ReplayReader.Rec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayRoundTripTest {

    private static final ReplayWriter.Header HEADER = new ReplayWriter.Header("abcd1234", "match-1", "NoDebuff", "DUEL",
            "Original", "Arena copy", 1_700_000_000_000L, 1234, true, 10.5, 20.0, 30.25);
    private static final String UUID_A = "11111111-2222-3333-4444-555555555555";
    private static final String UUID_B = "66666666-7777-8888-9999-000000000000";

    @Test
    void everyRecordTypeRoundTrips() throws IOException {
        ReplayWriter writer = new ReplayWriter();
        List<Rec> expected = new ArrayList<>();

        writer.writeActor(0, UUID_A, "Steve", false, "skin", "signature");
        expected.add(new Rec.Actor(0, UUID_A, "Steve", false, "skin", "signature"));
        writer.writeActor(1, UUID_B, "bot_1", true, "", "");
        expected.add(new Rec.Actor(1, UUID_B, "bot_1", true, "", ""));
        writer.writeRound(1);
        expected.add(new Rec.Round(1));
        writer.writeTick(16384);
        expected.add(new Rec.Tick(16384));
        writer.writePos(0, 1.5f, -2.25f, 300f, Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 0);
        expected.add(new Rec.Pos(0, 1.5f, -2.25f, 300f, Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 0));
        writer.writeState(1, (byte) 0xFF, (byte) 5);
        expected.add(new Rec.State(1, (byte) 0xFF, (byte) 5));
        writer.writeSwing(0, (byte) 1);
        expected.add(new Rec.Swing(0, (byte) 1));
        writer.writeHurt(1);
        expected.add(new Rec.Hurt(1));
        writer.writeEquip(0, (byte) 0, new byte[] {1, 2, 3});
        expected.add(new Rec.Equip(0, (byte) 0, new byte[] {1, 2, 3}));
        writer.writeEquip(0, (byte) 5, null);
        expected.add(new Rec.Equip(0, (byte) 5, new byte[0]));
        writer.writeEquipPatch(0, (byte) 2, 64, 0);
        expected.add(new Rec.EquipPatch(0, (byte) 2, 64, 0));
        writer.writeEquipPatch(1, (byte) 4, 1, 301);
        expected.add(new Rec.EquipPatch(1, (byte) 4, 1, 301));
        writer.writeVisible(1, false);
        expected.add(new Rec.Visible(1, false));
        writer.writePalette(0, "minecraft:stone[waterlogged=false]");
        expected.add(new Rec.Palette(0, "minecraft:stone[waterlogged=false]"));
        writer.writeBlock((short) -3, (short) 32767, (short) -32768, 0);
        expected.add(new Rec.Block((short) -3, (short) 32767, (short) -32768, 0));
        writer.writeEntitySpawn(0, "minecraft:item", 0.5f, 1f, 2f, (byte) 10, (byte) -10, new byte[] {9});
        expected.add(new Rec.EntitySpawn(0, "minecraft:item", 0.5f, 1f, 2f, (byte) 10, (byte) -10, new byte[] {9}));
        writer.writeEntityMove(0, -1f, -1f, -1f, (byte) 0, (byte) 0);
        expected.add(new Rec.EntityMove(0, -1f, -1f, -1f, (byte) 0, (byte) 0));
        writer.writeEntityRemove(0);
        expected.add(new Rec.EntityRemove(0));
        writer.writeEffect(0, 1f, 2f, 3f, 0x385DC6);
        expected.add(new Rec.Effect(0, 1f, 2f, 3f, 0x385DC6));
        writer.writeEffect(1, 4f, 5f, 6f, 0);
        expected.add(new Rec.Effect(1, 4f, 5f, 6f, 0));
        writer.writeEnd();

        ReplayReader.Replay replay = ReplayReader.read(ReplayWriter.file(HEADER, writer.body()));

        assertEquals(HEADER, replay.header());
        assertEquals(expected.size(), replay.records().size());
        for (int i = 0; i < expected.size(); i++) {
            assertSameRecord(expected.get(i), replay.records().get(i));
        }
    }

    @Test
    void wrongMagicIsRejected() throws IOException {
        byte[] file = ReplayWriter.file(HEADER, new ReplayWriter().body());
        file[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ReplayReader.read(file));
    }

    @Test
    void wrongVersionIsRejected() throws IOException {
        byte[] file = ReplayWriter.file(HEADER, new ReplayWriter().body());
        file[5] = 3;
        assertThrows(IllegalArgumentException.class, () -> ReplayReader.read(file));
    }

    @Test
    void version1IsRejected() throws IOException {
        byte[] file = ReplayWriter.file(HEADER, new ReplayWriter().body());
        file[5] = 1;
        assertThrows(IllegalArgumentException.class, () -> ReplayReader.read(file));
    }

    /** Arrays inside records compare by reference, so they are checked by content here. */
    private static void assertSameRecord(Rec expected, Rec actual) {
        if (expected instanceof Rec.Equip e && actual instanceof Rec.Equip a) {
            assertEquals(e.actor(), a.actor());
            assertEquals(e.slot(), a.slot());
            assertArrayEquals(e.item(), a.item());
        } else if (expected instanceof Rec.EntitySpawn e && actual instanceof Rec.EntitySpawn a) {
            assertEquals(e.id(), a.id());
            assertEquals(e.entityType(), a.entityType());
            assertArrayEquals(e.item(), a.item());
        } else {
            assertEquals(expected, actual);
        }
    }
}
