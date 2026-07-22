package rain.gtetcore.gtet.client;

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

import rain.gtetcore.gtet.common.item.StructureWriteBehavior;
import rain.gtetcore.gtet.common.item.StructureDetectBehavior;

/**
 * 客户端渲染器：手持结构工具时绘制选区半透明立方体，
 * 手持结构检测工具时绘制红色（错误）和绿色（正确）单方块线框。
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

        var mainStack = player.getMainHandItem();
        var offStack = player.getOffhandItem();

        // 结构选区工具 — 绿色大立方体
        var writeStack = StructureWriteBehavior.isItemStructureWriter(mainStack) ? mainStack :
                StructureWriteBehavior.isItemStructureWriter(offStack) ? offStack : null;
        if (writeStack != null) {
            renderWriteBox(event, writeStack);
        }

        // 结构检测工具 — 参照 GTO，错误位置蓝色线框
        var detectStack = StructureDetectBehavior.isItem(mainStack) ? mainStack :
                StructureDetectBehavior.isItem(offStack) ? offStack : null;
        if (detectStack != null) {
            renderDetectBoxes(event, detectStack);
        }
    }

    private void renderWriteBox(RenderLevelStageEvent event, net.minecraft.world.item.ItemStack stack) {
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

        // 绿色线框
        RenderSystem.lineWidth(2.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
        LevelRenderer.renderLineBox(poseStack, builder, aabb, 0.2F, 0.9F, 0.2F, 0.9F);
        tessellator.end();

        // 半透明面
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        fillFaces(builder, aabb, 0.2F, 0.9F, 0.2F, 0.15F);
        tessellator.end();

        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    /** 渲染结构检测工具的错误方块线框（参照 GTO 用蓝色） */
    private void renderDetectBoxes(RenderLevelStageEvent event, net.minecraft.world.item.ItemStack stack) {
        var errors = StructureDetectBehavior.getPos(stack);
        if (errors == null || errors.length == 0) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder builder = tessellator.getBuilder();

        RenderSystem.lineWidth(3.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (var p : errors) {
            if (p == null) continue;
            builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
            LevelRenderer.renderLineBox(poseStack, builder,
                    p.getX(), p.getY(), p.getZ(),
                    p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0,
                    0.2F, 0.4F, 1.0F, 0.9F);  // GTO: blue highlight
            tessellator.end();
        }

        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void fillFaces(BufferBuilder b, AABB box, float r, float g, float bl, float a) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;

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
