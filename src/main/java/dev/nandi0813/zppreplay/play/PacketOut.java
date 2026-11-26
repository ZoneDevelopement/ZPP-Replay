package dev.nandi0813.zppreplay.play;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Packets for the fake bodies, sent to one viewer. Positions are world coordinates, angles are degrees. */
public final class PacketOut {

    /** Avatar skin layers; 0x7F turns every layer on. */
    private static final int SKIN_LAYERS_INDEX = 16; // Avatar.DATA_PLAYER_MODE_CUSTOMISATION since 1.21.9 (17 is the float absorption field)
    private static final byte SKIN_LAYERS_ALL = 0x7F;
    private static final int FLAGS_INDEX = 0;
    private static final int HAND_INDEX = 8;
    private static final int POSE_INDEX = 6;
    private static final int ITEM_INDEX = 8;

    private PacketOut() {
    }

    public static void spawnPlayer(Player viewer, int id, UUID uuid, String name, String skinValue, String skinSignature,
                                   double x, double y, double z, float yaw, float pitch) {
        List<TextureProperty> textures = skinValue.isEmpty() ? List.of()
                : List.of(new TextureProperty("textures", skinValue, skinSignature.isEmpty() ? null : skinSignature));
        PlayerInfo info = new PlayerInfo(new UserProfile(uuid, name, textures), false, 0, GameMode.SURVIVAL, null, null);
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), List.of(info)));
        send(viewer, new WrapperPlayServerSpawnEntity(id, uuid, EntityTypes.PLAYER, new Location(x, y, z, yaw, pitch), yaw, 0, null));
        send(viewer, new WrapperPlayServerEntityMetadata(id,
                List.of(new EntityData<>(SKIN_LAYERS_INDEX, EntityDataTypes.BYTE, SKIN_LAYERS_ALL))));
    }

    /** A dropped item or a thrown potion, with its item as metadata. Other entities pass a null item. */
    public static void spawnEntity(Player viewer, int id, UUID uuid, EntityType type, double x, double y, double z,
                                   float yaw, float pitch, org.bukkit.inventory.ItemStack item) {
        send(viewer, new WrapperPlayServerSpawnEntity(id, uuid, type, new Location(x, y, z, yaw, pitch), yaw, 0, null));
        if (item != null) {
            send(viewer, new WrapperPlayServerEntityMetadata(id,
                    List.of(new EntityData<>(ITEM_INDEX, EntityDataTypes.ITEMSTACK, toPacket(item)))));
        }
    }

    public static void move(Player viewer, int id, double x, double y, double z, float yaw, float pitch) {
        send(viewer, new WrapperPlayServerEntityTeleport(id, new Vector3d(x, y, z), yaw, pitch, false));
    }

    public static void headRotation(Player viewer, int id, float headYaw) {
        send(viewer, new WrapperPlayServerEntityHeadLook(id, headYaw));
    }

    /** Shared flags, living hand state and pose of a body. */
    public static void state(Player viewer, int id, byte flags, byte hand, EntityPose pose) {
        send(viewer, new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(FLAGS_INDEX, EntityDataTypes.BYTE, flags),
                new EntityData<>(HAND_INDEX, EntityDataTypes.BYTE, hand),
                new EntityData<>(POSE_INDEX, EntityDataTypes.ENTITY_POSE, pose))));
    }

    public static void equipment(Player viewer, int id, EquipmentSlot slot, org.bukkit.inventory.ItemStack item) {
        send(viewer, new WrapperPlayServerEntityEquipment(id, List.of(new Equipment(slot, toPacket(item)))));
    }

    /** {@code hand} 0 is the main hand, 1 the off hand. */
    public static void swing(Player viewer, int id, byte hand) {
        send(viewer, new WrapperPlayServerEntityAnimation(id,
                hand == 0 ? EntityAnimationType.SWING_MAIN_ARM : EntityAnimationType.SWING_OFF_HAND));
    }

    public static void hurt(Player viewer, int id, float yaw) {
        send(viewer, new WrapperPlayServerHurtAnimation(id, yaw));
    }

    public static void destroy(Player viewer, int... ids) {
        send(viewer, new WrapperPlayServerDestroyEntities(ids));
    }

    public static void removeInfo(Player viewer, UUID... ids) {
        send(viewer, new WrapperPlayServerPlayerInfoRemove(ids));
    }

    private static ItemStack toPacket(org.bukkit.inventory.ItemStack item) {
        return item == null || item.getType().isAir() ? ItemStack.EMPTY : SpigotConversionUtil.fromBukkitItemStack(item);
    }

    private static void send(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }
}
