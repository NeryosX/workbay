package com.neryos.workbay.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.neryos.workbay.bus.BusConfig;
import net.minecraft.core.Direction;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which of a hosted machine's faces a link may use, per resource type. SPEC.md §4's isometric cube.
 *
 * <p>Three faces of state per resource, six faces each, three values each - packed two bits per
 * face into one int per resource rather than a map, because this rides in every snapshot the screen
 * receives and a map of maps would be most of the packet.
 *
 * <p><b>All-{@code NONE} means "any face", not "no face".</b> That is what an unconfigured bay
 * looks like, and it has to keep working exactly as it did before anyone opened the screen -
 * a machine that stops moving items because the player looked at it is the worst outcome here.
 */
public record FaceConfig(int items, int fluids, int energy, int chemicals) {

    public static final FaceConfig NONE = new FaceConfig(0, 0, 0, 0);

    public static final Codec<FaceConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.optionalFieldOf("Items", 0).forGetter(FaceConfig::items),
        Codec.INT.optionalFieldOf("Fluids", 0).forGetter(FaceConfig::fluids),
        Codec.INT.optionalFieldOf("Energy", 0).forGetter(FaceConfig::energy),
        // Optional and defaulting to zero, which is "any face" -- so a bay saved before chemicals
        // had a row reads back as one that was never configured, which is what it was.
        Codec.INT.optionalFieldOf("Chemicals", 0).forGetter(FaceConfig::chemicals)
    ).apply(i, FaceConfig::new));

    /** What one face does for one resource. Grey, green, blue on the cube, in this order. */
    public enum Role {
        NONE, INPUT, OUTPUT;

        /** One step round the ring. Back is what a right-click asks for. */
        public Role step(boolean back) {
            return values()[Math.floorMod(ordinal() + (back ? -1 : 1), values().length)];
        }
    }

    public Role role(BusConfig.Resource resource, Direction face) {
        return Role.values()[(packed(resource) >> (face.ordinal() * 2)) & 0b11];
    }

    public FaceConfig cycled(BusConfig.Resource resource, Direction face, boolean back) {
        int shift = face.ordinal() * 2;
        int updated = (packed(resource) & ~(0b11 << shift))
            | (role(resource, face).step(back).ordinal() << shift);
        return switch (resource) {
            case ITEM -> new FaceConfig(updated, fluids, energy, chemicals);
            case FLUID -> new FaceConfig(items, updated, energy, chemicals);
            case ENERGY -> new FaceConfig(items, fluids, updated, chemicals);
            case CHEMICAL -> new FaceConfig(items, fluids, energy, updated);
        };
    }

    /**
     * The faces a link may bind to, given what it is doing at the machine end.
     *
     * @param takingOut true when the link pulls out of the hosted machine, so it wants OUTPUT faces
     * @return every direction when nothing is configured - see the class note
     */
    public Set<Direction> usable(BusConfig.Resource resource, boolean takingOut) {
        if (packed(resource) == 0) {
            return EnumSet.allOf(Direction.class);
        }
        Role wanted = takingOut ? Role.OUTPUT : Role.INPUT;
        Role opposite = takingOut ? Role.INPUT : Role.OUTPUT;
        EnumSet<Direction> allowed = EnumSet.noneOf(Direction.class);
        EnumSet<Direction> notDenied = EnumSet.noneOf(Direction.class);
        for (Direction face : Direction.values()) {
            Role role = role(resource, face);
            if (role == wanted) {
                allowed.add(face);
            }
            if (role != opposite) {
                notDenied.add(face);
            }
        }
        // <b>Setting a face for one direction of travel must not close the other one.</b> This
        // returned the empty set whenever anything at all was configured and nothing carried the
        // role being asked for -- so marking the top face "in", which is the first and most
        // obvious thing anybody does, silently made the machine impossible to take anything out
        // of, and the row said "Idle. Nothing to move." Found in a live world: a furnace fed
        // through its top face would not give its cooked output back, because "out" had never
        // been set and every face had therefore been closed to it. OPEN_ISSUES #83.
        //
        // A face the player marked for the <em>opposite</em> role is still refused -- that is a
        // deliberate instruction and the whole point of the cube. What is no longer refused is a
        // face nobody has said anything about.
        return allowed.isEmpty() ? notDenied : allowed;
    }

    /**
     * The whole config in 48 bits - twelve per resource, two per face. Lets copy-and-paste ride the
     * one action packet every other button uses instead of earning a payload type of its own.
     *
     * <p>Thirty-six until chemicals got a row of their own. A long has sixty-four, so the fourth
     * resource cost nothing and a fifth would still fit.
     */
    public long bits() {
        return (items & 0xFFFL) | ((fluids & 0xFFFL) << 12) | ((energy & 0xFFFL) << 24)
            | ((chemicals & 0xFFFL) << 36);
    }

    public static FaceConfig fromBits(long bits) {
        return new FaceConfig((int) (bits & 0xFFF), (int) ((bits >> 12) & 0xFFF),
            (int) ((bits >> 24) & 0xFFF), (int) ((bits >> 36) & 0xFFF));
    }

    private int packed(BusConfig.Resource resource) {
        return switch (resource) {
            case ITEM -> items;
            case FLUID -> fluids;
            case ENERGY -> energy;
            // No face config for chemicals, and deliberately none: zero is "any face", and which
            // face a Mekanism machine offers gas on is its own side config's answer, not ours.
            // <b>A row of its own now.</b> It was zero -- "any face" -- on the argument that which
            // face a Mekanism machine offers gas on is its own side config's answer and not ours.
            // The same is true of its items and its fluids, and those have had a row since the
            // cube existed: the row is not us second-guessing the machine, it is the player saying
            // which of the faces it offers this link may use. Found in play, on the cube.
            case CHEMICAL -> chemicals;
        };
    }
}
