package com.neryos.workbay.bus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * One end of a bus, and the rules SPEC.md §9 puts around reaching it.
 *
 * <p><b>A bus knows a position and never searches the world.</b> Everything goes through a
 * {@link BlockCapabilityCache}, which also gives the right answer for free when the target is not
 * loaded: null, without dragging the chunk back in.
 *
 * <p><b>Never pass a null side.</b> Mekanism hands out a read-only handler for null, so a bus
 * written against "internal access" semantics finds a handler, moves nothing, and reports no error.
 * {@link #resolve} therefore tries the six real faces and keeps the first that accepts.
 */
public final class BusEndpoint<T> {

    private final BlockCapability<T, @Nullable Direction> capability;
    private final ServerLevel level;
    private final BlockPos pos;
    private final BooleanSupplier owner;

    /** One cache per face. Bound lazily, because which face works is not known until it is tried. */
    private final BlockCapabilityCache<T, @Nullable Direction>[] caches;

    @Nullable
    private Direction bound;

    private boolean dirty = true;

    @SuppressWarnings("unchecked")
    public BusEndpoint(BlockCapability<T, @Nullable Direction> capability, ServerLevel level,
        BlockPos pos, BooleanSupplier owner, @Nullable Direction preferred) {
        this.capability = capability;
        this.level = level;
        this.pos = pos.immutable();
        this.owner = owner;
        this.caches = new BlockCapabilityCache[Direction.values().length];
        this.bound = preferred;
    }

    /**
     * The handler on whichever face works, or null if the target is gone, unloaded, or has no port
     * on any side.
     *
     * @param accepts asked to confirm a candidate face can actually do the job. A handler that
     *                exists but refuses everything is the silent-failure case, so simulating here is
     *                the difference between binding to a real port and binding to a decoy.
     */
    @Nullable
    public T resolve(java.util.function.Predicate<T> accepts) {
        return resolve(accepts, java.util.EnumSet.allOf(Direction.class));
    }

    /**
     * @param allowed the faces this end may use - the bay's face config at the machine end, every
     *                direction at the target end. An empty set means the player has configured this
     *                machine for the other direction of travel, so there is nothing to bind to.
     */
    @Nullable
    public T resolve(java.util.function.Predicate<T> accepts, java.util.Set<Direction> allowed) {
        // Named for the profiler so `/perf start` can say how much of a link's tick is finding the
        // handler rather than moving anything through it. try/finally because the body has five
        // returns and an unbalanced pop corrupts every other reading in the report.
        level.getProfiler().push("resolve");
        try {
            return resolveNow(accepts, allowed);
        } finally {
            level.getProfiler().pop();
        }
    }

    @Nullable
    private T resolveNow(java.util.function.Predicate<T> accepts, java.util.Set<Direction> allowed) {
        if (allowed.isEmpty()) {
            bound = null;
            return null;
        }
        if (bound != null && !dirty && allowed.contains(bound)) {
            T handler = cacheFor(bound).getCapability();
            if (handler != null && accepts.test(handler)) {
                return handler;
            }
        }
        dirty = false;
        for (Direction side : Direction.values()) {
            if (!allowed.contains(side)) {
                continue;
            }
            T handler = cacheFor(side).getCapability();
            if (handler != null && accepts.test(handler)) {
                bound = side;
                return handler;
            }
        }
        bound = null;
        return null;
    }

    /** True when the target position is not currently loaded - a normal state, not an error. */
    public boolean targetLoaded() {
        return level.isLoaded(pos);
    }

    public ServerLevel level() {
        return level;
    }

    public BlockPos pos() {
        return pos;
    }

    private BlockCapabilityCache<T, @Nullable Direction> cacheFor(Direction side) {
        int index = side.ordinal();
        if (caches[index] == null) {
            // The invalidation listener may ONLY set a flag. Touching the level from it is
            // unsupported: the block being invalidated might not be ready to answer yet, and the
            // chunk the listener lives in might be the one unloading.
            caches[index] = BlockCapabilityCache.create(capability, level, pos, side,
                owner, () -> dirty = true);
        }
        return caches[index];
    }
}
