package com.neryos.workbay.world;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How much of somebody else's room a guest gets. SPEC.md §8.
 *
 * <p>Three levels. Two were shipped first - somebody you are showing the place to, and somebody
 * helping you run it - and the middle one kept suggesting itself until it was built: a factory
 * has people meant to <em>work</em> it and not rebuild it, and with two levels the only way to let
 * somebody take an ingot out of a barrel was to let them break the barrel. The ring is ordered by
 * how much it gives away, so a click steps one rung up and round to the safe end.
 *
 * <p>The owner is not a level. An owner is the network that owns the room, and there is exactly
 * one; putting them on this ring would make "owner" a thing you could be invited to.
 */
public enum RoomGuest implements StringRepresentable {
    /**
     * May stand in the room and nothing else. No block broken, no block placed, no container
     * opened - a right-click on a chest is a change to what is in it, and this level is the one
     * that means "I am showing you the place".
     */
    LOOK("look"),
    /**
     * May use what is here and change none of it: open a chest, take from a barrel, click a
     * machine, use whatever is standing in the room. No block broken, no block placed, nothing
     * attacked. The line is <em>the room's shape</em>, not the room's contents - a stage in a
     * chain is meant to be worked, and this is the level that says so.
     */
    USE("use"),
    /** May do anything in the room the owner may. The walls are still not breakable by anyone. */
    BUILD("build");

    public static final Codec<RoomGuest> CODEC = StringRepresentable.fromEnum(RoomGuest::values);

    private final String name;

    RoomGuest(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** One step round the ring, in the order the enum is written: LOOK, USE, BUILD, LOOK. */
    public RoomGuest step() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** May open, click and take: {@link #USE} and up. */
    public boolean mayUse() {
        return this != LOOK;
    }
}
