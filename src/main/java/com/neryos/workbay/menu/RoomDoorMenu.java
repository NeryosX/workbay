package com.neryos.workbay.menu;

import com.neryos.workbay.init.WBMenus;
import com.neryos.workbay.world.RoomRecord;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.RoomVisit;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * The screen a room's wall opens: the way out, and the way to the network's other rooms.
 * SPEC.md §8.
 *
 * <p><b>Its own menu rather than the Workbay's.</b> {@link WorkbayMenu} is built from a Workbay
 * <em>block entity</em>, and the whole point of a room is that it works with no Workbay standing in
 * the world at all - which is the failure Compact Machines is known for. This one is built from the
 * player and the block they clicked, so it opens for somebody who has lost everything.
 *
 * <p>No slots, like the Workbay's. Everything it draws rides the menu-open buffer.
 */
public class RoomDoorMenu extends AbstractContainerMenu {

    /** The rooms in the holder's bays the player may step to, and which of them they are in. */
    public record View(List<WorkbaySnapshot.Room> rooms, int current) {

        public static final View EMPTY = new View(List.of(), -1);

        public static final StreamCodec<RegistryFriendlyByteBuf, View> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.fromCodecWithRegistries(WorkbaySnapshot.Room.CODEC.listOf()),
                View::rooms,
                ByteBufCodecs.VAR_INT, View::current,
                View::new);
    }

    private final View view;

    /** Client side: the whole view arrives in the open buffer, so frame 1 is already correct. */
    public RoomDoorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, View.STREAM_CODEC.decode(buffer));
    }

    public RoomDoorMenu(int containerId, View view) {
        super(WBMenus.ROOM_DOOR.get(), containerId);
        this.view = view;
    }

    public View view() {
        return view;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * <b>Always valid.</b> A distance check would be a way to strand somebody: the player is
     * standing inside the thing they clicked, and the only two buttons take them somewhere else.
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /** Leave, or step to another room. Nothing else, so there is nothing else to guard. */
    public void act(WorkbayAction action, int arg, ServerPlayer player) {
        switch (action) {
            case LEAVE_ROOM -> {
                player.closeContainer();
                RoomVisit.leave(player);
            }
            case ENTER_ROOM -> {
                WorkbayRecord record = networkOf(player).orElse(null);
                if (record == null) {
                    return;
                }
                player.closeContainer();
                // Straight across: the room next door is held by the same Workbay, so Leave from
                // there goes where Leave from here would have.
                RoomVisit.enter(player, record, arg);
            }
            default -> { }
        }
    }

    /**
     * Opens the screen for whoever clicked a shell block.
     *
     * <p><b>The room is read off the player, not off the block.</b> The obvious version asked the
     * registry which room contains the clicked position and got nothing for the floor and the
     * walls - {@code roomAt} answers for a room's <em>interior</em>, and the shell is by definition
     * not inside it. The player standing in the room is the fact that matters anyway: they cannot
     * reach another room's wall.
     */
    public static void open(ServerPlayer player, BlockPos clicked) {
        RoomRegistry registry = RoomRegistry.get(player.server);
        RoomRecord here = RoomVisit.roomOf(player).flatMap(registry::room).orElse(null);
        if (here == null) {
            return;
        }
        // A room in nobody's bay still opens its door: Leave is the whole point of it, and the
        // list of other rooms is simply empty.
        View view = view(player, networkOf(player).orElse(null), here);
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, who) -> new RoomDoorMenu(id, view),
            com.neryos.workbay.WorkbayLang.gui("door.title")),
            buffer -> View.STREAM_CODEC.encode(buffer, view));
    }

    /**
     * The network holding the room the player is in, read from the room they are recorded as
     * occupying rather than from the block, because that is the record the standing rule already
     * trusts. Empty for a room that is an item right now.
     */
    private static Optional<WorkbayRecord> networkOf(ServerPlayer player) {
        RoomRegistry registry = RoomRegistry.get(player.server);
        return RoomVisit.roomOf(player).flatMap(registry::holderOf)
            .map(RoomRegistry.Holder::network);
    }

    private static View view(ServerPlayer player, @org.jetbrains.annotations.Nullable
        WorkbayRecord record, RoomRecord here) {
        RoomRegistry registry = RoomRegistry.get(player.server);
        List<WorkbaySnapshot.Room> rooms = new java.util.ArrayList<>();
        int current = -1;
        if (record == null) {
            return new View(rooms, current);
        }
        for (var entry : registry.roomsOf(record).entrySet()) {
            RoomRecord room = entry.getValue();
            // Only the rooms this player may be in. A guest invited to one room is looking at a
            // list of doors, and every one of them he cannot open is the name of a room he was
            // never told about -- the invitation was to a room, not to the network.
            if (!RoomVisit.mayEnter(registry, player.getUUID(), room)) {
                continue;
            }
            if (room.id().equals(here.id())) {
                current = rooms.size();
            }
            rooms.add(new WorkbaySnapshot.Room(entry.getKey(), room.label(),
                room.interior(), room.built(), room.effectiveBiome().location().toString(),
                room.colour(),
                // Never to the door screen. It is a list of doors, and a guest list on it would be
                // this room telling one guest who else was invited.
                List.of()));
        }
        return new View(rooms, current);
    }
}
