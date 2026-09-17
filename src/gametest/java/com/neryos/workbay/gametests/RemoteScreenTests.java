package com.neryos.workbay.gametests;

import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.remote.RemoteScreens;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.Optional;

/**
 * A hosted machine's screen, opened without going anywhere. SPEC.md §5.
 *
 * <p>Only half of that is testable here, and it is the half worth guarding: a gametest has no
 * client, so it can prove the server opened a screen and kept it open, never that the client drew
 * one. The client half - a menu resolving a block entity in a dimension it was never sent - is
 * {@code LevelMixin}, and only a real client can say whether it holds.
 *
 * <p>A <b>vanilla furnace</b> on purpose. Its {@code stillValid} is the standard one every mod
 * inherits when it does not write its own, and it lands exactly on {@code canInteractWithBlock} -
 * so this measures the injection rather than one mod's opinion of it.
 */
@ForEachTest(groups = "remote_screen")
public class RemoteScreenTests {

    @GameTest
    @TestHolder(description = "A machine's own screen opens where the player stands, and survives "
        + "the distance check that would otherwise close it on the next tick.")
    public static void aRemoteScreenSurvivesTheDistanceCheck(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Racked r = rack(helper);
            GameTestPlayer player = r.player();
            BlockPos machine = r.machine();
            ServerLevel backshop = r.backshop();
            helper.assertTrue(RemoteScreens.open(player, backshop, machine),
                "the furnace in the bay opened no screen for a player standing in the overworld");
            helper.assertTrue(player.level().dimension().equals(helper.getLevel().dimension()),
                "opening a screen remotely moved the player; it must not");

            helper.startSequence()
                // ServerPlayer#doTick asks stillValid every tick. Without the injection the menu is
                // gone on the first one, so waiting is the whole test.
                .thenIdle(5)
                .thenExecute(() -> helper.assertTrue(player.containerMenu != player.inventoryMenu,
                    "the remote screen was closed by the distance check within 5 ticks"))
                .thenExecute(player::closeContainer)
                .thenExecute(() -> helper.assertFalse(
                    RemoteScreens.isOpenAt(player, machine),
                    "closing the screen left the player reaching a machine in another dimension"))
                .thenSucceed();
        });
    }

    // ---------------------------------------------------------------- fixture

    private record Racked(GameTestPlayer player, ServerLevel backshop, BlockPos machine) {}

    /** One player in the overworld, one furnace in bay 1 of their own Workbay. */
    private static Racked rack(ExtendedGameTestHelper helper) {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            ServerLevel home = helper.getLevel();
            BlockPos pos = helper.absolutePos(new BlockPos(0, 1, 0));
            home.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WBBlocks.WORKBAY.get().setPlacedBy(home, pos, home.getBlockState(pos), player,
                new ItemStack(WBBlocks.WORKBAY.get()));
            player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

            WorkbayBlockEntity workbay = (WorkbayBlockEntity) home.getBlockEntity(pos);
            // Powered: a link that cannot pay does not run. OPEN_ISSUES #72.
            workbay.energy().deserializeNBT(null,
                net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = home.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());

            WorkbayMenu menu = new WorkbayMenu(1, player.getInventory(), workbay,
                WorkbayMenu.build(workbay, player, 0));
            menu.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FURNACE));
            menu.act(WorkbayAction.RACK, 0, Optional.empty());

        BlockPos machine = BayGeometry.machinePos(record.bayColumn(), 0);
        helper.assertFalse(backshop.getBlockState(machine).isAir(), "racking a furnace left the bay empty");
        return new Racked(player, backshop, machine);
    }

    @GameTest
    @TestHolder(description = "A mod's own GUI packet finds the machine through the player's level, "
        + "and stops finding it the moment the screen closes.")
    public static void aModsButtonFindsTheMachineThroughThePlayersLevel(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Racked r = rack(helper);
            GameTestPlayer player = r.player();
            BlockPos machine = r.machine();
            int chunkX = SectionPos.blockToSectionCoord(machine.getX());
            int chunkZ = SectionPos.blockToSectionCoord(machine.getZ());

            // Exactly the two calls a modded button makes, in order: Mekanism's PacketGuiInteract
            // resolves its machine with getTileEntity(player.level(), pos), and that is gated on
            // isBlockLoaded -> hasChunk. Both are asked of the *player's* level, not the bay's.
            //
            // The chunk question goes first and the block-entity question second, here and below,
            // because asking the second one is not free: Level#getBlockEntity bottoms out in
            // getChunk(..., true), which on a server level *loads the chunk*. Asked the other way
            // round this test loads the overworld chunk itself and then every later "is it loaded"
            // reads true no matter what the mod does -- which is how it first went green on nothing.
            helper.assertFalse(player.level().hasChunk(chunkX, chunkZ),
                "the bay's chunk reads loaded in the overworld with no screen open");

            helper.assertTrue(RemoteScreens.open(player, r.backshop(), machine), "no screen opened");

            helper.assertTrue(player.level().hasChunk(chunkX, chunkZ),
                "a mod asking whether the machine's chunk is loaded still hears no, so it gives up "
                    + "before ever looking the machine up");
            helper.assertFalse(player.level().getBlockEntity(machine) == null,
                "a mod resolving its machine through player.level() still finds nothing, which is "
                    + "why its buttons are silent no-ops");

            // Nothing about the player is faked, which is the whole argument for doing it this way.
            helper.assertTrue(player.level().dimension().equals(helper.getLevel().dimension()),
                "the player's own level was changed; only the machine may be answered for");

            player.closeContainer();
            helper.assertTrue(player.level().getBlockEntity(machine) == null,
                "the machine stayed reachable after the screen closed");
            helper.assertFalse(RemoteScreens.chunkHasOpenMachine(chunkX, chunkZ),
                "the chunk is still being answered for after the screen closed");
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 2. Neither {@code /workbay remote} nor {@code /workbay charge} asked
     * who was running it: any player could open the machine in any Workbay's bay, lock ignored,
     * and fill any energy block for free. {@code remote} now applies the menu's rule (a locked
     * Workbay opens only for its owner) and {@code charge}, a test fixture, needs permission 2.
     *
     * <p>Every refusal is paired with the same command landing for somebody allowed to run it, so
     * a pick that misses the block cannot pass this as a refusal.
     */
    @GameTest
    @TestHolder(description = "/workbay remote refuses a stranger on a locked Workbay and "
        + "/workbay charge refuses a non-op; the owner and an op are served.")
    public static void commandsRefuseStrangersAndNonOps(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 5, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Racked r = rack(helper);
            GameTestPlayer owner = r.player();
            BlockPos pos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = (WorkbayBlockEntity) helper.getLevel().getBlockEntity(pos);
            if (!workbay.record().orElseThrow().locked()) {
                helper.fail("a fresh Workbay is not locked");
                return;
            }
            var commands = helper.getLevel().getServer().getCommands();

            // Standing two blocks above the Workbay, looking straight down at it.
            GameTestPlayer stranger = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            stranger.moveTo(pos.getX() + 0.5, pos.getY() + 2, pos.getZ() + 0.5, 0.0F, 90.0F);
            commands.performPrefixedCommand(stranger.createCommandSourceStack().withPermission(0),
                "workbay remote 0");
            helper.assertTrue(stranger.containerMenu == stranger.inventoryMenu,
                "/workbay remote opened a locked Workbay's machine for a stranger");

            owner.moveTo(pos.getX() + 0.5, pos.getY() + 2, pos.getZ() + 0.5, 0.0F, 90.0F);
            commands.performPrefixedCommand(owner.createCommandSourceStack().withPermission(0),
                "workbay remote 0");
            helper.assertTrue(owner.containerMenu != owner.inventoryMenu,
                "/workbay remote opened nothing for the owner, so the refusal above proves nothing");
            owner.closeContainer();

            // charge: the Workbay's own buffer is an energy block. Emptied first.
            workbay.energy().deserializeNBT(null, net.minecraft.nbt.IntTag.valueOf(0));
            commands.performPrefixedCommand(owner.createCommandSourceStack().withPermission(0),
                "workbay charge");
            helper.assertValueEqual(workbay.energy().getEnergyStored(), 0,
                "FE a non-op pushed into a block with /workbay charge");
            commands.performPrefixedCommand(owner.createCommandSourceStack().withPermission(2),
                "workbay charge");
            helper.assertTrue(workbay.energy().getEnergyStored() > 0,
                "/workbay charge pushed nothing for an op, so the refusal above proves nothing");
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 12. The mixin widened {@code canInteractWithBlock} for every caller,
     * and use and dig ask it with a boost of 1.0 -- so while a remote screen was open, the block at
     * the machine's coordinates in the player's <em>own</em> dimension was usable and breakable
     * from anywhere. Only {@code stillValid}'s question (boost 4.0) is widened now.
     */
    @GameTest
    @TestHolder(description = "A remote screen widens the menu's distance check only, not use or dig.")
    public static void aRemoteScreenWidensOnlyTheMenusReach(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Racked r = rack(helper);
            GameTestPlayer player = r.player();
            BlockPos machine = r.machine();
            helper.assertTrue(RemoteScreens.open(player, r.backshop(), machine), "no screen opened");
            helper.assertTrue(RemoteScreens.isOpenAt(player, machine),
                "the screen does not read as open, so the widening is not what is under test");

            helper.assertTrue(player.canInteractWithBlock(machine, 4.0),
                "the menu's own distance check (boost 4.0) is not widened, so the screen closes");
            helper.assertFalse(player.canInteractWithBlock(machine, 1.0),
                "use and dig (boost 1.0) are widened too: the block at the machine's coordinates in "
                    + "the player's own dimension is reachable from anywhere");
            player.closeContainer();
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 13. The remote screen ships the machine's full save tag to the viewer,
     * and a hosted container full of nested NBT can put that over the 1 MiB payload cap -- which
     * disconnects the viewer on the button. Past {@link RemoteScreens#MAX_TAG_BYTES} the remote
     * path now answers false and the caller falls back to the walk-in, which needs no packet.
     */
    @GameTest
    @TestHolder(description = "A machine whose tag is too big for a packet opens no remote screen, and the viewer is not disconnected.")
    public static void anOversizedMachineTagFallsBackToTheWalkIn(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            Racked r = rack(helper);
            GameTestPlayer player = r.player();
            BlockPos machine = r.machine();

            // Positive control: the same furnace opens while its tag is small.
            helper.assertTrue(RemoteScreens.open(player, r.backshop(), machine), "no screen opened");
            player.closeContainer();

            // One item carrying 600 KiB of custom data: the tag is over the limit and under the
            // cap, so a disconnect here would be the mod's, not vanilla's.
            var big = new net.minecraft.nbt.CompoundTag();
            big.putByteArray("Blob", new byte[600 * 1024]);
            ItemStack stack = new ItemStack(Items.STONE);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(big));
            ((net.minecraft.world.Container) r.backshop().getBlockEntity(machine)).setItem(0, stack);

            helper.assertFalse(RemoteScreens.open(player, r.backshop(), machine),
                "a machine whose tag cannot ride a packet still opened a remote screen");
            helper.assertTrue(player.containerMenu == player.inventoryMenu,
                "the refused open left a menu on the player");
            helper.assertFalse(RemoteScreens.isOpenAt(player, machine),
                "the refused open left the machine reachable");
            helper.succeed();
        });
    }
}
