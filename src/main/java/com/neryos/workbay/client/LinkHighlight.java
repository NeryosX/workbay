package com.neryos.workbay.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.neryos.workbay.Workbay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The block a hovered LINKS row points at, outlined in the world. SPEC.md §7's first QOL item.
 *
 * <p>It is the only real answer to "which chest is this row?", which stops being a rhetorical
 * question at about the fourth link. LaserIO and XNet both do it.
 *
 * <p>Drawn as vanilla's own block outline, through {@code RenderType.lines()} and the level's
 * buffer source. A hand-rolled no-depth pass was tried first, to make a target behind a wall
 * findable, and drew nothing at all: the level's pose stack already carries the camera, so a
 * direct {@code BufferUploader} draw is transformed twice. An x-ray outline needs its own
 * {@code RenderType}, which is not worth a custom render type until somebody asks.
 *
 * <p>One static field rather than a per-screen listener: the screen sets it while it draws and the
 * world consumes it on the next frame, which is a frame of lag nobody can see and no plumbing.
 */
@EventBusSubscriber(modid = Workbay.MOD_ID, value = Dist.CLIENT)
public final class LinkHighlight {
    private LinkHighlight() {}

    @Nullable
    private static GlobalPos target;

    public static void set(GlobalPos at) {
        target = at;
    }

    /** Called every frame before the page draws, and when the screen closes. */
    public static void clear() {
        target = null;
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        GlobalPos at = target;
        Minecraft minecraft = Minecraft.getInstance();
        // A link may point into another dimension once the Resonator is installed. There is
        // nothing to draw here then, and the row's own text already names where it is.
        if (at == null || minecraft.level == null
            || !minecraft.level.dimension().equals(at.dimension())) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        // Fractionally larger than the block, or the outline z-fights with the block's own faces.
        AABB box = new AABB(at.pos()).inflate(0.004).move(-camera.x, -camera.y, -camera.z);

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        MultiBufferSource.BufferSource buffers =
            minecraft.renderBuffers().bufferSource();
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box,
            0.35F, 0.66F, 0.90F, 0.9F);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
}
