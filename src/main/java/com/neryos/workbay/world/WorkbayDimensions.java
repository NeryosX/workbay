package com.neryos.workbay.world;

import com.neryos.workbay.Workbay;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The Backshop: the private void dimension hosted machines live in. SPEC §8.
 *
 * <p>The {@code LEVEL_STEM}, {@code DIMENSION} and {@code DIMENSION_TYPE} keys deliberately share
 * one {@link ResourceLocation}; if the stem and the level key ever diverge,
 * {@code server.getLevel()} returns null and nothing downstream works.
 *
 * <p>{@link #MIN_Y} and {@link #HEIGHT} are a one-way door. Changing either invalidates every
 * saved bay and room Y coordinate, and NeoForge has no mod DataFixers to migrate them.
 */
public final class WorkbayDimensions {
    private WorkbayDimensions() {}

    public static final ResourceLocation BACKSHOP_ID = Workbay.rl("backshop");
    public static final ResourceKey<Level> BACKSHOP = ResourceKey.create(Registries.DIMENSION, BACKSHOP_ID);
    public static final ResourceKey<LevelStem> BACKSHOP_STEM = ResourceKey.create(Registries.LEVEL_STEM, BACKSHOP_ID);
    public static final ResourceKey<DimensionType> BACKSHOP_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, BACKSHOP_ID);

    public static final int MIN_Y = 0;
    public static final int HEIGHT = 256;

    public static void bootstrapType(BootstrapContext<DimensionType> context) {
        context.register(BACKSHOP_TYPE, new DimensionType(
            // No fixed_time. It pinned the sun at noon for the *renderer* and nothing else: a mod
            // reading getDayTime() in here already saw the overworld's clock, because a non-
            // overworld level's DerivedLevelData reads the overworld's, so the reason written
            // beside it was never true. What it did do was stop an Overworld room ever having a
            // night, and RoomWallBlock#openToTheSky is what makes that visible.
            OptionalLong.empty(),           // fixed_time - the Backshop keeps the overworld's hours
            false,                          // has_skylight - also stops solar panels running forever in a sealed bay
            false,                          // has_ceiling
            false,                          // ultrawarm
            false,                          // natural
            1.0,                            // coordinate_scale
            false,                          // bed_works
            false,                          // respawn_anchor_works
            MIN_Y,
            HEIGHT,
            HEIGHT,                         // logical_height
            BlockTags.INFINIBURN_OVERWORLD,
            // The overworld's own sky, sun, moon, stars and clouds. It was the End's - no sun, no
            // moon, no clouds, the closest vanilla look to a void - which was right while nothing
            // in the Backshop could see out of the box it was in. An Overworld room can, so this
            // is what it sees, and it is the whole feature: the game already ships this renderer.
            // Every other room and every bay is sealed, so none of them can tell the difference.
            BuiltinDimensionTypes.OVERWORLD_EFFECTS,
            1.0F,                           // ambient_light
            new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0)));
    }

    public static void bootstrapStem(BootstrapContext<LevelStem> context) {
        HolderGetter<DimensionType> types = context.lookup(Registries.DIMENSION_TYPE);
        HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);

        // Vanilla's own the_void preset sets decoration true and adds a start platform. This
        // does not: no layers, no lakes, no features, no structures.
        FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(
            Optional.of(HolderSet.direct()), biomes.getOrThrow(Biomes.THE_VOID), List.of());
        flat.updateLayers();

        context.register(BACKSHOP_STEM, new LevelStem(types.getOrThrow(BACKSHOP_TYPE), new FlatLevelSource(flat)));
    }
}
