package com.neryos.workbay.compat;

import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Mekanism's chemicals, read the way its fluids and energy already are. SPEC.md §5.
 *
 * <p><b>Why this is the one mod with a name in the source.</b> Every other gauge on Bay View comes
 * from a NeoForge capability that any mod can implement, and this class exists because a chemical
 * has none - Mekanism registers its own, and a machine that holds gas would otherwise show a
 * hosted tank as simply absent. Mekanism is too large a share of what anyone racks for "we cannot
 * see it" to be the answer.
 *
 * <p><b>Compiled against its API only, and guarded twice.</b> `compileOnly`, exactly as the JEI and
 * EMI plugins are, so the mod builds and loads with Mekanism absent; every entry point checks
 * {@link ModList} first, and the capability is looked up by name rather than by importing
 * {@code mekanism.common}. {@code BlockCapability.createSided} returns the same object Mekanism
 * registered when the name and type match, which is how a capability is shared without a hard
 * dependency.
 */
public final class MekanismChemicals {
    private MekanismChemicals() {}

    private static final String MOD_ID = "mekanism";

    /** One chemical tank, flattened to what a gauge needs and nothing more. */
    public record Tank(Component name, long amount, long capacity) {}

    /**
     * What one attempt to move chemicals came to. Our own enum, naming no Mekanism type, so
     * {@code BusRunner} can switch on it without ever loading a class that mentions one.
     */
    public enum Move { MOVED, NOTHING_TO_MOVE, NO_SOURCE_PORT, NO_SINK_PORT, NOT_LOADED }

    public static boolean present() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }

    /**
     * Every chemical tank a block holds, or nothing at all when Mekanism is absent or the block has
     * none. Reads the null side: this is a <em>reading</em>, and the null side is the one that
     * reports every tank rather than the subset one face is configured for - the same split
     * SPEC.md §5 already makes between reading a machine and writing to it.
     */
    public static List<Tank> tanks(ServerLevel level, BlockPos pos) {
        if (!present() || !level.isLoaded(pos)) {
            return List.of();
        }
        return Impl.tanks(level, pos);
    }

    /**
     * Moves chemicals one way along a link, the way the other three resources move: bind by
     * <b>simulating the move</b>, never by asking a handler what it says it can do, then commit
     * what the simulation said would fit.
     *
     * <p>Everything about it that names a Mekanism type is inside {@link Impl}, and this guard is
     * outside it - which is the shape yesterday's crash bought. A guard sitting in the same class
     * as a Mekanism-typed field is checked <em>after</em> the JVM has already failed to initialise
     * that class, and every install without Mekanism dies on the first call.
     */
    public static Move move(ServerLevel sourceLevel, BlockPos sourcePos,
        @Nullable Direction sourceFace, ServerLevel sinkLevel, BlockPos sinkPos,
        @Nullable Direction sinkFace, long budget,
        java.util.function.Predicate<ResourceLocation> allowed) {
        if (!present()) {
            return Move.NO_SOURCE_PORT;
        }
        if (!sourceLevel.isLoaded(sourcePos) || !sinkLevel.isLoaded(sinkPos)) {
            return Move.NOT_LOADED;
        }
        return Impl.move(sourceLevel, sourcePos, sourceFace, sinkLevel, sinkPos, sinkFace, budget,
            allowed);
    }

    /**
     * The ids of every chemical standing in this block's tanks. OPEN_ISSUES #41.
     *
     * <p><b>This is what a chemical filter is picked from.</b> The other three resources have
     * something to drag into a ghost slot - an item, a bucket - and a chemical has neither, so the
     * only honest way to name one is to read the tank it is already in. Ids rather than names,
     * because an id is what {@link com.neryos.workbay.bus.BusFilter} stores for the other two.
     */
    public static List<ResourceLocation> chemicalsIn(ServerLevel level, BlockPos pos) {
        if (!present() || !level.isLoaded(pos)) {
            return List.of();
        }
        return Impl.chemicalsIn(level, pos);
    }

    /**
     * Everything that names a Mekanism type, in a class of its own, because loading a class is
     * what resolves the types in its fields. A {@code static final} capability of type
     * {@code IChemicalHandler} on the outer class is resolved when the *guard* is called -- before
     * {@link #present()} can answer -- so a mod that loads perfectly well without Mekanism still
     * threw {@code NoClassDefFoundError} the first time anything read a bay. Found by opening Bay
     * View in a plain instance; a dev run always has Mekanism on the classpath and never can.
     * A nested class is initialised on its own first use, which is here, after the guard passed.
     */
    private static final class Impl {
        private Impl() {}

        /**
         * The same capability object Mekanism registers, built from its name.
         * {@code createSided} returns the very object when the name and type match, which is how a
         * capability is shared without a hard dependency.
         */
        private static final BlockCapability<IChemicalHandler, @Nullable Direction> CHEMICAL =
            BlockCapability.createSided(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "chemical_handler"),
                IChemicalHandler.class);

        /**
         * The six real faces, in {@code Direction.values} order, and never the null side. Mekanism
         * returns a read-only proxy for null (SPEC.md §9), so a bus bound there finds a handler,
         * moves nothing, and reports no error - total silent failure.
         */
        private static IChemicalHandler handler(ServerLevel level, BlockPos pos,
            @Nullable Direction face, java.util.function.Predicate<IChemicalHandler> accepts) {
            if (face != null) {
                IChemicalHandler fixed = level.getCapability(CHEMICAL, pos, face);
                if (fixed != null && accepts.test(fixed)) {
                    return fixed;
                }
                // and fall through. A configured face is a preference, not a pin: Mekanism's side
                // config decides which of a block's faces carry gas, and the face the player stuck
                // a Connector to is very often not one of them.
            }
            for (Direction side : Direction.values()) {
                IChemicalHandler candidate = level.getCapability(CHEMICAL, pos, side);
                if (candidate != null && accepts.test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * What this handler would give up, up to the budget, of something the link may carry.
         * Empty when it has nothing to offer - which now includes a tank full of a chemical the
         * filter says no to, and that has to be the same answer as an empty tank or a filtered
         * link would report a fault for doing exactly what it was told.
         */
        private static ChemicalStack offer(IChemicalHandler handler, long budget,
            java.util.function.Predicate<ResourceLocation> allowed) {
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                ChemicalStack drawn = handler.extractChemical(tank, budget, mekanism.api.Action.SIMULATE);
                if (!drawn.isEmpty() && allowed.test(drawn.getChemical().getRegistryName())) {
                    return drawn;
                }
            }
            return ChemicalStack.EMPTY;
        }

        static List<ResourceLocation> chemicalsIn(ServerLevel level, BlockPos pos) {
            IChemicalHandler handler = level.getCapability(CHEMICAL, pos, null);
            if (handler == null) {
                return List.of();
            }
            List<ResourceLocation> found = new ArrayList<>();
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                ChemicalStack held = handler.getChemicalInTank(tank);
                if (!held.isEmpty()) {
                    ResourceLocation id = held.getChemical().getRegistryName();
                    if (!found.contains(id)) {
                        found.add(id);
                    }
                }
            }
            return List.copyOf(found);
        }

        static Move move(ServerLevel sourceLevel, BlockPos sourcePos, @Nullable Direction sourceFace,
            ServerLevel sinkLevel, BlockPos sinkPos, @Nullable Direction sinkFace, long budget,
            java.util.function.Predicate<ResourceLocation> allowed) {
            IChemicalHandler from = handler(sourceLevel, sourcePos, sourceFace,
                h -> !offer(h, budget, allowed).isEmpty());
            if (from == null) {
                // Empty and unreachable are different things, and one answer for both is how a dead
                // link spends a session looking like a resting one.
                boolean any = handler(sourceLevel, sourcePos, sourceFace, h -> h.getChemicalTanks() > 0) != null;
                return any ? Move.NOTHING_TO_MOVE : Move.NO_SOURCE_PORT;
            }
            ChemicalStack offered = offer(from, budget, allowed);
            IChemicalHandler to = handler(sinkLevel, sinkPos, sinkFace,
                h -> h.insertChemical(offered, mekanism.api.Action.SIMULATE).getAmount() < offered.getAmount());
            if (to == null) {
                boolean any = handler(sinkLevel, sinkPos, sinkFace, h -> h.getChemicalTanks() > 0) != null;
                return any ? Move.NOTHING_TO_MOVE : Move.NO_SINK_PORT;
            }
            // Simulate, see what would fit, then take exactly that much: the same order the item
            // and fluid paths use, and the reason a partial move never loses anything.
            long accepted = offered.getAmount()
                - to.insertChemical(offered, mekanism.api.Action.SIMULATE).getAmount();
            if (accepted <= 0) {
                return Move.NOTHING_TO_MOVE;
            }
            ChemicalStack taken = extract(from, offered, accepted);
            if (taken.isEmpty()) {
                return Move.NOTHING_TO_MOVE;
            }
            ChemicalStack leftover = to.insertChemical(taken, mekanism.api.Action.EXECUTE);
            if (!leftover.isEmpty()) {
                // The sink took less than it said it would. Put the rest back where it came from
                // rather than dropping it: a chemical that vanishes is a duplication bug backwards.
                from.insertChemical(leftover, mekanism.api.Action.EXECUTE);
            }
            return Move.MOVED;
        }

        /** Takes exactly {@code amount} of the offered chemical, from whichever tank holds it. */
        private static ChemicalStack extract(IChemicalHandler from, ChemicalStack offered, long amount) {
            for (int tank = 0; tank < from.getChemicalTanks(); tank++) {
                ChemicalStack sample = from.extractChemical(tank, amount, mekanism.api.Action.SIMULATE);
                if (!sample.isEmpty() && sample.is(offered.getChemical())) {
                    return from.extractChemical(tank, amount, mekanism.api.Action.EXECUTE);
                }
            }
            return ChemicalStack.EMPTY;
        }

        static List<Tank> tanks(ServerLevel level, BlockPos pos) {
            IChemicalHandler handler = level.getCapability(CHEMICAL, pos, null);
            if (handler == null) {
                return List.of();
            }
            List<Tank> tanks = new ArrayList<>();
            for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
                ChemicalStack held = handler.getChemicalInTank(tank);
                long capacity = handler.getChemicalTankCapacity(tank);
                if (capacity > 0) {
                    tanks.add(new Tank(held.isEmpty() ? Component.empty() : held.getTextComponent(),
                        held.isEmpty() ? 0L : held.getAmount(), capacity));
                }
            }
            return List.copyOf(tanks);
        }
    }
}
