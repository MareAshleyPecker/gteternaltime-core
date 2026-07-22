package rain.gtetcore.GTET.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import org.lwjgl.opengl.GL11;

import rain.gtetcore.GTET.common.StructureWriteBehavior;

/**
 * 客户端渲染器：手持结构工具时绘制选区半透明立方体。
 * 用 @SubscribeEvent + 强引用防止被 GC。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureOverlayRenderer {

    // 持有自身引用防止 GC
    private static final StructureOverlayRenderer INSTANCE = new StructureOverlayRenderer();

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    @SubscribeEvent
    public void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var player = Minecraft.getInstance().player;
        if (player == null) return;

        var stack = player.getMainHandItem();
        if (!StructureWriteBehavior.isItemStructureWriter(stack)) {
            stack = player.getOffhandItem();
            if (!StructureWriteBehavior.isItemStructureWriter(stack)) return;
        }

        BlockPos[] pos = StructureWriteBehavior.getPos(stack);
        if (pos == null) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        BlockPos min = pos[0], max = pos[1];
        AABB aabb = new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder builder = tessellator.getBuilder();

        // 线框
        RenderSystem.lineWidth(2.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
        LevelRenderer.renderLineBox(poseStack, builder, aabb, 0.2F, 0.9F, 0.2F, 0.9F);
        tessellator.end();

        // 半透明面
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        fillFaces(builder, aabb);
        tessellator.end();

        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void fillFaces(BufferBuilder b, AABB box) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        float r = 0.2F, g = 0.9F, bl = 0.2F, a = 0.15F;

        quad(b, x2, y1, z1, x1, y1, z1, x1, y1, z2, x2, y1, z2, r, g, bl, a);
        quad(b, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, r, g, bl, a);
        quad(b, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, r, g, bl, a);
        quad(b, x2, y1, z2, x1, y1, z2, x1, y2, z2, x2, y2, z2, r, g, bl, a);
        quad(b, x1, y1, z2, x1, y1, z1, x1, y2, z1, x1, y2, z2, r, g, bl, a);
        quad(b, x2, y2, z1, x2, y2, z2, x2, y1, z2, x2, y1, z1, r, g, bl, a);
    }

    private static void quad(BufferBuilder b, double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4,
                             float r, float g, float bl, float a) {
        b.vertex(x1, y1, z1).color(r, g, bl, a).endVertex();
        b.vertex(x2, y2, z2).color(r, g, bl, a).endVertex();
        b.vertex(x3, y3, z3).color(r, g, bl, a).endVertex();
        b.vertex(x4, y4, z4).color(r, g, bl, a).endVertex();
    }
}
