package com.neryos.workbay.world;

import com.neryos.workbay.config.WorkbayConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * The Anchor's one job: the Workbay's own chunk. SPEC.md §12.
 *
 * <p>Rooms are not anchored any more (SPEC.md §0, OPEN_ISSUES #59): whether they stay loaded is
 * {@code roomsLoadWithWorkbay}, mirrored by the block that holds them, and the block only mirrors
 * while it ticks -- which is what this ticket keeps it doing with nobody around.
 *
 * <p><b>Every ticket here is conditional on {@link AnchorPresence}</b>, which is §12's last
 * line: anchored says what the network is entitled to, online says whether it holds it now.
 */
public final class RoomAnchors {
    private RoomAnchors() {}

    /**
     * Whether this network's Workbay holds <b>its own</b> chunk loaded. SPEC.md §12's Anchor row -
     * "the bay column stays loaded with nobody around" - which needs the block itself to keep
     * ticking, because the block is what runs the links and mirrors the column.
     *
     * <p>{@code maxAnchoredWorkbaysPerPlayer} is the switch. "Nobody around" is not "nobody
     * online". OPEN_ISSUES #52, #55.
     */
    public static boolean anchorsOwnChunk(MinecraftServer server, WorkbayRecord record) {
        return WorkbayConfig.SERVER.allowAnchors.get()
            && WorkbayConfig.SERVER.maxAnchoredWorkbaysPerPlayer.get() > 0
            && record.upgrades().anchors() > 0
            && AnchorPresence.holding(server, record.owner());
    }

    /**
     * The ticket a network owns: the chunk its Workbay block stands in.
     *
     * <p>Registered from here rather than only from the block's own tick because the block cannot
     * arm itself. Once the ticket is gone the chunk unloads, the block stops ticking, and nothing
     * in the world is left that could notice the owner logged back in.
     */
    static void applyOwnChunk(MinecraftServer server, WorkbayRecord record) {
        record.lastKnownPos().ifPresent(where -> {
            ServerLevel level = server.getLevel(where.dimension());
            if (level == null) {
                return;
            }
            UUID owner = WorkbayTickets.owner(where.dimension(), where.pos());
            ChunkPos chunk = new ChunkPos(where.pos());
            if (!anchorsOwnChunk(server, record)) {
                WorkbayTickets.release(level, owner, chunk);
                return;
            }
            WorkbayTickets.force(level, owner, chunk);
            // `lastKnownPos` is where the record last saw its block, and nothing clears it when
            // somebody breaks one -- so without this a Workbay that no longer exists holds a chunk
            // from every login until the owner leaves again. Forcing sync-loads the chunk, so by
            // here the block is readable and the answer is not a guess.
            if (!(level.getBlockEntity(where.pos())
                instanceof com.neryos.workbay.content.workbay.WorkbayBlockEntity)) {
                WorkbayTickets.release(level, owner, chunk);
            }
        });
    }
}
