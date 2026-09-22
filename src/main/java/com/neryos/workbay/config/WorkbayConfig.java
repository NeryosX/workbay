package com.neryos.workbay.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Every number that decides how this mod plays, and nothing that has entries. SPEC.md §13.
 *
 * <h2>Where the line is, and why</h2>
 *
 * <p>Two audiences. A <b>datapack</b> is what a modpack ships: versioned with the pack, loaded per
 * world, and the place for anything with <em>entries</em> - what a Workbay costs
 * ({@code data/workbay/recipe/}), and what may
 * be hosted (SPEC.md §11's tags). All three already exist and are already the only place those
 * answers live, so a pack carrying a mod we have never seen fixes a bad interaction there without
 * waiting for us. <b>Config</b> is what one host tunes for one server: switches, caps, and the
 * scalars below.
 *
 * <p><b>Nothing new joined the datapack side, on purpose.</b> What was left in Java was
 * <em>scalars</em> - a conversion rate, a throughput ceiling, a chunk radius - and a scalar in a
 * datapack registry buys a pack nothing that a shipped {@code defaultconfigs/} TOML does not
 * already give it, while costing a reload listener, a sync packet, and a second place the same
 * number can be written down and disagree with itself. SPEC.md §0 settled "everything else is a
 * tag or a recipe" against <em>a config section per subsystem</em>, which is why there is one
 * section here and not five: the root holds the caps and the chunk bill, and
 * {@code throughput} holds what a link is worth to run.
 *
 * <h2>What is deliberately not a knob, and where the answer is instead</h2>
 *
 * <p>Every one of these was asked and answered "no", so the next person does not have to ask again.
 * <ul>
 *   <li><b>The buffer's size (100,000 FE) and how fast it fills (10,000 FE/t).</b> The capacity is
 *       saved per block and restored off the item a Workbay was broken into, so lowering it mid-world
 *       leaves blocks holding more than they can hold; and at any draw a pack would plausibly set,
 *       100,000 is minutes of running. The two power draws below are the knob that makes the buffer mean
 *       something.</li>
 *   <li><b>What one rate step is worth per resource</b> ({@code MB_PER_RATE} 100,
 *       {@code FE_PER_RATE} 1000). Constants, because changing one <em>rescales every saved
 *       link</em> in every existing world.</li>
 *   <li><b>The speeds a link may run at.</b> Fixed, because each has to divide the 1200-tick
 *       transfer wheel; a free number does not.</li>
 *   <li><b>How many rows one Connector may carry.</b> As many as the player pulls in: each row is
 *       its own channel, and a cap on that is a cap on the product (SPEC.md §0).</li>
 *   <li><b>Room sizes, room biomes, and what may be hosted.</b> Datapack, all three: the rooms are
 *       recipes ({@code data/workbay/recipe/}), biomes are {@code #workbay:room_biomes}, hostability
 *       is §11's tags. A pack drops the Vast Room recipe to cap room size.</li>
 *   <li><b>Rooms as a feature.</b> {@code roomsLoadWithWorkbay} is the whole chunk bill, and it
 *       ships off; a host who wants no private dimensions at all drops the three room
 *       recipes, which is the datapack side of the same line.</li>
 *   <li><b>Bay and room geometry</b> - the 8-block bay pitch, the 8-chunk column spacing, the
 *       512-block room region spacing. Baked into every saved world: changing one moves bays and
 *       rooms that already exist.</li>
 *   <li><b>Whether the mod patches vanilla classes at all.</b> That one <em>is</em> a host's switch
 *       and it cannot live here: {@code config/workbay-mixins.properties} is read before class
 *       transformation, which is earlier than any {@code ModConfigSpec} exists.</li>
 * </ul>
 */
public class WorkbayConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        Pair<Server, ModConfigSpec> server = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();

        Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    public static class Server {
        public final ModConfigSpec.BooleanValue allowAnchors;
        public final ModConfigSpec.IntValue maxAnchoredWorkbaysPerPlayer;
        public final ModConfigSpec.IntValue anchorGraceMinutes;
        public final ModConfigSpec.IntValue chunkTicketRadius;
        public final ModConfigSpec.IntValue maxBaysPerWorkbay;
        public final ModConfigSpec.BooleanValue roomsLoadWithWorkbay;
        public final ModConfigSpec.BooleanValue allowCrossDimensionLinks;
        public final ModConfigSpec.BooleanValue blockSounds;
        public final ModConfigSpec.BooleanValue greeting;
        public final ModConfigSpec.IntValue maxNetworksPerPlayer;

        public final ModConfigSpec.IntValue linkDefaultRate;
        public final ModConfigSpec.IntValue linkMaxRate;
        public final ModConfigSpec.IntValue linkDefaultSpeed;
        public final ModConfigSpec.IntValue impellerStep;
        public final ModConfigSpec.IntValue maxImpellers;

        public final ModConfigSpec.IntValue powerPerLinkPerTick;
        public final ModConfigSpec.IntValue powerPerMove;

        /**
         * Whether running a link costs anything at all on this server. <b>False is the shipped
         * answer</b>, and every readout about the Workbay's own buffer is hidden while it is:
         * a bar, a figure and an intake rate that exist to price something free are a bill for
         * nothing, and the player has no way to tell that from a bill they have not paid.
         */
        public boolean chargesForRunning() {
            return powerPerLinkPerTick.get() > 0 || powerPerMove.get() > 0;
        }



        Server(ModConfigSpec.Builder builder) {
            // The one knob a host who distrusts chunkloaders is looking for. It does not switch
            // off every ticket this mod holds -- mirroring holds the bay column exactly while the
            // Workbay's own chunk is loaded by something else, so it is never a chunk a player is
            // not already paying for. What this switches off is every ticket nobody is standing
            // next to, which is the whole of what an Anchor buys.
            allowAnchors = builder
                .comment("Controls whether the Anchor upgrade can be installed at all. With this",
                    "disabled the Anchor still crafts, but its cap is zero, so it never goes in and",
                    "nothing is ever kept loaded while nobody is there: no Workbay running while its",
                    "owner is away. The bay column is still mirrored, which costs no chunk a player",
                    "is not already loading by standing at the Workbay.")
                .define("allowAnchors", true);

            maxAnchoredWorkbaysPerPlayer = builder
                .comment("How many of one player's Workbays may force-load at once. Further Anchors",
                    "install but stay inactive.")
                .defineInRange("maxAnchoredWorkbaysPerPlayer", 4, 0, 1024);

            // The ceiling is thirty days rather than one, because "offline chunk loading" is a
            // knob every chunkloader mod in the genre has and a small friends' server wants it
            // long: FTB-Chunks measures the same thing in days. Zero is the strictest setting
            // there is -- tickets drop on the logout tick -- and five is the shipped middle.
            anchorGraceMinutes = builder
                .comment("How long a player's anchored Workbays keep running after they",
                    "log out. This is the offline chunk-loading knob: 0 drops every ticket the",
                    "moment they disconnect, and a large number is how a small server lets a",
                    "friend's base keep running. Nothing is ever lost either way - a released",
                    "chunk is a chain standing still, not a chain dropping a batch.")
                .defineInRange("anchorGraceMinutes", 5, 0, 43_200);

            // The number behind every chunk count this mod prints, and the one worth reading
            // first: a ticket is a square, not a chunk. NeoForge's own controller always asks for
            // radius 2 -- ticket level 31, a 5x5 square, 25 chunks for one bay column -- which is
            // why this mod registers its own TicketType and asks directly (OPEN_ISSUES #60).
            //
            // <b>One is the shipped default and it is a quarter of the bill.</b> Radius 1 is
            // level 32: block entities still tick and random ticks still happen, which is
            // everything a hosted machine needs, and only entities freeze. What a host loses at 1
            // is entity ticking in the Backshop -- a mob farm in an *anchored, empty* room stops
            // while nobody is in it. Standing in the room gives entity ticking back for free,
            // because a player loads their own chunks.
            chunkTicketRadius = builder
                .comment("How far every ticket this mod holds reaches, in chunks. The cost of one",
                    "ticket is (2*r+1)^2 chunks, so 1 holds 9 and 2 holds 25 - this is the single",
                    "biggest number in the mod's chunk bill.",
                    "  1 (default): block entities and random ticks run, entities freeze.",
                    "  2: entities tick too, at nearly three times the chunks.",
                    "Hosted machines only ever needed 1. Raise it if your pack expects mobs or",
                    "item entities to keep moving inside an anchored room nobody is standing in.")
                .defineInRange("chunkTicketRadius",
                    com.neryos.workbay.world.WorkbayTickets.MIN_RADIUS,
                    com.neryos.workbay.world.WorkbayTickets.MIN_RADIUS,
                    com.neryos.workbay.world.WorkbayTickets.MAX_RADIUS);

            // Also the length of the Expansion Plate ladder: the plate's maximum is this minus the
            // two bays a base Workbay already grants (WorkbayUpgrade.EXPANSION_PLATE). One number,
            // so the ceiling and the ladder cannot disagree about where the top is. Eight is where
            // the bay rack stops fitting the screen and where BayGeometry's Y spacing was baked
            // into saved worlds, so raising it past eight is a rack and geometry job, not an edit
            // here -- which is exactly why the number below it is the one worth measuring.
            maxBaysPerWorkbay = builder
                .comment("Hard ceiling on bays, regardless of Expansion Plates installed. The base",
                    "Workbay grants two, so this also sets how many Expansion Plates can be",
                    "installed: this minus two.")
                .defineInRange("maxBaysPerWorkbay", 8, com.neryos.workbay.world.WorkbayRecord.BASE_BAYS,
                    com.neryos.workbay.world.BayGeometry.MAX_BAYS);

            // SPEC.md §0 and §12: the one room knob. Off is the cheap setting -- a room runs
            // while a player stands in it, because standing there loads its chunk, and otherwise
            // sleeps. On mirrors every room in a bay the way the bay column is mirrored: held
            // exactly while the Workbay's own chunk is, one chunk per room, no ticket a player is
            // not already paying for at the block. What is gone is the per-room anchor (#59).
            roomsLoadWithWorkbay = builder
                .comment("Whether the rooms in a Workbay's bays stay loaded while the Workbay's own",
                    "chunk is. Off (default): a room runs only while somebody is standing in it.",
                    "On: each room in a bay is one more loaded chunk for as long as the Workbay",
                    "is, so machines inside rooms keep running while the player is at the block.")
                .define("roomsLoadWithWorkbay", false);

            // The Resonator's switch, the same shape as allowAnchors: always register the item and
            // gate its behaviour, never the registry (SPEC.md §13). A host who does not want a
            // network reaching into the Nether or into somebody's End base sets this false and the
            // upgrade cannot be installed; every cross-dimension link then reads "Needs Resonator"
            // for ever, which is the refusal already drawn on the row.
            allowCrossDimensionLinks = builder
                .comment("Whether the Resonator upgrade does anything. With this disabled the",
                    "Resonator cannot be installed and no link may cross a dimension boundary;",
                    "links inside one dimension, and links into this network's own bays and",
                    "rooms, are unaffected.")
                .define("allowCrossDimensionLinks", true);

            blockSounds = builder
                .comment("Whether a Workbay makes any sound at its own position: a link starting,",
                    "stopping or getting stuck, a machine going into a bay or coming out, an",
                    "upgrade fitting, a door. Turn it off and the block is silent.",
                    "A busy Workbay is a noisy one - every link that starts and stops says so -",
                    "and that is worth hearing when you are standing at it and not otherwise.",
                    "This is a SERVER knob and not a client one on purpose: every sound the mod",
                    "makes is a borrowed vanilla one, so a client-side filter could not tell a",
                    "Workbay's copper bulb from a real copper bulb next to it.",
                    "The sounds that answer your own click - a refusal, a confirmation - are not",
                    "affected: they are feedback on something you just did, not noise from a block.")
                .define("blockSounds", true);

            greeting = builder
                .comment("Whether a player meeting Workbay for the first time gets its one line in",
                    "chat: thanks, a word that the mod is new, and a link to the issue tracker.",
                    "It is said once per game, remembered by the client, and never again.",
                    "Turn it off and the mod never writes to chat on joining; a modpack ships",
                    "it off in its own config/workbay-server.toml.")
                .define("greeting", true);

            maxNetworksPerPlayer = builder
                .comment("How many separate Workbay networks one player may own. One Workbay block",
                    "is one network: its own bays, machines, Connectors and energy, sharing nothing",
                    "with any other. Placing a Workbay is never refused - a block placed while every",
                    "network this player owns already has one opens on a list of them, and Transfer",
                    "moves the chosen network into it, leaving the block it came from empty.",
                    "A network with no block sleeps: everything is kept and nothing ticks.")
                .defineInRange("maxNetworksPerPlayer", 2, 1, 64);

            // ------------------------------------------------------------------ throughput
            //
            // How hard a link pushes. The ceiling is a separate knob from the default on purpose:
            // lowering the default only slows links nobody has touched, and lowering the ceiling
            // slows the ones somebody deliberately turned up.
            builder.push("throughput");

            linkDefaultRate = builder
                .comment("What one move of a new link is worth. The unit is per resource: one for",
                    "an item, 100mB for a fluid, 1000 FE for energy - so a link is born able to",
                    "feed a furnace and unable to keep up with a Mekanism machine, which is the",
                    "shape of the Impeller ladder.")
                .defineInRange("linkDefaultRate", 8, 1, 100_000);

            // Enforced in BusRunner#rate, on every move, rather than where a rate is stored: a
            // ceiling checked only where somebody sets a number is one that a saved world, a
            // pasted config or an older version of this mod walks straight past.
            linkMaxRate = builder
                .comment("The ceiling on one link's rate, before Impellers. Applied on every move,",
                    "so lowering it slows links that are already running.")
                .defineInRange("linkMaxRate", 64, 1, 1_000_000);

            linkDefaultSpeed = builder
                .comment("Ticks between moves for a new link. Must divide 1200, the transfer",
                    "wheel; the screen only ever offers 10, 20, 40, 60, 100 and 200.")
                .defineInRange("linkDefaultSpeed", 20, 5, 1200);

            impellerStep = builder
                .comment("What one Impeller multiplies a link by. It doubles the pile and halves",
                    "the wait, so one level is worth this squared.")
                .defineInRange("impellerStep", 2, 1, 16);

            maxImpellers = builder
                .comment("How many Impellers one Workbay may hold. At the shipped two, an item",
                    "link tops out level with EnderIO's enhanced conduit and an energy link just",
                    "under its plain one - a mod that sells space rather than throughput has no",
                    "business beating a cable mod at cables. Raise it if your pack disagrees.")
                .defineInRange("maxImpellers", 2, 0, 16);

            // ------------------------------------------------------------------ running power
            //
            // SPEC.md §9's three layers, as knobs rather than constants -- what a link is worth to
            // run depends on the pack it is in, and §9 calls its own numbers "a calibration start".
            //
            // <b>Both ship at zero, and that is the decision, not the knob.</b> The buffer is
            // spent now (OPEN_ISSUES #72), so a non-zero charge means an unpowered Workbay moves
            // nothing -- and a player running vanilla plus this mod has no source of FE in the
            // world at all. XNet has the same shape and its players prime a network by touching a
            // generator to it and taking it away again, which is an implementation detail escaping
            // into the game; there is an open request asking XNet for exactly the switch below.
            // A pack author knows whether energy mods are present. A solo player does not, and
            // must never install this and find it inert. So the cost is opt-in: a pack that ships
            // energy turns it on, at XNet's own numbers (1 and 2), which is what these were.
            //
            // <b>They are named for power, not for a fee.</b> The owner's call: a player who turns
            // these on is buying electricity, and every word the mod uses for that has to be a
            // word about power -- a fee, a levy or a tax is a thing somebody takes off you.
            powerPerLinkPerTick = builder
                .comment("FE per tick each switched-on link draws, whether or not it moves",
                    "anything - the standing draw of having automation at all. ZERO BY DEFAULT:",
                    "a mod that needs FE to do anything is inert in an install with no energy mod,",
                    "and only a pack author knows whether there is one. XNet's own number is 1.",
                    "While this and powerPerMove are both zero the Workbay's power bar, its FE",
                    "figure and its intake rate are hidden - there is nothing to spend it on.")
                .defineInRange("powerPerLinkPerTick", 0, 0, 10_000);

            powerPerMove = builder
                .comment("FE one link draws for one move. Taken before the move, so a link that",
                    "cannot pay does not move and says so; nothing is ever half-moved.",
                    "Zero by default, for the reason above. XNet's own number is 2.")
                .defineInRange("powerPerMove", 0, 0, 10_000);

            builder.pop();

        }
    }

    /** Rendering only, never gameplay. */
    public static class Client {
        public final ModConfigSpec.BooleanValue showHostabilityInTooltips;
        public final ModConfigSpec.BooleanValue renderMiniatures;

        Client(ModConfigSpec.Builder builder) {
            showHostabilityInTooltips = builder
                .comment("Adds one line to every block's tooltip saying whether a Workbay can host it.",
                    "The single most useful teaching moment in the mod, and intrusive to some packs.")
                .define("showHostabilityInTooltips", true);

            renderMiniatures = builder
                .comment("Draws a miniature of each hosted machine in the Workbay's display case.")
                .define("renderMiniatures", true);
        }
    }
}
