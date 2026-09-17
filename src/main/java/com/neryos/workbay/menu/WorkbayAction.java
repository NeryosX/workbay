package com.neryos.workbay.menu;

/**
 * Everything the screens can ask the server to do. SPEC.md §4.
 *
 * <p>One enum and one packet rather than a payload type per button: every one of these is
 * "an id and at most one number", and thirteen near-identical payload classes would be thirteen
 * places to forget the container-id guard.
 */
public enum WorkbayAction {
    /** Which bay the screen is showing. Server-side because the face buttons act on it. */
    SELECT_BAY,
    /** Rack whatever the player is holding into the selected bay. */
    RACK,
    /** Take the selected bay's machine back out, with everything inside it. */
    EJECT,
    TOGGLE_LOCK,
    /** {@code arg} packs the resource ordinal and the direction ordinal. */
    CYCLE_FACE,
    /** Pair the held Connector to this Workbay's network. No bay: a Connector is never on one. */
    PAIR,
    LINK_FLIP_MODE,
    LINK_CYCLE_RESOURCE,
    LINK_TOGGLE_ENABLED,
    LINK_REMOVE,
    /** {@code arg} is a {@link com.neryos.workbay.content.workbay.WorkbayUpgrade} ordinal. */
    INSTALL_UPGRADE,
    /** {@code arg} is a whole {@link com.neryos.workbay.world.FaceConfig} in bits. */
    PASTE_BAY,
    /**
     * One entry of a link's filter. {@code link} names the row; {@code arg} packs the slot in its
     * high 32 bits and, in the low 32, the network id of the item or fluid plus one - zero clears
     * that slot. Which registry the id belongs to is the link's own resource, the same way
     * {@link com.neryos.workbay.bus.BusFilter} reads its entries.
     */
    SET_FILTER,
    /**
     * Steps one filter row through the tags its resource belongs to, and round to the resource
     * itself. {@code arg} is the slot.
     *
     * <p>Server-side because the tag list is the server's: a client knows the tags it was synced,
     * which is every tag, but the order has to be the same one the next click steps from, and a
     * ring computed on two machines from two orderings is a ring that goes backwards. Shift-click
     * is the gesture, which is what every mod in the genre uses for "the other question about this
     * slot". OPEN_ISSUES #33.
     */
    CYCLE_FILTER_TAG,
    /** Whitelist or blacklist, for the link {@code link} names. */
    TOGGLE_FILTER_DENY,
    /**
     * Lists whatever is standing in a chemical link's target tanks right now, and clears the list
     * again when it already holds them.
     *
     * <p>The three other resources fill a filter by dragging a picture of the thing into a ghost
     * slot. A chemical has no item and no bucket to drag, so the only handle a player has on one is
     * the tank it is in - which means the <em>server</em> reads it, because the client was never
     * told what a Connector's target is holding. OPEN_ISSUES #41.
     */
    FILTER_FROM_TANK,
    /**
     * Bay to bay, no Connector. {@code arg} is the bay to point at; an arg that names the source
     * bay itself, or a bay this Workbay does not have, falls back to the next other bay.
     */
    CREATE_INTERNAL_LINK,
    /**
     * Gives bay {@code arg} a channel through the Connector {@code link} names - <b>the Connector's
     * own id, not a row's</b>. Always mints; never moves a row off another bay, because one
     * Connector is meant to be used from as many bays as the player likes. SPEC.md §0.
     *
     * <p>Named LINK_ASSIGN_BAY while Add moved rows between bays. Renamed in place rather than
     * appended: an action travels as its ordinal, so moving this one would silently rename every
     * action after it.
     */
    ADD_CHANNEL,
    /** Internal links only. {@code link} names the row; steps its target to the next other bay. */
    LINK_CYCLE_TARGET_BAY,
    /** {@code link} names the row; steps which face of the target block it reaches into. */
    LINK_CYCLE_TARGET_FACE,
    /** {@code text} is the selected bay's new name; empty falls back to the machine's own. */
    SET_BAY_NAME,
    /**
     * {@code link} names the row and {@code text} its new name; empty falls back to what the row
     * derives from its target. Appended at the end of this enum on purpose -- {@link
     * com.neryos.workbay.network.ActionPacket} puts an action on the wire as its ordinal, so
     * inserting one in the middle would silently rename every action after it.
     */
    SET_LINK_NAME,
    /** Cycles the selected bay's {@link com.neryos.workbay.world.RedstoneMode}. */
    CYCLE_REDSTONE,
    /**
     * <b>Dead, and kept.</b> It stepped the Assay's skim rate; the Assay and the Levy it banked
     * are gone. An action travels as its <em>ordinal</em>, so removing this one would silently
     * rename every action after it on the wire -- the same reason every new action is appended at
     * the end of this enum. The slot stays and the server does nothing with it.
     */
    UNUSED_WAS_SET_SKIM,
    /**
     * <b>Dead, and kept.</b> It opened Bay View, which is deleted (OPEN_ISSUES #65). Kept for the
     * reason {@link #UNUSED_WAS_SET_SKIM} is: an action travels as its ordinal.
     */
    UNUSED_WAS_OPEN_BAY_VIEW,
    /**
     * Gets the player to the selected machine's own screen (SPEC.md §5). <b>Where they stand if
     * both sides can</b>, and a trip into the bay if either cannot - {@code arg} is 1 when the
     * <em>client</em> has the mixins on, because a client without them cannot find a machine in a
     * chunk it was never sent, and only the client knows its own file.
     */
    ENTER_BAY,
    /** Puts the player in room {@code arg} of this network, building it on its first visit. */
    ENTER_ROOM,
    /**
     * Switches room {@code arg}'s Anchor on or off. Off is the default and the whole point: an
     * Anchor that lit every room the network owns would buy 36 ticking chunks with one upgrade.
     */
    TOGGLE_ROOM_ANCHOR,
    /**
     * Steps room {@code arg}'s biome to the next entry of {@code #workbay:room_biomes} and writes
     * it over the room's chunks. Appended at the end for the same reason {@link #SET_LINK_NAME} is:
     * an action travels as its ordinal.
     */
    CYCLE_ROOM_BIOME,
    /**
     * Repaints room {@code arg}'s shell. A right-click steps backwards, the way every stepped
     * does: ten colours is five clicks either way instead of nine one way.
     */
    CYCLE_ROOM_COLOUR,
    /**
     * Out of the room the player is standing in, back to where they entered from. Only
     * {@link RoomDoorMenu} sends it -- the Workbay screen has no way to be open in a room.
     */
    LEAVE_ROOM,
    /**
     * Room {@code arg}'s biome, chosen from a list rather than stepped: {@code arg} packs the room
     * in its low 16 bits and the index into {@link WorkbaySnapshot#roomBiomes} in the next 16.
     * A cycle button showed no state at all - it was an icon that never changed, so clicking it
     * looked exactly like nothing happening.
     */
    SET_ROOM_BIOME,
    /** The same for the shell's colour; the high half is a {@code RoomColour} ordinal. */
    SET_ROOM_COLOUR,
    /**
     * Invites a player into room {@code arg} at {@link com.neryos.workbay.world.RoomGuest#LOOK}.
     * {@code text} is the name they are known by, resolved to a UUID server-side -- the client has
     * no profile cache and could only ever send a name.
     *
     * <p>Appended at the end for the same reason every other room action was: an action travels as
     * its ordinal, so inserting one renames every action after it on the wire.
     */
    INVITE_ROOM_GUEST,
    /**
     * Steps one guest's level. {@code arg} packs the room in its low 16 bits and the guest's
     * position in that room's list in the next 16.
     */
    CYCLE_ROOM_GUEST,
    /** Un-invites one guest. Packed the same way as {@link #CYCLE_ROOM_GUEST}. */
    REMOVE_ROOM_GUEST,
    /**
     * How much one link moves in a step. {@code link} names the row and {@code arg} is the new
     * value, clamped server-side against {@code linkMaxRate} -- the client works out the step and
     * the server decides what is legal, because a config a client cannot see is the server's.
     */
    SET_LINK_RATE,
    /**
     * How long one link waits between steps. {@code arg} is an index into
     * {@link com.neryos.workbay.bus.BusConfig#SPEEDS}: a free number would not divide the tick
     * wheel, which is the whole reason the list is fixed.
     */
    SET_LINK_SPEED,
    /**
     * What to call room {@code arg}. {@code text} is the name; empty clears it, and the row falls
     * back to "Room 1" the way a bay falls back to its machine's own name.
     *
     * <p>Last in the enum, for the reason every room action before it was appended: an action
     * travels as its ordinal, so inserting one silently renames every action after it on the wire.
     */
    SET_ROOM_NAME,
    /**
     * Hands room {@code arg} back: takes the shell down and returns the slot to "Not opened yet".
     *
     * <p><b>Refused rather than destructive.</b> What is standing in a room is a player's build and
     * SPEC.md §8 does not void one, so a room with anything at all in it, or anybody in it, is not
     * given back and the refusal says which. OPEN_ISSUES #62. The confirm is the screen's job: the
     * server does not need to be asked twice, it needs to be asked correctly.
     *
     * <p>Last in the enum, for the reason every action before it was appended: an action travels
     * as its ordinal.
     */
    REMOVE_ROOM,
    /**
     * What to call the Connector the player is standing at. {@code text} is the name; empty clears
     * it and the row falls back to the Connector's own coordinates. Sent only by
     * {@link com.neryos.workbay.menu.ConnectorMenu}, which is the whole of what right-clicking a
     * placed Connector does now (OPEN_ISSUES #77).
     *
     * <p>Last in the enum, for the reason every action before it was appended: an action travels
     * as its ordinal.
     */
    SET_CONNECTOR_NAME,
    /**
     * <b>Moves a whole network into this block.</b> {@code link} names the network - the id, out of
     * {@link WorkbaySnapshot#networks}, which only ever lists the ones the player asking owns.
     *
     * <p>The network arrives with everything it had: its bays and the machines in them, its
     * Connectors, its channels, its upgrades and its energy. The block it came from is left
     * standing and holding nothing, which is the same state a Workbay placed at the quota is in -
     * so nothing is destroyed and the move is undone by walking back and pressing Transfer there.
     * Whatever <em>this</em> block was holding is put to sleep, not lost.
     *
     * <p>Last in the enum, for the reason every action before it was appended: an action travels
     * as its ordinal, so inserting one silently renames every action after it on the wire.
     */
    TRANSFER_NETWORK,
    /**
     * What to call a network. {@code link} names it and {@code text} is the name; blank is refused
     * rather than cleared, because a network with no name is a row a player cannot point at and
     * SPEC.md §0 will not fall back to the code.
     */
    SET_NETWORK_NAME,
    /**
     * <b>Starts a new network in this block</b>, when the player is under
     * {@code maxNetworksPerPlayer} and this Workbay is holding nothing.
     *
     * <p>Without it an empty Workbay is a dead end for everybody except a player at the quota: a
     * Transfer leaves the block it moved a network out of standing empty, and that player is now
     * <em>under</em> the limit with a block that has no way to become anything but by being broken
     * and placed again. Refused, silently, when the network would exceed the limit -- the screen
     * does not draw the button in that state, and a packet is not a promise.
     */
    NEW_NETWORK
}
