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

import org.lwjgl.opengl.GL11;

import rain.gtetcore.GTET.common.StructureWriteBehavior;

/**
 * 客户端渲染器：手持结构工具时绘制选区半透明立方体（线框 + 填充面）。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureOverlayRenderer {

    private static final float R = 0.2F, G = 0.9F, B = 0.2F;
    private static final float WIRE_A = 0.9F, FACE_A = 0.15F;

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(StructureOverlayRenderer::onRenderLevelStage);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var player = Minecraft.getInstance().player;
        if (player == null) return;

        // 检查主副手
        var main = player.getMainHandItem();
        var off  = player.getOffhandItem();
        var stack = StructureWriteBehavior.isItemStructureWriter(main) ? main
                  : StructureWriteBehavior.isItemStructureWriter(off)  ? off  : null;
        if (stack == null) return;

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
        LevelRenderer.renderLineBox(poseStack, builder, aabb, R, G, B, WIRE_A);
        tessellator.end();

        // 填充面
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addQuads(builder, aabb);
        tessellator.end();

        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void addQuads(BufferBuilder b, AABB box) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        float r = R, g = G, bl = B, a = FACE_A;
        // 6 faces
        quad(b, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, bl, a); // bottom
        quad(b, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, r, g, bl, a); // top
        quad(b, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, r, g, bl, a); // north
        quad(b, x2, y1, z2, x1, y1, z2, x1, y2, z2, x2, y2, z2, r, g, bl, a); // south
        quad(b, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, bl, a); // west
        quad(b, x2, y2, z1, x2, y2, z2, x2, y1, z2, x2, y1, z1, r, g, bl, a); // east
    }

    private static void quad(BufferBuilder b, double vx1, double vy1, double vz1,
                             double vx2, double vy2, double vz2,
                             double vx3, double vy3, double vz3,
                             double vx4, double vy4, double vz4,
                             float r, float g, float bl, float a) {
        b.vertex(vx1, vy1, vz1).color(r, g, bl, a).endVertex();
        b.vertex(vx2, vy2, vz2).color(r, g, bl, a).endVertex();
        b.vertex(vx3, vy3, vz3).color(r, g, bl, a).endVertex();
        b.vertex(vx4, vy4, vz4).color(r, g, bl, a).endVertex();
    }
}
