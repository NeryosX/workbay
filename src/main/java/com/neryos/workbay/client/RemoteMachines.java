package com.neryos.workbay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The client's copy of a machine it was never sent, for as long as its screen is open.
 *
 * <p>A whole chunk rather than a block entity, because that is the question mods actually ask -
 * see {@link com.neryos.workbay.mixin.ClientChunkCacheMixin}. It holds exactly one block: the
 * machine's state, so {@code getBlockState} is right, and its block entity, so {@code
 * getBlockEntity} is right. Everything else in it is air, and nothing renders it.
 *
 * <p><b>And the machine on its own, at its own position.</b> A whole chunk can only be handed over
 * where vanilla had no chunk to give, and the first network anybody makes lives in chunk (0, 0) -
 * which a player building near the world origin has loaded in their own dimension. So the copy is
 * readable both ways: as a chunk when there is no real one, and as one block entity at one position
 * when there is. {@link com.neryos.workbay.mixin.LevelChunkMixin} is the second door; OPEN_ISSUES
 * #80 is what happens without it.
 *
 * <p>One at a time. A player has one screen open.
 */
public final class RemoteMachines {
    private static ChunkPos where;
    private static LevelChunk chunk;

    /** The machine itself, so the per-position door answers without re-entering the chunk. */
    private static BlockPos machinePos;
    private static BlockEntity machine;

    private RemoteMachines() {}

    /** Null unless this is the chunk a remote screen is open on. Hot: called for every chunk miss. */
    public static LevelChunk chunkAt(int x, int z) {
        ChunkPos at = where;
        return at != null && at.x == x && at.z == z ? chunk : null;
    }

    /**
     * The open machine, if this is exactly its position, and null for every other position.
     *
     * <p>Answered from a field rather than by asking the synthetic chunk: the caller is
     * {@code LevelChunk#getBlockEntity}'s own return, and the synthetic chunk is a
     * {@code LevelChunk} too - so asking it would re-enter the same injection and never come back.
     *
     * <p>Hot: called for every block-entity miss in every loaded chunk, so the null field read is
     * all the ordinary path costs.
     */
    public static BlockEntity machineAt(BlockPos pos) {
        BlockPos at = machinePos;
        return at != null && at.equals(pos) ? machine : null;
    }

    public static void apply(BlockPos pos, BlockState state, CompoundTag data) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || data.isEmpty()) {
            clear();
            return;
        }

        BlockEntity open = machineAt(pos);
        if (open != null) {
            // Feed the machine the open screen is already holding, rather than replacing it: the
            // screen keeps a reference to that object, and a fresh one would leave it drawing a
            // copy nothing updates any more.
            //
            // handleUpdateTag paired with the server's getUpdateTag: the exact two methods a
            // chunk-tracking client uses, which is what this copy stands in for. A full save tag
            // through this door arrives nowhere -- Mekanism's handleUpdateTag deliberately calls
            // only BlockEntity's own loadAdditional -- and a save tag through loadWithComponents
            // carries no side config either, because that rides the update tag.
            open.handleUpdateTag(data, level.registryAccess());
            return;
        }

        LevelChunk copy = new LevelChunk(level, new ChunkPos(pos));
        copy.setBlockState(pos, state, false);
        BlockEntity built = BlockEntity.loadStatic(pos, state, data, level.registryAccess());
        if (built != null) {
            // setBlockEntity refuses a position whose state has no block entity, which is why the
            // state goes in first rather than the two being set in either order.
            copy.setBlockEntity(built);
        }
        chunk = copy;
        where = new ChunkPos(pos);
        machine = built;
        machinePos = built == null ? null : pos.immutable();
    }

    /** On close, and on disconnect, or the next screen inherits a machine from the last world. */
    public static void clear() {
        where = null;
        chunk = null;
        machinePos = null;
        machine = null;
    }
}
