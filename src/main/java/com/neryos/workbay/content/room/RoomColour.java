package com.neryos.workbay.content.room;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * What a room's shell is painted. SPEC.md §8.
 *
 * <p><b>Ours, not {@code DyeColor}'s sixteen.</b> Half of that enum is signage - lime, magenta,
 * orange - drawn to be seen across a field, and a room is a place somebody spends an evening
 * inside. These are muted on purpose, and the default is the grey-blue that reads closest to the
 * bedrock it replaced.
 *
 * <p>They are <b>tints, not textures</b>: one greyscale wall is multiplied by the value below, so a
 * new colour is one line here and never a new PNG. None of them is dark, because a multiply against
 * a mid-grey wall darkens it once already.
 *
 * <p><b>The floor gets its own tint</b>, and for every colour but one it is the same value. That
 * one is {@link #OVERWORLD}, where the point is that walls and floor disagree.
 */
public enum RoomColour implements StringRepresentable {
    SLATE("slate", 0xA8B2C4),
    CHARCOAL("charcoal", 0x8A9098),
    BONE("bone", 0xE8E2D4),
    SAND("sand", 0xE8D7AA),
    CLAY("clay", 0xD09A82),
    ROSE("rose", 0xE3B5BE),
    PLUM("plum", 0xAE9ABE),
    SKY("sky", 0xACD4EE),
    TEAL("teal", 0x96C5C0),
    SAGE("sage", 0xB3CFB0),
    /**
     * Sky above, ground below. The first, cheap half of the room that pretends to be outdoors:
     * the walls take the blue a Minecraft sky sits at and the floor takes grass green, so the room
     * reads as a field the moment you stand in it. The other half - real clouds, a horizon, depth -
     * is a renderer and a painted panorama, and it replaces the two numbers here without touching
     * anything else.
     */
    OVERWORLD("overworld", 0x9CC8F0, 0x8FC46A);

    public static final Codec<RoomColour> CODEC = StringRepresentable.fromEnum(RoomColour::values);

    /** What a room is painted before anybody chooses: the closest thing here to plain stone. */
    public static final RoomColour DEFAULT = SLATE;

    private final String name;
    private final int tint;
    private final int floorTint;

    RoomColour(String name, int tint) {
        this(name, tint, tint);
    }

    RoomColour(String name, int tint, int floorTint) {
        this.name = name;
        this.tint = tint;
        this.floorTint = floorTint;
    }

    /** The multiply the block colour handler applies. The swatch on the room row uses this one. */
    public int tint() {
        return tint;
    }

    /** The same, for the one face of the shell a player stands on. */
    public int tint(boolean floor) {
        return floor ? floorTint : tint;
    }

    /** The next one along, wrapping. The room row's swatch is a cycle, like the biome beside it. */
    public RoomColour next(boolean backwards) {
        RoomColour[] all = values();
        return all[(ordinal() + (backwards ? all.length - 1 : 1)) % all.length];
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
