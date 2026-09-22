package com.neryos.workbay.menu;

import com.neryos.workbay.WorkbaySounds;
import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.bus.BusRunner;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.content.workbay.WorkbayUpgrade;
import com.neryos.workbay.host.HostChecks;
import com.neryos.workbay.host.HostResult;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBMenus;
import com.neryos.workbay.network.SnapshotPacket;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.BayHosting;
import com.neryos.workbay.world.FaceConfig;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The Workbay's menu, behind all three screens. SPEC.md §4.
 *
 * <p><b>It has no slots at all.</b> The screen shows no player inventory: a machine enters a bay by
 * clicking the bay slot while holding it, not by dragging from a grid. That also means the vanilla
 * slot-sync path carries nothing, and everything the screens draw arrives as one
 * {@link WorkbaySnapshot}.
 *
 * <p>SPEC.md §4 has switching bays re-open a fresh menu. That was written for a menu with real
 * slots bound to a bay; with none, there is nothing to re-bind, so the selection is an action on
 * this menu instead - which also sidesteps the cursor recentring that
 * {@code shouldTriggerClientSideContainerClosingOnOpen} exists to prevent.
 */
public class WorkbayMenu extends AbstractContainerMenu {

    /** Rebuilt at most this often. It is a status screen, not an animation. */
    private static final int REFRESH_TICKS = 5;

    /**
     * Actions one menu runs per refresh window; the rest are dropped. A room repaint is up to
     * 13.4k {@code setBlock}s and a biome change nine chunk loads, per packet, and nothing else
     * rate-limits a client (night audit 1A, finding 4). Sized above the largest honest burst: the
     * Add picker's Apply sends one packet per ticked Connector plus one per ticked bay
     * ({@code BaysPage#applyPicked}), and a network holds at most {@code MAX_LINKS} = 64 links
     * plus seven other bays, so 71 is the most that can land anything.
     */
    public static final int MAX_ACTIONS_PER_WINDOW = 80;

    private final Player player;

    @Nullable
    private final WorkbayBlockEntity workbay;

    private WorkbaySnapshot snapshot;
    private WorkbaySnapshot sent = WorkbaySnapshot.EMPTY;
    private int selectedBay;
    private int cooldown;
    private int actionsThisWindow;

    /** Client side: the snapshot arrives in the menu-open buffer, so frame 1 is already correct. */
    public WorkbayMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, null, WorkbaySnapshot.STREAM_CODEC.decode(buffer));
    }

    public WorkbayMenu(int containerId, Inventory inventory, @Nullable WorkbayBlockEntity workbay,
        WorkbaySnapshot initial) {
        super(WBMenus.WORKBAY.get(), containerId);
        this.player = inventory.player;
        this.workbay = workbay;
        this.snapshot = initial;
        this.selectedBay = initial.selectedBay();
    }

    public WorkbaySnapshot snapshot() {
        return snapshot;
    }

    /**
     * What the client asked for, until the server's own answer agrees with it. Without it the
     * optimistic selection below survives for at most five ticks and is then quietly replaced by
     * a snapshot that was built before the click -- which is the whole of OPEN_ISSUES #71, and why
     * setting it client-side was not enough on its own. SELECT_BAY is a clamp on the server and
     * never refuses, so this always clears.
     */
    private int pendingBay = -1;

    /** Applied optimistically on the client before the packet goes out, so clicks feel immediate. */
    public void applySnapshot(WorkbaySnapshot updated) {
        this.snapshot = updated;
        if (pendingBay < 0 || updated.selectedBay() == pendingBay) {
            this.selectedBay = updated.selectedBay();
            pendingBay = -1;
        }
    }

    public int selectedBay() {
        return selectedBay;
    }

    public void setSelectedBayClientSide(int bay) {
        this.selectedBay = bay;
        this.pendingBay = bay;
    }

    /**
     * A menu with no slots still has to refuse a stack the player somehow drags into it, and
     * returning anything but EMPTY here is how vanilla ends up looping forever.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * Reach, and the lock: a stranger's screen closes on the tick the owner locks, the way any
     * menu closes when its block is broken. {@code act} asks the lock first so the refusal is said.
     */
    @Override
    public boolean stillValid(Player player) {
        return workbay == null
            || (!workbay.isRemoved() && player.distanceToSqr(
                workbay.getBlockPos().getX() + 0.5,
                workbay.getBlockPos().getY() + 0.5,
                workbay.getBlockPos().getZ() + 0.5) <= 64.0
                && workbay.record().map(record -> record.admits(player.getUUID())).orElse(true));
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (workbay == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (--cooldown > 0) {
            return;
        }
        cooldown = REFRESH_TICKS;
        actionsThisWindow = 0;
        snapshot = build(workbay, serverPlayer, selectedBay);
        if (!Objects.equals(snapshot, sent)) {
            sent = snapshot;
            PacketDistributor.sendToPlayer(serverPlayer, new SnapshotPacket(containerId, snapshot));
        }
    }

    /** Forces the next tick to send, after an action the player is waiting to see the result of. */
    private void refreshNow() {
        cooldown = 0;
    }

    // --------------------------------------------------------------- actions

    /**
     * Every action from every screen. Guarded once, here, rather than in each handler: a spectator
     * or a player whose menu has moved on must not be able to eject somebody's machine.
     */
    public void act(WorkbayAction action, long arg, Optional<UUID> linkId) {
        act(action, arg, linkId, Optional.empty(), false);
    }

    /**
     * @param back the control was right-clicked, so every cycle here steps to the previous value
     *             instead of the next one. One flag rather than a mirrored action per control:
     *             the guards above are the reason these are actions at all, and they must not be
     *             written out twice.
     */
    public void act(WorkbayAction action, long arg, Optional<UUID> linkId,
        Optional<String> text, boolean back) {
        if (workbay == null || !(player instanceof ServerPlayer serverPlayer)
            || player.isSpectator()) {
            return;
        }
        if (++actionsThisWindow > MAX_ACTIONS_PER_WINDOW) {
            return;
        }
        // The lock, before anything -- including Transfer, which would otherwise let a stranger
        // move their own network into this block and put the owner's to sleep. Budgeted above so
        // a hostile client cannot make the server say "locked" a thousand times a tick.
        WorkbayRecord record = workbay.record().orElse(null);
        if (record != null && record.refuses(serverPlayer)) {
            return;
        }
        if (!stillValid(player)) {
            return;
        }
        // Before the record check, and deliberately: these two are the only things a Workbay
        // holding <em>no</em> network can do, and they are the whole of what its screen offers.
        switch (action) {
            case TRANSFER_NETWORK -> {
                transfer(serverPlayer, linkId);
                return;
            }
            case SET_NETWORK_NAME -> {
                renameNetwork(serverPlayer, linkId, text.orElse("").strip());
                return;
            }
            case NEW_NETWORK -> {
                newNetwork(serverPlayer);
                return;
            }
            default -> { }
        }
        if (record == null) {
            return;
        }
        List<ItemStack> before = inventoryCopy(serverPlayer);
        switch (action) {
            case SELECT_BAY -> selectedBay = (int) Math.clamp(arg, 0, BayGeometry.MAX_BAYS - 1);
            case RACK -> rack(serverPlayer, record);
            case EJECT -> eject(serverPlayer, record);
            case TOGGLE_LOCK -> toggleLock(serverPlayer, record);
            case CYCLE_FACE -> cycleFace(serverPlayer, record, (int) arg, back);
            case PAIR -> pair(serverPlayer, record);
            case INSTALL_UPGRADE -> install(serverPlayer, record, (int) arg);
            case PASTE_BAY -> pasteBay(serverPlayer, record, arg);
            case LINK_FLIP_MODE -> editLink(linkId, link -> link.withMode(link.mode().flip()));
            case LINK_CYCLE_RESOURCE ->
                editLink(linkId, link -> link.withResource(link.resource().step(back)));
            case LINK_TOGGLE_ENABLED -> editLink(linkId, link -> link.withEnabled(!link.enabled()));
            // <b>Detach, not delete.</b> The Connector is still standing in the world and still
            // paired; taking it off a bay must not throw away its filter, rate, speed and name,
            // because the Add list is the only way back and it can only offer links that exist.
            // Breaking the Connector is what deletes a link for good. OPEN_ISSUES #70.
            case LINK_REMOVE -> editLink(linkId, link -> link.withBay(BusConfig.NO_BAY));
            case LINK_CYCLE_TARGET_FACE -> editLink(linkId,
                link -> link.withTargetFace(BusConfig.stepFace(link.targetFace(), back)));
            case SET_FILTER -> editLink(linkId, link -> link.withFilter(
                link.filter().with((int) (arg >>> 32), filterEntry(link, (int) arg))));
            case CYCLE_FILTER_TAG -> editLink(linkId,
                link -> link.withFilter(cycleTag(link, (int) arg, back)));
            case TOGGLE_FILTER_DENY -> editLink(linkId,
                link -> link.withFilter(link.filter().withDeny(!link.filter().deny())));
            case FILTER_FROM_TANK -> filterFromTank(serverPlayer, linkId);
            // Clamped here and nowhere else: the ceiling is a server config, and a client that
            // sends 4000 has to be told no by the side that owns the number.
            case SET_LINK_RATE -> editLink(linkId, link -> link.withRate((int) Math.clamp(arg, 1,
                com.neryos.workbay.config.WorkbayConfig.SERVER.linkMaxRate.get())));
            case SET_LINK_SPEED -> editLink(linkId, link -> link.withSpeed(
                BusConfig.SPEEDS[(int) Math.clamp(arg, 0, BusConfig.SPEEDS.length - 1)]));
            case SET_BAY_NAME -> editBay(serverPlayer, record,
                bay -> bay.withName(text.orElse("").strip()));
            // <b>Names the Connector, not the row.</b> Capped where every other player-typed name
            // in this mod is capped: the packet already limits the string to 64 bytes on the wire,
            // and a name is drawn on a fifty-six pixel column and kept in the registry forever.
            // An internal row has no Connector, so that one names itself. OPEN_ISSUES #97.
            case SET_LINK_NAME -> renameLink(linkId, text.orElse("").strip());
            case CYCLE_REDSTONE -> editBay(serverPlayer, record,
                bay -> bay.withRedstone(bay.redstone().step(back)));
            case ENTER_ROOM -> {
                // Closing first, for the same reason a bay visit does: the player is about to be
                // somewhere this menu's stillValid would refuse, and a screen left open over a
                // teleport is how you get a ghost. The room is the one in bay `arg`.
                serverPlayer.closeContainer();
                com.neryos.workbay.world.RoomVisit.enter(serverPlayer, record, (int) arg);
            }
            // The biome travels as its own id, never as a position in the list: the picker is
            // searchable, so the row a player clicked is a position in a *filtered* list and the
            // two ends would disagree the moment anybody typed. The colour below still packs an
            // ordinal, because eleven colours are ours and cannot be filtered or added to.
            case SET_ROOM_BIOME -> setRoomBiome(serverPlayer, record, (int) (arg & 0xFFFF),
                text.orElse(""));
            case SET_ROOM_COLOUR -> setRoomColour(serverPlayer, record, (int) (arg & 0xFFFF),
                (int) (arg >> 16));
            case SET_ROOM_NAME -> setRoomName(serverPlayer, record, (int) arg,
                text.orElse("").strip());
            case INVITE_ROOM_GUEST -> inviteGuest(serverPlayer, record, (int) arg,
                text.orElse("").strip());
            case CYCLE_ROOM_GUEST -> editGuest(serverPlayer, record, (int) (arg & 0xFFFF),
                (int) (arg >> 16), true);
            case REMOVE_ROOM_GUEST -> editGuest(serverPlayer, record, (int) (arg & 0xFFFF),
                (int) (arg >> 16), false);
            case ENTER_BAY -> {
                // The screen where the player stands first, and the trip only if that cannot
                // happen: a host with the mixins off (SPEC.md §0), a client with them off (arg),
                // or a machine that opens no screen at all. Entering is the fallback, not the
                // way in, and it is deliberately still here - it is the only path that needs
                // nothing patched.
                if (arg == 1 && com.neryos.workbay.remote.RemoteConfig.remoteScreensEnabled()
                    && openRemote(serverPlayer, record)) {
                    return;
                }
                // Closing first: the player is about to be somewhere this menu's stillValid would
                // refuse anyway, and a screen left open over a teleport is how you get a ghost.
                serverPlayer.closeContainer();
                if (!com.neryos.workbay.world.BayVisit.enter(serverPlayer, record, selectedBay,
                    workbay == null ? null : net.minecraft.core.GlobalPos.of(
                        serverPlayer.level().dimension(), workbay.getBlockPos()))) {
                    WorkbaySounds.refuse(serverPlayer,
                        com.neryos.workbay.WorkbayLang.message("bay_enter_failed"));
                }
            }
            case CREATE_INTERNAL_LINK -> createInternalLink(serverPlayer, record, (int) arg);
            case ADD_CHANNEL -> addChannel(serverPlayer, record, linkId, (int) arg);
            case LINK_CYCLE_TARGET_BAY -> editLink(linkId, link -> link.internal()
                ? link.withTarget(GlobalPos.of(WorkbayDimensions.BACKSHOP,
                    BayGeometry.machinePos(record.bayColumn(),
                        otherBay(record, link.bay(), currentTargetBay(record, link), back))))
                : link);
        }
        // This menu has no slots (SPEC.md §4), so the vanilla per-tick sync that normally covers
        // an open menu's slots never touches the player's own inventory while it is open. RACK
        // shrinks the held stack, EJECT and PAIR change it, and none of that reached the client on
        // its own - reported from play as a "shadow" item: the client kept showing what the item
        // looked like before the action, and acting on that stale copy is exactly how an item
        // sync bug turns into a duplication or loss bug. inventoryMenu.broadcastChanges() is a
        // normal AbstractContainerMenu, wired to the player's real inventory, and calling it here
        // forces the same slot diff-and-sync every other menu gets for free once a tick.
        //
        // <b>And that alone reached the hotbar only</b> (OPEN_ISSUES #126). It sends as container
        // 0, and while another menu is open the client applies container 0 to the nine hotbar
        // slots and drops the rest -- so an upgrade fitted from the main inventory, or a machine
        // ejected into it, stayed drawn until the player clicked the slot. Container -2 is
        // vanilla's "this inventory slot, whatever is open", which is what pick-block uses.
        serverPlayer.inventoryMenu.broadcastChanges();
        for (int slot = 0; slot < before.size(); slot++) {
            ItemStack now = serverPlayer.getInventory().getItem(slot);
            if (!ItemStack.matches(before.get(slot), now)) {
                serverPlayer.connection.send(new net.minecraft.network.protocol.game
                    .ClientboundContainerSetSlotPacket(-2, 0, slot, now.copy()));
            }
        }
        refreshNow();
    }

    /** Every slot of the player's own inventory, copied, so an action's changes can be found. */
    private static List<ItemStack> inventoryCopy(ServerPlayer serverPlayer) {
        net.minecraft.world.entity.player.Inventory inventory = serverPlayer.getInventory();
        List<ItemStack> copy = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            copy.add(inventory.getItem(slot).copy());
        }
        return copy;
    }

    /**
     * A registry id, the way every vanilla packet carries one: the network id plus one, so zero can
     * mean "clear this slot". Which registry is the link's own resource - an item link filters
     * items, a fluid link fluids - and an id this server does not know clears the slot rather than
     * being trusted into an array index.
     */
    /**
     * One step round a filter row's tags. SPEC.md §5, OPEN_ISSUES #33.
     *
     * <p>The ring is <b>the resource itself, then each of its tags in registry order</b>, so a row
     * always steps back to what the player dropped in and nothing is ever stranded on a tag they
     * cannot get off. Sorted by id rather than left in whatever order the tag manager hands them
     * back, because the ring has to be the same one on the next click and a hash order is not.
     *
     * <p>A resource in no tags at all steps nowhere, which is the honest answer and is why the
     * gesture is safe to offer on every row.
     */
    private static com.neryos.workbay.bus.BusFilter cycleTag(BusConfig link, int slot,
        boolean back) {
        var filter = link.filter();
        var row = filter.at(slot).orElse(null);
        if (row == null) {
            return filter;
        }
        List<ResourceLocation> tags = switch (link.resource()) {
            case ITEM -> BuiltInRegistries.ITEM.getHolder(
                    net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.ITEM, row.id()))
                .map(holder -> holder.tags().map(tag -> tag.location()).sorted().toList())
                .orElse(List.of());
            case FLUID -> BuiltInRegistries.FLUID.getHolder(
                    net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.FLUID, row.id()))
                .map(holder -> holder.tags().map(tag -> tag.location()).sorted().toList())
                .orElse(List.of());
            case ENERGY, CHEMICAL -> List.of();
        };
        if (tags.isEmpty()) {
            return filter;
        }
        // Position on the ring: 0 is the resource, 1..n are its tags.
        int at = row.tag().map(tag -> tags.indexOf(tag) + 1).orElse(0);
        int next = Math.floorMod(at + (back ? -1 : 1), tags.size() + 1);
        return filter.withTag(slot,
            next == 0 ? Optional.empty() : Optional.of(tags.get(next - 1)));
    }

    private static Optional<ResourceLocation> filterEntry(BusConfig link, int plusOne) {
        if (plusOne <= 0) {
            return Optional.empty();
        }
        return switch (link.resource()) {
            case ITEM -> {
                var item = BuiltInRegistries.ITEM.byId(plusOne - 1);
                yield item == net.minecraft.world.item.Items.AIR
                    ? Optional.empty() : Optional.ofNullable(BuiltInRegistries.ITEM.getKey(item));
            }
            case FLUID -> {
                var fluid = BuiltInRegistries.FLUID.byId(plusOne - 1);
                yield fluid == net.minecraft.world.level.material.Fluids.EMPTY
                    ? Optional.empty() : Optional.ofNullable(BuiltInRegistries.FLUID.getKey(fluid));
            }
            // Nothing to match on, so nothing to put in a slot. SPEC.md §5. A chemical has no
            // item to drag from either, so it joins energy rather than getting a fifth entry kind.
            case ENERGY, CHEMICAL -> Optional.empty();
        };
    }

    /**
     * Opens the selected bay's machine where the player is standing. SPEC.md §0.
     *
     * <p>False for an empty bay and for a machine with no screen of its own, both of which fall
     * through to the trip into the bay rather than leaving the player with a button that did
     * nothing.
     */
    private boolean openRemote(ServerPlayer viewer, WorkbayRecord record) {
        ServerLevel backshop = viewer.server.getLevel(WorkbayDimensions.BACKSHOP);
        if (backshop == null || selectedBay >= record.bayCapacity()
            || record.bay(selectedBay).hosted().isEmpty()) {
            return false;
        }
        // Closing the machine's screen comes back here, on the same bay: the button that opened it
        // lives on this screen, so Escape means "back", not "put everything away". Guarded on the
        // Workbay still being there, because the trip is not instant and a block can be broken.
        WorkbayBlockEntity origin = workbay;
        int bay = selectedBay;
        boolean opened = com.neryos.workbay.remote.RemoteScreens.open(viewer, backshop,
            BayGeometry.machinePos(record.bayColumn(), selectedBay),
            () -> {
                if (origin != null && !origin.isRemoved()) {
                    open(viewer, origin, bay);
                }
            });
        // A furnace screen over the overworld looks like a furnace in the overworld. One chat line
        // says which bay of which network it really is -- the same three facts the bay's own
        // title says on arrival -- and a chat line survives the screen. OPEN_ISSUES #90.
        if (opened) {
            viewer.sendSystemMessage(com.neryos.workbay.WorkbayLang.message("remote_opened",
                com.neryos.workbay.world.BayVisit.describe(record, bay)));
        }
        return opened;
    }

    private void editLink(Optional<UUID> linkId, java.util.function.UnaryOperator<BusConfig> edit) {
        linkId.flatMap(workbay::bus).ifPresent(link -> workbay.addBus(edit.apply(link)));
    }

    /**
     * A name belongs to the <b>Connector</b>, which is one object however many bays it carries a
     * channel on: renaming from any row, from the Add list or from the block's own panel in the
     * world lands on all of them at once. An internal bay-to-bay row has no Connector to name --
     * every internal row shares the Workbay as its anchor -- so that one keeps a name of its own.
     */
    private void renameLink(Optional<UUID> linkId, String name) {
        linkId.flatMap(workbay::bus).ifPresent(link -> {
            if (link.internal()) {
                workbay.addBus(link.withName(name));
            } else {
                workbay.renameConnector(link.connector(), name);
            }
        });
    }

    /**
     * Gives this bay a channel through a Connector. <b>Adding never takes anything away.</b> The
     * same Connector may carry a channel on every bay of the Workbay at once -- energy into the
     * bay holding the power cube, cobble out of the bay holding the generator, through one plate
     * on one machine -- so this only ever mints, and no other bay's rows are touched.
     *
     * <p>The one thing it does not mint is a second blank beside a channel the player took off
     * this Workbay with the X: that one kept its resource, direction, filter and rate, and giving
     * it back is what the player meant. OPEN_ISSUES #70.
     */
    private void addChannel(ServerPlayer serverPlayer, WorkbayRecord record,
        Optional<UUID> connectorId, int bay) {
        connectorId.map(id -> addChannel(workbay, record, id, bay))
            .ifPresent(refusal -> WorkbaySounds.refuse(serverPlayer, refusal));
    }

    /**
     * Static so a gametest presses the button rather than re-writing what the button does.
     *
     * <p>Two refusals, both said out loud because silently doing nothing is how a player concludes
     * the mod is broken. The cap is {@code addBus}'s, in the one place a link is minted; this is
     * the only thing that says so, because the 65th Add otherwise did nothing at all. And a
     * Connector whose block is gone is refused <em>before</em> anything is attached: attached, the
     * runner read CONNECTOR_GONE and the sweep deleted the channel - a waiting channel with a name
     * and a filter, eaten by the one button meant to bring it back. OPEN_ISSUES #112.
     *
     * @return why nothing was added, or null when a channel was
     */
    @Nullable
    public static net.minecraft.network.chat.Component addChannel(WorkbayBlockEntity workbay,
        WorkbayRecord record, UUID connectorId, int bay) {
        if (bay < 0 || bay >= record.bayCapacity()) {
            return null;
        }
        WorkbayRecord.Connector connector = record.connectors().stream()
            .filter(c -> c.id().equals(connectorId)).findFirst().orElse(null);
        if (connector == null || !(workbay.getLevel() instanceof ServerLevel server)) {
            return null;
        }
        if (com.neryos.workbay.content.connector.ConnectorBlock.gone(server.getServer(),
            connector.pos())) {
            // Named the way the Add list names it: its name, or its block's when it has none.
            return com.neryos.workbay.WorkbayLang.message("connector_gone",
                connector.name().isBlank() ? connector.targetBlock().map(net.minecraft.core.registries
                    .BuiltInRegistries.BLOCK::get).map(b -> b.getName().getString()).orElse("It")
                    : connector.name());
        }
        boolean added = workbay.addBus(workbay.linksAt(connector.pos()).stream()
            .filter(BusConfig::detached).findFirst()
            .map(waiting -> waiting.withBay(bay))
            // <b>Born EXTRACT</b>: pulling from the target into the bay. The commonest first
            // channel anybody makes is a chest of ore into a furnace, and born INSERT it moved
            // the furnace's output the other way -- which looks like nothing happened, on the
            // first thing a new player builds. OPEN_ISSUES #56, the owner's call.
            .orElseGet(() -> BusConfig.create(UUID.randomUUID(), bay,
                BusConfig.Resource.ITEM, BusConfig.Mode.EXTRACT,
                connector.pos(), connector.target())
                .withTargetBlock(connector.targetBlock())));
        return added ? null
            : com.neryos.workbay.WorkbayLang.message("link_cap", WorkbayBlockEntity.MAX_LINKS);
    }

    /**
     * Racks the machine in the player's hand. The gate runs first and a rejection is an action-bar
     * message naming the category - the item stays in hand, which is the whole point of rejecting
     * at the registry level rather than after placement (SPEC.md §11).
     */
    /**
     * Whether racking this stack would quietly throw its block entity data away.
     *
     * <p>Asked of a block entity built off the block rather than of the one in the bay, because by
     * the time there is one in the bay the data has already been dropped. Only a stack that is
     * actually carrying data can lose any, so a plain spawner out of the creative menu racks as it
     * always did.
     */
    private static boolean stripsBlockEntityData(ServerPlayer serverPlayer, ItemStack stack) {
        if (serverPlayer.canUseGameMasterBlocks()
            || stack.getOrDefault(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).isEmpty()
            || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)
            || !(blockItem.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entity)) {
            return false;
        }
        net.minecraft.world.level.block.entity.BlockEntity probe = entity.newBlockEntity(
            net.minecraft.core.BlockPos.ZERO, blockItem.getBlock().defaultBlockState());
        return probe != null && probe.onlyOpCanSetNbt();
    }

    /**
     * Fills a chemical link's filter from the tanks it points at, or empties it if it is already
     * exactly that. One button rather than a picker: the tank holds one or two chemicals, and a
     * list to choose from would be a second screen for a choice with no alternatives in it.
     *
     * <p>Reads the target through the level, which means the target's chunk has to be loaded - an
     * unloaded one reports nothing and the filter is left alone rather than being emptied by a
     * click that could not see anything. OPEN_ISSUES #41.
     */
    private void filterFromTank(ServerPlayer serverPlayer, java.util.Optional<java.util.UUID> linkId) {
        linkId.flatMap(workbay::bus).ifPresent(link -> {
            ServerLevel targetLevel = serverPlayer.server.getLevel(link.target().dimension());
            if (targetLevel == null) {
                return;
            }
            List<net.minecraft.resources.ResourceLocation> inTank =
                com.neryos.workbay.compat.MekanismChemicals.chemicalsIn(targetLevel,
                    link.target().pos());
            if (inTank.isEmpty()) {
                WorkbaySounds.refuse(serverPlayer,
                    com.neryos.workbay.WorkbayLang.message("filter_no_chemical"));
                return;
            }
            boolean already = link.filter().ids().equals(inTank);
            editLink(linkId, edited -> edited.withFilter(com.neryos.workbay.bus.BusFilter.ofIds(
                already ? List.of() : inTank, edited.filter().deny())));
        });
    }

    private void rack(ServerPlayer serverPlayer, WorkbayRecord record) {
        if (selectedBay >= record.bayCapacity()) {
            WorkbaySounds.refuse(serverPlayer, com.neryos.workbay.WorkbayLang.message("reject.no_bay"));
            return;
        }
        ServerLevel backshop = serverPlayer.server.getLevel(WorkbayDimensions.BACKSHOP);
        if (backshop == null) {
            return;
        }
        // OPEN_ISSUES #27. Every other refusal in this method says why; these two returned in
        // silence, so clicking the bay slot did nothing at all and the only way to find out what
        // was wrong was to read the Backshop with /execute in workbay:backshop. A button that
        // does nothing is the worst failure a screen has, because there is no next thing to try.
        if (!backshop.getBlockState(
            BayGeometry.machinePos(record.bayColumn(), selectedBay)).isAir()) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("reject.bay_occupied"));
            return;
        }
        ItemStack held = serverPlayer.getMainHandItem();
        if (held.isEmpty()) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("reject.empty_hand"));
            return;
        }
        HostResult verdict = HostChecks.evaluate(held);
        if (!verdict.allowed()) {
            WorkbaySounds.refuse(serverPlayer, verdict.message());
            return;
        }
        // A room has three refusals of its own -- a copy, a room already in a bay, a cycle --
        // asked before the block goes down. SPEC.md §0.
        if (com.neryos.workbay.world.RoomHolding.isRoom(held)) {
            var why = com.neryos.workbay.world.RoomHolding.refusal(
                RoomRegistry.get(serverPlayer.server), record,
                Optional.of(GlobalPos.of(workbay.getLevel().dimension(), workbay.getBlockPos())),
                held);
            if (why.isPresent()) {
                WorkbaySounds.refuse(serverPlayer, why.get());
                return;
            }
        }
        ItemStack one = held.copyWithCount(1);
        // A block whose NBT only an operator may place would be racked *stripped*: SPEC.md §10's
        // step 2 is `BlockItem.updateCustomBlockEntityTag`, which returns false without loading
        // anything when `onlyOpCanSetNbt` and the placer is not a game master. A silk-touched
        // spawner would come back a pig spawner and nothing would have said so. Refusing is the
        // answer rather than bypassing the gate: the gate is vanilla's and it is about who may
        // author block NBT, and a bay is not the place to win that argument. OPEN_ISSUES #15.
        if (stripsBlockEntityData(serverPlayer, one)) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("reject.op_only_data",
                    one.getHoverName()).withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        com.neryos.workbay.world.BayHosting.Outcome outcome = BayHosting.place(backshop,
            record.bayColumn(), selectedBay, one, serverPlayer, Direction.NORTH);
        if (!outcome.placed()) {
            // The placement was undone - either setPlacedBy threw (SPEC.md §10 step 4) or the
            // machine wrote over the bay around it (step 5). Two different things to do next, so
            // two different sentences: one is a broken block, the other is a block too big for a
            // bay, and telling a player the Digital Miner "wouldn't stand up" sends them looking
            // for a bug that is not there.
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message(
                    outcome == com.neryos.workbay.world.BayHosting.Outcome.NEEDS_ROOM
                        ? "reject.needs_room" : "reject.rack_failed",
                    one.getHoverName()));
            return;
        }
        held.shrink(1);
        // The bay may have grown to take this machine (BayHosting#place), so the record is told how
        // wide it now stands. Read out of the Backshop rather than guessed: the world is the source
        // of truth and this is the index.
        WorkbayRecord racked = record.withBay(record.bay(selectedBay)
            .withHosted(Optional.ofNullable(BuiltInRegistries.ITEM.getKey(one.getItem())))
            .withShell(com.neryos.workbay.world.BayBuilder.shellAt(
                backshop, record.bayColumn(), selectedBay)));
        RoomRegistry.get(serverPlayer.server).put(racked);
        if (com.neryos.workbay.world.RoomHolding.isRoom(one)) {
            // Binds the room to this bay, spends its ticket and brings its Connectors over.
            com.neryos.workbay.world.RoomHolding.loaded(serverPlayer.server, racked, selectedBay,
                one);
        }
        workbay.setChanged();
        // In the machine's own voice, at the Workbay rather than in the Backshop where the block
        // actually landed: the bay is nine hundred chunks away and nobody is standing in it.
        WorkbaySounds.racked(workbay.getLevel(), workbay.getBlockPos(),
            backshop.getBlockState(BayGeometry.machinePos(record.bayColumn(), selectedBay)));
    }

    private void eject(ServerPlayer serverPlayer, WorkbayRecord record) {
        ServerLevel backshop = serverPlayer.server.getLevel(WorkbayDimensions.BACKSHOP);
        if (backshop == null) {
            return;
        }
        // Read before the block goes, because the sound is the block's and afterwards it is air.
        BlockState was = backshop.getBlockState(
            BayGeometry.machinePos(record.bayColumn(), selectedBay));
        ItemStack machine = BayHosting.eject(backshop, record.bayColumn(), selectedBay, serverPlayer);
        if (machine.isEmpty()) {
            // #130: the one button on the panel that could be pressed and say nothing.
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("eject_empty"));
            return;
        }
        WorkbaySounds.ejected(workbay.getLevel(), workbay.getBlockPos(), was);
        // Narrow again: eject rebuilds the bay from nothing, so the record says what stands there.
        WorkbayRecord emptied = record.withBay(record.bay(selectedBay)
            .withHosted(Optional.empty())
            .withShell(com.neryos.workbay.world.BayGeometry.SHELL));
        if (record.bay(selectedBay).room().isPresent()) {
            // Stamps the item with the room and a fresh ticket, and takes the room's Connectors
            // and their channels with it. A player inside stays inside (SPEC.md §0).
            emptied = com.neryos.workbay.world.RoomHolding.pulled(serverPlayer.server, emptied,
                selectedBay, machine);
        } else {
            RoomRegistry.get(serverPlayer.server).put(emptied);
        }
        serverPlayer.getInventory().placeItemBackInInventory(machine);
        workbay.setChanged();
    }

    /** Only the owner may lock or unlock; an unlocked Workbay is shared, not given away. */
    private void toggleLock(ServerPlayer serverPlayer, WorkbayRecord record) {
        if (record.refusesNonOwner(serverPlayer)) {
            return;
        }
        RoomRegistry.get(serverPlayer.server).put(record.withLocked(!record.locked()));
        // #130: the lock changed only its own tooltip, so a click on it read as nothing happening.
        WorkbaySounds.confirm(serverPlayer, com.neryos.workbay.WorkbayLang.message(
            record.locked() ? "unlocked_now" : "locked_now"));
    }

    private void cycleFace(ServerPlayer serverPlayer, WorkbayRecord record, int packed,
        boolean back) {
        BusConfig.Resource resource = BusConfig.Resource.values()[
            Math.clamp(packed & 0xF, 0, BusConfig.Resource.values().length - 1)];
        Direction face = Direction.values()[
            Math.clamp((packed >> 4) & 0xF, 0, Direction.values().length - 1)];
        WorkbayRecord.Bay bay = record.bay(selectedBay);
        setFaces(serverPlayer, record, bay.faces().cycled(resource, face, back));
    }

    /**
     * Screen 1's paste. Faces only, which is everything a bay carries today; the redstone mode and
     * the filter join it when they exist, and the action already carries the room for them.
     */
    private void pasteBay(ServerPlayer serverPlayer, WorkbayRecord record, long bits) {
        setFaces(serverPlayer, record, FaceConfig.fromBits(bits));
    }

    private void setFaces(ServerPlayer serverPlayer, WorkbayRecord record, FaceConfig faces) {
        editBay(serverPlayer, record, bay -> bay.withFaces(faces));
    }

    /** Every edit to one bay goes through here, so none of them can forget to drop its caches. */
    private void editBay(ServerPlayer serverPlayer, WorkbayRecord record,
        java.util.function.UnaryOperator<WorkbayRecord.Bay> edit) {
        RoomRegistry.get(serverPlayer.server)
            .put(record.withBay(edit.apply(record.bay(selectedBay))));
        // The link's cached endpoint may have been bound to a face that is no longer allowed.
        workbay.forgetBay(selectedBay);
    }

    /**
     * Screen 1's `+ Pair`: stamps the held Connector with <b>this network</b>, and with no bay.
     *
     * <p>The same pairing the world gesture makes, because there is only one - a Connector belongs
     * to a network and is picked up by whichever bay a player later presses Add on. It used to name
     * the selected bay as well, and that is the row nobody asked for that {@link
     * com.neryos.workbay.content.connector.ConnectorBlock#setPlacedBy} exists to not mint.
     *
     * <p><b>The offhand as well as the main hand, which is the whole of what made this button
     * reachable.</b> It read the main hand only - and a Connector in the main hand is what pairs a
     * Connector by right-clicking the block, which opens no screen at all. So the button could
     * never be pressed with anything in it (OPEN_ISSUES #28). Hold it in the offhand and open the
     * screen with an empty hand.
     */
    private void pair(ServerPlayer serverPlayer, WorkbayRecord record) {
        ItemStack held = serverPlayer.getMainHandItem();
        if (!held.is(WBBlocks.CONNECTOR.get().asItem())) {
            held = serverPlayer.getOffhandItem();
        }
        if (!held.is(WBBlocks.CONNECTOR.get().asItem())) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("pair_needs_connector"));
            return;
        }
        WorkbayBlock.pair(held, record,
            GlobalPos.of(serverPlayer.level().dimension(), workbay.getBlockPos()));
        WorkbaySounds.confirm(serverPlayer,
            com.neryos.workbay.WorkbayLang.message("connector_paired", record.label()));
    }

    /**
     * Bay to bay, inside this Workbay, no Connector. SPEC.md §4's dashed-arrow flow made real: the
     * closed decision that a link needs a physical anchor was about links that leave the block -
     * both ends of this one are bays in the same menu the player already has open, so there is
     * nothing in the world to find, break or audit that this screen does not already show.
     */
    private void createInternalLink(ServerPlayer serverPlayer, WorkbayRecord record, int wanted) {
        int capacity = record.bayCapacity();
        if (capacity < 2) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("internal_link_needs_second_bay"));
            return;
        }
        // The picker names the bay it wants. A bay that is out of range, or the source bay itself,
        // falls through to the next other bay rather than being refused: this action predates the
        // picker and is still sent with no useful arg from a keybind or an older client.
        boolean usable = wanted >= 0 && wanted < capacity && wanted != selectedBay;
        int targetBay = usable ? wanted : otherBay(record, selectedBay, selectedBay, false);
        GlobalPos anchor = GlobalPos.of(serverPlayer.level().dimension(), workbay.getBlockPos());
        GlobalPos target = GlobalPos.of(WorkbayDimensions.BACKSHOP,
            BayGeometry.machinePos(record.bayColumn(), targetBay));
        if (!workbay.addBus(BusConfig.createInternal(UUID.randomUUID(), selectedBay, anchor,
            target))) {
            WorkbaySounds.refuse(serverPlayer, com.neryos.workbay.WorkbayLang.message("link_cap",
                WorkbayBlockEntity.MAX_LINKS));
        }
    }

    /** Which bay an internal link's {@code target} currently points at, by position. */
    private static int currentTargetBay(WorkbayRecord record, BusConfig link) {
        for (int i = 0; i < BayGeometry.MAX_BAYS; i++) {
            if (BayGeometry.machinePos(record.bayColumn(), i).equals(link.target().pos())) {
                return i;
            }
        }
        return link.bay();
    }

    /**
     * The next bay after {@code current} that is not {@code sourceBay} itself, wrapping - or the
     * previous one when {@code back}, which is what a right-click on the row's target asks for.
     */
    private static int otherBay(WorkbayRecord record, int sourceBay, int current, boolean back) {
        int capacity = record.bayCapacity();
        for (int step = 1; step <= capacity; step++) {
            int candidate = Math.floorMod(current + (back ? -step : step), capacity);
            if (candidate != sourceBay) {
                return candidate;
            }
        }
        return current;
    }


    /**
     * Upgrades are consumed on install and there is no removal path (SPEC.md §1), so this takes the
     * item and never gives it back. Refusing at the cap rather than silently eating it matters.
     */
    private void install(ServerPlayer serverPlayer, WorkbayRecord record, int ordinal) {
        if (ordinal < 0 || ordinal >= WorkbayUpgrade.values().length) {
            return;
        }
        WorkbayUpgrade upgrade = WorkbayUpgrade.values()[ordinal];
        if (record.refusesNonOwner(serverPlayer)) {
            return;
        }
        int installed = record.upgrades().installed(upgrade);
        if (installed >= upgrade.max()) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("upgrade_maxed"));
            return;
        }
        int slot = serverPlayer.getInventory().findSlotMatchingItem(new ItemStack(upgrade.item()));
        if (slot < 0) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("upgrade_missing"));
            return;
        }
        serverPlayer.getInventory().removeItem(slot, 1);
        RoomRegistry.get(serverPlayer.server).put(record.withUpgrades(record.upgrades().plus(upgrade)));
        WorkbaySounds.upgraded(workbay.getLevel(), workbay.getBlockPos());
    }

    /**
     * Invites a player into one room, by the name they are known by.
     *
     * <p><b>Only the owner may.</b> Same guard as the Anchor and the colour, and for a stronger
     * reason: this one hands somebody else the key. An invitation starts at
     * {@link com.neryos.workbay.world.RoomGuest#LOOK} because the safe end of the ring is the one
     * an accidental click lands on, and the ring steps upward from there.
     *
     * <p>The name is resolved against the players who are online and nothing else. The profile
     * cache is what a whitelist command reads, and a miss there is a synchronous Mojang HTTP
     * request on the server thread -- once per packet, from any player with a Room Frame, which
     * is why it is not read here (night audit 1A, finding 3). Somebody offline cannot be invited,
     * and the refusal says so rather than adding a guest with an id made up on the spot.
     */
    private void inviteGuest(ServerPlayer serverPlayer, WorkbayRecord record, int index,
        String name) {
        if (record.refusesNonOwner(serverPlayer)) {
            return;
        }
        if (name.isEmpty()) {
            return;
        }
        com.neryos.workbay.world.RoomRegistry registry =
            com.neryos.workbay.world.RoomRegistry.get(serverPlayer.server);
        com.neryos.workbay.world.RoomRecord room = registry.roomInBay(record, index).orElse(null);
        if (room == null) {
            return;
        }
        ServerPlayer online = serverPlayer.server.getPlayerList().getPlayerByName(name);
        if (online == null) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("guest_unknown", name));
            return;
        }
        com.mojang.authlib.GameProfile profile = online.getGameProfile();
        if (profile.getId().equals(record.owner())) {
            WorkbaySounds.refuse(serverPlayer,
                com.neryos.workbay.WorkbayLang.message("guest_is_owner"));
            return;
        }
        registry.putRoom(room.withGuest(profile.getId(), profile.getName(),
            com.neryos.workbay.world.RoomGuest.LOOK));
    }

    /**
     * Steps a guest's level, or takes the invitation away. One method for both because the two
     * differ by one line and share the whole of the lookup -- which room, which guest, and whether
     * the person clicking owns it.
     */
    private void editGuest(ServerPlayer serverPlayer, WorkbayRecord record, int index,
        int guest, boolean step) {
        if (record.refusesNonOwner(serverPlayer)) {
            return;
        }
        com.neryos.workbay.world.RoomRegistry registry =
            com.neryos.workbay.world.RoomRegistry.get(serverPlayer.server);
        com.neryos.workbay.world.RoomRecord room = registry.roomInBay(record, index).orElse(null);
        if (room == null || guest < 0 || guest >= room.guests().size()) {
            return;
        }
        com.neryos.workbay.world.RoomRecord.Guest who = room.guests().get(guest);
        registry.putRoom(step
            ? room.withGuest(who.id(), who.name(), who.level().step())
            : room.withoutGuest(who.id()));
    }

    /**
     * One room's biome, named by its own id.
     *
     * <p><b>Checked against the tag on this side, always.</b> The id arrives from a client, and
     * {@code #workbay:room_biomes} is the whole of what a pack said a room may be - a client that
     * asks for {@code the_end} gets nothing, not a private End.
     */
    private void setRoomBiome(ServerPlayer serverPlayer, WorkbayRecord record, int index,
        String id) {
        com.neryos.workbay.world.RoomRecord room = editableRoom(serverPlayer, record, index);
        net.minecraft.resources.ResourceLocation where =
            net.minecraft.resources.ResourceLocation.tryParse(id);
        List<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> all =
            com.neryos.workbay.world.RoomBiomes.choices(serverPlayer.server.registryAccess());
        var chosen = where == null ? null
            : all.stream().filter(key -> key.location().equals(where)).findFirst().orElse(null);
        if (room == null || chosen == null) {
            return;
        }
        com.neryos.workbay.world.RoomRecord updated = room.withBiome(chosen);
        com.neryos.workbay.world.RoomRegistry.get(serverPlayer.server).putRoom(updated);
        ServerLevel backshop = serverPlayer.server.getLevel(WorkbayDimensions.BACKSHOP);
        if (backshop != null) {
            com.neryos.workbay.world.RoomBiomes.apply(backshop, updated);
        }
        refreshNow();
    }

    /** One room's shell colour, by {@code RoomColour} ordinal. */
    private void setRoomColour(ServerPlayer serverPlayer, WorkbayRecord record, int index,
        int choice) {
        com.neryos.workbay.world.RoomRecord room = editableRoom(serverPlayer, record, index);
        com.neryos.workbay.content.room.RoomColour[] all =
            com.neryos.workbay.content.room.RoomColour.values();
        if (room == null || choice < 0 || choice >= all.length) {
            return;
        }
        com.neryos.workbay.world.RoomRecord painted = room.withColour(all[choice]);
        com.neryos.workbay.world.RoomRegistry.get(serverPlayer.server).putRoom(painted);
        ServerLevel backshop = serverPlayer.server.getLevel(WorkbayDimensions.BACKSHOP);
        if (backshop != null) {
            // The room's own tier, not the network's: repainting must never also grow it.
            com.neryos.workbay.world.RoomBuilder.ensure(backshop, painted, painted.builtTier());
        }
        refreshNow();
    }

    /**
     * What to call one room. Nothing is written to the world: a name is the one room setting that
     * changes no block, which is why this is four lines and repainting is twenty.
     */
    private void setRoomName(ServerPlayer serverPlayer, WorkbayRecord record, int index,
        String name) {
        com.neryos.workbay.world.RoomRecord room = editableRoom(serverPlayer, record, index);
        if (room == null) {
            return;
        }
        // Empty is "no name", not a name that is empty -- the row derives "Room 1" from the index,
        // and a stored blank would draw as a blank.
        com.neryos.workbay.world.RoomRegistry.get(serverPlayer.server).putRoom(
            room.withName(name.isEmpty() ? Optional.empty() : Optional.of(name)));
        refreshNow();
    }

    /**
     * The room in bay {@code index} if this player may change it, or null. Built or not: a
     * colour or a biome chosen before the first entry is painted by that entry.
     */
    @Nullable
    private com.neryos.workbay.world.RoomRecord editableRoom(ServerPlayer serverPlayer,
        WorkbayRecord record, int index) {
        if (record.refusesNonOwner(serverPlayer)) {
            return null;
        }
        return com.neryos.workbay.world.RoomRegistry.get(serverPlayer.server)
            .roomInBay(record, index).orElse(null);
    }

    // -------------------------------------------------------------- snapshot

    /**
     * Reads the whole world state the screens need, once. The hosted machines live in the Backshop,
     * so every read there is guarded by {@code isLoaded} - a status screen must never be the thing
     * that drags a chunk in synchronously on the server thread (SPEC.md §9).
     */
    /**
     * Moves the named network into this block, and leaves the one it came from empty.
     *
     * <p>Three writes, in an order chosen so no tick can ever see two blocks on one network: the
     * block that had this network lets go first, then this block lets go of whatever it was
     * holding, then it takes the new one. A crash between any two leaves a sleeping network, which
     * is a state the mod already has and loses nothing.
     *
     * <p>Ownership is re-checked here rather than trusted from the list. The list is built from the
     * viewer's own networks, but a packet is a thing anybody can send, and the one thing this must
     * not become is a way to take somebody else's factory.
     */
    private void transfer(ServerPlayer player, Optional<UUID> networkId) {
        if (workbay == null || networkId.isEmpty()) {
            return;
        }
        com.neryos.workbay.world.RoomRegistry registry =
            com.neryos.workbay.world.RoomRegistry.get(player.server);
        WorkbayRecord target = registry.byId(networkId.get()).orElse(null);
        if (target == null || !target.owner().equals(player.getUUID())) {
            return;
        }
        if (workbay.workbayId().map(id -> id.equals(target.id())).orElse(false)) {
            return; // already here
        }
        // A network moved into a block standing in one of its own rooms is a cycle. SPEC.md §0.
        if (com.neryos.workbay.world.RoomHolding.wouldCycle(registry, target,
            GlobalPos.of(workbay.getLevel().dimension(), workbay.getBlockPos()))) {
            WorkbaySounds.refuse(player, com.neryos.workbay.WorkbayLang.message("room_cycle"));
            return;
        }

        // Asked before anything moves: whether there was a block to leave is what the line says.
        boolean hadBlock = target.live();

        // 1. The block the network is leaving, if it has one. Loaded or not: the record is the
        //    registry's, and the block finds out it is empty the next time it ticks or is opened.
        //    Only if that block still holds *this* network: a sleeping network's last position is
        //    a block another network may have moved into since; the 09-14 QA lost a
        //    network's screen to it. `transferringASleepingNetworkDoesNotUnbindTheBlockItLastStoodIn`.
        target.lastKnownPos().ifPresent(where -> {
            ServerLevel level = player.server.getLevel(where.dimension());
            if (level != null && level.isLoaded(where.pos())
                && level.getBlockEntity(where.pos()) instanceof WorkbayBlockEntity was
                && was != workbay
                && was.workbayId().map(target.id()::equals).orElse(false)) {
                was.unbind();
            }
        });
        registry.put(target.withDeployedCount(0));

        // 2. Whatever this block was holding goes to sleep, keeping everything it has.
        workbay.record().ifPresent(leaving ->
            registry.put(leaving.withDeployedCount(leaving.deployedCount() - 1)));
        workbay.unbind();

        // 3. And this block becomes that network.
        workbay.bindTo(target.id());
        registry.put(registry.byId(target.id()).orElseThrow().withDeployedCount(1));
        workbay.rememberPosition();
        com.neryos.workbay.WorkbaySounds.confirm(player,
            // #129: a sleeping network has no block to leave behind, and the line said it did.
            com.neryos.workbay.WorkbayLang.message(hadBlock ? "network_transferred"
                : "network_woken", target.label()),
            com.neryos.workbay.init.WBSounds.NETWORK_ARRIVES.get(), 1.4F);
        refreshNow();
    }

    /**
     * Mints a network and binds this block to it. Only for a Workbay holding nothing, and only
     * under {@code maxNetworksPerPlayer} -- both re-checked here, because the screen's decision not
     * to draw the button is a decision about pixels and this is the one about the save.
     */
    private void newNetwork(ServerPlayer player) {
        if (workbay == null || workbay.record().isPresent()) {
            return;
        }
        com.neryos.workbay.world.RoomRegistry registry =
            com.neryos.workbay.world.RoomRegistry.get(player.server);
        if (registry.ownedBy(player.getUUID()).size()
            >= com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.get()) {
            return;
        }
        WorkbayRecord made = registry.create(player.getUUID(),
            player.getGameProfile().getName(), player.serverLevel().getRandom());
        workbay.bindTo(made.id());
        registry.put(made.withDeployedCount(1));
        workbay.rememberPosition();
        com.neryos.workbay.WorkbaySounds.confirm(player,
            com.neryos.workbay.WorkbayLang.message("network_created", made.label()),
            com.neryos.workbay.init.WBSounds.NETWORK_ARRIVES.get(), 1.2F);
        refreshNow();
    }

    /**
     * Renames a network, everywhere. One object, one name - the rule a Connector already lives by
     * (SPEC.md §0): the list, the chat line when a Workbay joins it, the Connector tooltip stamped
     * on the next pairing, all read this one string.
     */
    private void renameNetwork(ServerPlayer player, Optional<UUID> networkId, String name) {
        if (networkId.isEmpty() || name.isBlank()) {
            return;
        }
        com.neryos.workbay.world.RoomRegistry registry =
            com.neryos.workbay.world.RoomRegistry.get(player.server);
        registry.byId(networkId.get())
            .filter(record -> record.owner().equals(player.getUUID()))
            // Bounded for the same reason every other name in the mod is: a name is drawn in a
            // column, and a thousand-character one is a row nothing else fits beside.
            .ifPresent(record -> registry.put(record.withName(
                name.length() > 32 ? name.substring(0, 32) : name)));
        refreshNow();
    }

    /**
     * Opens the Workbay screen on one bay. <b>The only place that knows how</b>: the block's
     * right-click and {@link com.neryos.workbay.world.BayVisit}'s return trip both come here, so a
     * player who entered a bay from this screen gets <em>this screen</em> back rather than an empty
     * hand - closing the machine's screen should undo the visit, not undo everything.
     */
    public static void open(ServerPlayer viewer, WorkbayBlockEntity workbay, int bay) {
        // The lock, at the only door: a stranger gets the refusal and no screen -- not the bays,
        // not NETWORKS, nothing to read. BayVisit's return trip lands here too, so an owner who
        // locked while a guest was in a bay leaves them with the message rather than the screen.
        if (workbay.record().map(record -> record.refuses(viewer)).orElse(false)) {
            return;
        }
        viewer.openMenu(new net.minecraft.world.SimpleMenuProvider(
            (id, inventory, who) -> new WorkbayMenu(id, inventory, workbay,
                build(workbay, viewer, bay)),
            com.neryos.workbay.WorkbayLang.gui("title")),
            buffer -> WorkbaySnapshot.STREAM_CODEC.encode(buffer, build(workbay, viewer, bay)));
    }

    public static WorkbaySnapshot build(WorkbayBlockEntity workbay, ServerPlayer player, int selected) {
        WorkbayRecord record = workbay.record().orElse(null);
        // <b>A Workbay holding no network still has a screen.</b> EMPTY here meant a block placed
        // at the quota opened on nothing at all -- no bays, and no list either, so the only way to
        // find out what had happened was the chat line. It gets the list and nothing else.
        if (record == null) {
            return new WorkbaySnapshot("", false, 1, 0, 0, 1, List.of(), List.of(),
                WorkbayRecord.Upgrades.NONE, networksOf(workbay, player),
                com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.get(),
                false, List.of(), false, List.of());
        }
        ServerLevel backshop = player.server.getLevel(WorkbayDimensions.BACKSHOP);

        List<WorkbaySnapshot.Bay> bays = new ArrayList<>();
        for (int index = 0; index < BayGeometry.MAX_BAYS; index++) {
            bays.add(readBay(record, index, backshop, workbay));
        }

        // A record written before Connectors were objects knows only the rows they carry, so the
        // Connector each row is anchored by is minted here, once, keeping the name that row had.
        // Nothing else in the mod can mint one -- placing the block is the only other way in.
        List.copyOf(workbay.buses()).stream()
            .filter(link -> !link.internal() && workbay.connectorAt(link.connector()).isEmpty())
            .forEach(link -> workbay.addConnector(new WorkbayRecord.Connector(UUID.randomUUID(),
                link.connector(), link.name(), link.target(), link.targetBlock())));

        List<WorkbaySnapshot.Link> links = new ArrayList<>();
        List<BusConfig> backfill = new ArrayList<>();
        for (BusConfig link : List.copyOf(workbay.buses())) {
            Optional<ResourceLocation> block = targetBlockOf(player, link);
            if (link.targetBlock().isEmpty() && isRealBlock(block)) {
                backfill.add(link.withTargetBlock(block));
            }
            // Stamped, not stored: the row travels carrying its Connector's name so that every
            // reader on the client -- the row, the tooltip, the flow map, the Add list -- reads
            // one name for one block without any of them having to know where it lives.
            links.add(new WorkbaySnapshot.Link(link.withName(workbay.nameOf(link)),
                workbay.busStatus(link.id()), block,
                link.internal() ? Optional.of(currentTargetBay(record, link)) : Optional.empty(),
                roomOf(player, record, link)));
        }
        // After the loop, not inside it: addBus rewrites the record's list, and one write per link
        // that has something to learn is still nothing next to a write per poll.
        backfill.forEach(workbay::addBus);

        // A locked Workbay no longer opens for a stranger at all, but a screen open on the tick the
        // owner locks polls once more before stillValid closes it. That one snapshot carries no
        // Connector or target coordinates, room names or guest lists. Night 2026-09-11, 1A #7.
        List<WorkbaySnapshot.Room> rooms = readRooms(player, record);
        List<WorkbayRecord.Connector> connectors = workbay.connectors();
        if (record.locked() && !record.owner().equals(player.getUUID())) {
            GlobalPos nowhere = GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, BlockPos.ZERO);
            links = links.stream().map(link -> new WorkbaySnapshot.Link(
                link.config().withPlaces(nowhere, nowhere), link.status(), link.targetBlock(),
                link.targetBay(), link.targetRoom())).toList();
            rooms = rooms.stream().map(room -> new WorkbaySnapshot.Room(room.index(), "",
                room.interior(), room.built(), room.biome(), room.colour(), List.of())).toList();
            connectors = List.of();
        }

        return new WorkbaySnapshot(record.label(), record.locked(), record.bayCapacity(), selected,
            workbay.energy().getEnergyStored(), workbay.energy().getMaxEnergyStored(),
            bays, links, record.upgrades(), networksOf(workbay, player),
            com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.get(),
            com.neryos.workbay.remote.RemoteConfig.remoteScreensEnabled(),
            rooms,
            com.neryos.workbay.config.WorkbayConfig.SERVER.chargesForRunning(),
            connectors);
    }

    /**
     * The viewer's own networks, in a stable order, saying which one this block is holding.
     *
     * <p>Sorted by name so the list does not reshuffle between two polls of a {@code HashMap} -
     * rows that move under the cursor is the fault OPEN_ISSUES #74 already paid for once.
     * {@code where} is the network's block, and only when one is actually standing on it: a
     * {@code lastKnownPos} left over from a broken Workbay would draw a sleeping network as live
     * and point the player at a hole in the ground.
     */
    private static List<WorkbaySnapshot.Net> networksOf(WorkbayBlockEntity workbay,
        ServerPlayer player) {
        UUID here = workbay.workbayId().orElse(null);
        return com.neryos.workbay.world.RoomRegistry.get(player.server)
            .ownedBy(player.getUUID()).stream()
            .sorted(java.util.Comparator.comparing(WorkbayRecord::label))
            .map(record -> new WorkbaySnapshot.Net(record.id(), record.label(),
                record.id().equals(here), record.live() ? record.lastKnownPos() : Optional.empty(),
                (int) record.bays().stream().filter(b -> b.hosted().isPresent()).count(),
                record.connectors().size()))
            .toList();
    }

    /** One entry per bay holding a room, keyed by the bay. */
    private static List<WorkbaySnapshot.Room> readRooms(ServerPlayer player, WorkbayRecord record) {
        List<WorkbaySnapshot.Room> out = new ArrayList<>();
        com.neryos.workbay.world.RoomRegistry.get(player.server).roomsOf(record)
            .forEach((bay, room) -> out.add(new WorkbaySnapshot.Room(bay, room.label(),
                room.interior(), room.built(), room.effectiveBiome().location().toString(),
                room.colour(),
                // The owner's alone. Anybody may open an unlocked Workbay, so sending every room's
                // guest list on the snapshot would let one guest read who else was invited to
                // every other room -- which is the thing the door screen already refuses to do.
                record.owner().equals(player.getUUID()) ? room.guests() : List.of())));
        return out;
    }

    private static WorkbaySnapshot.Bay readBay(WorkbayRecord record, int index,
        @Nullable ServerLevel backshop, WorkbayBlockEntity workbay) {
        WorkbayRecord.Bay bay = record.bay(index);
        if (index >= record.bayCapacity()) {
            return new WorkbaySnapshot.Bay(index, Optional.empty(), 0, 0,
                WorkbaySnapshot.State.LOCKED, bay.faces(), bay.name(), bay.redstone());
        }
        if (bay.hosted().isEmpty()) {
            return new WorkbaySnapshot.Bay(index, Optional.empty(), 0, 0,
                WorkbaySnapshot.State.EMPTY, bay.faces(), bay.name(), bay.redstone());
        }
        // A room has no ports and that is not a fault: idle, never inert.
        if (bay.room().isPresent()) {
            return new WorkbaySnapshot.Bay(index, bay.hosted(), 0, 0,
                WorkbaySnapshot.State.IDLE, bay.faces(), bay.name(), bay.redstone());
        }
        int energy = 0;
        int capacity = 0;
        WorkbaySnapshot.State state = WorkbaySnapshot.State.IDLE;
        BlockPos machine = BayGeometry.machinePos(record.bayColumn(), index);
        if (backshop != null && backshop.isLoaded(machine)) {
            IEnergyStorage store = null;
            boolean anyPort = false;
            for (Direction side : Direction.values()) {
                IEnergyStorage found = backshop.getCapability(Capabilities.EnergyStorage.BLOCK, machine, side);
                if (found != null && store == null) {
                    store = found;
                }
                anyPort |= found != null
                    || backshop.getCapability(Capabilities.ItemHandler.BLOCK, machine, side) != null;
            }
            if (store != null) {
                energy = store.getEnergyStored();
                capacity = store.getMaxEnergyStored();
            }
            BlockState here = backshop.getBlockState(machine);
            // Inert is the two-phase gate's non-blocking half: the machine is racked and staying
            // racked, but nothing can reach it, so the row says so instead of ejecting it.
            state = here.isAir() ? WorkbaySnapshot.State.EMPTY
                : !anyPort ? WorkbaySnapshot.State.INERT
                : runningAnyLink(workbay, index) ? WorkbaySnapshot.State.RUNNING
                : WorkbaySnapshot.State.IDLE;
        }
        return new WorkbaySnapshot.Bay(index, bay.hosted(), energy, capacity, state, bay.faces(),
            bay.name(), bay.redstone());
    }

    private static boolean runningAnyLink(WorkbayBlockEntity workbay, int bay) {
        return workbay.buses().stream().filter(link -> link.bay() == bay)
            .anyMatch(link -> workbay.busStatus(link.id()) == BusRunner.BusStatus.RUNNING);
    }

    /**
     * What the Connector is stuck to, for the row's target text and the flow map's icon. Never
     * loads a chunk to find out - and falls back to what the link remembered when it was made,
     * which is the ordinary case: a link's target is nearly always in a chunk nobody is standing
     * in, and before the fallback existed every such row read as two coordinates and every such
     * flow node drew an empty box.
     *
     * <p>A link made before {@link BusConfig#targetBlock} existed remembers <b>nothing</b>, so it
     * reads as two coordinates for the rest of its life however often anyone stands next to it -
     * and a flow map of number-boxes is a map of nowhere. {@code build} therefore writes the live
     * answer back the first time there is one. See {@link #isRealBlock}: air is not one.
     */
    private static Optional<ResourceLocation> targetBlockOf(ServerPlayer player, BusConfig link) {
        ServerLevel level = player.server.getLevel(link.target().dimension());
        if (level == null || !level.isLoaded(link.target().pos())) {
            return link.targetBlock();
        }
        return Optional.ofNullable(
            BuiltInRegistries.BLOCK.getKey(level.getBlockState(link.target().pos()).getBlock()));
    }

    /**
     * Which of this network's rooms a link's Connector stands in, by name.
     *
     * <p>Only this network's own rooms are consulted, and only the {@link com.neryos.workbay.bus.BusConfig#connector()}
     * end: a Connector is paired to exactly one Workbay and one bay, so a link into a room can only
     * ever be a link into a room of the network that owns it. Nothing here can name somebody
     * else's room, which is what makes this safe to send to a client.
     */
    private static Optional<Integer> roomOf(ServerPlayer player, WorkbayRecord record,
        BusConfig link) {
        if (!link.connector().dimension().equals(WorkbayDimensions.BACKSHOP)) {
            return Optional.empty();
        }
        for (var entry : RoomRegistry.get(player.server).roomsOf(record).entrySet()) {
            if (entry.getValue().contains(link.connector().pos())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Air is what a target that has been <em>broken</em> reads as, and what an unloaded position
     * would read as if anything ever asked one. Stamping it would fix the wrong answer in place
     * for good, because the backfill only ever fires on an empty stamp.
     */
    private static boolean isRealBlock(Optional<ResourceLocation> block) {
        return block.isPresent()
            && !block.get().equals(ResourceLocation.withDefaultNamespace("air"));
    }
}
