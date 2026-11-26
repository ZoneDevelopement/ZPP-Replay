package dev.nandi0813.zppreplay.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class ReplayReader {

    public record Replay(ReplayWriter.Header header, List<Rec> records) {
    }

    /** One body record. Positions are relative to the arena bounds minimum corner. */
    public sealed interface Rec {
        record Tick(int delta) implements Rec {
        }

        record Pos(int actor, float x, float y, float z, byte yaw, byte pitch, byte headYaw) implements Rec {
        }

        record State(int actor, byte flags, byte pose) implements Rec {
        }

        record Swing(int actor, byte hand) implements Rec {
        }

        record Hurt(int actor) implements Rec {
        }

        record Equip(int actor, byte slot, byte[] item) implements Rec {
        }

        /** Same item as the slot's previous EQUIP or EQUIP_PATCH; damage 0 = no DAMAGE component, else damage + 1. */
        record EquipPatch(int actor, byte slot, int amount, int damage) implements Rec {
        }

        record Visible(int actor, boolean visible) implements Rec {
        }

        record Palette(int index, String blockData) implements Rec {
        }

        record Block(short x, short y, short z, int palette) implements Rec {
        }

        record EntitySpawn(int id, String entityType, float x, float y, float z, byte yaw, byte pitch, byte[] item)
                implements Rec {
        }

        record EntityMove(int id, float x, float y, float z, byte yaw, byte pitch) implements Rec {
        }

        record EntityRemove(int id) implements Rec {
        }

        record Effect(int kind, float x, float y, float z, int rgb) implements Rec {
        }

        record Round(int round) implements Rec {
        }

        record Actor(int index, String uuid, String name, boolean bot, String skinValue, String skinSignature)
                implements Rec {
        }
    }

    private ReplayReader() {
    }

    public static Replay read(byte[] file) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        if (in.readInt() != ReplayFormat.MAGIC || in.readShort() != ReplayFormat.VERSION) {
            throw new IllegalArgumentException("Unsupported replay file");
        }
        ReplayWriter.Header header = new ReplayWriter.Header(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(),
                in.readUTF(), in.readUTF(), in.readLong(), in.readInt(), in.readBoolean(), in.readDouble(),
                in.readDouble(), in.readDouble());
        byte[] compressed = new byte[in.readInt()];
        in.readFully(compressed);
        return new Replay(header, parse(header, inflate(compressed)));
    }

    private static List<Rec> parse(ReplayWriter.Header header, byte[] raw) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        List<Rec> records = new ArrayList<>();
        while (true) {
            byte type = in.readByte();
            Rec rec;
            switch (type) {
                case ReplayFormat.END:
                    return records;
                case ReplayFormat.TICK:
                    rec = new Rec.Tick(ReplayFormat.readVarInt(in));
                    break;
                case ReplayFormat.POS:
                    rec = new Rec.Pos(ReplayFormat.readVarInt(in), in.readFloat(), in.readFloat(), in.readFloat(),
                            in.readByte(), in.readByte(), in.readByte());
                    break;
                case ReplayFormat.STATE:
                    rec = new Rec.State(ReplayFormat.readVarInt(in), in.readByte(), in.readByte());
                    break;
                case ReplayFormat.SWING:
                    rec = new Rec.Swing(ReplayFormat.readVarInt(in), in.readByte());
                    break;
                case ReplayFormat.HURT:
                    rec = new Rec.Hurt(ReplayFormat.readVarInt(in));
                    break;
                case ReplayFormat.EQUIP:
                    rec = new Rec.Equip(ReplayFormat.readVarInt(in), in.readByte(), readBlob(in));
                    break;
                case ReplayFormat.EQUIP_PATCH:
                    rec = new Rec.EquipPatch(ReplayFormat.readVarInt(in), in.readByte(), ReplayFormat.readVarInt(in),
                            ReplayFormat.readVarInt(in));
                    break;
                case ReplayFormat.VISIBLE:
                    rec = new Rec.Visible(ReplayFormat.readVarInt(in), in.readBoolean());
                    break;
                case ReplayFormat.PALETTE:
                    rec = new Rec.Palette(ReplayFormat.readVarInt(in), in.readUTF());
                    break;
                case ReplayFormat.BLOCK:
                    rec = new Rec.Block(in.readShort(), in.readShort(), in.readShort(), ReplayFormat.readVarInt(in));
                    break;
                case ReplayFormat.ENTITY_SPAWN:
                    rec = new Rec.EntitySpawn(ReplayFormat.readVarInt(in), in.readUTF(), in.readFloat(), in.readFloat(),
                            in.readFloat(), in.readByte(), in.readByte(), readBlob(in));
                    break;
                case ReplayFormat.ENTITY_MOVE:
                    rec = new Rec.EntityMove(ReplayFormat.readVarInt(in), in.readFloat(), in.readFloat(), in.readFloat(),
                            in.readByte(), in.readByte());
                    break;
                case ReplayFormat.ENTITY_REMOVE:
                    rec = new Rec.EntityRemove(ReplayFormat.readVarInt(in));
                    break;
                case ReplayFormat.EFFECT:
                    rec = readEffect(in);
                    break;
                case ReplayFormat.ROUND:
                    rec = new Rec.Round(ReplayFormat.readVarInt(in));
                    break;
                case ReplayFormat.ACTOR:
                    rec = new Rec.Actor(ReplayFormat.readVarInt(in), in.readUTF(), in.readUTF(), in.readBoolean(),
                            in.readUTF(), in.readUTF());
                    break;
                default:
                    throw new IOException("Unknown replay record " + type + " in " + header.id());
            }
            records.add(rec);
        }
    }

    private static Rec readEffect(DataInputStream in) throws IOException {
        int kind = ReplayFormat.readVarInt(in);
        float x = in.readFloat();
        float y = in.readFloat();
        float z = in.readFloat();
        int rgb = kind == 0 ? in.readInt() : 0;
        return new Rec.Effect(kind, x, y, z, rgb);
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        byte[] data = new byte[ReplayFormat.readVarInt(in)];
        in.readFully(data);
        return data;
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IOException("Truncated replay body");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("Corrupt replay body", e);
        } finally {
            inflater.end();
        }
    }
}
