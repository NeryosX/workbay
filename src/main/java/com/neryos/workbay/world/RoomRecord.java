package com.neryos.workbay.world;

import com.mojang.serialization.Codec;
import com.neryos.workbay.content.room.RoomColour;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One room. SPEC.md §8.
 *
 * <p>Kept in {@link RoomRegistry}. Which bay holds it is a fact of the <em>network's</em> record
 * ({@code WorkbayRecord.Bay#room}) and derived from there ({@link RoomRegistry#holderOf}), never
 * stored here, so the two can never disagree. What is stored here is what travels with the room
 * when it is an item: its size, its name, its look, its guests, its Connectors -- and the
 * {@link #ticket} that says which item is the real one.
 *
 * <p>{@code tier} is the size the room was crafted at and never changes; {@code builtTier} is what
 * is <b>standing in the Backshop</b>, zero until the first entry spends the shell.
 */
public record RoomRecord(UUID id, int region, int tier, Optional<String> name, int builtTier,
    Optional<ResourceKey<Biome>> biome, RoomColour colour,
    /**
     * Who else may be in here, and how much of it they get. Empty on every room until somebody is
     * invited, which is the point: <b>an invitation is to one room, never to the dimension.</b>
     *
     * <p>A list rather than a map because the screen has to draw a name beside each level, and the
     * name is not derivable from a UUID without a profile lookup the client cannot make.
     */
    List<RoomRecord.Guest> guests,
    /**
     * <b>Which item is the room.</b> Minted fresh every time the room is ejected and stamped on the
     * item; spent (cleared) the moment an item is racked. An item whose ticket is not this one is
     * a copy -- pick-block, a duplicator, a stale stack -- and cannot open the room. Empty while
     * the room sits in a bay, when no item should exist at all. SPEC.md §0.
     */
    Optional<UUID> ticket,
    /** The network that last held this room, so Leave has a Workbay to aim at while it is out. */
    Optional<UUID> lastHolder,
    /**
     * The Connectors standing inside this room while it is <b>out of a bay</b>. In a bay they are
     * on the holding network's list like any other Connector; pulling the room out moves them
     * here and racking it moves them onto the new network. SPEC.md §0.
     */
    List<WorkbayRecord.Connector> connectors) {

    /**
     * One invitation. The name is what it was when the invitation was made and is only ever drawn;
     * the id is what every check uses, so somebody who renames themselves keeps their access and
     * somebody who takes their old name does not inherit it.
     */
    public record Guest(UUID id, String name, RoomGuest level) {
        public static final Codec<Guest> CODEC = RecordCodecBuilder.create(g -> g.group(
            UUIDUtil.CODEC.fieldOf("Id").forGetter(Guest::id),
            Codec.STRING.fieldOf("Name").forGetter(Guest::name),
            RoomGuest.CODEC.fieldOf("Level").forGetter(Guest::level)
        ).apply(g, Guest::new));
    }

    public static final Codec<RoomRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("Id").forGetter(RoomRecord::id),
        Codec.INT.fieldOf("Region").forGetter(RoomRecord::region),
        // Optional for the rooms written before a room had a size of its own; they read as the
        // smallest, which is the one whose shell fits inside whatever they had.
        Codec.INT.optionalFieldOf("Tier", 1).forGetter(RoomRecord::tier),
        Codec.STRING.optionalFieldOf("Name").forGetter(RoomRecord::name),
        Codec.INT.optionalFieldOf("BuiltTier", 0).forGetter(RoomRecord::builtTier),
        ResourceKey.codec(Registries.BIOME).optionalFieldOf("Biome").forGetter(RoomRecord::biome),
        // Absent on every room saved before rooms had a colour, and those rooms are bedrock: the
        // default is the shade that reads closest to it, so nothing visibly changes under them
        // until somebody chooses.
        RoomColour.CODEC.optionalFieldOf("Colour", RoomColour.DEFAULT).forGetter(RoomRecord::colour),
        // Optional, so every room saved before guests existed loads with nobody invited -- which
        // is the correct answer for one and not a migration.
        Guest.CODEC.listOf().optionalFieldOf("Guests", List.of()).forGetter(RoomRecord::guests),
        UUIDUtil.CODEC.optionalFieldOf("Ticket").forGetter(RoomRecord::ticket),
        UUIDUtil.CODEC.optionalFieldOf("LastHolder").forGetter(RoomRecord::lastHolder),
        WorkbayRecord.Connector.CODEC.listOf().optionalFieldOf("Connectors", List.of())
            .forGetter(RoomRecord::connectors)
    ).apply(i, RoomRecord::new));

    /**
     * A fresh room of one size in a region nobody else has. Unbuilt until somebody opens it:
     * SPEC.md §8 spends the shell on first entry.
     */
    /**
     * What the room is called everywhere a player reads it: its name, or "Room N" off its own
     * region, which nothing but the room has. It was its bay's number, so one room was "Room 2"
     * in this Workbay and "Room 1" in the next. OPEN_ISSUES #114. Written here once and carried
     * on the snapshot, so no screen derives a default of its own.
     */
    public String label() {
        return name.filter(n -> !n.isBlank())
            .orElseGet(() -> com.neryos.workbay.WorkbayLang.gui("rooms.name", region + 1).getString());
    }

    public static RoomRecord fresh(UUID id, int region, int tier) {
        return new RoomRecord(id, region, Math.clamp(tier, 1, RoomGeometry.MAX_TIER),
            Optional.empty(), 0, Optional.empty(), RoomColour.DEFAULT, List.of(),
            Optional.empty(), Optional.empty(), List.of());
    }

    /** True once the shell exists in the Backshop. */
    public boolean built() {
        return builtTier > 0;
    }

    /** The interior side this room was crafted at, built or not. */
    public int interior() {
        return RoomGeometry.interior(tier);
    }

    /**
     * The biome the room's chunks carry. Plains by default because it is the one biome where grass
     * grows, water does not freeze and nothing is on fire - the neutral answer.
     */
    public ResourceKey<Biome> effectiveBiome() {
        return biome.orElse(Biomes.PLAINS);
    }

    /** True when a Backshop position is inside this room's built interior. */
    public boolean contains(net.minecraft.core.BlockPos pos) {
        return built() && RoomGeometry.inside(pos, region, builtTier);
    }

    public RoomRecord withBuiltTier(int nowBuilt) {
        return new RoomRecord(id, region, tier, name, nowBuilt, biome, colour, guests, ticket,
            lastHolder, connectors);
    }

    public RoomRecord withName(Optional<String> nowName) {
        return new RoomRecord(id, region, tier, nowName, builtTier, biome, colour, guests, ticket,
            lastHolder, connectors);
    }

    public RoomRecord withColour(RoomColour nowColour) {
        return new RoomRecord(id, region, tier, name, builtTier, biome, nowColour, guests, ticket,
            lastHolder, connectors);
    }

    public RoomRecord withBiome(ResourceKey<Biome> nowBiome) {
        return new RoomRecord(id, region, tier, name, builtTier, Optional.of(nowBiome), colour,
            guests, ticket, lastHolder, connectors);
    }

    public RoomRecord withTicket(Optional<UUID> nowTicket) {
        return new RoomRecord(id, region, tier, name, builtTier, biome, colour, guests, nowTicket,
            lastHolder, connectors);
    }

    public RoomRecord withLastHolder(UUID network) {
        return new RoomRecord(id, region, tier, name, builtTier, biome, colour, guests, ticket,
            Optional.of(network), connectors);
    }

    public RoomRecord withConnectors(List<WorkbayRecord.Connector> nowConnectors) {
        return new RoomRecord(id, region, tier, name, builtTier, biome, colour, guests, ticket,
            lastHolder, List.copyOf(nowConnectors));
    }

    /** What this player may do in here, or empty for somebody who was never invited. */
    public Optional<RoomGuest> guestLevel(UUID player) {
        return guests.stream().filter(guest -> guest.id().equals(player))
            .map(Guest::level).findFirst();
    }

    /** Invites, or changes an existing invitation's level. One entry per player, always. */
    public RoomRecord withGuest(UUID player, String playerName, RoomGuest level) {
        List<Guest> updated = new java.util.ArrayList<>(
            guests.stream().filter(guest -> !guest.id().equals(player)).toList());
        updated.add(new Guest(player, playerName, level));
        return withGuests(updated);
    }

    public RoomRecord withoutGuest(UUID player) {
        return withGuests(guests.stream().filter(guest -> !guest.id().equals(player)).toList());
    }

    private RoomRecord withGuests(List<Guest> nowGuests) {
        return new RoomRecord(id, region, tier, name, builtTier, biome, colour,
            List.copyOf(nowGuests), ticket, lastHolder, connectors);
    }
}
