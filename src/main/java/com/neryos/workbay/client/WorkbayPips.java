package com.neryos.workbay.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.neryos.workbay.Workbay;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The eight status pips on the front of a Workbay. One per link, in the LINKS list's own order.
 *
 * <p>SPEC.md §7 gives the block one job - answer "is something wrong?" from across the room -
 * and the frame's own lit state already does it for the bay as a whole. These say <i>which</i>
 * link, which is the question a player asks second and which no single-colour block can answer:
 * eight independent lamps is 65,536 combinations, so it cannot be a blockstate and has to be
 * drawn.
 *
 * <p>The texture draws eight <b>grey sockets</b> and this lights the ones with a link in them, so
 * a Workbay with no links, an unloaded chunk, or a client that failed to get the update tag all
 * degrade to the same honest picture rather than to a hole. Only OK / ATTENTION / BROKEN are
 * drawn; {@code NONE} is the socket already in the texture.
 */
public class WorkbayPips implements BlockEntityRenderer<WorkbayBlockEntity> {

    /**
     * A 2x2 white pixel of our own. Vanilla has white textures too, but every one of them is an
     * atlas sprite or bound by a specific render type, and shipping four bytes is cheaper than
     * depending on where Mojang keeps theirs this version.
     */
    private static final ResourceLocation WHITE = Workbay.rl("textures/misc/pip.png");

    /**
     * Where the sockets are, in the front texture's own pixel grid - the same eight the {@code
     * rack} direction draws in the texture generator, which is the only other place these
     * numbers live. <b>Move one and you must move the other.</b> Column first, then row, so the list reads
     * down the left stack and then down the right, which is the order the LINKS list is in.
     */
    private static final int[][] SOCKETS = {
        {3, 3}, {3, 6}, {3, 9}, {3, 12},
        {12, 3}, {12, 6}, {12, 9}, {12, 12},
    };

    /** The owner's palette: blue says nothing is wrong, yellow says decide something, red says fix
     *  something. Amber is never a fault anywhere else in this mod and is not one here either -
     *  ATTENTION is a link that <i>can</i> be made to work, BROKEN is one that cannot. */
    private static final int[] COLOURS = {
        0x000000,           // NONE, never drawn
        0x6FD6FF,           // OK
        0xFFC132,           // ATTENTION
        0xEE342C,           // BROKEN
    };

    /** Off the face by a hair, or the pip z-fights with the pixel it sits on. */
    private static final float PROUD = 0.002f;

    public WorkbayPips(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WorkbayBlockEntity workbay, float partial, PoseStack pose,
        MultiBufferSource buffers, int light, int overlay) {
        byte[] pips = workbay.pips();
        if (pips.length == 0 || !workbay.getBlockState().hasProperty(HorizontalDirectionalBlock.FACING)) {
            return;
        }
        Direction facing = workbay.getBlockState().getValue(HorizontalDirectionalBlock.FACING);

        pose.pushPose();
        // Draw as if the block faced north, then turn the whole thing. NORTH.toYRot() is 180, so
        // this is a no-op for north and the sign is right for the other three by construction.
        pose.translate(0.5F, 0.5F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot()));
        pose.translate(-0.5F, -0.5F, -0.5F);

        VertexConsumer quads = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        Matrix4f matrix = pose.last().pose();
        for (int i = 0; i < pips.length && i < SOCKETS.length; i++) {
            int pip = pips[i];
            if (pip <= 0 || pip >= COLOURS.length) {
                continue;
            }
            // The north face's u runs against x and its v runs down from the top, so a texture
            // cell (column, row) is at block x = 15 - column and block y = 15 - row. Getting this
            // backwards mirrors the whole set and nothing else, which is why it is written out.
            float x0 = (15 - SOCKETS[i][0]) / 16.0F;
            float y0 = (15 - SOCKETS[i][1]) / 16.0F;
            pip(quads, matrix, x0, y0, COLOURS[pip]);
        }
        pose.popPose();
    }

    private static void pip(VertexConsumer quads, Matrix4f matrix, float x0, float y0, int rgb) {
        float x1 = x0 + 1 / 16.0F;
        float y1 = y0 + 1 / 16.0F;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        for (float[] corner : new float[][] {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}}) {
            quads.addVertex(matrix, corner[0], corner[1], -PROUD)
                .setColor(r, g, b, 255)
                .setUv(0.5F, 0.5F)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                // Full bright: a lamp that goes dark in a dark room is the one place the player
                // most needs to read it. The rest of the block is lit normally.
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 0.0F, -1.0F);
        }
    }

    /** Small, and only worth drawing close enough to tell eight of them apart. */
    @Override
    public int getViewDistance() {
        return 48;
    }

    @Override
    public boolean shouldRender(WorkbayBlockEntity workbay, Vec3 camera) {
        return workbay.pips().length > 0
            && camera.closerThan(Vec3.atCenterOf(workbay.getBlockPos()), getViewDistance());
    }
}
