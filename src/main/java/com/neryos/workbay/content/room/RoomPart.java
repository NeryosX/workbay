package com.neryos.workbay.content.room;

import net.minecraft.util.StringRepresentable;

/**
 * Which piece of a room's shell a block is. SPEC.md §8.
 *
 * <p>One block with a part rather than six blocks: they share a colour, a hardness, a refusal to be
 * broken and the right-click that opens the way out, and every one of those would otherwise be
 * written six times.
 *
 * <p>The {@code DOOR_*} parts are two doors: a <b>1×2</b> one ({@code DOOR_BOTTOM}, {@code
 * DOOR_TOP}) for a wall with an odd number of blocks, which has a middle block, and the quarters
 * of a <b>2×2</b> one for a wall with an even number, which has a middle seam. Either way the door
 * is dead centre, which is what the owner asked for on 2026-09-14: every shipped size is odd, so
 * the 2×2 sat half a block off and the eye caught it.
 */
public enum RoomPart implements StringRepresentable {
    WALL("wall"),
    FLOOR("floor"),
    /**
     * The bottom course of every wall: a plinth with a chamfered top.
     *
     * <p>It exists because the first room anybody looked at was one grey value on all six faces,
     * and the thing that fixes that is not a better wall texture - it is a <b>line where the floor
     * meets the wall</b>. Every real room has one and no box does.
     */
    SKIRTING("skirting"),
    /**
     * The ceiling, and deliberately <em>darker</em> than the wall under it.
     *
     * <p>The first attempt made it a bright luminous panel, to explain where a sealed room's light
     * comes from. That cannot work: Minecraft shades a bottom face to half, so 250 under the
     * ceiling renders darker than 200 on a wall and no texture value can beat it. What a ceiling
     * can be is unmistakably not the wall - coffered, so the room has a top rather than a fourth
     * copy of its sides.
     */
    CEILING("ceiling"),
    /**
     * A light fixture, set into the ceiling on a grid. OPEN_ISSUES #45: the shell emits block light
     * everywhere and nothing in the room showed where it came from, which reads as a lit box rather
     * than as a lit room.
     *
     * <p>It is the one part of the shell that is <b>untinted, unshaded and full-bright</b>. Untinted
     * because a lamp wearing the room's colour is not a lamp; unshaded because Minecraft multiplies
     * a bottom face by half, which is the exact reason a bright ceiling texture could not work; and
     * full-bright because a fixture that takes its brightness from the light it is emitting is
     * lighting itself from behind.
     */
    LIGHT("light"),
    DOOR_BOTTOM_LEFT("door_bl"),
    DOOR_BOTTOM_RIGHT("door_br"),
    DOOR_TOP_LEFT("door_tl"),
    DOOR_TOP_RIGHT("door_tr"),
    DOOR_BOTTOM("door_b"),
    DOOR_TOP("door_t");

    private final String name;

    RoomPart(String name) {
        this.name = name;
    }

    /** True for the one face a player stands on, which is the only part with its own tint. */
    public boolean isFloor() {
        return this == FLOOR;
    }

    /** True for every piece of a drawn door, which are the only blocks that open one. */
    public boolean isDoor() {
        return name.startsWith("door_");
    }

    /** The texture this part draws, under {@code textures/block/}. */
    public String texture() {
        return "room_" + name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
