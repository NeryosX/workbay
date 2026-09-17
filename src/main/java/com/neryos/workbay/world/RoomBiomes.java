package com.neryos.workbay.world;

import com.neryos.workbay.Workbay;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * The biome a room carries. SPEC.md §8.
 *
 * <p>What a machine asks about its surroundings is the <b>biome</b> - temperature, precipitation,
 * {@code warmEnoughToRain}, {@code coldEnoughToSnow} - and a biome is a per-chunk value that can be
 * written, so a room has one and the room screen steps through them.
 *
 * <p><b>The list is a tag, not the registry.</b> Every biome that exists is a list nobody wants to
 * scroll and most of it answers nothing a machine asked; {@code #workbay:room_biomes} ships the six
 * that span temperature and rainfall, and a pack that wants its own adds one JSON file.
 *
 * <p><b>Weather still never happens.</b> There is no sky (SPEC.md §0), so nothing falls in any of
 * them: a room's chunks are sealed under bedrock and {@code ServerLevel#tickPrecipitation} works on
 * the heightmap top, which is the outside of the roof. What the biome changes is what a machine
 * <em>reads</em>, and what the blocks in it look like.
 */
public final class RoomBiomes {
    private RoomBiomes() {}

    public static final TagKey<Biome> ROOM_BIOMES = TagKey.create(Registries.BIOME,
        ResourceLocation.fromNamespaceAndPath(Workbay.MOD_ID, "room_biomes"));

    /**
     * What a room may be set to, in the tag's own order. Empty only if a pack has emptied the tag,
     * and then the picker is not drawn at all.
     *
     * <p>Takes a {@link net.minecraft.core.RegistryAccess} rather than the server, because
     * <b>the client can answer this too</b>: tags are synced, so the room screen reads the list
     * from its own registry and it never has to ride the snapshot. That matters more than it
     * sounds - the snapshot's codec group is full at sixteen fields.
     */
    public static List<ResourceKey<Biome>> choices(net.minecraft.core.RegistryAccess registries) {
        return registries.registryOrThrow(Registries.BIOME).getTag(ROOM_BIOMES)
            .map(tag -> tag.stream().flatMap(holder -> holder.unwrapKey().stream()).toList())
            .orElse(List.of());
    }

    /** The next biome after this room's, wrapping. Falls back to plains on an empty tag. */
    public static ResourceKey<Biome> next(MinecraftServer server, RoomRecord room) {
        List<ResourceKey<Biome>> all = choices(server.registryAccess());
        if (all.isEmpty()) {
            return Biomes.PLAINS;
        }
        int at = all.indexOf(room.effectiveBiome());
        return all.get((at + 1) % all.size());
    }

    /**
     * Writes the room's biome over every chunk it occupies, by the path {@code /fillbiome} takes:
     * {@code fillBiomesFromNoise} per chunk, mark unsaved, then one resend so a player standing in
     * it sees the change without relogging.
     *
     * <p>Whole chunks, not the interior box - a room's footprint <em>is</em> a whole number of
     * chunks (SPEC.md §8), so there is no partial chunk to preserve and no bounding box to test
     * per quart.
     */
    public static void apply(ServerLevel backshop, RoomRecord room) {
        if (!room.built()) {
            return;
        }
        Holder<Biome> biome = backshop.registryAccess().registryOrThrow(Registries.BIOME)
            .getHolderOrThrow(room.effectiveBiome());
        List<ChunkAccess> chunks = new ArrayList<>();
        for (ChunkPos pos : RoomGeometry.chunks(room.region(), room.builtTier())) {
            chunks.add(backshop.getChunk(pos.x, pos.z));
        }
        var sampler = backshop.getChunkSource().randomState().sampler();
        for (ChunkAccess chunk : chunks) {
            chunk.fillBiomesFromNoise((x, y, z, ignored) -> biome, sampler);
            chunk.setUnsaved(true);
        }
        backshop.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
    }
}
