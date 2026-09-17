package com.neryos.workbay.datagen;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.content.connector.ConnectorBlock;
import com.neryos.workbay.content.room.RoomWallBlock;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import com.neryos.workbay.content.workbay.WorkbayState;
import com.neryos.workbay.init.WBBlocks;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class WBBlockStateProvider extends BlockStateProvider {

    public WBBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, Workbay.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // One model per lit state, all three orientable, all three sharing the top. SPEC.md §7
        // wants three readings distinguishable at distance and in the dark; the textures carry the
        // first half (the textures are generated, not hand-painted) and WBBlocks' lightLevel the second.
        // POWERED is not in the key: §7 says it may share art with idle, and a fourth axis of
        // twenty-four variants would be twenty-four ways to draw the same three pictures.
        java.util.Map<WorkbayState, ModelFile> models = new java.util.EnumMap<>(WorkbayState.class);
        for (WorkbayState lit : WorkbayState.values()) {
            String suffix = lit == WorkbayState.IDLE ? "" : "_" + lit.getSerializedName();
            models.put(lit, models().orientable("workbay" + suffix,
                blockTexture("workbay_side" + suffix),
                blockTexture("workbay_front" + suffix),
                blockTexture("workbay_top")));
        }

        // toYRot() + 180, not toYRot(). toYRot is the yaw of a player *looking* that way, which is
        // the opposite of the angle a north-facing model has to be turned by, so the bare value puts
        // the front of every block in this mod on its back. Vanilla's furnace blockstate is the
        // reference: facing=east is y=90, and Direction.EAST.toYRot() is 270.
        getVariantBuilder(WBBlocks.WORKBAY.get()).forAllStates(state -> ConfiguredModel.builder()
            .modelFile(models.get(state.getValue(WorkbayBlock.STATE)))
            .rotationY(((int) state.getValue(HorizontalDirectionalBlock.FACING).toYRot() + 180) % 360)
            .build());

        // The three rooms: one picture, three sizes of cube (RoomBlock#inset), the item being
        // the cube. Standing on the floor rather than centred, because a cube floating in the
        // middle of its block is a bug and one on the ground is a small block. Explicit 0..16
        // UVs, because an element's UVs default to its bounds and an 8-pixel cube would
        // otherwise show the middle quarter of the texture.
        for (var room : java.util.List.of(WBBlocks.ROOM, WBBlocks.WIDE_ROOM, WBBlocks.VAST_ROOM)) {
            int in = com.neryos.workbay.content.room.RoomBlock.inset(room.get().tier());
            ModelFile model = models().withExistingParent(room.getId().getPath(), mcBlock("block"))
                .texture("particle", blockTexture(WBBlocks.ROOM.get()))
                .texture("all", blockTexture(WBBlocks.ROOM.get()))
                .element().from(in, 0, in).to(16 - in, 16 - 2 * in, 16 - in)
                .allFaces((face, builder) -> {
                    builder.texture("#all").uvs(0, 0, 16, 16);
                    if (in == 0) {
                        builder.cullface(face);
                    }
                })
                .end();
            simpleBlock(room.get(), model);
        }

        // A thin plate drawn on the NORTH side, then rotated onto whichever face it is stuck to.
        // Same +180 as above; here getting it wrong put the plate on the far side of the block it
        // was stuck to, inside it, where nobody could see it at all.
        // The plate's own face samples the MIDDLE 8x8 of connector.png, because Minecraft derives
        // an element's UVs from its bounds and this one starts at 4,4. The texture is drawn there for
        // that reason; a plate drawn across the whole texture shows its bolts on the rim.
        // And a neck, poking two pixels into whatever it is stuck to. A Connector against a full
        // block never showed a gap; against a chest, which is fourteen wide and inset by one, the
        // plate floated with daylight behind it. An element may reach outside its own cell, so the
        // neck bridges the inset and is simply buried inside a full block. Found in play.
        ModelFile plate = models().withExistingParent("connector", mcBlock("block"))
            .texture("particle", blockTexture("connector"))
            .texture("plate", blockTexture("connector"))
            .element().from(4, 4, 0).to(12, 12, 2)
            .allFaces((face, builder) -> builder.texture("#plate")).end()
            .element().from(6, 6, -2).to(10, 10, 0)
            .allFaces((face, builder) -> builder.texture("#plate")).end();

        getVariantBuilder(WBBlocks.CONNECTOR.get()).forAllStates(state -> {
            Direction facing = state.getValue(ConnectorBlock.FACING);
            return ConfiguredModel.builder()
                .modelFile(plate)
                // x=90 puts the plate on the bottom of its own cell, which is where a Connector
                // pointing down belongs. Checked in game against a full block: on a chest it looks
                // like it floats, but that is the chest being 14/16 tall, not the rotation.
                .rotationX(facing == Direction.DOWN ? 90 : facing == Direction.UP ? 270 : 0)
                .rotationY(facing.getAxis().isHorizontal() ? ((int) facing.toYRot() + 180) % 360 : 0)
                .build();
        });


        // A Port is only ever seen by a player standing in a room, which cannot happen in v1 - but
        // six of them seal one hosted machine, so the face has to read as a door from the inside.
        simpleBlock(WBBlocks.PORT.get(), models().cubeAll("port", blockTexture("port")));

        // A room's shell: one greyscale cube per part, and a variant per (colour, part) pointing
        // at it. cubeAll() cannot be used -- it writes no tintindex, and without one the block
        // colour handler is never asked and every room is grey whatever its record says.
        java.util.Map<com.neryos.workbay.content.room.RoomPart, ModelFile> parts =
            new java.util.EnumMap<>(com.neryos.workbay.content.room.RoomPart.class);
        for (com.neryos.workbay.content.room.RoomPart part
                : com.neryos.workbay.content.room.RoomPart.values()) {
            // The door too: it is drawn in the mod's own iron with a lit lintel, the same in
            // every room colour, so a player sees the way out from across the room (#119).
            parts.put(part, part == com.neryos.workbay.content.room.RoomPart.LIGHT || part.isDoor()
                ? lampCube(part.texture(), part.texture())
                : tintedCube(part.texture(), part.texture()));
        }
        getVariantBuilder(WBBlocks.ROOM_WALL.get()).forAllStates(state -> ConfiguredModel.builder()
            .modelFile(parts.get(state.getValue(RoomWallBlock.PART))).build());
    }

    /** A full cube of one texture with a tint index on every face, which {@code cubeAll} omits. */
    private ModelFile tintedCube(String name, String texture) {
        return models().withExistingParent(name, mcBlock("block"))
            .texture("particle", blockTexture(texture))
            .texture("all", blockTexture(texture))
            .element().from(0, 0, 0).to(16, 16, 16)
            .allFaces((face, builder) -> builder.texture("#all").tintindex(0).cullface(face))
            .end();
    }

    /**
     * The ceiling's light fixture. The one shell model with no tint index, no diffuse shading and
     * full block light - OPEN_ISSUES #45, which is exactly the three reasons a bright ceiling
     * texture could not be one: a lamp wearing the room's colour is not a lamp, a bottom face is
     * multiplied by <b>half</b> whatever value is drawn on it, and a fixture shaded by the light it
     * is itself emitting is lit from behind.
     */
    private ModelFile lampCube(String name, String texture) {
        return models().withExistingParent(name, mcBlock("block"))
            .texture("particle", blockTexture(texture))
            .texture("all", blockTexture(texture))
            .element().from(0, 0, 0).to(16, 16, 16)
            .shade(false)
            .emissivity(15, 15)
            .allFaces((face, builder) -> builder.texture("#all").cullface(face))
            .end();
    }

    private static ResourceLocation mcBlock(String path) {
        return ResourceLocation.withDefaultNamespace("block/" + path);
    }

    /** One of ours, under {@code assets/workbay/textures/block}. */
    private static ResourceLocation blockTexture(String path) {
        return ResourceLocation.fromNamespaceAndPath(Workbay.MOD_ID, "block/" + path);
    }
}
