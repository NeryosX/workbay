package com.neryos.workbay.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One Workbay, as the registry knows it. SPEC.md §14.
 *
 * <p>The Backshop's actual contents are the source of truth and this is an index: {@code SavedData}
 * writes at level-save time while a Workbay's chunk saves on its own schedule, so a hard crash can
 * leave the two disagreeing. Nothing here may be the only copy of anything, with one exception -
 * {@link Bay#hosted()} is deliberately kept after a mod is uninstalled, because vanilla drops the
 * unknown block and the Backshop is then simply empty. That record is the only thing left that can
 * say what used to be in the bay.
 *
 * <p>A room is a thing a bay holds ({@link Bay#room}); the network keeps no list of rooms of its
 * own. The {@code Rooms} key older files carry is ignored on read.
 */
public record WorkbayRecord(
    UUID id,
    String code,
    /**
     * What the player calls this network, and <b>the only thing they are ever shown</b>. The code
     * is still minted, still unique and still what {@code /workbay recover} takes, because typing
     * one to an operator is the whole reason it exists (SPEC.md §0) -- but nothing on a screen, in
     * chat or on a tooltip prints it any more. A network is one object the player points at, so it
     * has one name, the way a Connector does.
     *
     * <p>Never blank in practice: {@link RoomRegistry#create} stamps "Workbay 1", "Workbay 2" per
     * owner at mint time and {@link RoomRegistry#load} fills one in for every record written before
     * this field existed. {@link #label()} is the fallback of last resort.
     */
    String name,
    UUID owner,
    String ownerName,
    boolean locked,
    ChunkPos bayColumn,
    Upgrades upgrades,
    Optional<GlobalPos> lastKnownPos,
    List<Bay> bays,
    List<com.neryos.workbay.bus.BusConfig> buses,
    int deployedCount,
    /**
     * Every Connector paired to this network and standing in the world. <b>A Connector is one
     * object, and this is where it lives</b> - placing one adds an entry here and no channel at
     * all; every channel is minted later, by a player pressing Add on a bay. Before this list
     * existed a Connector was only ever represented by the rows it happened to carry, so placing
     * one had to mint a row for it to be findable, and the row a player never asked for is the
     * fault this whole list exists to delete.
     */
    List<Connector> connectors) {

    public static final Codec<WorkbayRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("Id").forGetter(WorkbayRecord::id),
        Codec.STRING.fieldOf("Code").forGetter(WorkbayRecord::code),
        // Optional: every world written before networks had names reads back blank, and load()
        // stamps one on before anything can draw it.
        Codec.STRING.optionalFieldOf("Name", "").forGetter(WorkbayRecord::name),
        UUIDUtil.CODEC.fieldOf("Owner").forGetter(WorkbayRecord::owner),
        Codec.STRING.fieldOf("OwnerName").forGetter(WorkbayRecord::ownerName),
        Codec.BOOL.fieldOf("Locked").forGetter(WorkbayRecord::locked),
        Codec.LONG.xmap(ChunkPos::new, ChunkPos::toLong).fieldOf("BayColumn").forGetter(WorkbayRecord::bayColumn),
        Upgrades.CODEC.fieldOf("Upgrades").forGetter(WorkbayRecord::upgrades),
        GlobalPos.CODEC.optionalFieldOf("LastKnownPos").forGetter(WorkbayRecord::lastKnownPos),
        Bay.CODEC.listOf().fieldOf("Bays").forGetter(WorkbayRecord::bays),
        // Optional and empty by default: a record written before links moved into the registry
        // reads back with none, which is exactly what it had.
        com.neryos.workbay.bus.BusConfig.CODEC.listOf().optionalFieldOf("Buses", List.of())
            .forGetter(WorkbayRecord::buses),
        // How many live Workbay blocks are currently bound to this record. Read by placement to
        // decide whether an unbound item may reuse this network or must be refused (SPEC.md §14).
        Codec.INT.optionalFieldOf("DeployedCount", 0).forGetter(WorkbayRecord::deployedCount),
        // Optional and empty: a record written before Connectors were objects reads back with
        // none, and WorkbayMenu#build fills them in from the rows those Connectors carry.
        Connector.CODEC.listOf().optionalFieldOf("Connectors", List.of())
            .forGetter(WorkbayRecord::connectors)
    ).apply(i, WorkbayRecord::new));

    /**
     * One Connector, as the network knows it. SPEC.md §0.
     *
     * <p><b>The name lives here and nowhere else.</b> A Connector may carry a channel on every bay
     * of its Workbay at once, and all of them are the same block on the same machine - so a name
     * stored per channel is four names for one object, and renaming it from the world panel could
     * only ever reach one of them. OPEN_ISSUES #97.
     *
     * <p>{@code target} and {@code targetBlock} are stamped when the Connector is placed, which is
     * the one moment the block at the far end is guaranteed to be loaded; every channel minted from
     * this Connector later is born pointing at them.
     */
    public record Connector(UUID id, GlobalPos pos, String name, GlobalPos target,
        Optional<ResourceLocation> targetBlock) {

        public static final Codec<Connector> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("Id").forGetter(Connector::id),
            GlobalPos.CODEC.fieldOf("Pos").forGetter(Connector::pos),
            Codec.STRING.optionalFieldOf("Name", "").forGetter(Connector::name),
            GlobalPos.CODEC.fieldOf("Target").forGetter(Connector::target),
            ResourceLocation.CODEC.optionalFieldOf("TargetBlock").forGetter(Connector::targetBlock)
        ).apply(i, Connector::new));

        public Connector withName(String nowName) {
            return new Connector(id, pos, nowName, target, targetBlock);
        }
    }

    /** The Connector standing at a position, if this network owns one there. */
    public Optional<Connector> connectorAt(GlobalPos pos) {
        return connectors.stream().filter(c -> c.pos().equals(pos)).findFirst();
    }

    /** The same record with the Connector at {@code pos} renamed; unchanged if none stands there. */
    public WorkbayRecord withConnectorRenamed(GlobalPos pos, String name) {
        return withConnectors(connectors.stream()
            .map(c -> c.pos().equals(pos) ? c.withName(name) : c).toList());
    }

    /** Without the Connector at one position and every channel it anchored, waiting ones too. */
    public WorkbayRecord withoutConnectorAt(GlobalPos at) {
        return withConnectors(connectors.stream().filter(c -> !c.pos().equals(at)).toList())
            .withBuses(buses.stream().filter(b -> !b.connector().equals(at)).toList());
    }

    public WorkbayRecord withConnectors(List<Connector> newConnectors) {
        return new WorkbayRecord(id, code, name, owner, ownerName, locked, bayColumn, upgrades,
            lastKnownPos, bays, buses, deployedCount, List.copyOf(newConnectors));
    }

    public WorkbayRecord withUpgrades(Upgrades newUpgrades) {
        return new WorkbayRecord(id, code, name, owner, ownerName, locked, bayColumn, newUpgrades,
            lastKnownPos, bays, buses, deployedCount, connectors);
    }

    public WorkbayRecord withLastKnownPos(GlobalPos pos) {
        return new WorkbayRecord(id, code, name, owner, ownerName, locked, bayColumn, upgrades,
            Optional.of(pos), bays, buses, deployedCount, connectors);
    }

    public WorkbayRecord withLocked(boolean nowLocked) {
        return new WorkbayRecord(id, code, name, owner, ownerName, nowLocked, bayColumn, upgrades,
            lastKnownPos, bays, buses, deployedCount, connectors);
    }

    public WorkbayRecord withBays(List<Bay> newBays) {
        return new WorkbayRecord(id, code, name, owner, ownerName, locked, bayColumn, upgrades,
            lastKnownPos, List.copyOf(newBays), buses, deployedCount, connectors);
    }

    public WorkbayRecord withBuses(List<com.neryos.workbay.bus.BusConfig> newBuses) {
        return new WorkbayRecord(id, code, name, owner, ownerName, locked, bayColumn, upgrades,
            lastKnownPos, bays, List.copyOf(newBuses), deployedCount, connectors);
    }

    /** This network's own name, and never the code. */
    public WorkbayRecord withName(String nowName) {
        return new WorkbayRecord(id, code, nowName, owner, ownerName, locked, bayColumn, upgrades,
            lastKnownPos, bays, buses, deployedCount, connectors);
    }

    /**
     * What to print when this network has to be named and {@link #name()} is somehow blank.
     *
     * <p>Only reachable by a record minted outside {@link RoomRegistry#create} - a gametest, or a
     * hand-built one. Never the code: SPEC.md §0 keeps codes off everything a player reads, and a
     * screen that falls back to one is a screen that shows a player a code.
     */
    public String label() {
        return name.isBlank() ? "Workbay" : name;
    }

    /**
     * <b>The one question every door asks.</b> A locked network is its owner's alone -- not
     * look-only, not for operators: nothing opens, nothing is read, and the refusal says why. The
     * block's two right-clicks, the menu (open, act, {@code stillValid}), the Connector's placement,
     * panel and rename, the remote screen, the bay trip and the commands all ask this and nothing
     * else, because the lock has already leaked once through a door nobody had listed. A room's own
     * guest list is a different question ({@link RoomVisit#mayEnter}) asked once the player is
     * through this one.
     */
    public boolean admits(UUID who) {
        return !locked || owner.equals(who);
    }

    /** {@link #admits}, said out loud. True when the door stays shut. */
    public boolean refuses(net.minecraft.server.level.ServerPlayer who) {
        if (admits(who.getUUID())) {
            return false;
        }
        com.neryos.workbay.WorkbaySounds.refuse(who, com.neryos.workbay.WorkbayLang.message("locked"));
        return true;
    }

    /**
     * The owner's alone even on an unlocked network: the lock itself, upgrades, rooms and guests.
     * Its own message, because "locked" is a lie on a Workbay whose owner chose to share.
     */
    public boolean refusesNonOwner(net.minecraft.server.level.ServerPlayer who) {
        if (owner.equals(who.getUUID())) {
            return false;
        }
        com.neryos.workbay.WorkbaySounds.refuse(who,
            com.neryos.workbay.WorkbayLang.message("owner_only"));
        return true;
    }

    /**
     * <b>A network is awake exactly while a Workbay block stands on it.</b> One block, one network
     * (SPEC.md §0); with no block, nothing ticks it, nothing holds a chunk for it and its bays,
     * machines, Connectors and channels are all still there waiting - which is what makes placing
     * a Workbay something that never has to be refused.
     */
    public boolean live() {
        return deployedCount > 0;
    }

    /**
     * Incremented when a Workbay block binds to this record and decremented when that block is
     * broken or transferred away (not merely unloaded - {@code WorkbayBlock#onRemove}, not
     * {@code BlockEntity#setRemoved}). One block per network now, so this is 0 or 1 and
     * {@link #live()} is the question anything actually asks; it stays a count because every saved
     * world already has one, and because a count that drifts to 2 reads as a bug rather than
     * silently looking correct.
     */
    public WorkbayRecord withDeployedCount(int nowDeployedCount) {
        return new WorkbayRecord(id, code, name, owner, ownerName, locked, bayColumn, upgrades,
            lastKnownPos, bays, buses, Math.max(0, nowDeployedCount), connectors);
    }



    /**
     * The base Workbay's bays, before any Expansion Plate. <b>Two, not one.</b>
     *
     * <p>It was two because the Assay ate one of them and a network with a single bay could never
     * earn the Levy to buy a second. The Assay is gone and the argument with it, and two is still
     * right for a plainer reason: one bay is a machine in a box, and the mod's whole claim is that
     * a rack replaces a floor. Two is the smallest number that is a rack.
     */
    public static final int BASE_BAYS = 2;

    /**
     * How many bays this Workbay may use: {@link #BASE_BAYS}, plus one per Expansion Plate, capped
     * by the server's {@code maxBaysPerWorkbay}. The cap is applied here rather than at install
     * time so lowering it never destroys an Expansion Plate somebody already spent.
     */
    public int bayCapacity() {
        return Math.min(BASE_BAYS + upgrades.expansionPlates(),
            com.neryos.workbay.config.WorkbayConfig.SERVER.maxBaysPerWorkbay.get());
    }

    /**
     * One bay. {@code hosted} is the block last known to be racked here, and it is deliberately
     * <em>not</em> cleared when that block's mod disappears - that is the {@code was:} record
     * SPEC.md §14 requires, and what {@code /workbay orphans} reports.
     */
    public record Bay(int index, Optional<ResourceLocation> hosted, FaceConfig faces,
        String name, RedstoneMode redstone,
        /**
         * The room standing in this bay, when what is racked is a room rather than a machine.
         * <b>This is the one place a room's holder is written</b>: {@code hosted} says a room
         * block is here, this says which room, and {@link RoomRegistry#holderOf} reads it back.
         */
        Optional<UUID> room,
        /**
         * How wide this bay's shell is: {@link BayGeometry#SHELL} normally, {@link
         * BayGeometry#WIDE_SHELL} once a machine that claims the blocks around it was racked here.
         * <b>Written by racking and cleared by ejecting</b>, so a bay is wide only while it holds
         * the machine that needed it to be.
         *
         * <p>The Backshop is still the source of truth - {@link BayBuilder#shellAt} reads the
         * answer back out of the world - and this is the copy that saves a block read on every
         * visit and every rebuild.
         */
        int shell) {

        /** 5 or 7 and nothing else, whatever a save or a hand-built record hands over. */
        public Bay {
            shell = BayGeometry.validShell(shell);
        }

        // Every field after the index is optional, so a Workbay written before any of them existed
        // reads back as an unnamed narrow bay that always runs -- which is what it was.
        public static final Codec<Bay> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("Index").forGetter(Bay::index),
            ResourceLocation.CODEC.optionalFieldOf("Hosted").forGetter(Bay::hosted),
            FaceConfig.CODEC.optionalFieldOf("Faces", FaceConfig.NONE).forGetter(Bay::faces),
            Codec.STRING.optionalFieldOf("Name", "").forGetter(Bay::name),
            StringRepresentable.fromEnum(RedstoneMode::values)
                .optionalFieldOf("Redstone", RedstoneMode.ALWAYS).forGetter(Bay::redstone),
            UUIDUtil.CODEC.optionalFieldOf("Room").forGetter(Bay::room),
            Codec.INT.optionalFieldOf("Shell", BayGeometry.SHELL).forGetter(Bay::shell)
        ).apply(i, Bay::new));

        public static Bay empty(int index) {
            return new Bay(index, Optional.empty(), FaceConfig.NONE, "", RedstoneMode.ALWAYS,
                Optional.empty(), BayGeometry.SHELL);
        }

        public Bay withHosted(Optional<ResourceLocation> nowHosted) {
            return new Bay(index, nowHosted, faces, name, redstone, room, shell);
        }

        public Bay withFaces(FaceConfig nowFaces) {
            return new Bay(index, hosted, nowFaces, name, redstone, room, shell);
        }

        public Bay withName(String nowName) {
            return new Bay(index, hosted, faces, nowName, redstone, room, shell);
        }

        public Bay withRedstone(RedstoneMode nowRedstone) {
            return new Bay(index, hosted, faces, name, nowRedstone, room, shell);
        }

        public Bay withRoom(Optional<UUID> nowRoom) {
            return new Bay(index, hosted, faces, name, redstone, nowRoom, shell);
        }

        /** How wide this bay stands. {@link BayGeometry#validShell} decides what that may be. */
        public Bay withShell(int nowShell) {
            return new Bay(index, hosted, faces, name, redstone, room, nowShell);
        }
    }

    /** The bay holding one room, if any of this network's do. */
    public Optional<Bay> bayHolding(UUID room) {
        return bays.stream().filter(b -> b.room().map(room::equals).orElse(false)).findFirst();
    }

    /** The bay at an index, minting an empty one rather than returning nothing for a bay in range. */
    public Bay bay(int index) {
        return bays.stream().filter(b -> b.index() == index).findFirst().orElseGet(() -> Bay.empty(index));
    }

    /** Replaces one bay, adding it if this Workbay had never written that index before. */
    public WorkbayRecord withBay(Bay bay) {
        List<Bay> updated = new java.util.ArrayList<>(bays.stream()
            .filter(b -> b.index() != bay.index()).toList());
        updated.add(bay);
        updated.sort(java.util.Comparator.comparingInt(Bay::index));
        return withBays(updated);
    }

    /**
     * Upgrades are consumed on install and recorded as counters - there is no upgrade inventory and
     * no removal path (SPEC.md §1). The {@code AnnexPlates} and {@code RoomTier} keys older files
     * carry belonged to the frames and are ignored on read: a room is an item now.
     */
    public record Upgrades(int expansionPlates, int resonators, int anchors, int impellers) {
        public static final Upgrades NONE = new Upgrades(0, 0, 0, 0);

        /**
         * What one Impeller is worth, on both halves of what a link does.
         *
         * <p>It doubles how much moves in a step <b>and</b> halves the wait between steps, so a
         * level is worth four and the two-level ladder is worth sixteen. Split rather than all on
         * the rate because a link that moves a bigger pile once every twenty ticks still looks
         * stalled; the wait is the half a player watches.
         *
         * <p>The top of that ladder puts an item link at EnderIO's enhanced conduit and an energy
         * link just under its plain one, which is where a mod whose promise is space rather than
         * throughput (SPEC.md §0) belongs: fast enough to feed what you racked, never the reason
         * to build here.
         */
        public static int impellerStep() {
            return com.neryos.workbay.config.WorkbayConfig.SERVER.impellerStep.get();
        }

        public static final Codec<Upgrades> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("ExpansionPlates", 0).forGetter(Upgrades::expansionPlates),
            Codec.INT.optionalFieldOf("Resonators", 0).forGetter(Upgrades::resonators),
            Codec.INT.optionalFieldOf("Anchors", 0).forGetter(Upgrades::anchors),
            Codec.INT.optionalFieldOf("Impellers", 0).forGetter(Upgrades::impellers)
        ).apply(i, Upgrades::new));

        /** One more of the named upgrade. Upgrades are consumed on install (SPEC.md §1). */
        public Upgrades plus(com.neryos.workbay.content.workbay.WorkbayUpgrade upgrade) {
            return switch (upgrade) {
                case EXPANSION_PLATE -> new Upgrades(expansionPlates + 1, resonators, anchors,
                    impellers);
                case RESONATOR -> new Upgrades(expansionPlates, resonators + 1, anchors, impellers);
                case IMPELLER -> new Upgrades(expansionPlates, resonators, anchors, impellers + 1);
                case ANCHOR -> new Upgrades(expansionPlates, resonators, anchors + 1, impellers);
            };
        }

        public int installed(com.neryos.workbay.content.workbay.WorkbayUpgrade upgrade) {
            return switch (upgrade) {
                case EXPANSION_PLATE -> expansionPlates;
                case RESONATOR -> resonators;
                case IMPELLER -> impellers;
                case ANCHOR -> anchors;
            };
        }

        /**
         * What every link on this Workbay multiplies its rate by, and divides its wait by. One
         * with no Impeller fitted, and the same number for both halves.
         */
        public int impellerFactor() {
            int factor = 1;
            int step = impellerStep();
            for (int i = 0; i < impellers; i++) {
                factor *= step;
            }
            return factor;
        }

        public boolean anchored() {
            return anchors > 0;
        }

        public boolean canCrossDimensions() {
            return resonators > 0;
        }
    }
}
