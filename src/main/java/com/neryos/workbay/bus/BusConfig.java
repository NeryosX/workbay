package com.neryos.workbay.bus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;

import java.util.Optional;
import java.util.UUID;

/**
 * One link: one bay, one Connector, one resource. SPEC.md §0, §4 and §9.
 *
 * <p><b>"Link" is the word the player reads and "bus" is the word the code uses.</b> They are the
 * same thing; the screen calls the list LINKS because every row is anchored by a Connector block
 * the player put there.
 *
 * <p>A link exists because a {@link com.neryos.workbay.content.connector.ConnectorBlock} exists in
 * the world. There is no way to type a position into a screen, which is why {@link #connector} and
 * {@link #target} are both plain fields rather than optionals: a link with neither is not a link.
 * The target is derived once, when the Connector is placed, so the runner never has to read the
 * Connector's blockstate from a tick.
 *
 * <p>A link targets exactly one position. No priority, no round-robin, no distance tiebreak - those
 * only mean anything with several targets, and Connectors are cheap enough that three destinations
 * are three Connectors. That deletes three widgets, a persisted rotation index, and everything they
 * would need synced, saved and tested.
 *
 * <p>Immutable, so a config can be handed around and compared with {@code equals} for the
 * server-side polling that {@code broadcastChanges} does.
 */
public record BusConfig(
    UUID id,
    String name,
    int bay,
    Resource resource,
    Mode mode,
    GlobalPos connector,
    GlobalPos target,
    Optional<Direction> targetFace,
    Optional<Direction> machineFace,
    int rate,
    int speed,
    DyeColor channel,
    boolean enabled,
    BusFilter filter,
    boolean internal,
    /**
     * What this link was made against, remembered. The server only knows the block at the far end
     * while that chunk is loaded, and a link's target is nearly always in a chunk nobody is
     * standing in - so without this the screen has nothing to name it by and falls back to two
     * coordinates, and the flow map draws a box with no icon. Stamped when the Connector is
     * placed, which is the one moment the block is guaranteed to be there; the live read still
     * wins when the chunk happens to be loaded, so a target that has been swapped out reads
     * correctly the moment anybody is near it.
     */
    Optional<ResourceLocation> targetBlock) {

    /**
     * Legal speeds, in ticks. A fixed list rather than free entry so every one of them divides the
     * 1200-tick wheel in SPEC.md §9 - that is the whole reason the wheel works.
     */
    public static final int[] SPEEDS = { 10, 20, 40, 60, 100, 200 };

    /**
     * What a link is born with, both from the server's config so a host can set the pace of a
     * fresh base. The ceiling on the rate is enforced every move in
     * {@link BusRunner#rate}, not here: a link saved by an older config would otherwise keep a
     * number the server no longer allows.
     */
    public static int defaultRate() {
        return com.neryos.workbay.config.WorkbayConfig.SERVER.linkDefaultRate.get();
    }

    public static int defaultSpeed() {
        return com.neryos.workbay.config.WorkbayConfig.SERVER.linkDefaultSpeed.get();
    }

    public static final Codec<BusConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("Id").forGetter(BusConfig::id),
        Codec.STRING.fieldOf("Name").forGetter(BusConfig::name),
        Codec.INT.fieldOf("Bay").forGetter(BusConfig::bay),
        StringRepresentable.fromEnum(Resource::values).fieldOf("Resource").forGetter(BusConfig::resource),
        StringRepresentable.fromEnum(Mode::values).fieldOf("Mode").forGetter(BusConfig::mode),
        GlobalPos.CODEC.fieldOf("Connector").forGetter(BusConfig::connector),
        GlobalPos.CODEC.fieldOf("Target").forGetter(BusConfig::target),
        Direction.CODEC.optionalFieldOf("TargetFace").forGetter(BusConfig::targetFace),
        Direction.CODEC.optionalFieldOf("MachineFace").forGetter(BusConfig::machineFace),
        Codec.INT.fieldOf("Rate").forGetter(BusConfig::rate),
        Codec.INT.fieldOf("Speed").forGetter(BusConfig::speed),
        DyeColor.CODEC.optionalFieldOf("Channel", DyeColor.WHITE).forGetter(BusConfig::channel),
        Codec.BOOL.optionalFieldOf("Enabled", true).forGetter(BusConfig::enabled),
        BusFilter.CODEC.optionalFieldOf("Filter", BusFilter.NONE).forGetter(BusConfig::filter),
        // True for a bay-to-bay link with no Connector at all. SPEC.md §0 closed "a link with no
        // physical anchor cannot be found, broken or audited in the world" against links that leave
        // the Workbay; that rationale does not reach a link whose both ends are bays in the same
        // menu you are already looking at. See SPEC.md §4's bay-to-bay note.
        Codec.BOOL.optionalFieldOf("Internal", false).forGetter(BusConfig::internal),
        // Optional, so every link saved before this existed still loads -- it simply has nothing
        // remembered and reads as it did, by position, until it is remade.
        ResourceLocation.CODEC.optionalFieldOf("TargetBlock").forGetter(BusConfig::targetBlock)
    ).apply(i, BusConfig::new));

    /**
     * Through the codec rather than {@code StreamCodec.composite}, which caps at six components and
     * this exceeds twice over. SPEC.md §4 says to nest sub-records instead; there is nothing here
     * that groups naturally yet, and a nesting invented only to satisfy the cap would read worse.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, BusConfig> STREAM_CODEC =
        ByteBufCodecs.fromCodecWithRegistries(CODEC);

    /**
     * A new link starts <b>disabled</b>. Placing a Connector is how a link is made, and a link that
     * starts moving the moment it exists will empty a chest into the wrong machine before the
     * player has seen the row - which is exactly what happened in play. Enabling it is one click
     * on the row's power button.
     *
     * <p><b>And it starts with no name at all.</b> The row derives one from what the link points at
     * ({@link com.neryos.workbay.menu.WorkbaySnapshot.Link#label()}), which is the only thing that
     * tells four rows apart; a stored default was the same three words on every one of them, and it
     * went stale the moment the link was retargeted.
     */
    /**
     * The bay of a link that belongs to no bay. SPEC.md §9's links are per bay; this is the one
     * value outside that, and it exists so the X on a link row can stop being destructive.
     *
     * <p><b>Detached, not deleted.</b> The X removed the row outright, and the Add list can only
     * offer links that exist -- so a Connector still standing in the world, still paired, still
     * pointing at its chest, was reachable from no list on the screen and could only be revived by
     * walking to it and right-clicking, which mints a fresh link with the filter, rate, speed and
     * name gone. OPEN_ISSUES #70. A detached link keeps all of that and waits in the Add list.
     * Deleting one for real is what breaking its Connector does, which is SPEC.md §0's model
     * anyway: a Connector <em>is</em> the link.
     */
    public static final int NO_BAY = -1;

    /** True for a link that is not attached to any bay, and therefore never runs. */
    public boolean detached() {
        return bay < 0;
    }

    public static BusConfig create(UUID id, int bay, Resource resource, Mode mode,
        GlobalPos connector, GlobalPos target) {
        return new BusConfig(id, "", bay, resource, mode, connector, target,
            Optional.empty(), Optional.empty(), defaultRate(), defaultSpeed(), DyeColor.WHITE,
            false, BusFilter.NONE, false, Optional.empty());
    }

    /**
     * Bay to bay, inside one Workbay, no Connector. {@code anchor} is the Workbay's own position -
     * there is nothing else to anchor an internal link to - and {@code target} is the other bay's
     * machine position in the same mirrored Backshop column, so every existing capability-cache and
     * mirroring guarantee the transfer core already has just applies.
     */
    public static BusConfig createInternal(UUID id, int bay, GlobalPos anchor, GlobalPos target) {
        return new BusConfig(id, "", bay, Resource.ITEM, Mode.INSERT, anchor, target,
            Optional.empty(), Optional.empty(), defaultRate(), defaultSpeed(), DyeColor.WHITE,
            false, BusFilter.NONE, true, Optional.empty());
    }

    /**
     * What this link may carry. {@link BusFilter} says what it matches on and why that is all it
     * matches on; the row's ghost slot and the panel behind it are what a drag out of JEI or EMI
     * lands in.
     */
    public BusConfig withFilter(BusFilter nowFilter) {
        return new BusConfig(id, name, bay, resource, mode, connector, target, targetFace,
            machineFace, rate, speed, channel, enabled, nowFilter, internal, targetBlock);
    }

    public BusConfig withRate(int newRate) {
        return new BusConfig(id, name, bay, resource, mode, connector, target, targetFace,
            machineFace, newRate, speed, channel, enabled, filter, internal, targetBlock);
    }

    public BusConfig withSpeed(int newSpeed) {
        return new BusConfig(id, name, bay, resource, mode, connector, target, targetFace,
            machineFace, rate, newSpeed, channel, enabled, filter, internal, targetBlock);
    }

    public BusConfig withEnabled(boolean nowEnabled) {
        return new BusConfig(id, name, bay, resource, mode, connector, target, targetFace,
            machineFace, rate, speed, channel, nowEnabled, filter, internal, targetBlock);
    }

    public BusConfig withMode(Mode newMode) {
        return new BusConfig(id, name, bay, resource, newMode, connector, target, targetFace,
            machineFace, rate, speed, channel, enabled, filter, internal, targetBlock);
    }

    /**
     * <b>And drops the filter.</b> Its entries are ids in whichever registry the old resource named,
     * so keeping them across a change to fluids leaves a link matching nine item ids against fluids
     * and refusing everything, with a row of items still drawn in the panel to say it should work.
     */
    public BusConfig withResource(Resource newResource) {
        return new BusConfig(id, name, bay, newResource, mode, connector, target, targetFace,
            machineFace, rate, speed, channel, enabled,
            newResource == resource ? filter : BusFilter.NONE, internal, targetBlock);
    }

    public BusConfig withName(String newName) {
        return new BusConfig(id, newName, bay, resource, mode, connector, target, targetFace,
            machineFace, rate, speed, channel, enabled, filter, internal, targetBlock);
    }

    /**
     * Internal only: which bay this link points at. Refused elsewhere for every other kind.
     *
     * <p>Drops what was remembered about the old target, for the same reason
     * {@link #withResource} drops the filter: a name kept across a retarget is a screen confidently
     * calling this link by the name of a block it no longer talks to.
     */
    public BusConfig withTarget(GlobalPos newTarget) {
        return new BusConfig(id, name, bay, resource, mode, connector, newTarget, targetFace,
            machineFace, rate, speed, channel, enabled, filter, internal, Optional.empty());
    }

    /** Both positions replaced and everything else kept: what a stranger's snapshot gets. */
    public BusConfig withPlaces(GlobalPos nowConnector, GlobalPos nowTarget) {
        return new BusConfig(id, name, bay, resource, mode, nowConnector, nowTarget, targetFace,
            machineFace, rate, speed, channel, enabled, filter, internal, targetBlock);
    }

    /** Stamps what this link points at, at the one moment the block is known to be loaded. */
    public BusConfig withTargetBlock(Optional<ResourceLocation> block) {
        return new BusConfig(id, name, bay, resource, mode, connector, target, targetFace,
            machineFace, rate, speed, channel, enabled, filter, internal, block);
    }

    /**
     * Moves this link to another bay. SPEC.md §4's list is per bay: a link belongs to exactly one,
     * and this is how a player hands it to a different one without breaking the Connector.
     */
    public BusConfig withBay(int newBay) {
        return new BusConfig(id, name, newBay, resource, mode, connector, target, targetFace,
            machineFace, rate, speed, channel, enabled, filter, internal, targetBlock);
    }

    /**
     * Which face of the target block this link reaches into; empty means any face that answers.
     *
     * <p>Not cosmetic: a machine with a separate input and output slot exposes them on different
     * faces, and a link with no face pinned takes whichever the block hands out first.
     */
    public BusConfig withTargetFace(Optional<Direction> newFace) {
        return new BusConfig(id, name, bay, resource, mode, connector, target, newFace,
            machineFace, rate, speed, channel, enabled, filter, internal, targetBlock);
    }

    /**
     * One step round the face picker's ring of seven: any, then the six in the order
     * {@link Direction#values()} declares, then back to any. {@code back} is a right-click.
     */
    public static Optional<Direction> stepFace(Optional<Direction> face, boolean back) {
        int count = Direction.values().length + 1;
        // "Any" is slot 0 of the ring and each direction sits one past its own ordinal.
        int here = face.map(d -> d.ordinal() + 1).orElse(0);
        int next = Math.floorMod(here + (back ? -1 : 1), count);
        return next == 0 ? Optional.empty() : Optional.of(Direction.values()[next - 1]);
    }

    public enum Resource implements StringRepresentable {
        ITEM("item"), FLUID("fluid"), ENERGY("energy"),
        /**
         * Mekanism's chemicals (OPEN_ISSUES #31). The fourth case, and the only one that is not a
         * NeoForge capability every mod can implement - so it is the only resource that can be
         * <em>absent</em>, and {@link #step} skips it when Mekanism is not installed rather than
         * offering a player a link that can never bind.
         */
        CHEMICAL("chemical");

        private final String name;

        Resource(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        /**
         * One step round the ring. Back is what a right-click asks for, and a resource whose mod
         * is not here is not on the ring at all - a link cycled onto CHEMICAL without Mekanism
         * would read as broken rather than as unavailable.
         */
        public Resource step(boolean back) {
            Resource next = this;
            for (int guard = 0; guard < values().length; guard++) {
                next = values()[Math.floorMod(next.ordinal() + (back ? -1 : 1), values().length)];
                if (next.available()) {
                    return next;
                }
            }
            return this;
        }

        /** False only for a resource whose mod is not installed. Named no Mekanism type here. */
        public boolean available() {
            return this != CHEMICAL || com.neryos.workbay.compat.MekanismChemicals.present();
        }
    }

    /**
     * What the link does <em>at its target</em>. Insert pushes the hosted machine's output into the
     * target; extract pulls from the target into the hosted machine. Stated this way round because
     * the target is the end the player picks and names.
     */
    public enum Mode implements StringRepresentable {
        INSERT("insert"), EXTRACT("extract");

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public Mode flip() {
            return this == INSERT ? EXTRACT : INSERT;
        }
    }
}
