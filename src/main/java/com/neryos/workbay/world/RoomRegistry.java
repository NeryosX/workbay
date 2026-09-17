package com.neryos.workbay.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every Workbay in the world, and where its bays live. SPEC.md §14.
 *
 * <p>Kept on the <b>overworld</b>, the only dimension never fully unloaded, so it is readable
 * whatever else is loaded. The third argument to {@link SavedData.Factory} is null on purpose:
 * NeoForge has no mod DataFixers, so {@link #DATA_VERSION} and the branch in {@link #load} are the
 * entire migration mechanism and there is no rescue if a schema change is got wrong.
 */
public class RoomRegistry extends SavedData {
    private static final Logger LOG = LogUtils.getLogger();

    /** First key of every structure this mod persists. Branch on it in load(), no exceptions. */
    public static final int DATA_VERSION = 1;
    public static final String VERSION_KEY = "DataVersion";

    private static final String FILE = "workbay_registry";

    /**
     * Compact Machines' alphabet with the ambiguous letters removed - no C, E, I, O or U, so no
     * code can be misread off a screenshot or misheard down a voice call. Recovering a lost
     * Workbay by typing its code to an operator is the point of having one.
     */
    private static final String ALPHABET = "0123456789ABDFGHJKLMNPQRSTVWXYZ";
    private static final int CODE_LENGTH = 12;

    /**
     * Chunks between bay columns. SPEC.md §8 allocates them adjacently, but a NeoForge forced-chunk
     * ticket covers radius 2, so an anchored Workbay would hold twenty-four of its neighbours'
     * columns loaded as well (OPEN_ISSUES #17). The Backshop is empty and infinite; spacing costs
     * nothing and makes an Anchor pay for exactly one column.
     */
    private static final int COLUMN_SPACING = 8;

    private final Map<UUID, WorkbayRecord> byId = new HashMap<>();
    private final Map<String, UUID> byCode = new HashMap<>();

    /**
     * Every room in the world, by its own UUID. Kept here and not on a Workbay record because a
     * room is not a network's: it is an item that sits in a bay of whichever Workbay holds it
     * today (SPEC.md §0), and it exists whether or not any bay does.
     */
    private final Map<UUID, RoomRecord> rooms = new HashMap<>();

    /**
     * Which tick each network's buses were last run on. <b>Not saved</b>: it is a claim on the
     * current tick and nothing more.
     *
     * <p>Every Workbay on a record ticks <em>every</em> bus on it, so two blocks standing on one
     * network moved every link twice - a link the panel printed as one
     * item every twenty ticks delivered two a second, measured in a live world (OPEN_ISSUES #54,
     * and #40 is the fault). The rate and the wheel were both right; the link was simply stepped by
     * two runners. One entry per network, replaced every tick.
     */
    private final Map<UUID, Long> busTurn = new HashMap<>();

    /** What each network's links last reported, shared by every Workbay on it (#39). */
    private final Map<UUID, Map<UUID, com.neryos.workbay.bus.BusRunner.BusStatus>>
        busStatuses = new HashMap<>();

    /**
     * What this build could not read, carried through {@link #save} untouched so a later build
     * (or a human) still can. Elements of {@code Workbays} / {@code Rooms} whose codec failed, and
     * - {@link #foreign} - the entire file when its {@link #DATA_VERSION} is not ours. Throwing
     * or dropping here is not refusal: vanilla swallows the throw and the next save makes the
     * drop permanent (night 2026-09-11, 1B #11a/#11b, 1C #5).
     */
    private final List<Tag> unparsedWorkbays = new ArrayList<>();
    private final List<Tag> unparsedRooms = new ArrayList<>();
    private CompoundTag foreign;

    /**
     * How a change reaches the disk <em>now</em>. A SavedData is written at autosave and on a clean
     * stop; chunks are also written when they unload. A dedicated server hard-killed three minutes
     * after a room was built came back with the room's shell standing in the Backshop and no
     * record of it (night 2026-09-11, 4C): the next room minted would have landed on top of it. Set
     * by {@link #read}; null for a registry the tests build by hand, or while {@link #load} runs.
     */
    private Runnable flush;

    private int nextBayColumn = 0;

    /**
     * Monotonic and never reused, so no two rooms can ever be handed the same 512-block region -
     * including after one is orphaned, which is why it is a counter and not a free list.
     */
    private int nextRoomRegion = 0;

    public static final SavedData.Factory<RoomRegistry> FACTORY =
        new SavedData.Factory<>(RoomRegistry::new, RoomRegistry::load, null);

    public static RoomRegistry get(MinecraftServer server) {
        return read(server.overworld().getDataStorage(),
            server.getWorldPath(LevelResource.ROOT).resolve("data"), FILE);
    }

    /**
     * {@code dataFolder} is the storage's own (private) folder; the tests pass a probe name.
     *
     * <p>Vanilla's {@code readSavedData} catches every exception a file raises - truncated,
     * corrupt, not NBT at all - logs it and hands out {@code null}, so {@code computeIfAbsent}
     * mints a fresh empty registry and the first {@code put} writes it over the only copy of every
     * network (night 2026-09-11, 1C #5a). {@link #load} itself no longer throws, so this is the
     * one path left that can replace a file: copy it aside first, once.
     */
    public static RoomRegistry read(DimensionDataStorage storage, Path dataFolder, String name) {
        if (storage.get(FACTORY, name) == null) {
            Path file = dataFolder.resolve(name + ".dat");
            if (Files.exists(file)) {
                Path copy = dataFolder.resolve(name + ".dat.corrupt-" + DateTimeFormatter
                    .ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()));
                try {
                    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                    LOG.error("{} could not be read (see the error above). A copy is kept at {}; "
                        + "the registry starts empty and every network in it is unreachable "
                        + "until the copy is restored", file, copy);
                } catch (IOException e) {
                    LOG.error("{} could not be read and could not be copied aside either", file, e);
                }
            }
        }
        RoomRegistry registry = storage.computeIfAbsent(FACTORY, name);
        registry.flush = storage::save;
        return registry;
    }

    /**
     * Every write to this registry is structural -- a network, a room, an upgrade, a link, a lock --
     * and the per-tick state ({@link #busTurn}, {@link #busStatuses}) never comes through here, so
     * every one is worth a write to disk. {@code DimensionDataStorage#save} encodes the dirty
     * SavedData on this thread (a few KB) and writes them on the IO worker.
     */
    @Override
    public void setDirty() {
        super.setDirty();
        if (flush != null) {
            flush.run();
        }
    }

    /**
     * True for exactly one Workbay per network per tick - whichever asks first.
     *
     * <p>An election rather than a fixed block, because a fixed one stops the whole network the
     * moment its chunk unloads. Block entities tick in a stable order, so the same Workbay wins
     * every tick while it is loaded and another takes over on its own when it is not.
     */
    /**
     * One link-status map per network, shared by every Workbay standing on it.
     *
     * <p>Buses are run by one elected Workbay per tick, so the blocks that lose the turn used to
     * hold no statuses at all and read <em>Idle</em> for ever - a second front door that always
     * said nothing was wrong. §7's question is asked of the network, so the answer lives with the
     * network. Transient: it is derived from a tick and rebuilt on the next one. OPEN_ISSUES #39.
     */
    public java.util.Map<UUID, com.neryos.workbay.bus.BusRunner.BusStatus> busStatuses(
        UUID network) {
        return busStatuses.computeIfAbsent(network, key -> new java.util.HashMap<>());
    }

    public boolean takeBusTurn(UUID network, long gameTime) {
        Long ranOn = busTurn.put(network, gameTime);
        return ranOn == null || ranOn != gameTime;
    }

    // ---------------------------------------------------------------- reading

    public Optional<WorkbayRecord> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Lookup is forgiving about dashes and case, because a player types this one by hand. */
    public Optional<WorkbayRecord> byCode(String code) {
        UUID id = byCode.get(normalise(code));
        return id == null ? Optional.empty() : byId(id);
    }

    /**
     * Which Workbay owns a bay column. The Backshop side of the mod only ever knows a position, so
     * this is how a Port or a bay maps back to the Workbay it belongs to.
     */
    public Optional<WorkbayRecord> byBayColumn(ChunkPos column) {
        long key = column.toLong();
        return byId.values().stream().filter(r -> r.bayColumn().toLong() == key).findFirst();
    }

    public List<WorkbayRecord> ownedBy(UUID owner) {
        return byId.values().stream().filter(r -> r.owner().equals(owner)).toList();
    }

    /** Every Workbay this server knows about, in no particular order. */
    public java.util.Collection<WorkbayRecord> all() {
        return java.util.List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    public int nextRoomRegion() {
        return nextRoomRegion;
    }

    public Optional<RoomRecord> room(UUID id) {
        return Optional.ofNullable(rooms.get(id));
    }

    /** The room standing in one bay of a network, if that bay holds one. */
    public Optional<RoomRecord> roomInBay(WorkbayRecord record, int bay) {
        return record.bay(bay).room().flatMap(this::room);
    }

    /** Every room a network's bays hold, by bay index, in bay order. */
    public java.util.SortedMap<Integer, RoomRecord> roomsOf(WorkbayRecord record) {
        java.util.SortedMap<Integer, RoomRecord> out = new java.util.TreeMap<>();
        for (WorkbayRecord.Bay bay : record.bays()) {
            bay.room().map(rooms::get).ifPresent(room -> out.put(bay.index(), room));
        }
        return out;
    }

    /** A room and the bay it stands in. */
    public record Holder(WorkbayRecord network, int bay) {}

    /**
     * Which network holds a room, and in which bay -- read off the bays, never stored on the
     * room, so the two cannot disagree. Empty while the room is an item.
     */
    public Optional<Holder> holderOf(RoomRecord room) {
        return holderOf(room.id());
    }

    public Optional<Holder> holderOf(UUID room) {
        for (WorkbayRecord record : byId.values()) {
            Optional<WorkbayRecord.Bay> bay = record.bayHolding(room);
            if (bay.isPresent()) {
                return Optional.of(new Holder(record, bay.get().index()));
            }
        }
        return Optional.empty();
    }

    /** Which room a Backshop position falls inside, or empty. How a shell block finds its room. */
    public Optional<RoomRecord> roomAt(net.minecraft.core.BlockPos pos) {
        return rooms.values().stream()
            .filter(r -> r.built() && RoomGeometry.inside(pos, r.region(), r.builtTier()))
            .findFirst();
    }

    /**
     * Whose room this is: the owner of the network holding it, or of the one that held it last
     * while it is an item -- so the owner standing in a pulled-out room may still build in it.
     * Entering needs a holder ({@link RoomVisit#enter}); staying does not.
     */
    public Optional<UUID> ownerOf(RoomRecord room) {
        return holderOf(room).map(holder -> holder.network().owner())
            .or(() -> room.lastHolder().flatMap(this::byId).map(WorkbayRecord::owner));
    }

    // ---------------------------------------------------------------- writing

    /**
     * Mints a Workbay: a fresh id, a code nobody else has, a bay column nobody else uses, and a
     * <b>name</b> - "Workbay 1" for this owner's first, "Workbay 2" for their second.
     *
     * <p>Stamped at mint rather than derived on read. A name derived from a position in a list
     * renumbers itself the moment another network is created or the map iterates in a different
     * order, and a network that answers to a different name each session is not a name at all.
     */
    public WorkbayRecord create(UUID owner, String ownerName, RandomSource random) {
        UUID id = UUID.randomUUID();
        ChunkPos column = allocateBayColumn();
        WorkbayRecord record = new WorkbayRecord(id, mintCode(random),
            // Locked at mint: an unlocked Workbay lets any passer-by eject the owner's machines
            // into their own hand, so sharing is what the owner opts into with the Lock button.
            defaultName(ownerName, ownedBy(owner).size() + 1), owner, ownerName, true,
            column, WorkbayRecord.Upgrades.NONE, Optional.empty(), List.of(), List.of(),
            0, List.of());
        byId.put(id, record);
        byCode.put(normalise(record.code()), id);
        setDirty();
        return record;
    }

    /** Replaces a record in place. Every mutation goes through here so setDirty is never missed. */
    public void put(WorkbayRecord record) {
        byId.put(record.id(), record);
        byCode.put(normalise(record.code()), record.id());
        setDirty();
    }

    /**
     * Mints a room of one size and hands it a region nobody else has. The shell is <b>not</b>
     * built here: SPEC.md §8 spends that on first entry.
     */
    public RoomRecord createRoom(int tier) {
        RoomRecord room = RoomRecord.fresh(UUID.randomUUID(), nextRoomRegion++, tier);
        rooms.put(room.id(), room);
        setDirty();
        return room;
    }

    /** Replaces a room in place. Every room mutation goes through here so setDirty is never missed. */
    public void putRoom(RoomRecord room) {
        rooms.put(room.id(), room);
        setDirty();
    }

    /**
     * The name a network is born with. Not translated: it is written into the save the moment the
     * network is minted, so a server that changes language would otherwise have its networks
     * change name - and the player may rename it to anything in one gesture.
     *
     * <p><b>The owner's name is in it.</b> Numbered per owner, every player's first network was
     * "Workbay 1", so a chat line, a bay title or a room subtitle naming one on a server named
     * nobody's in particular. "Neryos's Workbay", then "Neryos's Workbay 2": unique, says whose,
     * and still one gesture from anything else. OPEN_ISSUES #108, the owner's call. A blank owner
     * name (a record from before names were kept) falls back to the old form. Always {@code 's},
     * even after an s -- the bare apostrophe is the older rule and reads as a cut.
     */
    public static String defaultName(String ownerName, int ordinal) {
        String whose = ownerName.isBlank() ? "Workbay" : ownerName + "'s Workbay";
        return ordinal == 1 && !ownerName.isBlank() ? whose : whose + " " + ordinal;
    }

    /**
     * Gives a name to every record written before networks had one, numbering each owner's from 1
     * in code order so the same world names the same networks the same way every time it loads.
     * Runs once, on the load that finds them; {@link #create} stamps every one after that.
     */
    private void nameTheUnnamed() {
        List<WorkbayRecord> unnamed = byId.values().stream()
            .filter(record -> record.name().isBlank())
            .sorted(java.util.Comparator.comparing(WorkbayRecord::code))
            .toList();
        if (unnamed.isEmpty()) {
            return;
        }
        // Seeded with what each owner already has named, so a half-migrated registry cannot mint a
        // second "Workbay 1" beside the first.
        Map<UUID, Integer> next = new HashMap<>();
        byId.values().stream().filter(record -> !record.name().isBlank())
            .forEach(record -> next.merge(record.owner(), 1, Integer::sum));
        unnamed.forEach(record -> byId.put(record.id(), record.withName(defaultName(
            record.ownerName(), next.merge(record.owner(), 1, Integer::sum)))));
        setDirty();
    }

    private ChunkPos allocateBayColumn() {
        int index = nextBayColumn++; // create() calls setDirty once the record is in.
        // Positive quadrant, well away from the room regions at far negative x (SPEC.md §8).
        return new ChunkPos((index & 0xFFF) * COLUMN_SPACING, (index >> 12) * COLUMN_SPACING);
    }

    private String mintCode(RandomSource random) {
        for (int attempt = 0; attempt < 64; attempt++) {
            StringBuilder raw = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                raw.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
            if (!byCode.containsKey(normalise(code))) {
                return code;
            }
        }
        // 31^12 codes; sixty-four collisions in a row is not luck, it is a broken RandomSource.
        throw new IllegalStateException("could not mint a unique Workbay code in 64 attempts");
    }

    public static String normalise(String code) {
        return code.replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------ persistence

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (foreign != null) {
            tag.merge(foreign);
            return tag;
        }
        tag.putInt(VERSION_KEY, DATA_VERSION);
        tag.putInt("NextBayColumn", nextBayColumn);
        tag.putInt("NextRoomRegion", nextRoomRegion);
        ListTag roomList = (ListTag) RoomRecord.CODEC.listOf()
            .encodeStart(NbtOps.INSTANCE, List.copyOf(rooms.values()))
            .getOrThrow(e -> new IllegalStateException("could not write the room registry: " + e));
        roomList.addAll(unparsedRooms);
        tag.put("Rooms", roomList);
        ListTag list = (ListTag) WorkbayRecord.CODEC.listOf()
            .encodeStart(NbtOps.INSTANCE, List.copyOf(byId.values()))
            .getOrThrow(e -> new IllegalStateException("could not write the Workbay registry: " + e));
        list.addAll(unparsedWorkbays);
        tag.put("Workbays", list);
        return tag;
    }

    public static RoomRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        RoomRegistry registry = new RoomRegistry();
        int version = tag.getInt(VERSION_KEY);
        // There is no DataFixer to fall back on. Never throw here: vanilla catches it, hands out
        // an empty registry, and the next save writes that over the file.
        if (version != DATA_VERSION) {
            LOG.error("workbay_registry is version {}, this build reads version {}. Update Workbay. "
                + "The file is kept as it is and nothing in it is reachable until then.",
                version, DATA_VERSION);
            registry.foreign = tag.copy();
            return registry;
        }
        registry.nextBayColumn = tag.getInt("NextBayColumn");
        registry.nextRoomRegion = tag.getInt("NextRoomRegion");

        for (Tag element : tag.getList("Rooms", Tag.TAG_COMPOUND)) {
            RoomRecord.CODEC.parse(NbtOps.INSTANCE, element)
                .resultOrPartial(e -> {
                    LOG.error("could not read room record {}: {} (kept as it is)", idOf(element), e);
                    registry.unparsedRooms.add(element);
                })
                .ifPresent(room -> registry.rooms.put(room.id(), room));
        }
        for (Tag element : tag.getList("Workbays", Tag.TAG_COMPOUND)) {
            WorkbayRecord.CODEC.parse(NbtOps.INSTANCE, element)
                .resultOrPartial(e -> {
                    LOG.error("could not read Workbay record {}: {} (kept as it is)",
                        idOf(element), e);
                    registry.unparsedWorkbays.add(element);
                })
                .ifPresent(record -> {
                    registry.byId.put(record.id(), record);
                    registry.byCode.put(normalise(record.code()), record.id());
                });
        }
        registry.nameTheUnnamed();
        return registry;
    }

    private static String idOf(Tag element) {
        if (element instanceof CompoundTag c) {
            return c.contains("Code") ? c.getString("Code") : c.get("Id") + "";
        }
        return element.toString();
    }

    /** Exposed for the gametests: a round trip without touching the level's data storage. */
    public static RoomRegistry roundTrip(RoomRegistry source, HolderLookup.Provider registries) {
        return load(source.save(new CompoundTag(), registries), registries);
    }
}
