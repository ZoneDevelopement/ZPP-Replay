package dev.nandi0813.zppreplay.format;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Constants and primitive helpers of the v2 replay file (see docs/replay/R4-recorder.md). */
public final class ReplayFormat {

    public static final int MAGIC = 0x5A525031;
    public static final short VERSION = 2;

    public static final byte END = 0;
    public static final byte TICK = 1;
    public static final byte POS = 2;
    public static final byte STATE = 3;
    public static final byte SWING = 4;
    public static final byte HURT = 5;
    public static final byte EQUIP = 6;
    public static final byte VISIBLE = 7;
    public static final byte PALETTE = 8;
    public static final byte BLOCK = 9;
    public static final byte ENTITY_SPAWN = 10;
    public static final byte ENTITY_MOVE = 11;
    public static final byte ENTITY_REMOVE = 12;
    public static final byte EFFECT = 13;
    public static final byte ROUND = 14;
    public static final byte ACTOR = 15;
    public static final byte EQUIP_PATCH = 16;

    private ReplayFormat() {
    }

    /** One byte per 1/256 of a turn. */
    public static byte angle(float degrees) {
        return (byte) Math.floorMod(Math.round(degrees * 256f / 360f), 256);
    }

    /** Returns the angle in degrees, in (-180, 180]. */
    public static float angle(byte value) {
        float degrees = (value & 0xFF) * 360f / 256f;
        return degrees > 180f ? degrees - 360f : degrees;
    }

    /** Unsigned LEB128. */
    public static void writeVarInt(DataOutput out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int readVarInt(DataInput in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("Malformed varint");
    }
}
