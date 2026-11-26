package dev.nandi0813.zppreplay.format;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;

/** Writes the body records of one replay in memory; {@link #file} adds the header and deflates the body. */
public final class ReplayWriter {

    public record Header(String id, String matchId, String ladder, String matchType, String originalArena, String arena,
                         long startedAtMillis, int durationTicks, boolean build, double sizeX, double sizeY, double sizeZ) {
    }

    private static final byte[] NO_BYTES = new byte[0];

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    public void writeTick(int delta) throws IOException {
        out.writeByte(ReplayFormat.TICK);
        ReplayFormat.writeVarInt(out, delta);
    }

    public void writePos(int actor, float x, float y, float z, byte yaw, byte pitch, byte headYaw) throws IOException {
        out.writeByte(ReplayFormat.POS);
        ReplayFormat.writeVarInt(out, actor);
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeFloat(z);
        out.writeByte(yaw);
        out.writeByte(pitch);
        out.writeByte(headYaw);
    }

    public void writeState(int actor, byte flags, byte pose) throws IOException {
        out.writeByte(ReplayFormat.STATE);
        ReplayFormat.writeVarInt(out, actor);
        out.writeByte(flags);
        out.writeByte(pose);
    }

    public void writeSwing(int actor, byte hand) throws IOException {
        out.writeByte(ReplayFormat.SWING);
        ReplayFormat.writeVarInt(out, actor);
        out.writeByte(hand);
    }

    public void writeHurt(int actor) throws IOException {
        out.writeByte(ReplayFormat.HURT);
        ReplayFormat.writeVarInt(out, actor);
    }

    /** A null or empty item is written as length 0. */
    public void writeEquip(int actor, byte slot, byte[] item) throws IOException {
        out.writeByte(ReplayFormat.EQUIP);
        ReplayFormat.writeVarInt(out, actor);
        out.writeByte(slot);
        writeBlob(item);
    }

    /** Same item as the slot's last EQUIP, with a new amount and damage; damage 0 = no DAMAGE component, else damage + 1. */
    public void writeEquipPatch(int actor, byte slot, int amount, int damage) throws IOException {
        out.writeByte(ReplayFormat.EQUIP_PATCH);
        ReplayFormat.writeVarInt(out, actor);
        out.writeByte(slot);
        ReplayFormat.writeVarInt(out, amount);
        ReplayFormat.writeVarInt(out, damage);
    }

    public void writeVisible(int actor, boolean visible) throws IOException {
        out.writeByte(ReplayFormat.VISIBLE);
        ReplayFormat.writeVarInt(out, actor);
        out.writeBoolean(visible);
    }

    public void writePalette(int index, String blockData) throws IOException {
        out.writeByte(ReplayFormat.PALETTE);
        ReplayFormat.writeVarInt(out, index);
        out.writeUTF(blockData);
    }

    public void writeBlock(short x, short y, short z, int palette) throws IOException {
        out.writeByte(ReplayFormat.BLOCK);
        out.writeShort(x);
        out.writeShort(y);
        out.writeShort(z);
        ReplayFormat.writeVarInt(out, palette);
    }

    /** Item bytes are only set for items and thrown potions; other entities pass null. */
    public void writeEntitySpawn(int id, String entityType, float x, float y, float z, byte yaw, byte pitch, byte[] item)
            throws IOException {
        out.writeByte(ReplayFormat.ENTITY_SPAWN);
        ReplayFormat.writeVarInt(out, id);
        out.writeUTF(entityType);
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeFloat(z);
        out.writeByte(yaw);
        out.writeByte(pitch);
        writeBlob(item);
    }

    public void writeEntityMove(int id, float x, float y, float z, byte yaw, byte pitch) throws IOException {
        out.writeByte(ReplayFormat.ENTITY_MOVE);
        ReplayFormat.writeVarInt(out, id);
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeFloat(z);
        out.writeByte(yaw);
        out.writeByte(pitch);
    }

    public void writeEntityRemove(int id) throws IOException {
        out.writeByte(ReplayFormat.ENTITY_REMOVE);
        ReplayFormat.writeVarInt(out, id);
    }

    /** The color is written only for kind 0 (potion splash). */
    public void writeEffect(int kind, float x, float y, float z, int rgb) throws IOException {
        out.writeByte(ReplayFormat.EFFECT);
        ReplayFormat.writeVarInt(out, kind);
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeFloat(z);
        if (kind == 0) out.writeInt(rgb);
    }

    public void writeRound(int round) throws IOException {
        out.writeByte(ReplayFormat.ROUND);
        ReplayFormat.writeVarInt(out, round);
    }

    public void writeActor(int index, String uuid, String name, boolean bot, String skinValue, String skinSignature)
            throws IOException {
        out.writeByte(ReplayFormat.ACTOR);
        ReplayFormat.writeVarInt(out, index);
        out.writeUTF(uuid);
        out.writeUTF(name);
        out.writeBoolean(bot);
        out.writeUTF(skinValue);
        out.writeUTF(skinSignature);
    }

    public void writeEnd() throws IOException {
        out.writeByte(ReplayFormat.END);
        out.flush();
    }

    /** The raw, uncompressed body. */
    public byte[] body() {
        return bytes.toByteArray();
    }

    public int size() {
        return bytes.size();
    }

    /** Header, then the deflated body. */
    public static byte[] file(Header header, byte[] rawBody) throws IOException {
        byte[] body = deflate(rawBody);
        ByteArrayOutputStream file = new ByteArrayOutputStream(body.length + 128);
        DataOutputStream out = new DataOutputStream(file);
        out.writeInt(ReplayFormat.MAGIC);
        out.writeShort(ReplayFormat.VERSION);
        out.writeUTF(header.id());
        out.writeUTF(header.matchId());
        out.writeUTF(header.ladder());
        out.writeUTF(header.matchType());
        out.writeUTF(header.originalArena());
        out.writeUTF(header.arena());
        out.writeLong(header.startedAtMillis());
        out.writeInt(header.durationTicks());
        out.writeBoolean(header.build());
        out.writeDouble(header.sizeX());
        out.writeDouble(header.sizeY());
        out.writeDouble(header.sizeZ());
        out.writeInt(body.length);
        out.write(body);
        out.flush();
        return file.toByteArray();
    }

    private void writeBlob(byte[] data) throws IOException {
        byte[] value = data == null ? NO_BYTES : data;
        ReplayFormat.writeVarInt(out, value.length);
        out.write(value);
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
