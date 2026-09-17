package com.neryos.workbay.gametests;

import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.UUID;

/**
 * The riskiest assumption in the whole mod, isolated: a block entity that belongs to somebody else,
 * standing in the Backshop, must tick, must survive a chunk save and load, and must be reachable
 * from another dimension through the standard capability interfaces. Everything else Workbay does
 * is worthless if these fail.
 *
 * <p>A {@code @GameTest} always runs in the overworld - {@code GameTestInfo} holds exactly one
 * {@code ServerLevel} - so every test here reaches across into the Backshop rather than running
 * inside it.
 */
@ForEachTest(groups = "hosted_machine")
public class HostedMachineTests {

    // One owner and one chunk per test. Gametest batches run concurrently, and a forced-chunk
    // ticket is registered at radius 2, so adjacent test chunks kept each other loaded and every
    // test that waited for an unload waited forever.
    private static final UUID TICKS_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ba71");
    private static final UUID PERSIST_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ba72");
    private static final UUID CAP_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ba73");

    private static final ChunkPos TICKS_CHUNK = new ChunkPos(2048, 0);
    private static final ChunkPos PERSIST_CHUNK = new ChunkPos(2048, 64);
    private static final ChunkPos CAP_CHUNK = new ChunkPos(2048, 128);

    private static ServerLevel backshop(ExtendedGameTestHelper helper) {
        ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
        helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
        return backshop;
    }

    private static BlockPos placeFurnace(ServerLevel backshop, UUID owner, ChunkPos chunk) {
        WorkbayTickets.force(backshop, owner, chunk);
        BlockPos pos = chunk.getMiddleBlockPosition(16);
        // Clear first. The Backshop is persistent, and setBlock over an identical state keeps the
        // existing block entity -- which meant a furnace still burning last run's coal.
        backshop.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        backshop.setBlock(pos, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
        return pos;
    }

    /**
     * OPEN_ISSUES #11 as well: this is a force-loaded chunk with {@code ticking = true}, no player
     * in the dimension and no other ticket. If the furnace smelts, hosted block entities tick.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A vanilla furnace in the Backshop smelts with no player in that dimension.")
    public static void hostedFurnaceTicks(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            BlockPos pos = placeFurnace(backshop, TICKS_OWNER, TICKS_CHUNK);

            FurnaceBlockEntity furnace = (FurnaceBlockEntity) backshop.getBlockEntity(pos);
            helper.assertNotNull(furnace, "no FurnaceBlockEntity at " + pos + " in the Backshop");
            furnace.setItem(0, new ItemStack(Items.RAW_IRON, 8));
            furnace.setItem(1, new ItemStack(Items.COAL, 4));
            furnace.setChanged();

            // Polled rather than a fixed idle: the Backshop ticks on the same server tick as the
            // overworld the test runs in, but nothing guarantees the two counts line up, and a
            // fixed idle that is one tick short reads exactly like "hosting does not work".
            int[] waited = { 0 };
            helper.startSequence()
                .thenWaitUntil(() -> {
                    waited[0]++;
                    if (!furnace.getItem(2).is(Items.IRON_INGOT)) {
                        throw new GameTestAssertException("hosted furnace has smelted nothing after "
                            + waited[0] + " test ticks"
                            + " [removed=" + furnace.isRemoved()
                            + " sameBE=" + (backshop.getBlockEntity(pos) == furnace)
                            + " loaded=" + backshop.getChunkSource().hasChunk(TICKS_CHUNK.x, TICKS_CHUNK.z)
                            + " ticking=" + backshop.getChunkSource().isPositionTicking(TICKS_CHUNK.toLong())
                            + " in=" + furnace.getItem(0) + " fuel=" + furnace.getItem(1) + "]");
                    }
                })
                .thenExecute(() -> {
                    if (furnace.getItem(1).getCount() == 4) {
                        helper.fail("hosted furnace never consumed fuel");
                    }
                    if (furnace.getItem(0).getCount() != 7) {
                        helper.fail("hosted furnace produced an ingot without consuming its input");
                    }
                })
                .thenExecute(() -> WorkbayTickets.release(backshop, TICKS_OWNER, TICKS_CHUNK))
                .thenSucceed();
        });
    }

    /**
     * A real chunk save and load, not a {@code saveWithFullMetadata}/{@code loadStatic} round trip:
     * the ticket is dropped, the chunk is allowed to unload and write itself out, and the block
     * entity that comes back has to be a different object carrying the same contents.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A hosted furnace's contents survive the Backshop chunk unloading and loading again.")
    public static void hostedStateSurvivesSaveAndLoad(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            BlockPos pos = placeFurnace(backshop, PERSIST_OWNER, PERSIST_CHUNK);

            FurnaceBlockEntity before = (FurnaceBlockEntity) backshop.getBlockEntity(pos);
            helper.assertNotNull(before, "no FurnaceBlockEntity at " + pos + " in the Backshop");
            before.setItem(0, new ItemStack(Items.RAW_GOLD, 7));
            before.setItem(1, new ItemStack(Items.COAL, 3));
            before.setChanged();

            // Slot counts as they stood the instant the chunk left memory. Not the values set
            // above: the furnace is running, so it eats its own input while the test waits, and an
            // expectation fixed in advance would be asserting that hosting does nothing.
            int[] frozen = new int[3];

            helper.startSequence()
                .thenIdle(210) // long enough that at least one gold has actually been smelted
                .thenExecute(() -> {
                    WorkbayTickets.release(backshop, PERSIST_OWNER, PERSIST_CHUNK);
                    backshop.getChunkSource().save(false);
                })
                .thenWaitUntil(() -> {
                    // hasChunk() only reports the ticking level, and a chunk that has dropped below
                    // it is still in memory with the same block entities. isRemoved() is set by
                    // LevelChunk#clearAllBlockEntities, which runs after the chunk has been written
                    // out, so it is the real "this chunk has left memory" signal.
                    if (!before.isRemoved()) {
                        throw new GameTestAssertException("Backshop chunk has not unloaded yet");
                    }
                })
                .thenExecute(() -> {
                    // The old block entity is orphaned now and can no longer change, so this is
                    // exactly the state that was written to disk.
                    for (int slot = 0; slot < 3; slot++) {
                        frozen[slot] = before.getItem(slot).getCount();
                    }
                    if (frozen[2] < 1) {
                        helper.fail("the hosted furnace smelted nothing in 210 ticks, so this test "
                            + "would only prove that an idle furnace persists");
                    }
                })
                .thenExecute(() -> {
                    backshop.getChunk(PERSIST_CHUNK.x, PERSIST_CHUNK.z);
                    BlockEntity after = backshop.getBlockEntity(pos);
                    if (!(after instanceof FurnaceBlockEntity reloaded)) {
                        helper.fail("after reload the Backshop held " + after + ", not a furnace");
                        return;
                    }
                    if (reloaded == before) {
                        helper.fail("the same block entity object came back - the chunk never really "
                            + "unloaded, so this proves nothing about saving");
                        return;
                    }
                    helper.assertValueEqual(reloaded.getItem(0).getItem(), Items.RAW_GOLD, "input item after reload");
                    helper.assertValueEqual(reloaded.getItem(0).getCount(), frozen[0], "input count after reload");
                    helper.assertValueEqual(reloaded.getItem(1).getCount(), frozen[1], "fuel count after reload");
                    helper.assertValueEqual(reloaded.getItem(2).getItem(), Items.GOLD_INGOT, "output item after reload");
                    helper.assertValueEqual(reloaded.getItem(2).getCount(), frozen[2], "output count after reload");
                })
                .thenSucceed();
        });
    }

    /**
     * OPEN_ISSUES #12. Every {@code BlockCapabilityCache} call site in EnderIO, Mekanism, Create and
     * FTB-Chunks is same-level and neighbour-only; the whole transfer core rests on this working
     * across dimensions. Also pins the unloaded behaviour: null, and no sneaky chunk load.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A BlockCapabilityCache aimed into the Backshop resolves, goes null when that chunk unloads, and comes back.")
    public static void capabilityCacheReachesAcrossDimensions(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            BlockPos pos = placeFurnace(backshop, CAP_OWNER, CAP_CHUNK);

            // The cache is created from the overworld test, pointing at another ServerLevel. That
            // is exactly what a bus does.
            BlockCapabilityCache<IItemHandler, Direction> cache = BlockCapabilityCache.create(
                Capabilities.ItemHandler.BLOCK, backshop, pos, Direction.UP);

            IItemHandler handler = cache.getCapability();
            helper.assertNotNull(handler, "item handler on the Backshop furnace's UP face was null");

            ItemStack left = handler.insertItem(0, new ItemStack(Items.RAW_COPPER, 5), false);
            if (!left.isEmpty()) {
                helper.fail("cross-dimension insert left " + left + " behind");
            }
            FurnaceBlockEntity furnace = (FurnaceBlockEntity) backshop.getBlockEntity(pos);
            helper.assertValueEqual(furnace.getItem(0).getCount(), 5, "furnace input after cross-dimension insert");

            helper.startSequence()
                .thenExecute(() -> {
                    WorkbayTickets.release(backshop, CAP_OWNER, CAP_CHUNK);
                    backshop.getChunkSource().save(false);
                })
                .thenWaitUntil(() -> {
                    if (backshop.getChunkSource().hasChunk(CAP_CHUNK.x, CAP_CHUNK.z)) {
                        throw new GameTestAssertException("Backshop chunk has not unloaded yet");
                    }
                })
                .thenExecute(() -> {
                    if (cache.getCapability() != null) {
                        helper.fail("the cache still resolved after its target chunk unloaded");
                    }
                    if (backshop.getChunkSource().hasChunk(CAP_CHUNK.x, CAP_CHUNK.z)) {
                        helper.fail("querying the cache force-loaded the target chunk - a bus would "
                            + "keep the whole Backshop resident");
                    }
                })
                .thenExecute(() -> WorkbayTickets.force(backshop, CAP_OWNER, CAP_CHUNK))
                .thenIdle(5)
                .thenExecute(() -> {
                    IItemHandler again = cache.getCapability();
                    if (again == null) {
                        helper.fail("the cache did not re-resolve after its target chunk loaded again");
                        return;
                    }
                    helper.assertValueEqual(again.getStackInSlot(0).getCount(), 5, "furnace input after reload");
                })
                .thenExecute(() -> WorkbayTickets.release(backshop, CAP_OWNER, CAP_CHUNK))
                .thenSucceed();
        });
    }
}
