package com.neryos.workbay.gametests;

import com.neryos.workbay.content.room.RoomItemEntity;
import com.neryos.workbay.content.room.RoomStamp;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBDataComponents;
import com.neryos.workbay.init.WBEntities;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.world.RoomGeometry;
import com.neryos.workbay.world.RoomHolding;
import com.neryos.workbay.world.RoomRecord;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.RoomVisit;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.Optional;

/**
 * A room is a thing a bay holds, and it travels as an item. SPEC.md §0's four new rows, one test
 * each: it carries everything built in it, a copy opens nothing, a cycle is refused, and Leave
 * always gets a player out. Plus the item that cannot die.
 */
@ForEachTest(groups = "room")
public class RoomItemTests {

    /** A Workbay bound to a mock player who owns it, with a Room racked in bay 0. */
    private record Site(GameTestPlayer player, ServerLevel backshop, BlockPos workbayPos) {
        WorkbayBlockEntity workbay(ExtendedGameTestHelper helper) {
            return (WorkbayBlockEntity) helper.getLevel().getBlockEntity(workbayPos);
        }

        WorkbayRecord record(ExtendedGameTestHelper helper) {
            return workbay(helper).record().orElseThrow();
        }

        /** The owner's menu on this block, showing {@code bay}. The player has to stand at it. */
        WorkbayMenu menu(ExtendedGameTestHelper helper, int bay) {
            player.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            WorkbayMenu menu = new WorkbayMenu(1, player.getInventory(), workbay(helper),
                WorkbayMenu.build(workbay(helper), player, bay));
            menu.act(WorkbayAction.SELECT_BAY, bay, Optional.empty());
            return menu;
        }
    }

    /** Places a Workbay at {@code local} for {@code player} and, if asked, racks a Room in bay 0. */
    private static Site workbay(ExtendedGameTestHelper helper, GameTestPlayer player,
        BlockPos local, boolean withRoom) {
        BlockPos pos = helper.absolutePos(local);
        ServerLevel level = helper.getLevel();
        level.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
        WBBlocks.WORKBAY.get().setPlacedBy(level, pos, level.getBlockState(pos), player,
            new ItemStack(WBBlocks.WORKBAY.get()));
        WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(pos);
        workbay.energy().deserializeNBT(null,
            net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
        Site site = new Site(player, level.getServer().getLevel(WorkbayDimensions.BACKSHOP), pos);
        if (withRoom) {
            RoomTests.loadRoom(helper, site.record(helper), 0, RoomTests.roomItem(1), player);
        }
        return site;
    }

    private static RoomRecord roomOf(ExtendedGameTestHelper helper, Site site, int bay) {
        return RoomRegistry.get(helper.getLevel().getServer())
            .roomInBay(site.record(helper), bay).orElseThrow();
    }

    /** The one room item in the player's inventory, or empty. */
    private static ItemStack roomInHand(GameTestPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (RoomHolding.isRoom(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static int iron(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container container)) {
            return -1;
        }
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(Items.IRON_INGOT)) {
                total += container.getItem(slot).getCount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ tests

    /**
     * <b>A room pulled out of one Workbay and loaded into another is the same room</b>: the chest
     * inside still holds its sixteen iron, the Connector on it is on the new network's list and
     * off the old one's, and the old network's channel through it is gone. SPEC.md §0.
     */
    @GameTest
    @TestHolder(description = "Pulling a room out and loading it into another Workbay keeps everything inside it, Connectors included.")
    public static void aRoomTravelsWithEverythingInIt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 3, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            Site first = workbay(helper, owner, new BlockPos(1, 1, 1), true);
            Site second = workbay(helper, owner, new BlockPos(5, 1, 5), false);
            RoomRegistry registry = RoomRegistry.get(helper.getLevel().getServer());
            RoomRecord room = roomOf(helper, first, 0);

            // Build in it: a chest of iron with a Connector on top, added as a channel on bay 1.
            helper.assertTrue(RoomVisit.enter(owner, first.record(helper), 0), "entering was refused");
            BlockPos chest = RoomGeometry.origin(room.region()).offset(2, 1, 3);
            first.backshop().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            ((net.minecraft.world.Container) first.backshop().getBlockEntity(chest))
                .setItem(0, new ItemStack(Items.IRON_INGOT, 16));
            var facing = WBBlocks.CONNECTOR.get().defaultBlockState()
                .setValue(com.neryos.workbay.content.connector.ConnectorBlock.FACING, Direction.DOWN);
            first.backshop().setBlock(chest.above(), facing, Block.UPDATE_ALL);
            WBBlocks.CONNECTOR.get().setPlacedBy(first.backshop(), chest.above(), facing, owner,
                new ItemStack(WBBlocks.CONNECTOR.get()));
            GlobalPos plate = GlobalPos.of(WorkbayDimensions.BACKSHOP, chest.above());
            helper.assertTrue(first.record(helper).connectorAt(plate).isPresent(),
                "a Connector placed in the room did not join the holder's list");
            WorkbayMenu.addChannel(first.workbay(helper), first.record(helper),
                first.record(helper).connectorAt(plate).orElseThrow().id(), 1);
            helper.assertValueEqual(first.workbay(helper).linksAt(plate).size(), 1,
                "channels through the room's Connector on the first network");
            RoomVisit.leave(owner);

            // Pull it out. The item carries the room and a ticket; the first network keeps neither
            // the Connector nor the channel.
            first.menu(helper, 0).act(WorkbayAction.EJECT, 0, Optional.empty());
            ItemStack item = roomInHand(owner);
            helper.assertFalse(item.isEmpty(), "the room did not come back as an item");
            RoomStamp stamp = item.get(WBDataComponents.ROOM.get());
            helper.assertTrue(stamp != null && stamp.room().equals(room.id())
                && stamp.ticket().isPresent(), "the item is not stamped with the room and a ticket");
            helper.assertTrue(first.record(helper).bay(0).room().isEmpty()
                && first.record(helper).bay(0).hosted().isEmpty(), "bay 1 still holds the room");
            helper.assertTrue(registry.holderOf(room).isEmpty(), "the room still has a holder");
            helper.assertTrue(first.record(helper).connectorAt(plate).isEmpty(),
                "the room's Connector stayed on the old network");
            helper.assertTrue(first.workbay(helper).linksAt(plate).isEmpty(),
                "the old network's channel through the room's Connector survived the pull");
            helper.assertValueEqual(registry.room(room.id()).orElseThrow().connectors().size(), 1,
                "Connectors travelling with the room");

            // Load it into the other Workbay, by hand, the way a player does.
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, item);
            second.menu(helper, 0).act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(second.record(helper).bay(0).room().map(room.id()::equals).orElse(false),
                "the second Workbay's bay 1 does not hold the room");
            helper.assertTrue(roomInHand(owner).isEmpty(), "the item was not taken from the hand");
            helper.assertTrue(second.record(helper).connectorAt(plate).isPresent(),
                "the room's Connector did not join the new network");
            helper.assertTrue(registry.room(room.id()).orElseThrow().ticket().isEmpty(),
                "the ticket was not spent");

            // And everything is still there, counted.
            helper.assertTrue(RoomVisit.enter(owner, second.record(helper), 0),
                "entering from the second Workbay was refused");
            helper.assertValueEqual(iron(second.backshop(), chest), 16, "iron in the chest after the move");
            helper.assertTrue(second.backshop().getBlockState(chest.above()).is(WBBlocks.CONNECTOR.get()),
                "the Connector is gone from the room");
            helper.succeed();
        });
    }

    /**
     * <b>One room has exactly one holder.</b> A copy of the item -- a duplicator, a stale stack --
     * carries the same ticket, so whichever is racked first wins and the other is refused while
     * the room is held; the next eject mints a new ticket and the copy is a copy for ever. A
     * pick-block copy has no ticket at all and never opens anything.
     */
    @GameTest
    @TestHolder(description = "A copied room item cannot open the room: refused while it is held, and stale after the next eject.")
    public static void aCopiedRoomItemOpensNothing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 3, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            Site site = workbay(helper, owner, new BlockPos(1, 1, 1), true);
            RoomRegistry registry = RoomRegistry.get(helper.getLevel().getServer());
            RoomRecord room = roomOf(helper, site, 0);
            GlobalPos at = GlobalPos.of(helper.getLevel().dimension(), site.workbayPos());

            site.menu(helper, 0).act(WorkbayAction.EJECT, 0, Optional.empty());
            ItemStack real = roomInHand(owner).copy();
            ItemStack copy = real.copy();
            owner.getInventory().clearContent();

            // The real one goes in; the copy, same ticket, is refused because the room is held.
            helper.assertTrue(RoomHolding.refusal(registry, site.record(helper), Optional.of(at),
                real).isEmpty(), "the real item was refused");
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, real);
            site.menu(helper, 0).act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(site.record(helper).bay(0).room().isPresent(), "the real item did not load");
            helper.assertTrue(RoomHolding.refusal(registry, site.record(helper), Optional.of(at),
                copy).isPresent(), "a copy of a held room was not refused");
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, copy.copy());
            site.menu(helper, 1).act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(site.record(helper).bay(1).room().isEmpty(),
                "the copy opened the room a second time, in bay 2");

            // Pull it out again: a fresh ticket, so the copy is stale even with the room free.
            site.menu(helper, 0).act(WorkbayAction.EJECT, 0, Optional.empty());
            helper.assertTrue(registry.holderOf(room).isEmpty(), "the room is still held");
            helper.assertTrue(RoomHolding.refusal(registry, site.record(helper), Optional.of(at),
                copy).isPresent(), "a stale copy was accepted once the room was free");
            owner.getInventory().clearContent();
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, copy.copy());
            site.menu(helper, 0).act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(site.record(helper).bay(0).room().isEmpty(),
                "a stale copy loaded the room");

            // And a pick-block copy: the room's id and no ticket.
            ItemStack picked = RoomTests.roomItem(1);
            picked.set(WBDataComponents.ROOM.get(), new RoomStamp(room.id(), Optional.empty()));
            helper.assertTrue(RoomHolding.refusal(registry, site.record(helper), Optional.of(at),
                picked).isPresent(), "a ticketless copy was accepted");
            helper.succeed();
        });
    }

    /**
     * <b>A room may never end up inside itself.</b> Both doors: racking a room into a Workbay
     * that stands in that room, and binding a network to a block standing in a room the network
     * holds. Nesting that closes no loop stays allowed, which is the positive control.
     */
    @GameTest
    @TestHolder(description = "A room cannot be loaded into a Workbay standing inside it, and a network cannot be placed inside its own room.")
    public static void aCycleIsRefused(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 3, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            Site outer = workbay(helper, owner, new BlockPos(1, 1, 1), true);
            RoomRegistry registry = RoomRegistry.get(helper.getLevel().getServer());
            RoomRecord room = roomOf(helper, outer, 0);
            helper.assertTrue(RoomVisit.enter(owner, outer.record(helper), 0), "entering was refused");

            // A second Workbay standing inside the room: allowed, that is nesting.
            BlockPos innerPos = RoomGeometry.origin(room.region()).offset(3, 1, 3);
            ServerLevel backshop = outer.backshop();
            backshop.setBlock(innerPos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WBBlocks.WORKBAY.get().setPlacedBy(backshop, innerPos, backshop.getBlockState(innerPos),
                owner, new ItemStack(WBBlocks.WORKBAY.get()));
            WorkbayBlockEntity inner = (WorkbayBlockEntity) backshop.getBlockEntity(innerPos);
            helper.assertTrue(inner.record().isPresent(), "a Workbay inside a room got no network");
            GlobalPos innerAt = GlobalPos.of(WorkbayDimensions.BACKSHOP, innerPos);

            // A second, unrelated room goes into it fine.
            ItemStack other = RoomTests.roomItem(1);
            helper.assertTrue(RoomHolding.refusal(registry, inner.record().orElseThrow(),
                Optional.of(innerAt), other).isEmpty(), "a fresh room was refused inside a room");

            // The room the inner Workbay stands in, pulled out and offered to it: refused.
            RoomVisit.leave(owner);
            outer.menu(helper, 0).act(WorkbayAction.EJECT, 0, Optional.empty());
            ItemStack item = roomInHand(owner);
            helper.assertFalse(item.isEmpty(), "the room did not come out");
            helper.assertTrue(RoomHolding.refusal(registry, inner.record().orElseThrow(),
                Optional.of(innerAt), item).isPresent(),
                "a room was allowed into a Workbay standing inside that very room");
            // And not merely refused for being a copy: the same item is welcome elsewhere.
            helper.assertTrue(RoomHolding.refusal(registry, outer.record(helper),
                Optional.of(GlobalPos.of(helper.getLevel().dimension(), outer.workbayPos())), item)
                .isEmpty(), "the pulled room is refused everywhere, so the cycle check proved nothing");

            // The other door: put the room back, then bind the outer network to a block inside it.
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, item);
            outer.menu(helper, 0).act(WorkbayAction.RACK, 0, Optional.empty());
            WorkbayRecord network = outer.record(helper);
            helper.assertTrue(RoomHolding.wouldCycle(registry, network, innerAt),
                "binding a network to a block inside its own room is not a cycle");
            helper.assertFalse(RoomHolding.wouldCycle(registry, network,
                GlobalPos.of(helper.getLevel().dimension(), outer.workbayPos())),
                "binding a network to a block in the world reads as a cycle");
            // For real: the outer block goes, its item remembers the network, and placing that
            // item inside the room leaves a block holding nothing.
            helper.getLevel().setBlock(outer.workbayPos(), Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
            ItemStack bound = new ItemStack(WBBlocks.WORKBAY.get());
            bound.set(WBDataComponents.BINDING.get(),
                com.neryos.workbay.content.workbay.WorkbayBinding.of(network, 1, 0, 0));
            BlockPos again = RoomGeometry.origin(room.region()).offset(3, 1, 2);
            backshop.setBlock(again, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WBBlocks.WORKBAY.get().setPlacedBy(backshop, again, backshop.getBlockState(again),
                owner, bound);
            helper.assertTrue(((WorkbayBlockEntity) backshop.getBlockEntity(again)).record().isEmpty(),
                "a network was bound to a block standing in its own room");
            helper.succeed();
        });
    }

    /**
     * <b>Leave always gets a player out.</b> With the Workbay broken, beside where it stood; with
     * that spot walled in, at the respawn point.
     */
    @GameTest
    @TestHolder(description = "Leave brings a player out beside where the Workbay stood, and to their respawn point when that is walled in.")
    public static void leaveAlwaysGetsAPlayerOut(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(9, 9, 9));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            Site site = workbay(helper, owner, new BlockPos(4, 1, 4), true);
            ServerLevel level = helper.getLevel();

            // 1. The Workbay is gone. Its last position is still the way out.
            helper.assertTrue(RoomVisit.enter(owner, site.record(helper), 0), "entering was refused");
            level.setBlock(site.workbayPos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(RoomVisit.leave(owner), "Leave did nothing");
            helper.assertTrue(owner.level().dimension().equals(level.dimension()),
                "the player is in " + owner.level().dimension().location());
            helper.assertTrue(owner.blockPosition().distManhattan(site.workbayPos()) <= 4,
                "the player came out at " + owner.blockPosition() + ", not where the Workbay stood");

            // 2. The Workbay is back and walled in: nowhere to stand within three blocks.
            Site back = workbay(helper, owner, new BlockPos(4, 1, 4), false);
            helper.assertTrue(back.record(helper).id().equals(site.record(helper).id())
                || back.record(helper).bay(0).room().isPresent(),
                "the re-placed Workbay did not pick its sleeping network back up");
            helper.assertTrue(RoomVisit.enter(owner, back.record(helper), 0), "re-entering was refused");
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -1; dy <= 5; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        BlockPos at = back.workbayPos().offset(dx, dy, dz);
                        if (!at.equals(back.workbayPos())) {
                            level.setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    }
                }
            }
            BlockPos bed = helper.absolutePos(new BlockPos(0, 7, 0));
            level.setBlock(bed.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            owner.setRespawnPosition(level.dimension(), bed, 0.0F, true, false);
            helper.assertTrue(RoomVisit.leave(owner), "Leave did nothing the second time");
            helper.assertTrue(owner.level().dimension().equals(level.dimension()),
                "the player is in " + owner.level().dimension().location());
            helper.assertTrue(owner.blockPosition().distManhattan(bed) <= 3,
                "walled in, the player came out at " + owner.blockPosition()
                    + " instead of at the respawn point " + bed);
            helper.succeed();
        });
    }

    /**
     * <b>A player inside when the room is pulled out: allowed, and nothing happens to them.</b>
     * SPEC.md §0. The room sleeps with them in it, and Leave still brings them out beside the
     * Workbay that last held it.
     */
    @GameTest
    @TestHolder(description = "Pulling a room out with somebody inside leaves them inside; Leave still brings them out.")
    public static void aPlayerInsideAPulledRoomStaysAndLeaves(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 3, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            GameTestPlayer other = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            Site site = workbay(helper, owner, new BlockPos(1, 1, 1), true);
            RoomRegistry registry = RoomRegistry.get(helper.getLevel().getServer());
            RoomRecord room = roomOf(helper, site, 0);
            helper.assertTrue(RoomVisit.enter(owner, site.record(helper), 0), "entering was refused");

            // The owner is inside, so somebody else pulls the room: the network is unlocked for
            // them, which is what sharing a Workbay means.
            registry.put(site.record(helper).withLocked(false));
            Site shared = new Site(other, site.backshop(), site.workbayPos());
            shared.menu(helper, 0).act(WorkbayAction.EJECT, 0, Optional.empty());
            helper.assertTrue(registry.holderOf(room).isEmpty(), "the room was not pulled out");
            helper.assertFalse(roomInHand(other).isEmpty(), "the room did not go to the puller");

            helper.startSequence()
                .thenIdle(8)
                .thenExecute(() -> {
                    helper.assertTrue(owner.level().dimension().equals(WorkbayDimensions.BACKSHOP)
                        && RoomVisit.isInside(owner)
                        && registry.room(room.id()).orElseThrow().contains(owner.blockPosition()),
                        "the owner was thrown out of a room that was pulled with them inside");
                    helper.assertTrue(RoomVisit.leave(owner), "Leave did nothing from a pulled room");
                    helper.assertTrue(owner.level().dimension().equals(helper.getLevel().dimension())
                        && owner.blockPosition().distManhattan(site.workbayPos()) <= 4,
                        "Leave from a pulled room put the owner at " + owner.blockPosition()
                            + " instead of beside the Workbay that last held it");
                })
                .thenSucceed();
        });
    }

    /**
     * <b>A room item is never destroyed.</b> Lava, an explosion, the void, and time: the entity
     * that carries it refuses every one. The plain item entity the game spawns is swapped for
     * ours on the next tick, which is what the first wait is for.
     */
    @GameTest(timeoutTicks = 200)
    @TestHolder(description = "A room item survives lava, an explosion and the void, never expires, and still ages (spins).")
    public static void aRoomItemCannotBeDestroyed(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
            ItemStack stack = RoomTests.roomItem(2);
            stack.set(WBDataComponents.ROOM.get(),
                new RoomStamp(java.util.UUID.randomUUID(), Optional.of(java.util.UUID.randomUUID())));
            level.addFreshEntity(new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.5,
                at.getZ() + 0.5, stack.copy()));

            helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    RoomItemEntity room = one(level, at);
                    helper.assertTrue(room != null, "the room on the ground is a plain item entity");
                    helper.assertTrue(room.getItem().get(WBDataComponents.ROOM.get()) != null,
                        "the stamp was lost in the swap");
                    // Lava, and time enough for the game to burn it four times over.
                    level.setBlock(at, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    RoomItemEntity room = one(level, at);
                    helper.assertTrue(room != null && room.isAlive(), "lava destroyed the room");
                    // It ages like any item, because the age is what spins it; the lifespan is
                    // what never runs out. Frozen at -32768 was the release candidate's bug.
                    helper.assertTrue(room.getAge() > 30, "the room's age stopped counting: "
                        + room.getAge() + " (it lies frozen on the ground)");
                    helper.assertValueEqual(room.lifespan, Integer.MAX_VALUE, "the room's lifespan");
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    level.explode(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 4.0F,
                        Level.ExplosionInteraction.TNT);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    RoomItemEntity room = one(level, at);
                    helper.assertTrue(room != null && room.isAlive(), "an explosion destroyed the room");
                    // The void: sixty-four under the world is where vanilla discards an entity.
                    room.teleportTo(at.getX() + 0.5, level.getMinBuildHeight() - 70, at.getZ() + 0.5);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    RoomItemEntity room = level.getEntities(WBEntities.ROOM_ITEM.get(),
                        e -> e.isAlive()).stream().findFirst().orElse(null);
                    helper.assertTrue(room != null, "the void destroyed the room");
                    helper.assertTrue(room.getY() >= level.getMinBuildHeight(),
                        "the room is still falling through the void at y " + room.getY());
                    helper.assertTrue(room.getItem().get(WBDataComponents.ROOM.get()) != null,
                        "the stamp did not survive the trip");
                    room.discard();
                })
                .thenSucceed();
        });
    }

    private static RoomItemEntity one(ServerLevel level, BlockPos near) {
        return level.getEntitiesOfClass(RoomItemEntity.class,
            new net.minecraft.world.phys.AABB(near).inflate(3)).stream().findFirst().orElse(null);
    }
}
