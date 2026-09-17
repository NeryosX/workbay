package com.neryos.workbay.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.world.FaceConfig;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * The real block, turned by dragging it. SPEC.md §4's face config and upgrades preview.
 *
 * <p>SPEC.md §4 asked for "an isometric cube". A drawn cube is a picture of <em>a</em> block, not of
 * <b>this</b> block, and a player configuring an Enrichment Chamber's faces needs to see an
 * Enrichment Chamber. So this renders the actual block model through the game's own block renderer,
 * at whatever angle the player has dragged it to.
 *
 * <p>The face roles ride as markers at each visible face's projected centre rather than as coloured
 * quads laid over the model. Painting over the block would hide the thing the player came here to
 * look at, and depth-fighting a rotating model in a GUI layer is a fight with no prize.
 */
public class BlockPreview {

    /** Turned so the model's north face -- where a machine's front is -- points at the camera. */
    private static final float DEFAULT_YAW = 145F;
    private static final float DEFAULT_PITCH = 22F;

    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;

    private boolean dragging;
    private double dragged;

    /**
     * The same block as it stands in the bay: faced north, which is the side {@link #DEFAULT_YAW}
     * looks at. Without it the preview opens on the back of half the machines in the game,
     * because a default block state is not reliably north-facing. <b>The bay's own rule</b>, not
     * a copy of it: a second rule here laid a barrel on its side that the rack stood upright.
     * OPEN_ISSUES #99.
     */
    public static BlockState facingCamera(BlockState state) {
        return com.neryos.workbay.world.BayHosting.orient(state, Direction.NORTH);
    }

    /** Turned back to the angle the screen opened at, so a lost block is one double-click away. */
    public void reset() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
    }

    /**
     * Draws the block centred on (cx, cy) at {@code size} pixels per block.
     *
     * <p>{@code -size} on Y because GUI space runs downward and model space runs upward; without it
     * every block renders upside down.
     */
    public void render(GuiGraphics graphics, BlockState state, int cx, int cy, int size) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 200);
        pose.scale(size, -size, size);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-0.5F, -0.5F, -0.5F);

        MultiBufferSource.BufferSource buffers = graphics.bufferSource();
        RenderSystem.enableDepthTest();
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers,
            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        buffers.endBatch();
        pose.popPose();
    }

    /**
     * The role markers, drawn flat after the block so they are never behind it. Only faces turned
     * toward the viewer get one, which is also what makes a click unambiguous.
     */
    public void renderFaces(GuiGraphics graphics, net.minecraft.client.gui.Font font, int cx, int cy,
        int size, FaceConfig faces, BusConfig.Resource resource) {
        // In front of the block, not behind it. The model is rendered at z=200 and reaches its own
        // half-diagonal either side of that, so a flat marker at the default z=0 is simply hidden.
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 400);
        for (Direction face : Direction.values()) {
            if (!visible(face)) {
                continue;
            }
            int[] at = project(face, cx, cy, size);
            int colour = Draw.roleColour(faces.role(resource, face));
            // A chip, cut the way every other shape on these screens is cut. Two square fills
            // was the whole marker, and on a rounded panel six square chips stuck to a cube were
            // the one part of the picture that still looked drawn by a different hand.
            Draw.round(graphics, at[0] - 6, at[1] - 6, 12, 12, 3, Draw.EDGE_DARK);
            Draw.round(graphics, at[0] - 5, at[1] - 5, 10, 10, 2,
                Draw.mix(colour, 0xFFFFFFFF, 0.18F), colour);
            String letter = label(face);
            Draw.textCentre(graphics, font, letter, at[0], at[1] - 4, 12, 0xFF101214);
        }
        pose.popPose();
    }

    /**
     * A face's name from the machine's point of view, not the compass's.
     *
     * <p>Compass letters were the first thing tried and they are useless here: a bay always racks
     * its machine facing north ({@code BayHosting.rack}), the preview always opens looking at that
     * north face, and the player has no way to tell which side of their machine "W" is. Worse, a
     * chest -- and every other block drawn by a BlockEntityRenderer rather than a baked model --
     * renders as a featureless box in {@code renderSingleBlock}, so there is no latch or panel to
     * orient by either. Front and back are the two the player is actually choosing between.
     */
    static String label(Direction face) {
        return switch (face) {
            case NORTH -> "F";
            case SOUTH -> "B";
            case WEST -> "L";
            case EAST -> "R";
            case UP -> "T";
            case DOWN -> "D";
        };
    }

    /**
     * The lang suffix for a face, in the same words as {@link #label}. Here rather than beside the
     * one control that spells it out, so a second control naming the same six directions cannot
     * choose a different set of six words.
     */
    static String faceKey(Direction face) {
        return switch (face) {
            case NORTH -> "front";
            case SOUTH -> "back";
            case WEST -> "left";
            case EAST -> "right";
            case UP -> "top";
            case DOWN -> "bottom";
        };
    }

    /** Which face the player just clicked, or null when the click missed every visible one. */
    @Nullable
    public Direction faceAt(double mx, double my, int cx, int cy, int size) {
        Direction best = null;
        double bestDistance = 8 * 8;
        for (Direction face : Direction.values()) {
            if (!visible(face)) {
                continue;
            }
            int[] at = project(face, cx, cy, size);
            double distance = (mx - at[0]) * (mx - at[0]) + (my - at[1]) * (my - at[1]);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = face;
            }
        }
        return best;
    }

    // --------------------------------------------------------------- dragging

    public void press() {
        dragging = true;
        dragged = 0;
    }

    public void drag(double dx, double dy) {
        if (!dragging) {
            return;
        }
        dragged += Math.abs(dx) + Math.abs(dy);
        yaw += (float) dx * 2.0F;
        // Clamped so the block can be looked at from above and below but never rolled over, which
        // is disorienting and makes the face markers cross each other.
        pitch = Math.clamp(pitch + (float) dy * 2.0F, -85F, 85F);
    }

    /** True when the gesture was a click rather than a turn, so the face under it should cycle. */
    public boolean release() {
        boolean wasClick = dragging && dragged < 3.0;
        dragging = false;
        return wasClick;
    }

    // ------------------------------------------------------------ projection

    /**
     * The same two rotations the pose applies, done by hand so a face's centre can be turned into a
     * screen position without reading back a matrix. Yaw first, then pitch, matching
     * {@link #render}; {@code -y} again because GUI space runs downward.
     */
    private int[] project(Direction face, int cx, int cy, int size) {
        float[] p = rotate(face.getStepX() * 0.62F, face.getStepY() * 0.62F, face.getStepZ() * 0.62F);
        return new int[] { Math.round(cx + p[0] * size), Math.round(cy - p[1] * size) };
    }

    /** A face is worth drawing when its own normal, once turned, points out of the screen. */
    private boolean visible(Direction face) {
        return rotate(face.getStepX(), face.getStepY(), face.getStepZ())[2] > 0.15F;
    }

    /**
     * Yaw about +Y then pitch about +X, both right-handed, exactly as {@code Axis.YP} and
     * {@code Axis.XP} do them. The pitch used to be applied with the sign inverted, which left the
     * markers correct for the horizontal faces and swapped for the vertical pair: tilting up to
     * look at the bottom of a machine labelled it U.
     */
    private float[] rotate(float x, float y, float z) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        float x1 = (float) (x * Math.cos(yawRad) + z * Math.sin(yawRad));
        float z1 = (float) (-x * Math.sin(yawRad) + z * Math.cos(yawRad));
        float y2 = (float) (y * Math.cos(pitchRad) - z1 * Math.sin(pitchRad));
        float z2 = (float) (y * Math.sin(pitchRad) + z1 * Math.cos(pitchRad));
        return new float[] { x1, y2, z2 };
    }
}
