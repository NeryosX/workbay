package com.neryos.workbay.gametests;

import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBAttachments;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.BayVisit;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A visit to a bay is a screen that opens and closes. SPEC.md §5.
 *
 * <p>The thing that can go wrong is worse than a lost item: a player sealed in a 3x3x3 bedrock
 * box is <em>lost</em>. So these assert every half of the visit - that the screen opens only after
 * the client can show it, that closing it is the way home, and that nobody stays in the Backshop
 * without one, whatever put them there.
 */
@ForEachTest(groups = "bay_visit")
public class BayVisitTests {

    private static final ResourceLocation MACHINE = ResourceLocation.parse("mekanism:enrichment_chamber");

    // ---------------------------------------------------------------- fixture

    /** One visitor, standing in bay 1 of their own Workbay, with a real Mekanism machine in it. */
    private record Visit(GameTestPlayer player, ServerLevel backshop, WorkbayRecord record,
        Vec3 from, float yRot, float xRot, WorkbayBlockEntity workbay) {

        BlockPos machine() {
            return BayGeometry.machinePos(record.bayColumn(), 0);
        }

        boolean screenOpen() {
            return player.containerMenu != player.inventoryMenu;
        }
    }

    private static Visit visit(ExtendedGameTestHelper helper, boolean chunkArrives) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
        WorkbayBlockEntity workbay = placeWorkbay(helper, helper.absolutePos(new BlockPos(0, 1, 0)), player);
        WorkbayRecord record = workbay.record().orElseThrow();
        ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
        WorkbayTickets.force(backshop, record.id(), record.bayColumn());

        Block machine = BuiltInRegistries.BLOCK.get(MACHINE);
        helper.assertFalse(machine == Blocks.AIR, MACHINE + " is not registered - check the "
            + "gametestRuntimeOnly Mekanism dependency in build.gradle");
        WorkbayMenu menu = new WorkbayMenu(1, player.getInventory(), workbay, WorkbayMenu.build(workbay, player, 0));
        menu.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(machine));
        menu.act(WorkbayAction.RACK, 0, Optional.empty());
        helper.assertFalse(backshop.getBlockState(BayGeometry.machinePos(record.bayColumn(), 0)).isAir(),
            "racking " + MACHINE + " left bay 1 empty");

        Visit visit = new Visit(player, backshop, record, player.position(), player.getYRot(),
            player.getXRot(), workbay);
        player.clearOutboundPackets();
        helper.assertTrue(BayVisit.enter(player, record, 0, net.minecraft.core.GlobalPos.of(
            helper.getLevel().dimension(), workbay.getBlockPos())), "entering bay 1 was refused");
        helper.assertTrue(player.level().dimension().equals(WorkbayDimensions.BACKSHOP),
            "the player is not in the Backshop after entering a bay");
        if (!chunkArrives) {
            // A client that never receives the chunk. The mock acknowledges every batch itself,
            // so the sender cannot be stalled, and the view is recomputed every tick, so it cannot
            // be emptied; what can be done is to take the chunk back out of the queue the moment
            // it is put in. It is queued when its holder is ready, a tick or more after the
            // teleport, never in the teleport's own tick, so Watch is the only place to catch it.
            ChunkPos bay = new ChunkPos(visit.machine());
            player.subscribe((net.neoforged.neoforge.event.level.ChunkWatchEvent.Watch event) -> {
                if (event.getPlayer() == player && event.getPos().equals(bay)) {
                    player.connection.chunkSender.dropChunk(player, bay);
                }
            });
        }
        return visit;
    }

    private static WorkbayBlockEntity placeWorkbay(ExtendedGameTestHelper helper, BlockPos pos,
        GameTestPlayer player) {
        ServerLevel level = helper.getLevel();
        level.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
        WBBlocks.WORKBAY.get().setPlacedBy(level, pos, level.getBlockState(pos), player,
            new ItemStack(WBBlocks.WORKBAY.get()));
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(pos);
        // Powered, because a real one has to be: SPEC.md §9 charges the buffer for every
        // link that is switched on and again for every move, so an unfed Workbay runs
        // nothing and every link on it reads NO_POWER. OPEN_ISSUES #72. A test that is not
        // about the bill pays it up front and says so here.
        workbay.energy().deserializeNBT(null,
            net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
        return workbay;
    }

    private static void assertHome(ExtendedGameTestHelper helper, Visit v) {
        helper.assertTrue(v.player().level().dimension().equals(helper.getLevel().dimension()),
            "the player is in " + v.player().level().dimension().location() + ", not back home");
        helper.assertTrue(v.player().position().distanceTo(v.from()) < 0.001,
            "the player came back to " + v.player().position() + " instead of " + v.from());
        helper.assertTrue(v.player().getYRot() == v.yRot() && v.player().getXRot() == v.xRot(),
            "the player came back facing " + v.player().getYRot() + "/" + v.player().getXRot()
                + " instead of " + v.yRot() + "/" + v.xRot());
        helper.assertFalse(BayVisit.isVisiting(v.player()),
            "the player is still recorded as a visitor after coming home");
    }

    private static boolean opensAScreen(Packet<?> packet) {
        return packet instanceof ClientboundOpenScreenPacket
            || packet instanceof ClientboundCustomPayloadPacket custom
                && custom.payload() instanceof AdvancedOpenScreenPayload;
    }

    // ------------------------------------------------------------------ tests

    /**
     * The disconnect, guarded. Opening the screen in the tick of the teleport sends the open packet
     * ahead of the chunk, and the client throws on a block entity it does not have. So the open
     * must be absent in that tick and, when it comes, must sit <em>after</em> the bay chunk in the
     * connection's own packet order - which is the one thing the client's lookup needs.
     */
    @GameTest
    @TestHolder(description = "Entering a bay opens the machine's own screen, and only after the bay chunk has been sent.")
    public static void enteringOpensTheMachinesScreenOnlyAfterItsChunkIsSent(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Visit v = visit(helper, true);
            helper.assertFalse(v.screenOpen(),
                "the screen opened in the same tick as the teleport, before the client had the chunk");

            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(v.screenOpen(), "no screen opened yet"))
                .thenExecute(() -> {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    List<Packet<?>> sent = v.player().getOutboundPackets((Class) Packet.class).toList();
                    ChunkPos bay = new ChunkPos(v.machine());
                    int chunkAt = -1;
                    int openAt = -1;
                    for (int i = 0; i < sent.size(); i++) {
                        Packet<?> p = sent.get(i);
                        if (chunkAt < 0 && p instanceof ClientboundLevelChunkWithLightPacket chunk
                            && chunk.getX() == bay.x && chunk.getZ() == bay.z) {
                            chunkAt = i;
                        }
                        if (openAt < 0 && opensAScreen(p)) {
                            openAt = i;
                        }
                    }
                    helper.assertTrue(chunkAt >= 0, "the bay chunk was never sent to the player");
                    helper.assertTrue(openAt >= 0, "no open-screen packet was sent");
                    helper.assertTrue(chunkAt < openAt, "the open-screen packet (#" + openAt
                        + ") went out before the bay chunk (#" + chunkAt + ") - that is the disconnect");

                    // And they stand in air, within reach: the corner column really is free.
                    BlockPos feet = v.player().blockPosition();
                    helper.assertTrue(v.backshop().getBlockState(feet).isAir()
                            && v.backshop().getBlockState(feet.above()).isAir(),
                        "the player is inside the shell at " + feet);
                    helper.assertTrue(!v.backshop().getBlockState(feet.below()).isAir(),
                        "nothing under the player's feet");
                    helper.assertTrue(feet.getCenter().distanceTo(v.machine().getCenter()) < 4.5,
                        "the machine is out of reach");
                })
                .thenSucceed();
        });
    }

    @GameTest
    @TestHolder(description = "Closing the machine's screen puts the visitor back exactly where they stood, facing the same way.")
    public static void closingTheScreenPutsYouBackWhereYouStood(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Visit v = visit(helper, true);
            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(v.screenOpen(), "no screen opened yet"))
                .thenExecute(() -> v.player().closeContainer())
                .thenIdle(BayVisit.GRACE + 2)
                .thenExecute(() -> assertHome(helper, v))
                .thenSucceed();
        });
    }

    /**
     * Coming back means coming back to <b>where you were</b>, and where you were was the Workbay
     * screen on the bay you entered - not standing on the ground with nothing open. A visit is
     * supposed to read as a screen that opened and closed over the top of the one you had.
     */
    @GameTest
    @TestHolder(description = "Leaving a bay puts the Workbay screen back up on the bay you entered.")
    public static void leavingABayGivesTheWorkbayScreenBack(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Visit v = visit(helper, true);
            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(v.screenOpen(), "no screen opened yet"))
                .thenExecute(() -> v.player().closeContainer())
                // The trip home, and then the wait the client needs before a screen will stick.
                .thenIdle(BayVisit.GRACE + BayVisit.REOPEN_DELAY + 4)
                .thenExecute(() -> {
                    assertHome(helper, v);
                    helper.assertTrue(v.player().containerMenu instanceof WorkbayMenu,
                        "the visitor came back to no screen at all; the Workbay screen they left "
                            + "from should be up again");
                })
                .thenSucceed();
        });
    }

    /**
     * The trap. Mekanism's Digital Miner config, its multiblock stats tabs and its back button all
     * close one container and open another; a return fired on "no screen" would eject the player
     * exactly when they open the settings this feature exists for. Two shapes: the switch inside
     * one packet handler ({@code openMenu} closes the old one itself), and a close and an open a
     * tick apart, which is what the grace is for.
     */
    @GameTest
    @TestHolder(description = "Switching from one screen to another inside the bay is not leaving.")
    public static void switchingScreensInsideTheBayIsNotLeaving(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Visit v = visit(helper, true);
            SimpleMenuProvider next = new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory), Component.empty());
            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(v.screenOpen(), "no screen opened yet"))
                .thenExecute(() -> v.player().openMenu(next))
                .thenIdle(BayVisit.GRACE + 2)
                .thenExecute(() -> {
                    helper.assertTrue(v.player().level().dimension().equals(WorkbayDimensions.BACKSHOP),
                        "an atomic screen switch sent the visitor home");
                    helper.assertTrue(v.player().containerMenu instanceof ChestMenu, "the new screen is not up");
                })
                .thenExecute(() -> v.player().closeContainer())
                .thenIdle(1)
                .thenExecute(() -> v.player().openMenu(next))
                .thenIdle(BayVisit.GRACE + 2)
                .thenExecute(() -> {
                    helper.assertTrue(v.player().level().dimension().equals(WorkbayDimensions.BACKSHOP),
                        "a close followed by an open one tick later sent the visitor home");
                    helper.assertTrue(v.player().containerMenu instanceof ChestMenu, "the new screen is not up");
                })
                .thenExecute(() -> BayVisit.leave(v.player()))
                .thenSucceed();
        });
    }

    /** Over the walls, not through them: whatever moved them, they are out of the bay, so they go home. */
    @GameTest
    @TestHolder(description = "A visitor moved out of their bay by anything is put back where they came from.")
    public static void aVisitorMovedOutOfTheBayIsSentHome(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Visit v = visit(helper, true);
            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(v.screenOpen(), "no screen opened yet"))
                .thenExecute(() -> {
                    Vec3 at = v.player().position();
                    v.player().teleportTo(v.backshop(), at.x + 20, at.y + 40, at.z, Set.of(),
                        v.player().getYRot(), v.player().getXRot());
                })
                .thenIdle(2)
                .thenExecute(() -> assertHome(helper, v))
                .thenSucceed();
        });
    }

    @GameTest
    @TestHolder(description = "Someone in the Backshop with no record of a visit is sent to spawn, screen or no screen.")
    public static void aStowawayInTheBackshopIsSentToSpawn(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Visit v = visit(helper, true);
            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(v.screenOpen(), "no screen opened yet"))
                .thenExecute(() -> v.player().removeData(WBAttachments.BAY_RETURN.get()))
                .thenIdle(2)
                .thenExecute(() -> helper.assertFalse(
                    v.player().level().dimension().equals(WorkbayDimensions.BACKSHOP),
                    "a player with no way home is still in the Backshop"))
                .thenSucceed();
        });
    }

    @GameTest
    @TestHolder(description = "The Backshop refuses entry by any route that is not a bay visit.")
    public static void nothingEntersTheBackshopByAnotherRoute(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            player.teleportTo(backshop, 100.5, 20.0, 100.5, Set.of(), 0.0F, 0.0F);
            helper.assertFalse(player.level().dimension().equals(WorkbayDimensions.BACKSHOP),
                "a plain teleport got a player into the Backshop");
            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "An empty bay cannot be entered: there is no screen to open, so there is nothing to visit.")
    public static void anEmptyBayCannotBeEntered(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, helper.absolutePos(new BlockPos(0, 1, 0)), player);
            WorkbayRecord record = workbay.record().orElseThrow();
            helper.assertFalse(BayVisit.enter(player, record, 0, null), "an empty bay was entered");
            helper.assertTrue(player.level().dimension().equals(helper.getLevel().dimension()),
                "the player left their dimension for an empty bay");
            helper.assertFalse(BayVisit.isVisiting(player), "a refused entry left a visit record");
            helper.succeed();
        });
    }

    /** The chunk never arrives. Home, with a message, not a hang. */
    @GameTest(timeoutTicks = BayVisit.OPEN_TIMEOUT + 60)
    @TestHolder(description = "A visitor whose client never gets the bay chunk is sent home instead of left waiting.")
    public static void theChunkWaitGivesUpAndSendsYouHome(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Visit v = visit(helper, false);
            helper.startSequence()
                .thenIdle(BayVisit.OPEN_TIMEOUT - 2)
                .thenExecute(() -> helper.assertTrue(
                    v.player().level().dimension().equals(WorkbayDimensions.BACKSHOP)
                        && !v.screenOpen(),
                    "the visit did not wait for the chunk at all"))
                .thenIdle(6)
                .thenExecute(() -> assertHome(helper, v))
                .thenSucceed();
        });
    }
}
