package com.neryos.workbay.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.neryos.workbay.Workbay;
import com.neryos.workbay.WorkbaySounds;
import com.neryos.workbay.init.WBAttachments;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Set;
import java.util.UUID;

/**
 * Standing in a room. SPEC.md §8.
 *
 * <p>Deliberately not {@link BayVisit}. A bay visit is a screen with a trip hidden inside it and
 * ends the moment the screen closes; a room is a place, and its occupant is meant to stand there
 * with nothing open, log out in it, and log back in where they left off. The two rules are
 * opposites, so they are two classes rather than one with a flag in it.
 *
 * <p>What the two share is the door: {@link BayVisit#admit} is the only route into the Backshop,
 * and rooms go through it rather than opening a second one.
 */
@EventBusSubscriber(modid = Workbay.MOD_ID)
public final class RoomVisit {
    private RoomVisit() {}

    /**
     * The room an occupant is in. Persisted, and that is the point: SPEC.md §14 says a player who
     * disconnects inside a room is still inside it. <b>No return address</b>: where Leave goes is
     * worked out when Leave is pressed, from where the room's Workbay stands then
     * ({@link RoomHolding#exit}), because the place a player came in from is stale the moment the
     * block moves and, for a nested room, is inside a room that may since have been pulled out.
     */
    public record Inside(UUID room) {
        public static final Codec<Inside> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("room").forGetter(Inside::room)
        ).apply(i, Inside::new));
    }

    /**
     * {@code hasData}, never {@code getData}: asking for an absent attachment materialises its
     * default, which here is null, and NeoForge then fails to serialize it at the next player save.
     */
    public static boolean isInside(ServerPlayer player) {
        return player.hasData(WBAttachments.ROOM_RETURN.get());
    }

    /** Which room they are in, as far as the attachment knows. Empty when they are in none. */
    public static java.util.Optional<UUID> roomOf(ServerPlayer player) {
        return isInside(player)
            ? java.util.Optional.of(player.getData(WBAttachments.ROOM_RETURN.get()).room())
            : java.util.Optional.empty();
    }

    // ------------------------------------------------------------- permission

    /**
     * Who may be in a room. SPEC.md 8.
     *
     * <p><b>The owner, and whoever the owner invited to that room.</b> Nothing else, including an
     * operator: an op who wants in can invite themselves from the screen, and a silent exception
     * here would be a rule that is true until it is not.
     *
     * <p>A room in nobody's bay has no owner to admit anybody, which is the right answer: it can
     * only be entered from the bay that holds it. Somebody already inside when it was pulled out
     * is {@link #mayStay}'s question, not this one.
     */
    public static boolean mayEnter(RoomRegistry registry, UUID player, RoomRecord room) {
        return registry.ownerOf(room).map(owner -> owner.equals(player)).orElse(false)
            || room.guestLevel(player).isPresent();
    }

    /** May change the room itself: the owner, or a guest invited at {@link RoomGuest#BUILD}. */
    public static boolean mayBuild(RoomRegistry registry, UUID player, RoomRecord room) {
        return registry.ownerOf(room).map(owner -> owner.equals(player)).orElse(false)
            || room.guestLevel(player).filter(level -> level == RoomGuest.BUILD).isPresent();
    }

    /**
     * May work what is standing here without rebuilding it: {@link RoomGuest#USE} and up.
     *
     * <p>Separate from {@link #mayBuild} because they are the two halves the middle level splits.
     * Opening a chest is a change to what is <em>in</em> the room and breaking the chest is a
     * change to the room, and a factory has people meant to do the first and not the second.
     */
    public static boolean mayUse(RoomRegistry registry, UUID player, RoomRecord room) {
        return registry.ownerOf(room).map(owner -> owner.equals(player)).orElse(false)
            || room.guestLevel(player).filter(RoomGuest::mayUse).isPresent();
    }

    /**
     * True when this player may still be standing where they are. Used on login and every tick.
     *
     * <p>A room out of every bay keeps whoever is in it: SPEC.md §0 lets a room be pulled with a
     * player inside, and nothing happens to them -- the room sleeps and Leave still works.
     */
    public static boolean mayStay(ServerPlayer player) {
        if (!isInside(player)) {
            return false;
        }
        RoomRegistry registry = RoomRegistry.get(player.server);
        RoomRecord room = registry
            .room(player.getData(WBAttachments.ROOM_RETURN.get()).room()).orElse(null);
        return room != null && room.contains(player.blockPosition())
            && (registry.holderOf(room).isEmpty() || mayEnter(registry, player.getUUID(), room));
    }

    /**
     * Sends a player into the room standing in bay {@code bay} of {@code record}, building it on
     * its first visit.
     *
     * @return false when there is no room to enter: no dimension, or nothing but a machine in
     *         that bay
     */
    public static boolean enter(ServerPlayer player, WorkbayRecord record, int bay) {
        ServerLevel backshop = player.server.getLevel(WorkbayDimensions.BACKSHOP);
        RoomRegistry registry = RoomRegistry.get(player.server);
        RoomRecord room = registry.roomInBay(record, bay).orElse(null);
        if (backshop == null || room == null) {
            return false;
        }
        // A Workbay is a block anybody may right-click, and until this line ENTER_ROOM was the one
        // action on the screen with no owner check on it at all -- not even the lock. Standing at
        // somebody's Workbay was standing in every room they own.
        if (!mayEnter(registry, player.getUUID(), room)) {
            WorkbaySounds.refuse(player,
                com.neryos.workbay.WorkbayLang.message("room_not_yours"));
            return false;
        }
        // Every entry re-checks the shell, because the first one spends it and a repaint while
        // nobody was in here has to land before anybody stands in it.
        RoomRecord built = RoomBuilder.ensure(backshop, room, room.tier());
        if (!built.built()) {
            return false;
        }
        if (built != room) {
            registry.putRoom(built);
        }
        player.setData(WBAttachments.ROOM_RETURN.get(), new Inside(built.id()));
        Vec3 spot = RoomGeometry.entrySpot(built.region());
        BayVisit.admit(player, () -> player.teleportTo(backshop, spot.x, spot.y, spot.z, Set.of(),
            RoomGeometry.ENTRY_YAW, 0.0F));
        if (!player.level().dimension().equals(WorkbayDimensions.BACKSHOP)) {
            player.removeData(WBAttachments.ROOM_RETURN.get());
            return false;
        }
        // Where you are and the way out, once, on arrival. OPEN_ISSUES #119: the first exit was
        // found by right-clicking walls.
        WorkbaySounds.note(player, com.neryos.workbay.WorkbayLang.message("room_entered",
            built.label()));
        return true;
    }

    /**
     * Gets an occupant out. Silent and harmless if they are not one.
     *
     * <p><b>Leave always works</b> (SPEC.md §0): the way out is read off the registry, not off a
     * block, so it works with the Workbay broken, moved, or holding nothing. Where it goes is
     * {@link RoomHolding#exit}'s answer; a room nested in a room lands the player in the parent
     * room, beside the Workbay that holds it, and still <em>inside</em> as far as this class is
     * concerned.
     */
    public static boolean leave(ServerPlayer player) {
        if (!isInside(player)) {
            return false;
        }
        RoomRegistry registry = RoomRegistry.get(player.server);
        RoomRecord room = registry.room(player.getData(WBAttachments.ROOM_RETURN.get()).room())
            .orElse(null);
        player.removeData(WBAttachments.ROOM_RETURN.get());
        RoomHolding.Exit exit = RoomHolding.exit(player, room);
        WorkbaySounds.travel(player, () -> player.teleportTo(exit.level(), exit.at().x,
            exit.at().y, exit.at().z, Set.of(), player.getYRot(), player.getXRot()));
        // Out into the parent room: the record has to say so before the standing rule looks.
        if (exit.level().dimension().equals(WorkbayDimensions.BACKSHOP)) {
            registry.roomAt(player.blockPosition()).ifPresent(parent ->
                player.setData(WBAttachments.ROOM_RETURN.get(), new Inside(parent.id())));
        }
        // And the screen of the Workbay the player is now standing beside, the way a bay visit
        // gives it back. OPEN_ISSUES #69.
        exit.workbay().ifPresent(at -> BayVisit.oweTheScreen(player, at, exit.bay()));
        return true;
    }

    /**
     * A player who logs in <b>already inside</b> a room has their room brought up to date, because
     * nothing else will: {@link RoomBuilder#ensure} runs on entry, and this player is not entering.
     *
     * <p>It is not a nicety. A room built by an older version of the mod has an older version's
     * shell, and the way out is a property of the shell - somebody who logged out in a room and
     * came back after an update would be standing in a box whose walls do not open.
     */
    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isInside(player)) {
            return;
        }
        ServerLevel backshop = player.server.getLevel(WorkbayDimensions.BACKSHOP);
        RoomRegistry registry = RoomRegistry.get(player.server);
        RoomRecord room = registry.room(player.getData(WBAttachments.ROOM_RETURN.get()).room())
            .orElse(null);
        if (backshop == null || room == null) {
            return;
        }
        // Removed from the room while they were offline: they must not wake up in it. Checked
        // before the shell is repaired, because repairing a room for somebody who is about to be
        // put out of it is work done for nobody.
        if (registry.holderOf(room).isPresent() && !mayEnter(registry, player.getUUID(), room)) {
            leave(player);
            return;
        }
        RoomRecord fixed = RoomBuilder.ensure(backshop, room, room.tier());
        if (fixed != room) {
            registry.putRoom(fixed);
        }
    }

    // ----------------------------------------------------------- block guards

    /**
     * What a guest may not do here, asked twice: {@code changesTheRoom} separates breaking, placing
     * and attacking - which need {@link RoomGuest#BUILD} - from opening and clicking,
     * which need {@link RoomGuest#USE}.
     *
     * <p>Right-clicking is on the second list on purpose. Opening a chest does not change a block
     * and does change what is in it, which is the whole of what a room in a chain holds -- a level
     * that let a LOOK guest empty every barrel would be "look only" in name, and one that made a
     * USE guest break the barrel to reach the ingots would be no level at all.
     *
     * <p>Scoped to a room's own interior. Everything else in the Backshop is void with a bedrock
     * floor that {@link BayVisit} already refuses to let anybody stand on.
     */
    private static boolean refused(net.minecraft.world.entity.Entity who,
        net.minecraft.core.BlockPos where, boolean changesTheRoom) {
        if (!(who instanceof ServerPlayer player)
            || !player.level().dimension().equals(WorkbayDimensions.BACKSHOP)) {
            return false;
        }
        RoomRegistry registry = RoomRegistry.get(player.server);
        RoomRecord room = registry.roomAt(where).orElse(null);
        if (room == null) {
            return false;
        }
        UUID id = player.getUUID();
        boolean uses = mayUse(registry, id, room);
        if (changesTheRoom ? mayBuild(registry, id, room) : uses) {
            return false;
        }
        // Which refusal, because the two say different things to the player: somebody who may work
        // the room and just tried to break a barrel is not being told they may only look at it.
        WorkbaySounds.refuse(player,
            com.neryos.workbay.WorkbayLang.message(uses ? "room_use_only" : "room_look_only"));
        return true;
    }

    @SubscribeEvent
    public static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (refused(event.getPlayer(), event.getPos(), true)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlace(
        net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (refused(event.getEntity(), event.getPos(), true)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClick(
        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (refused(event.getEntity(), event.getPos(), false)) {
            event.setCanceled(true);
        }
    }

    /**
     * A bucket is not a block. The client PASSes {@code useItemOn} on one and sends
     * {@code ServerboundUseItem}, so no {@code EntityPlaceEvent} fires and {@link #onPlace} never
     * sees the lava. In a room it needs {@link RoomGuest#BUILD} like any placement; outside every
     * room -- a bay, whose standing spot the next visitor lands on -- nobody pours anything.
     * Night 2026-09-11, 1A #6.
     */
    @SubscribeEvent
    public static void onRightClickItem(
        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        net.minecraft.world.item.Item item = event.getItemStack().getItem();
        if (!(item instanceof net.minecraft.world.item.BucketItem
            || item instanceof net.minecraft.world.item.SolidBucketItem
            || item instanceof net.minecraft.world.item.DispensibleContainerItem)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !player.level().dimension().equals(WorkbayDimensions.BACKSHOP)) {
            return;
        }
        net.minecraft.core.BlockPos where = player.blockPosition();
        if (refused(player, where, true)
            || RoomRegistry.get(player.server).roomAt(where).isEmpty()) {
            event.setCanceled(true);
        }
    }

    /**
     * And the same for whatever is <em>standing</em> in the room, not only what is built into it.
     *
     * <p>Blocks were the obvious half and the only half at first, which left a look-only guest
     * free to smash an item frame off the wall, empty a chest minecart or kill the cow somebody was
     * keeping - every one of them a change to what is in the room, made by somebody invited to look
     * at it. The two events are the entity mirror of break and right-click; the position asked
     * about is the entity's own.
     */
    @SubscribeEvent
    public static void onAttackEntity(
        net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (refused(event.getEntity(), event.getTarget().blockPosition(), true)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(
        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (refused(event.getEntity(), event.getTarget().blockPosition(), false)) {
            event.setCanceled(true);
        }
    }

    /**
     * Keeps an occupant honest, and nothing else: a room has no screen to watch and no timeout.
     *
     * <p>The one thing checked is that they are still inside the room they were sent to. Outside
     * it, the Backshop is void with a bedrock floor and nothing to do, so whatever moved them -
     * a teleport, another mod, a command - puts them back where they came from instead.
     */
    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isInside(player)) {
            return;
        }
        if (!player.level().dimension().equals(WorkbayDimensions.BACKSHOP)) {
            // Something else took them out. The record must not outlive the visit, or it is a
            // return address for a room they are no longer in.
            player.removeData(WBAttachments.ROOM_RETURN.get());
            return;
        }
        // Inside the bounds of the room they were sent to, and still allowed in it. Regions are
        // 512 blocks apart, which is nothing to somebody who has just launched himself upward with
        // an item from another mod -- and being un-invited while standing in a room has to take
        // effect where the player is, not the next time they ask to come in.
        if (!mayStay(player)) {
            leave(player);
        }
    }
}
