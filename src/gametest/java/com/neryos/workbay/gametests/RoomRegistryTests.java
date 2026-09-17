package com.neryos.workbay.gametests;

import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** SPEC.md §14. The registry is what makes a lost Workbay recoverable, so it is worth pinning down. */
@ForEachTest(groups = "room_registry")
public class RoomRegistryTests {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000a11ce");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

    @GameTest
    @TestHolder(description = "The registry mints unique codes and non-overlapping bay columns.")
    public static void mintsUniqueCodesAndColumns(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            RoomRegistry registry = new RoomRegistry();
            RandomSource random = RandomSource.create(1234L);

            Set<String> codes = new HashSet<>();
            Set<Long> columns = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                WorkbayRecord record = registry.create(ALICE, "Alice", random);
                if (!codes.add(record.code())) {
                    helper.fail("the registry minted the same code twice: " + record.code());
                    return;
                }
                if (!columns.add(record.bayColumn().toLong())) {
                    helper.fail("two Workbays were given the same bay column: " + record.bayColumn());
                    return;
                }
                if (record.code().length() != 14 || record.code().charAt(4) != '-'
                    || record.code().charAt(9) != '-') {
                    helper.fail("code is not formatted XXXX-XXXX-XXXX: " + record.code());
                    return;
                }
                // No ambiguous letters: a code has to survive being read off a screenshot.
                if (record.code().matches(".*[CEIOU].*")) {
                    helper.fail("code contains an ambiguous letter: " + record.code());
                    return;
                }
            }

            // A forced-chunk ticket covers radius 2, so anything closer than 5 chunks apart means an
            // anchored Workbay would hold its neighbour's bays loaded too (OPEN_ISSUES #17).
            WorkbayRecord first = registry.ownedBy(ALICE).get(0);
            helper.assertValueEqual(registry.size(), 200, "registry size");
            if (first.bayColumn().x % 5 != 0 && first.bayColumn().z % 5 != 0) {
                helper.fail("bay columns are not spaced far enough apart: " + first.bayColumn());
            }
            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "A code can be looked up with or without its dashes, in any case.")
    public static void codeLookupIsForgiving(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            RoomRegistry registry = new RoomRegistry();
            WorkbayRecord record = registry.create(ALICE, "Alice", RandomSource.create(7L));

            for (String typed : List.of(record.code(), record.code().replace("-", ""),
                record.code().toLowerCase(java.util.Locale.ROOT))) {
                Optional<WorkbayRecord> found = registry.byCode(typed);
                if (found.isEmpty() || !found.get().id().equals(record.id())) {
                    helper.fail("looking up " + typed + " did not find the Workbay it belongs to");
                    return;
                }
            }
            if (registry.byCode("0000-0000-0000").isPresent()) {
                helper.fail("an unknown code found something");
            }
            helper.succeed();
        });
    }

    /**
     * The whole point of the registry: an operator can still find a player's Workbay after the
     * block is gone. If a save and load loses any of this, that recovery path is gone with it.
     */
    @GameTest
    @TestHolder(description = "Every registry field survives a save and load.")
    public static void survivesSaveAndLoad(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            HolderLookup.Provider registries = helper.getLevel().registryAccess();
            RoomRegistry before = new RoomRegistry();
            RandomSource random = RandomSource.create(99L);

            WorkbayRecord alice = before.create(ALICE, "Alice", random);
            before.create(BOB, "Bob", random);

            alice = alice
                .withUpgrades(new WorkbayRecord.Upgrades(3, 1, 1, 0))
                .withLocked(true)
                .withLastKnownPos(GlobalPos.of(WorkbayDimensions.BACKSHOP, new BlockPos(12, 34, -56)))
                .withBays(List.of(
                    new WorkbayRecord.Bay(0, Optional.of(
                        net.minecraft.resources.ResourceLocation.parse("mekanism:enrichment_chamber")),
                        com.neryos.workbay.world.FaceConfig.NONE
                            .cycled(com.neryos.workbay.bus.BusConfig.Resource.ITEM,
                                net.minecraft.core.Direction.NORTH, false),
                        // Every optional bay field rides the same round trip, so a codec that
                        // drops one is caught here rather than the first time a player names a bay
                        // or racks something that needed the bay to grow.
                        "Ore line",
                        com.neryos.workbay.world.RedstoneMode.WITHOUT_SIGNAL, Optional.empty(),
                        com.neryos.workbay.world.BayGeometry.WIDE_SHELL),
                    WorkbayRecord.Bay.empty(1)))
                // Links moved into the registry so they survive breaking the Workbay; this is the
                // exact bug that move fixed, pinned down so nobody moves them back by accident.
                .withBuses(List.of(com.neryos.workbay.bus.BusConfig.create(
                    UUID.fromString("00000000-0000-0000-0000-00000000feed"), 0,
                    com.neryos.workbay.bus.BusConfig.Resource.ITEM,
                    com.neryos.workbay.bus.BusConfig.Mode.INSERT,
                    GlobalPos.of(WorkbayDimensions.BACKSHOP, new BlockPos(1, 2, 3)),
                    GlobalPos.of(WorkbayDimensions.BACKSHOP, new BlockPos(4, 5, 6)))))
                .withDeployedCount(1)
                // Every optional field is set, or a codec that drops one round-trips vacuously.
                // player spends. All four, none of them the default, and `since` non-zero because
                // a batch half converted at the moment of a save is exactly when it matters.
;

            // A room is a place somebody built in, so it has to come back byte for byte: its
            // region (where their chests are), its size, the tier standing in the world, the
            // biome, and the ticket, last holder and Connector list that travel with it.
            com.neryos.workbay.world.RoomRecord room = before.createRoom(2)
                .withBuiltTier(2)
                .withName(Optional.of("Greenhouse"))
                .withTicket(Optional.of(java.util.UUID.randomUUID()))
                .withLastHolder(alice.id())
                .withConnectors(List.of(new WorkbayRecord.Connector(java.util.UUID.randomUUID(),
                    GlobalPos.of(WorkbayDimensions.BACKSHOP, new BlockPos(-1_048_500, 3, 4)),
                    "Barrel", GlobalPos.of(WorkbayDimensions.BACKSHOP, new BlockPos(-1_048_500, 3, 5)),
                    Optional.of(net.minecraft.resources.ResourceLocation.parse("minecraft:barrel")))))
                // Colour and the guest list ride the same trip for the same reason: both are
                // optional fields with a default, and an optional field nobody sets is a field
                // nobody tests.
                .withColour(com.neryos.workbay.content.room.RoomColour.TEAL)
                .withGuest(BOB, "Bob", com.neryos.workbay.world.RoomGuest.BUILD)
                .withBiome(net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS);
            before.putRoom(room);
            // And which bay holds it, which is the only place a holder is written.
            alice = alice.withBay(alice.bay(1).withRoom(Optional.of(room.id())));
            before.put(alice);

            RoomRegistry after = RoomRegistry.roundTrip(before, registries);

            helper.assertValueEqual(after.room(room.id()).orElse(null), room, "the room after reload");
            helper.assertValueEqual(after.roomsOf(after.byId(alice.id()).orElseThrow()).size(), 1,
                "rooms in Alice's bays after reload");
            helper.assertValueEqual(after.holderOf(room).map(RoomRegistry.Holder::bay).orElse(-1), 1,
                "the bay holding the room after reload");
            // The region allocator is monotonic and never reused, so a fresh room after a reload
            // must not be handed the region somebody is already standing in.
            helper.assertFalse(after.createRoom(1).region() == room.region(),
                "the room region allocator restarted from zero after a reload");

            helper.assertValueEqual(after.size(), 2, "records after reload");
            WorkbayRecord reloaded = after.byId(alice.id()).orElse(null);
            if (reloaded == null) {
                helper.fail("Alice's Workbay is not in the reloaded registry");
                return;
            }
            helper.assertValueEqual(reloaded, alice, "the whole record after reload");

            // The bay column must not move: it is where the machines physically are.
            helper.assertValueEqual(reloaded.bayColumn(), alice.bayColumn(), "bay column after reload");
            // Codes have to keep working after a restart or recovery is impossible.
            if (after.byCode(alice.code()).isEmpty()) {
                helper.fail("the code index was not rebuilt on load");
                return;
            }
            // A new Workbay must not be handed a column an old one already owns.
            WorkbayRecord fresh = after.create(BOB, "Bob", random);
            if (fresh.bayColumn().equals(alice.bayColumn())) {
                helper.fail("the bay column allocator restarted from zero after a reload");
            }
            helper.succeed();
        });
    }

    /**
     * SPEC.md §14: DataVersion is the entire migration mechanism, because NeoForge has no mod
     * DataFixers. A registry written by a newer build used to throw -- and vanilla's
     * {@code DimensionDataStorage#readSavedData} swallowed the throw, handed out an empty registry,
     * and the next save wrote it over the file (night 2026-09-11, 1B #11b, 1C #5). Now it never
     * throws: the raw file rides through {@code save} untouched and the memory copy is empty.
     */
    @GameTest
    @TestHolder(description = "A registry from a newer version is carried through a load and save untouched.")
    public static void refusesAnUnknownDataVersion(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            HolderLookup.Provider registries = helper.getLevel().registryAccess();
            RoomRegistry before = new RoomRegistry();
            before.create(ALICE, "Alice", RandomSource.create(3L));

            CompoundTag tag = before.save(new CompoundTag(), registries);
            helper.assertValueEqual(tag.getInt(RoomRegistry.VERSION_KEY), RoomRegistry.DATA_VERSION,
                "DataVersion written");

            tag.putInt(RoomRegistry.VERSION_KEY, RoomRegistry.DATA_VERSION + 1);
            tag.putString("FieldFromTheFuture", "kept");
            RoomRegistry loaded = RoomRegistry.load(tag, registries);
            helper.assertValueEqual(loaded.all().size(), 0,
                "records a build that cannot read the file pretends to understand");
            CompoundTag after = loaded.save(new CompoundTag(), registries);
            helper.assertValueEqual(after, tag,
                "what a newer-version registry file reads after this build saved it");
            helper.succeed();
        });
    }

    private static Path dataFile(ExtendedGameTestHelper helper, String name) {
        // DimensionDataStorage#getDataFile is private; the overworld's storage is built on
        // <world root>/data, which is what this resolves.
        return helper.getLevel().getServer()
            .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("data").resolve(name + ".dat");
    }

    private static CompoundTag wrapped(CompoundTag data) {
        CompoundTag file = new CompoundTag();
        file.put("data", data);
        file.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion()
            .getDataVersion().getVersion());
        return file;
    }

    /**
     * Night 2026-09-11, 1B #11b: the server's own path, not {@code load} by hand. Vanilla's
     * {@code readSavedData} catches whatever {@code load} throws and hands out an empty registry;
     * this proves the file survives one save through it.
     */
    @GameTest
    @TestHolder(description = "A registry file from a newer version is not replaced by an empty one on the next save.")
    public static void aNewerRegistryFileIsNotOverwrittenByAnEmptyOne(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            HolderLookup.Provider registries = helper.getLevel().registryAccess();
            RoomRegistry before = new RoomRegistry();
            before.create(ALICE, "Alice", RandomSource.create(3L));
            CompoundTag data = before.save(new CompoundTag(), registries);
            data.putInt(RoomRegistry.VERSION_KEY, RoomRegistry.DATA_VERSION + 1);

            String name = "workbay_registry_probe_" + UUID.randomUUID().toString().substring(0, 8);
            Path file = dataFile(helper, name);
            try {
                Files.createDirectories(file.getParent());
                NbtIo.writeCompressed(wrapped(data), file);
                DimensionDataStorage storage =
                    helper.getLevel().getServer().overworld().getDataStorage();
                RoomRegistry loaded = storage.computeIfAbsent(RoomRegistry.FACTORY, name);
                loaded.setDirty();
                storage.save();
                // NeoForge writes SavedData on the IO pool.
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                CompoundTag after = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                    .getCompound("data");
                helper.assertValueEqual(after.getList("Workbays", Tag.TAG_COMPOUND).size(), 1,
                    "Workbay records left in a newer-version registry file after one save");
            } catch (IOException e) {
                helper.fail("could not stage the probe registry file: " + e);
            } finally {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            }
            helper.succeed();
        });
    }

    /** Night 2026-09-11, 1B #11a / 1C #5c: one bad record used to be dropped, then saved away. */
    @GameTest
    @TestHolder(description = "One malformed record in the registry does not vanish from the file on the next save.")
    public static void aMalformedRecordSurvivesALoadAndSave(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            HolderLookup.Provider registries = helper.getLevel().registryAccess();
            RoomRegistry before = new RoomRegistry();
            before.create(ALICE, "Alice", RandomSource.create(3L));
            before.create(ALICE, "Alice", RandomSource.create(4L));
            CompoundTag data = before.save(new CompoundTag(), registries);
            // The second record loses its owner: a required field, so the codec fails on it.
            CompoundTag broken = data.getList("Workbays", Tag.TAG_COMPOUND).getCompound(1);
            broken.remove("Owner");

            RoomRegistry loaded = RoomRegistry.load(data, registries);
            helper.assertValueEqual(loaded.all().size(), 1, "records this build could read");
            CompoundTag after = loaded.save(new CompoundTag(), registries);
            helper.assertValueEqual(after.getList("Workbays", Tag.TAG_COMPOUND).size(), 2,
                "Workbay records written back after loading a file with one malformed record");
            helper.assertTrue(after.getList("Workbays", Tag.TAG_COMPOUND).contains(broken),
                "the malformed record was not written back verbatim");
            helper.succeed();
        });
    }

    /**
     * Night 2026-09-11, 1C #5a: a file vanilla cannot even parse is replaced by a fresh registry,
     * and the first {@code put} overwrites it. The only copy is kept beside it before that.
     */
    @GameTest
    @TestHolder(description = "A registry file that cannot be parsed is copied aside before a fresh registry can overwrite it.")
    public static void anUnreadableRegistryFileIsCopiedAsideBeforeItIsReplaced(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            String name = "workbay_registry_probe_" + UUID.randomUUID().toString().substring(0, 8);
            Path file = dataFile(helper, name);
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
                RoomRegistry fresh = RoomRegistry.read(
                    helper.getLevel().getServer().overworld().getDataStorage(), file.getParent(), name);
                helper.assertTrue(fresh.all().isEmpty(), "garbage read as a registry");
                long copies;
                try (var siblings = Files.list(file.getParent())) {
                    copies = siblings
                        .filter(p -> p.getFileName().toString().startsWith(name + ".dat.corrupt-"))
                        .peek(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        })
                        .count();
                }
                helper.assertValueEqual(copies, 1L,
                    "copies of an unreadable registry file kept beside it");
            } catch (IOException e) {
                helper.fail("could not stage the probe registry file: " + e);
            } finally {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            }
            helper.succeed();
        });
    }

    /**
     * Night 2026-09-11, 4C: a dedicated server hard-killed three minutes after a room was built
     * came back with no room, {@code NextRoomRegion 0} and the shell still standing in the
     * Backshop. A SavedData is written at autosave and on a clean stop; chunks also on unload. A
     * structural change to the registry has to reach the disk when it happens, not at the next
     * autosave.
     */
    @GameTest
    @TestHolder(description = "A minted network and room are on disk before any autosave or save-all.")
    public static void aMintReachesTheDiskWithoutAnAutosave(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            String name = "workbay_registry_probe_" + UUID.randomUUID().toString().substring(0, 8);
            Path file = dataFile(helper, name);
            try {
                Files.createDirectories(file.getParent());
                Files.deleteIfExists(file);
                RoomRegistry registry = RoomRegistry.read(
                    helper.getLevel().getServer().overworld().getDataStorage(), file.getParent(), name);
                WorkbayRecord minted = registry.create(ALICE, "Alice", RandomSource.create(7L));
                registry.createRoom(1);
                // No storage.save(), no save-all: only what the mint itself wrote.
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                if (!Files.exists(file)) {
                    helper.fail("the registry file does not exist after a mint; a hard kill "
                        + "before the next autosave loses the network");
                    return;
                }
                CompoundTag after = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                    .getCompound("data");
                boolean found = after.getList("Workbays", Tag.TAG_COMPOUND).stream()
                    .anyMatch(t -> ((CompoundTag) t).getString("Code").equals(minted.code()));
                helper.assertTrue(found, "the minted network is not in the registry file");
                helper.assertValueEqual(after.getInt("NextRoomRegion"), 1,
                    "NextRoomRegion in the registry file after one room was minted");
            } catch (IOException e) {
                helper.fail("could not read the probe registry file: " + e);
            } finally {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            }
            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "The registry lives on the overworld and is the same instance every time.")
    public static void livesOnTheOverworld(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            var server = helper.getLevel().getServer();
            RoomRegistry registry = RoomRegistry.get(server);
            helper.assertNotNull(registry, "RoomRegistry.get returned null");
            if (RoomRegistry.get(server) != registry) {
                helper.fail("RoomRegistry.get built a second instance; the two would overwrite "
                    + "each other at save time");
            }
            helper.succeed();
        });
    }
}
