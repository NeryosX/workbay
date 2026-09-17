package com.neryos.workbay.world;

import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.content.room.RoomItem;
import com.neryos.workbay.content.room.RoomStamp;
import com.neryos.workbay.init.WBDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A room going into a bay and coming back out. SPEC.md §0.
 *
 * <p>Three rules live here and nowhere else. <b>One holder</b>: the item carries a ticket the
 * registry minted at the last eject, and only that item opens the room. <b>No cycle</b>: a room
 * may never end up inside itself through any chain of bays and blocks, or Leave has no way out.
 * <b>Connectors travel with the room</b>: pulling a room out takes its Connectors off the old
 * network, with every channel through them, and racking it hands them to the new one.
 */
public final class RoomHolding {
    private RoomHolding() {}

    /** True for a stack that is a room, of any size, stamped or blank. */
    public static boolean isRoom(ItemStack stack) {
        return stack.getItem() instanceof RoomItem;
    }

    /**
     * Why this room may not go into a bay of {@code network}, or empty when it may. Asked before
     * the block is racked, so a refusal costs nothing to undo.
     */
    public static Optional<Component> refusal(RoomRegistry registry, WorkbayRecord network,
        Optional<GlobalPos> workbay, ItemStack stack) {
        RoomStamp stamp = stack.get(WBDataComponents.ROOM.get());
        if (stamp == null) {
            return Optional.empty();
        }
        RoomRecord room = registry.room(stamp.room()).orElse(null);
        if (room == null) {
            return Optional.of(WorkbayLang.message("room_unknown"));
        }
        Optional<RoomRegistry.Holder> held = registry.holderOf(room);
        if (held.isPresent()) {
            return Optional.of(WorkbayLang.message("room_held", held.get().network().label()));
        }
        // The ticket: minted at eject, spent here. A copy carries a stale one, or none.
        if (room.ticket().isEmpty() || !room.ticket().equals(stamp.ticket())) {
            return Optional.of(WorkbayLang.message("room_copy"));
        }
        // The cycle. The Workbay stands in some chain of rooms; none of them may be this room or
        // anything this room contains.
        Set<UUID> inside = subtree(registry, room);
        inside.add(room.id());
        if (workbay.isPresent() && chain(registry, workbay.get()).stream().anyMatch(inside::contains)) {
            return Optional.of(WorkbayLang.message("room_cycle"));
        }
        return Optional.empty();
    }

    /**
     * Whether binding {@code network} to a block at {@code where} would put one of its rooms
     * inside itself. Asked when a Workbay is placed or a network transferred into one.
     */
    public static boolean wouldCycle(RoomRegistry registry, WorkbayRecord network, GlobalPos where) {
        Set<UUID> below = subtree(registry, network);
        return !below.isEmpty() && chain(registry, where).stream().anyMatch(below::contains);
    }

    /**
     * The room is in the bay: binds it, spends the ticket, brings its Connectors over. Minting the
     * record for a blank item. Returns the network as it now is.
     */
    public static WorkbayRecord loaded(MinecraftServer server, WorkbayRecord network, int bay,
        ItemStack stack) {
        RoomRegistry registry = RoomRegistry.get(server);
        RoomStamp stamp = stack.get(WBDataComponents.ROOM.get());
        RoomRecord room = stamp == null ? registry.createRoom(((RoomItem) stack.getItem()).tier())
            : registry.room(stamp.room()).orElseThrow();
        // The Connectors it carried join this network's list, and its own list empties.
        List<WorkbayRecord.Connector> connectors = new ArrayList<>(network.connectors());
        connectors.addAll(room.connectors());
        registry.putRoom(room.withTicket(Optional.empty()).withLastHolder(network.id())
            .withConnectors(List.of()));
        // A bay holding a room has no channels of its own: whatever the bay carried before is
        // taken off it (detached, not deleted -- the Add list is the way back on another bay).
        WorkbayRecord now = network.withConnectors(connectors)
            .withBay(network.bay(bay).withRoom(Optional.of(room.id())))
            .withBuses(network.buses().stream()
                .map(b -> b.bay() == bay ? b.withBay(com.neryos.workbay.bus.BusConfig.NO_BAY) : b)
                .toList());
        registry.put(now);
        return now;
    }

    /**
     * The room has left the bay: stamps the item with a fresh ticket, takes the room's Connectors
     * and every channel through them off the network, and clears the bay. Returns the network as
     * it now is; the stack is edited in place.
     */
    public static WorkbayRecord pulled(MinecraftServer server, WorkbayRecord network, int bay,
        ItemStack stack) {
        RoomRegistry registry = RoomRegistry.get(server);
        Optional<UUID> id = network.bay(bay).room();
        WorkbayRecord now = network.withBay(network.bay(bay).withRoom(Optional.empty()));
        RoomRecord room = id.flatMap(registry::room).orElse(null);
        if (room == null) {
            registry.put(now);
            return now;
        }
        List<WorkbayRecord.Connector> going = network.connectors().stream()
            .filter(c -> c.pos().dimension().equals(WorkbayDimensions.BACKSHOP)
                && room.contains(c.pos().pos())).toList();
        Set<GlobalPos> gone = new HashSet<>();
        going.forEach(c -> gone.add(c.pos()));
        now = now.withConnectors(network.connectors().stream().filter(c -> !gone.contains(c.pos()))
            .toList())
            .withBuses(network.buses().stream().filter(b -> !gone.contains(b.connector())).toList());
        registry.put(now);
        UUID ticket = UUID.randomUUID();
        registry.putRoom(room.withTicket(Optional.of(ticket)).withLastHolder(network.id())
            .withConnectors(going));
        stack.set(WBDataComponents.ROOM.get(), new RoomStamp(room.id(), Optional.of(ticket)));
        // The item is named after the room, so a pocketful of rooms can be told apart.
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            Component.literal(room.label()));
        return now;
    }

    // ------------------------------------------------------------ the shape

    /**
     * The rooms enclosing a position, innermost first: the room it is in, the room the block
     * holding that room stands in, and so on until the chain leaves the Backshop or reaches a
     * room nobody holds. A room seen twice ends the walk -- that is the cycle this exists to
     * refuse, and walking it forever is not an answer.
     */
    public static List<UUID> chain(RoomRegistry registry, GlobalPos from) {
        List<UUID> out = new ArrayList<>();
        GlobalPos at = from;
        while (at.dimension().equals(WorkbayDimensions.BACKSHOP)) {
            RoomRecord room = registry.roomAt(at.pos()).orElse(null);
            if (room == null || out.contains(room.id())) {
                break;
            }
            out.add(room.id());
            Optional<GlobalPos> next = registry.holderOf(room)
                .flatMap(holder -> holder.network().lastKnownPos());
            if (next.isEmpty()) {
                break;
            }
            at = next.get();
        }
        return out;
    }

    /** Every room reachable downward from a network: its room bays, and everything under them. */
    public static Set<UUID> subtree(RoomRegistry registry, WorkbayRecord network) {
        Set<UUID> out = new HashSet<>();
        descend(registry, network, out);
        return out;
    }

    /** Every room reachable downward from a room: through the Workbays standing inside it. */
    public static Set<UUID> subtree(RoomRegistry registry, RoomRecord room) {
        Set<UUID> out = new HashSet<>();
        for (WorkbayRecord standing : standingIn(registry, room)) {
            descend(registry, standing, out);
        }
        return out;
    }

    private static void descend(RoomRegistry registry, WorkbayRecord network, Set<UUID> out) {
        for (RoomRecord room : registry.roomsOf(network).values()) {
            if (out.add(room.id())) {
                for (WorkbayRecord standing : standingIn(registry, room)) {
                    descend(registry, standing, out);
                }
            }
        }
    }

    /** The networks whose block stands inside a room right now. */
    private static List<WorkbayRecord> standingIn(RoomRegistry registry, RoomRecord room) {
        return registry.all().stream()
            .filter(WorkbayRecord::live)
            .filter(record -> record.lastKnownPos()
                .filter(at -> at.dimension().equals(WorkbayDimensions.BACKSHOP))
                .map(at -> room.contains(at.pos())).orElse(false))
            .toList();
    }

    // ---------------------------------------------------------------- Leave

    /** Where a player leaving a room is put, and how they may still be standing in a room there. */
    public record Exit(ServerLevel level, net.minecraft.world.phys.Vec3 at,
        Optional<GlobalPos> workbay, int bay) {}

    /**
     * Where Leave goes. SPEC.md §0: beside the Workbay holding the room; failing that, beside the
     * last one that did, where it last stood; failing a safe spot there, the player's respawn
     * point. The <em>Workbay</em>, not the position the player came in from: that position is
     * stale the moment the block moves, and for a nested room it is inside a room that may have
     * been pulled out since.
     */
    public static Exit exit(net.minecraft.server.level.ServerPlayer player,
        @org.jetbrains.annotations.Nullable RoomRecord room) {
        RoomRegistry registry = RoomRegistry.get(player.server);
        Optional<RoomRegistry.Holder> holder = room == null ? Optional.empty()
            : registry.holderOf(room);
        Optional<WorkbayRecord> network = holder.map(RoomRegistry.Holder::network)
            .or(() -> room == null ? Optional.empty()
                : room.lastHolder().flatMap(registry::byId));
        int bay = holder.map(RoomRegistry.Holder::bay).orElse(0);
        Optional<GlobalPos> block = network.flatMap(WorkbayRecord::lastKnownPos);
        if (block.isPresent()) {
            ServerLevel level = player.server.getLevel(block.get().dimension());
            if (level != null) {
                Optional<net.minecraft.world.phys.Vec3> spot = standingSpot(level, block.get().pos());
                if (spot.isPresent()) {
                    // The screen comes back only when the block is really there.
                    boolean stands = network.get().live() && level.isLoaded(block.get().pos())
                        && level.getBlockEntity(block.get().pos())
                            instanceof com.neryos.workbay.content.workbay.WorkbayBlockEntity;
                    return new Exit(level, spot.get(), stands ? block : Optional.empty(), bay);
                }
            }
        }
        ServerLevel respawn = Optional.ofNullable(player.getRespawnDimension())
            .map(player.server::getLevel).orElse(player.server.overworld());
        BlockPos bed = player.getRespawnPosition();
        Optional<net.minecraft.world.phys.Vec3> spot = bed != null
            && respawn.dimension().equals(player.getRespawnDimension())
            ? standingSpot(respawn, bed) : Optional.empty();
        return new Exit(respawn, spot.orElseGet(() -> net.minecraft.world.phys.Vec3.atBottomCenterOf(
            respawn.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                respawn.getSharedSpawnPos()))), Optional.empty(), bay);
    }

    /**
     * Somewhere a player can stand beside a block, nearest first: the eight neighbours on the
     * block's own level, then a level up and down, out to three blocks. Empty when the block is
     * walled in on every side, which is the case Leave falls through to the respawn point.
     */
    public static Optional<net.minecraft.world.phys.Vec3> standingSpot(ServerLevel level,
        BlockPos beside) {
        if (!level.isLoaded(beside)) {
            level.getChunk(beside); // Leave is a click a player made; one chunk, once.
        }
        List<BlockPos> ring = new ArrayList<>();
        for (int r = 1; r <= 3; r++) {
            for (int dy = 0; dy <= r; dy++) {
                for (int y : dy == 0 ? new int[] {0} : new int[] {dy, -dy}) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) == r || dy == r) {
                                ring.add(beside.offset(dx, y, dz));
                            }
                        }
                    }
                }
            }
        }
        for (BlockPos feet : ring) {
            if (canStand(level, feet)) {
                return Optional.of(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet));
            }
        }
        return Optional.empty();
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        net.minecraft.world.level.block.state.BlockState floor = level.getBlockState(feet.below());
        return floor.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
            && level.getFluidState(feet).isEmpty();
    }
}
