package com.neryos.workbay.gametests;

import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.content.connector.ConnectorBlock;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.menu.ConnectorMenu;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.network.ActionPacket;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.Optional;
import java.util.UUID;

/**
 * <b>A locked Workbay is closed to everyone but its owner.</b> Not look-only: nothing opens,
 * nothing is read, and the refusal says why. The night of 2026-09-11 made every Workbay born locked
 * and still let a stranger open the BAYS screen to look; every door here was walked through as a
 * stranger and watched red before {@link WorkbayRecord#admits} closed it.
 *
 * <p>Every refusal is paired with the owner doing the same thing and being served, so a fixture
 * that never reaches the door cannot pass as a refusal.
 */
@ForEachTest(groups = "lock")
public class LockTests {

    /**
     * The key of the last action-bar line the server sent this player, or "" for none.
     * {@code displayClientMessage(.., true)} is a system chat packet with {@code overlay} set, not
     * a {@code SetActionBarText} one.
     */
    static String lastRefusal(GameTestPlayer player) {
        return actionBar(player).reduce((a, b) -> b).orElse("");
    }

    static java.util.stream.Stream<String> actionBar(GameTestPlayer player) {
        return player.getOutboundPackets(ClientboundSystemChatPacket.class)
            .filter(ClientboundSystemChatPacket::overlay)
            .map(packet -> packet.content().getContents() instanceof TranslatableContents t
                ? t.getKey() : packet.content().getString());
    }

    private static void rightClick(ServerLevel level, BlockPos pos, GameTestPlayer player) {
        level.getBlockState(pos).useWithoutItem(level, player,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static WorkbayBlockEntity placeWorkbay(ExtendedGameTestHelper helper, BlockPos pos,
        GameTestPlayer owner) {
        ServerLevel level = helper.getLevel();
        level.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
        WBBlocks.WORKBAY.get().setPlacedBy(level, pos, level.getBlockState(pos), owner,
            new ItemStack(WBBlocks.WORKBAY.get()));
        WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(pos);
        workbay.energy().deserializeNBT(null,
            net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
        owner.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (!workbay.record().orElseThrow().locked()) {
            helper.fail("a fresh Workbay is not locked, so nothing here refuses anything");
        }
        return workbay;
    }

    /** A stranger standing at the block, close enough that reach is not what refuses them. */
    private static GameTestPlayer strangerAt(ExtendedGameTestHelper helper, BlockPos pos) {
        GameTestPlayer stranger = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
        stranger.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return stranger;
    }

    /**
     * A menu this player holds without having been let in -- what a client that kept a screen
     * open across the owner's Lock, or forged one, hands the packet handler.
     */
    private static WorkbayMenu held(GameTestPlayer player, WorkbayBlockEntity workbay, int id) {
        WorkbayMenu menu = new WorkbayMenu(id, player.getInventory(), workbay,
            WorkbayMenu.build(workbay, player, 0));
        player.containerMenu = menu;
        return menu;
    }

    /** The wire path: {@link ActionPacket#handle} with a context that knows only the player. */
    private static void send(GameTestPlayer from, int containerId, WorkbayAction action, long arg,
        Optional<UUID> link) {
        IPayloadContext context = (IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(
            IPayloadContext.class.getClassLoader(), new Class<?>[] { IPayloadContext.class },
            (proxy, method, args) -> method.getName().equals("player") ? from : null);
        ActionPacket.handle(new ActionPacket(containerId, action, arg, link, Optional.empty(),
            false), context);
    }

    private static void unlock(WorkbayBlockEntity workbay, GameTestPlayer owner) {
        new WorkbayMenu(1, owner.getInventory(), workbay, WorkbayMenu.build(workbay, owner, 0))
            .act(WorkbayAction.TOGGLE_LOCK, 0, Optional.empty());
    }

    // ------------------------------------------------------------------ the block

    @GameTest
    @TestHolder(description = "A stranger right-clicking a locked Workbay gets the message and no "
        + "screen; the owner gets the screen; unlocked, the stranger gets in.")
    public static void aLockedWorkbayOpensForNobodyButItsOwner(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, owner);
            GameTestPlayer stranger = strangerAt(helper, pos);

            rightClick(level, pos, stranger);
            helper.assertTrue(stranger.containerMenu == stranger.inventoryMenu,
                "a stranger opened a locked Workbay's screen");
            helper.assertValueEqual(lastRefusal(stranger), WorkbayLang.messageKey("locked"),
                "what the stranger was told");

            rightClick(level, pos, owner);
            helper.assertTrue(owner.containerMenu instanceof WorkbayMenu,
                "the owner could not open their own locked Workbay, so the refusal proves nothing");
            ((WorkbayMenu) owner.containerMenu).act(WorkbayAction.TOGGLE_LOCK, 0, Optional.empty());
            owner.closeContainer();
            helper.assertFalse(workbay.record().orElseThrow().locked(), "the owner could not unlock");

            // Shared: the owner chose to, and the stranger is let in.
            rightClick(level, pos, stranger);
            helper.assertTrue(stranger.containerMenu instanceof WorkbayMenu,
                "an unlocked Workbay refused a stranger");

            // And locking again while they are inside closes their screen on the next tick, the way
            // breaking the block would: stillValid asks the lock.
            rightClick(level, pos, owner);
            ((WorkbayMenu) owner.containerMenu).act(WorkbayAction.TOGGLE_LOCK, 0, Optional.empty());
            helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> helper.assertTrue(
                    stranger.containerMenu == stranger.inventoryMenu,
                    "the owner locked and the stranger's screen stayed open"))
                .thenExecute(() -> helper.assertTrue(owner.containerMenu instanceof WorkbayMenu,
                    "locking closed the owner's own screen"))
                .thenSucceed();
        });
    }

    /**
     * The world gesture with a Connector in hand. Refusing with FAIL let the item's own use run
     * next: the Connector was placed against the Workbay and "isn't paired yet" wrote over the
     * refusal. OPEN_ISSUES #107.
     */
    @GameTest
    @TestHolder(description = "A stranger's Connector click on a locked Workbay says locked, once, "
        + "and places nothing.")
    public static void aStrangersConnectorClickSaysLockedAndPlacesNothing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 4, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            placeWorkbay(helper, pos, owner);
            // Both beside the block, not in it: a body standing where the Connector would land is
            // what would stop the placement, and this test is about the lock stopping it.
            owner.moveTo(pos.getX() - 0.5, pos.getY(), pos.getZ() + 0.5);
            GameTestPlayer stranger = strangerAt(helper, pos.east());

            ItemStack connector = new ItemStack(WBBlocks.CONNECTOR.get());
            stranger.setItemInHand(InteractionHand.MAIN_HAND, connector);
            // The whole server-side click, not the block's hook alone: this is the path that ran
            // the item's own use after the block said no.
            stranger.gameMode.useItemOn(stranger, level, connector, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false));
            helper.assertTrue(level.getBlockState(pos.above()).isAir(),
                "a stranger's refused Connector was placed on the Workbay anyway");
            helper.assertValueEqual(lastRefusal(stranger), WorkbayLang.messageKey("locked"),
                "the last thing the stranger was told");
            helper.assertValueEqual(actionBar(stranger).count(), 1L,
                "action-bar lines for one refused click");
            helper.succeed();
        });
    }

    // -------------------------------------------------------------- the Connector

    @GameTest
    @TestHolder(description = "A locked network's Connector opens no rename panel for a stranger and "
        + "takes no rename from one; an unpaired one still says unpaired.")
    public static void aLockedNetworksConnectorIsClosedToStrangers(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 4, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 3));
            BlockPos connectorPos = chest.above();
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, owner);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

            ItemStack paired = new ItemStack(WBBlocks.CONNECTOR.get());
            WorkbayBlock.pair(paired, workbay.record().orElseThrow(),
                GlobalPos.of(level.dimension(), workbayPos));
            BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
                .setValue(ConnectorBlock.FACING, Direction.DOWN);
            level.setBlock(connectorPos, state, Block.UPDATE_ALL);
            WBBlocks.CONNECTOR.get().setPlacedBy(level, connectorPos, state, owner, paired);
            GlobalPos here = GlobalPos.of(level.dimension(), connectorPos);
            if (workbay.connectorAt(here).isEmpty()) {
                helper.fail("the owner's paired Connector did not register");
                return;
            }

            GameTestPlayer stranger = strangerAt(helper, connectorPos);
            rightClick(level, connectorPos, stranger);
            helper.assertTrue(stranger.containerMenu == stranger.inventoryMenu,
                "a stranger opened the rename panel of a locked network's Connector");
            helper.assertValueEqual(lastRefusal(stranger), WorkbayLang.messageKey("locked"),
                "what the stranger was told at the Connector");

            // The panel's packet with no panel: a forged rename.
            new ConnectorMenu(7, new ConnectorMenu.View(connectorPos, "", "", 0, true))
                .act(WorkbayAction.SET_CONNECTOR_NAME, "mine now", stranger);
            helper.assertValueEqual(workbay.connectorAt(here).orElseThrow().name(), "",
                "the Connector's name after a stranger's forged rename");

            // A stranger's own unpaired Connector says what is wrong with it, not "locked".
            BlockPos theirs = helper.absolutePos(new BlockPos(3, 1, 1));
            level.setBlock(theirs, state, Block.UPDATE_ALL);
            level.setBlock(theirs.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            rightClick(level, theirs, stranger);
            helper.assertValueEqual(lastRefusal(stranger),
                WorkbayLang.messageKey("connector_unpaired"),
                "what the stranger was told at an unpaired Connector");

            // The owner opens and renames.
            owner.moveTo(connectorPos.getX() + 0.5, connectorPos.getY(), connectorPos.getZ() + 0.5);
            rightClick(level, connectorPos, owner);
            if (!(owner.containerMenu instanceof ConnectorMenu panel)) {
                helper.fail("the owner could not open their own Connector's panel");
                return;
            }
            panel.act(WorkbayAction.SET_CONNECTOR_NAME, "Ore feed", owner);
            helper.assertValueEqual(workbay.connectorAt(here).orElseThrow().name(), "Ore feed",
                "the Connector's name after the owner's rename");
            helper.succeed();
        });
    }

    /**
     * A Connector paired to a locked network is a door into it wherever it is placed: placing one
     * registers it on that network. A stranger holding one -- dropped, traded -- places nothing.
     */
    @GameTest
    @TestHolder(description = "A stranger cannot place a Connector paired to a locked network; the owner can.")
    public static void aStrangerCannotPlaceAConnectorPairedToALockedNetwork(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 4, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, owner);
            BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 3));
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            GameTestPlayer stranger = strangerAt(helper, chest.east());

            ItemStack paired = new ItemStack(WBBlocks.CONNECTOR.get());
            WorkbayBlock.pair(paired, workbay.record().orElseThrow(),
                GlobalPos.of(level.dimension(), workbayPos));
            BlockHitResult onChest = new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0.5, 0),
                Direction.UP, chest, false);
            // Sneaking, as a player placing against a container does; the chest opens otherwise.
            stranger.setShiftKeyDown(true);
            owner.setShiftKeyDown(true);

            stranger.setItemInHand(InteractionHand.MAIN_HAND, paired.copy());
            stranger.gameMode.useItemOn(stranger, level, stranger.getMainHandItem(),
                InteractionHand.MAIN_HAND, onChest);
            helper.assertTrue(level.getBlockState(chest.above()).isAir(),
                "a stranger placed a Connector paired to somebody else's locked network");
            helper.assertValueEqual(lastRefusal(stranger), WorkbayLang.messageKey("locked"),
                "what the stranger was told");

            owner.moveTo(chest.getX() + 1.5, chest.getY(), chest.getZ() + 0.5);
            owner.setItemInHand(InteractionHand.MAIN_HAND, paired);
            owner.gameMode.useItemOn(owner, level, owner.getMainHandItem(),
                InteractionHand.MAIN_HAND, onChest);
            helper.assertTrue(level.getBlockState(chest.above()).is(WBBlocks.CONNECTOR.get()),
                "the owner could not place their own paired Connector");
            helper.succeed();
        });
    }

    // ----------------------------------------------------------- the remote screen

    @GameTest
    @TestHolder(description = "A stranger holding a locked Workbay's menu gets no remote machine "
        + "screen and no bay trip; the owner gets the screen.")
    public static void aStrangerGetsNoRemoteScreenFromALockedWorkbay(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, owner);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FURNACE));
            held(owner, workbay, 1).act(WorkbayAction.RACK, 0, Optional.empty());
            if (backshop.getBlockState(BayGeometry.machinePos(record.bayColumn(), 0)).isAir()) {
                helper.fail("the owner could not rack a furnace");
                return;
            }
            owner.closeContainer();

            GameTestPlayer stranger = strangerAt(helper, pos);
            WorkbayMenu theirs = held(stranger, workbay, 2);
            theirs.act(WorkbayAction.ENTER_BAY, 1, Optional.empty());
            helper.assertTrue(stranger.containerMenu == stranger.inventoryMenu
                || stranger.containerMenu == theirs,
                "a stranger opened a locked Workbay's machine screen remotely");
            helper.assertFalse(stranger.level().dimension().equals(WorkbayDimensions.BACKSHOP),
                "a stranger was sent into a locked Workbay's bay");
            helper.assertValueEqual(lastRefusal(stranger), WorkbayLang.messageKey("locked"),
                "what the stranger was told");

            held(owner, workbay, 3).act(WorkbayAction.ENTER_BAY, 1, Optional.empty());
            helper.assertTrue(owner.containerMenu != owner.inventoryMenu
                && !(owner.containerMenu instanceof WorkbayMenu),
                "the owner got no remote screen, so the refusal above proves nothing");
            owner.closeContainer();
            helper.succeed();
        });
    }

    // ------------------------------------------------------------ the raw packet

    /**
     * The packet handler with no screen behind it. With no menu the packet lands nowhere; with a
     * menu the stranger should never have been given, every action is refused at the one door --
     * including Transfer, which used to run before the record check and let a stranger move their
     * own network into the owner's block, putting the owner's to sleep.
     */
    @GameTest
    @TestHolder(description = "A stranger's action packet with no menu does nothing, and with a held "
        + "menu is refused -- Transfer included.")
    public static void aStrangersRawPacketIsRefusedAtTheOneDoor(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, owner);
            WorkbayRecord ownersNetwork = workbay.record().orElseThrow();

            // The stranger owns a network of their own, standing on a second block.
            GameTestPlayer stranger = strangerAt(helper, pos);
            BlockPos theirPos = helper.absolutePos(new BlockPos(3, 1, 1));
            WorkbayBlockEntity theirBlock = placeWorkbay(helper, theirPos, stranger);
            UUID theirNetwork = theirBlock.record().orElseThrow().id();
            stranger.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

            // No menu: nowhere to land.
            send(stranger, 5, WorkbayAction.SET_BAY_NAME, 0, Optional.empty());
            send(stranger, 5, WorkbayAction.TRANSFER_NETWORK, 0, Optional.of(theirNetwork));
            helper.assertValueEqual(workbay.workbayId().orElse(null), ownersNetwork.id(),
                "the network on the owner's block after a stranger's menuless packets");
            helper.assertValueEqual(lastRefusal(stranger), "",
                "a menuless packet was answered at all");

            // A held menu: refused, and told.
            held(stranger, workbay, 5);
            send(stranger, 5, WorkbayAction.SET_BAY_NAME, 0, Optional.empty());
            send(stranger, 5, WorkbayAction.TRANSFER_NETWORK, 0, Optional.of(theirNetwork));
            helper.assertValueEqual(workbay.workbayId().orElse(null), ownersNetwork.id(),
                "the network on the owner's block after a stranger's Transfer");
            helper.assertTrue(theirBlock.record().isPresent(),
                "a stranger's Transfer into a locked block emptied their own block");
            helper.assertValueEqual(lastRefusal(stranger), WorkbayLang.messageKey("locked"),
                "what the stranger was told");

            // The owner's own Transfer of a second network lands: the door is the lock, not the
            // action. Their second network is minted onto a third block first.
            BlockPos otherPos = helper.absolutePos(new BlockPos(3, 1, 0));
            WorkbayBlockEntity otherBlock = placeWorkbay(helper, otherPos, owner);
            UUID second = otherBlock.record().orElseThrow().id();
            owner.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            held(owner, workbay, 6);
            send(owner, 6, WorkbayAction.TRANSFER_NETWORK, 0, Optional.of(second));
            helper.assertValueEqual(workbay.workbayId().orElse(null), second,
                "the network on the owner's block after their own Transfer, so the refusal above "
                    + "proves nothing if this is wrong");
            helper.succeed();
        });
    }

    // -------------------------------------------------------------- the message

    /**
     * An owner-only action on an <em>unlocked</em> Workbay used to say "This Workbay is locked",
     * which was not true. OPEN_ISSUES #107.
     */
    @GameTest
    @TestHolder(description = "On a shared Workbay a guest reaching for the lock is told owner-only, not locked.")
    public static void anOwnerOnlyRefusalOnASharedWorkbaySaysWhy(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, owner);
            unlock(workbay, owner);

            GameTestPlayer guest = strangerAt(helper, pos);
            rightClick(helper.getLevel(), pos, guest);
            if (!(guest.containerMenu instanceof WorkbayMenu menu)) {
                helper.fail("a shared Workbay did not open for a guest");
                return;
            }
            menu.act(WorkbayAction.TOGGLE_LOCK, 0, Optional.empty());
            helper.assertFalse(workbay.record().orElseThrow().locked(), "a guest locked it");
            helper.assertValueEqual(lastRefusal(guest), WorkbayLang.messageKey("owner_only"),
                "what a guest reaching for the lock was told");

            menu.act(WorkbayAction.INSTALL_UPGRADE, 0, Optional.empty());
            helper.assertValueEqual(lastRefusal(guest), WorkbayLang.messageKey("owner_only"),
                "what a guest reaching for an upgrade was told");
            helper.succeed();
        });
    }
}
