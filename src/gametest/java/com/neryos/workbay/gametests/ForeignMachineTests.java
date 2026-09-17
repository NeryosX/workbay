package com.neryos.workbay.gametests;

import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.UUID;

/**
 * The same proof as {@link HostedMachineTests}, but with a machine nobody here wrote. A vanilla
 * furnace shares Minecraft's own block entity plumbing; a Mekanism machine brings its own
 * registration, its own capability providers, an owner UUID recorded in {@code setPlacedBy} and its
 * own serialization, and those are what the mod's central claim actually rests on.
 *
 * <p>Mekanism is {@code gametestRuntimeOnly}, so nothing here compiles against it. Everything goes
 * through the block registry and the standard capability interfaces - which is also exactly how
 * Workbay reaches a hosted machine, so this tests the real path rather than a friendlier one.
 *
 * <p>These tests earned SPEC.md §10 its insert order. With only {@code setBlock} and
 * {@code setPlacedBy}, a Mekanism machine exposes handlers on <em>none</em> of its six faces and
 * only on the null side - the one side §9 forbids, because Mekanism's null-side handler is
 * read-only and fails silently. Add steps 2 and 3 and all six faces answer. That is the difference
 * between hosting Mekanism and a bay that reports "no ports" for every machine in the pack, and
 * {@link #shortInsertOrderLeavesTheMachineUnreachable} is here to stop anyone tidying those two
 * lines away again.
 */
@ForEachTest(groups = "foreign_machine")
public class ForeignMachineTests {

    private static final ResourceLocation MACHINE = ResourceLocation.parse("mekanism:enrichment_chamber");

    private static final UUID STATE_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ba81");
    private static final UUID FACES_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ba82");
    private static final UUID SHORT_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ba83");

    private static final ChunkPos STATE_CHUNK = new ChunkPos(2048, 192);
    private static final ChunkPos FACES_CHUNK = new ChunkPos(2048, 256);
    private static final ChunkPos SHORT_CHUNK = new ChunkPos(2048, 320);

    private static ServerLevel backshop(ExtendedGameTestHelper helper) {
        ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
        helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
        return backshop;
    }

    private static Block foreignMachine(ExtendedGameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(MACHINE);
        if (block == Blocks.AIR) {
            helper.fail(MACHINE + " is not registered. This test exists to prove Workbay hosts other "
                + "mods' machines, so a missing partner mod is a failure, never a skip. Check the "
                + "gametestRuntimeOnly Mekanism dependency in build.gradle.");
        }
        return block;
    }

    /**
     * SPEC.md §10's insert order. With {@code fullOrder} false it stops after
     * {@code setBlock} + {@code setPlacedBy}, which is what the order looked like before these
     * tests, so the difference the two extra steps make is measurable rather than asserted.
     */
    private static BlockPos placeForeign(ExtendedGameTestHelper helper, ServerLevel backshop,
        UUID owner, ChunkPos chunk, Block machine, boolean fullOrder) {
        WorkbayTickets.force(backshop, owner, chunk);
        BlockPos pos = chunk.getMiddleBlockPosition(16);
        backshop.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        BlockState state = machine.defaultBlockState();
        backshop.setBlock(pos, state, Block.UPDATE_ALL);

        // The test framework's own mock, not GameTestHelper#makeMockServerPlayerInLevel: that one
        // has a connection the framework's CLIENT_SYNC feature then sends test-status packets down,
        // which throws straight out of the server tick loop.
        GameTestPlayer placer = helper.makeTickingMockServerPlayerInLevel(GameType.CREATIVE);
        ItemStack stack = new ItemStack(machine);
        try {
            if (fullOrder) {
                BlockItem.updateCustomBlockEntityTag(backshop, placer, pos, stack);
                BlockEntity placed = backshop.getBlockEntity(pos);
                if (placed != null) {
                    placed.applyComponentsFromItemStack(stack);
                }
            }
            // Always: this is what records the owner UUID every Mekanism machine keeps, and
            // setBlock alone never calls it.
            machine.setPlacedBy(backshop, pos, state, placer, stack);
        } catch (Exception e) {
            helper.fail("the SPEC.md §10 insert order threw for " + MACHINE + ": " + e);
        }
        backshop.invalidateCapabilities(pos);
        return pos;
    }

    /** Every capability that resolves, as a readable line for a failure message. */
    private static String describeFaces(ServerLevel level, BlockPos pos) {
        StringBuilder found = new StringBuilder();
        for (Direction side : Direction.values()) {
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null) {
                found.append(side).append(":item ");
            }
            if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null) {
                found.append(side).append(":energy ");
            }
        }
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) {
            found.append("NULLSIDE:item ");
        }
        if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) != null) {
            found.append("NULLSIDE:energy ");
        }
        return found.toString().trim();
    }

    private static int realFaceCount(ServerLevel level, BlockPos pos) {
        int n = 0;
        for (Direction side : Direction.values()) {
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null
                || level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * A foreign machine, hosted in the Backshop, is reachable exactly the way a bus will reach it:
     * a real face, never the null side, and energy that actually moves.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A hosted Mekanism machine exposes real faces and takes energy through one.")
    public static void foreignMachineIsReachableThroughRealFaces(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            Block machine = foreignMachine(helper);
            BlockPos pos = placeForeign(helper, backshop, FACES_OWNER, FACES_CHUNK, machine, true);

            BlockEntity be = backshop.getBlockEntity(pos);
            helper.assertNotNull(be, "no block entity for " + MACHINE + " in the Backshop");
            if (!be.getClass().getName().startsWith("mekanism.")) {
                helper.fail("expected a Mekanism block entity, got " + be.getClass().getName());
            }

            if (realFaceCount(backshop, pos) == 0) {
                helper.fail("the hosted " + MACHINE + " exposes nothing on any of its six faces, so "
                    + "a bay would have nothing to connect to. faces=[" + describeFaces(backshop, pos) + "]");
                return;
            }

            // SPEC.md §9's bind step: simulate on every real face, take the first that accepts.
            Direction accepting = null;
            for (Direction side : Direction.values()) {
                IEnergyStorage e = backshop.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
                if (e != null && e.canReceive() && e.receiveEnergy(1000, true) > 0) {
                    accepting = side;
                    break;
                }
            }
            if (accepting == null) {
                helper.fail("no face of the hosted " + MACHINE + " would accept energy, so a bay "
                    + "cannot power it. faces=[" + describeFaces(backshop, pos) + "]");
                return;
            }

            IEnergyStorage energy = backshop.getCapability(Capabilities.EnergyStorage.BLOCK, pos, accepting);
            int taken = energy.receiveEnergy(20_000, false);
            if (taken <= 0 || energy.getEnergyStored() <= 0) {
                helper.fail("a simulated insert on " + accepting + " succeeded but the real one moved "
                    + taken + " FE, leaving " + energy.getEnergyStored() + " stored");
            }

            WorkbayTickets.release(backshop, FACES_OWNER, FACES_CHUNK);
            helper.succeed();
        });
    }

    /**
     * The reason SPEC.md §10 steps 2 and 3 exist, kept executable. Place the same machine without
     * {@code updateCustomBlockEntityTag} and {@code applyComponentsFromItemStack} and it answers on
     * no real face at all - a bay would call it inert and every Mekanism machine in the pack would
     * look broken.
     *
     * <p>If this ever goes green-by-accident because the short order started working, delete it. If
     * it fails because the short order still leaves a face exposed, the two steps may have become
     * unnecessary. Either way the order is the thing under test, not Mekanism.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "Skipping SPEC §10 steps 2 and 3 leaves a hosted Mekanism machine unreachable.")
    public static void shortInsertOrderLeavesTheMachineUnreachable(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            Block machine = foreignMachine(helper);
            BlockPos pos = placeForeign(helper, backshop, SHORT_OWNER, SHORT_CHUNK, machine, false);

            helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    if (realFaceCount(backshop, pos) != 0) {
                        helper.fail("the short insert order now exposes real faces: ["
                            + describeFaces(backshop, pos) + "]. SPEC.md §10 steps 2 and 3 may no "
                            + "longer be load-bearing - check, then update the spec and delete this test.");
                    }
                })
                .thenExecute(() -> WorkbayTickets.release(backshop, SHORT_OWNER, SHORT_CHUNK))
                .thenSucceed();
        });
    }

    /**
     * A machine from another mod keeps its own state across a real chunk save and load. Compared as
     * serialized NBT rather than through any Mekanism API, so it covers everything the machine chose
     * to persist - owner, security, energy, side configuration - without compiling against a line
     * of it.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A hosted Mekanism machine keeps its own state across a Backshop chunk save and load.")
    public static void foreignMachineStateSurvivesSaveAndLoad(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            Block machine = foreignMachine(helper);
            BlockPos pos = placeForeign(helper, backshop, STATE_OWNER, STATE_CHUNK, machine, true);

            BlockEntity be = backshop.getBlockEntity(pos);
            helper.assertNotNull(be, "no block entity for " + MACHINE + " in the Backshop");

            // Give it something of its own to remember, through the same faces a bus would use.
            for (Direction side : Direction.values()) {
                IEnergyStorage e = backshop.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
                if (e != null && e.receiveEnergy(20_000, false) > 0) {
                    break;
                }
            }

            CompoundTag[] frozen = { null };

            helper.startSequence()
                .thenIdle(5) // let the machine run a few of its own ticks first
                .thenExecute(() -> {
                    WorkbayTickets.release(backshop, STATE_OWNER, STATE_CHUNK);
                    backshop.getChunkSource().save(false);
                })
                .thenWaitUntil(() -> {
                    if (!be.isRemoved()) {
                        throw new GameTestAssertException("Backshop chunk has not unloaded yet");
                    }
                })
                .thenExecute(() -> frozen[0] = be.saveWithFullMetadata(backshop.registryAccess()))
                .thenExecute(() -> {
                    backshop.getChunk(STATE_CHUNK.x, STATE_CHUNK.z);
                    BlockEntity reloaded = backshop.getBlockEntity(pos);
                    if (reloaded == be) {
                        helper.fail("the same block entity object came back - the chunk never really "
                            + "unloaded, so this proves nothing about saving");
                        return;
                    }
                    helper.assertNotNull(reloaded, "the hosted " + MACHINE + " did not come back");

                    // A block entity with nothing to say writes id/x/y/z and little else. If the tag
                    // were that small, matching tags would prove nothing about a modded machine's
                    // own fields, which is the whole reason Mekanism is here rather than a chest.
                    if (frozen[0].size() < 6) {
                        helper.fail("the hosted machine only persisted " + frozen[0].size()
                            + " tags (" + frozen[0].getAllKeys() + "), too little to be a real test");
                        return;
                    }
                    CompoundTag after = reloaded.saveWithFullMetadata(backshop.registryAccess());
                    if (!frozen[0].equals(after)) {
                        helper.fail("the hosted " + MACHINE + " came back different. saved=" + frozen[0]
                            + " loaded=" + after);
                    }
                })
                .thenExecute(() -> WorkbayTickets.release(backshop, STATE_OWNER, STATE_CHUNK))
                .thenSucceed();
        });
    }
}
