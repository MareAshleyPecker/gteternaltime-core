package rain.gtetcore.gtet.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import rain.gtetcore.gtet.common.item.tool.StructureDetectBehavior;
import rain.gtetcore.gtet.common.item.tool.StructureWriteBehavior;
import rain.gtetcore.gtet.config.GTETConfig;

/**
 * 客户端渲染器：手持结构工具时绘制选区半透明立方体，
 * 手持结构检测工具时绘制蓝色单方块线框。
 *
 * <p>关键点（旧实现的两个坑）：
 * <ol>
 *   <li>所有顶点都交给原版 <b>RenderType 管线</b>绘制，不再直接用 {@code Tesselator} +
 *       {@code RenderSystem}：手写顶点时必须自己乘 pose 矩阵，
 *       而 {@link LevelRenderer#renderLineBox} 这类原版工具内部已经乘过了，
 *       两者混用会让一部分几何被平移一整个相机坐标（飘出视野）。</li>
 *   <li>线框走 {@link RenderType#lines()}（{@code POSITION_COLOR_NORMAL} + {@code rendertype_lines} 着色器，
 *       由法线把线段展开成屏幕空间四边形）。旧的 {@code GameRenderer.getPositionColorShader()}
 *       配 {@code Mode.LINES} 在现代 GL 下只能是 1px 发丝线，
 *       {@code RenderSystem.lineWidth} 对它完全无效。该 RenderType 还带
 *       {@code VIEW_OFFSET_Z_LAYERING}，顺带解决线框与方块面重合导致的闪烁。</li>
 * </ol>
 *
 * @author rain fox
 */
@OnlyIn(Dist.CLIENT)
public final class StructureOverlayRenderer {

    // 持有自身引用防止 GC
    private static final StructureOverlayRenderer INSTANCE = new StructureOverlayRenderer();

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── 覆盖层颜色 ──
    // 配在 config/gtetcore/gtetcore-common.toml 的 [overlay] 段，一行一个颜色：
    //   writeColor  = "R;G;B;线透明度;填充透明度"  ← 选区导出（线框 + 半透明填充）
    //   detectColor = "R;G;B;透明度"               ← 结构检测错误位置（线框）
    // 渲染每帧都要取一次颜色，所以按「上次解析过的原始串」缓存，配置改了才重新解析。

    /** 默认：选区绿色线框 + 淡绿填充。 */
    private static final OverlayColor DEFAULT_WRITE_COLOR = new OverlayColor(0.2F, 0.9F, 0.2F, 1.0F, 0.15F);

    /** 默认：错误位置蓝色线框。 */
    private static final OverlayColor DEFAULT_DETECT_COLOR = new OverlayColor(0.2F, 0.4F, 1.0F, 1.0F, 1.0F);

    private static volatile String writeColorRawCache;
    private static volatile OverlayColor writeColorCache = DEFAULT_WRITE_COLOR;
    private static volatile String detectColorRawCache;
    private static volatile OverlayColor detectColorCache = DEFAULT_DETECT_COLOR;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    @SubscribeEvent
    public void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var player = Minecraft.getInstance().player;
        if (player == null) return;

        ItemStack mainStack = player.getMainHandItem();
        ItemStack offStack = player.getOffhandItem();

        ItemStack writeStack = StructureWriteBehavior.isItemStructureWriter(mainStack) ? mainStack
                : StructureWriteBehavior.isItemStructureWriter(offStack) ? offStack : null;
        ItemStack detectStack = StructureDetectBehavior.isItem(mainStack) ? mainStack
                : StructureDetectBehavior.isItem(offStack) ? offStack : null;
        if (writeStack == null && detectStack == null) return;

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();

        // 原版给的 pose stack 是相机空间且未做相机平移（原版画方块轮廓时也是手动减相机坐标），
        // 所以这里平移一次，之后一律用世界坐标。
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        try {
            if (writeStack != null) {
                renderWriteBox(poseStack, buffers, writeStack);
            }
            if (detectStack != null) {
                renderDetectBoxes(poseStack, buffers, detectStack);
            }
        } finally {
            poseStack.popPose();
            // 半透明面先画、线框后画，避免线被面糊上一层
            buffers.endBatch(RenderType.debugFilledBox());
            buffers.endBatch(RenderType.lines());
        }
    }

    /** 结构工具：选区立方体（半透明面 + 线框，颜色走配置）。 */
    private void renderWriteBox(PoseStack poseStack, MultiBufferSource buffers, ItemStack stack) {
        BlockPos[] pos = StructureWriteBehavior.getPos(stack);
        if (pos == null) return;

        // pos[0] / pos[1] 是含端点的最小 / 最大方块，所以 +1 才是覆盖整块的包围盒
        AABB box = blockRangeBox(pos[0], pos[1]);

        OverlayColor color = writeColor();
        DebugRenderer.renderFilledBox(poseStack, buffers, box,
                color.r(), color.g(), color.b(), color.fillAlpha());
        LevelRenderer.renderLineBox(
                poseStack, buffers.getBuffer(RenderType.lines()), box,
                color.r(), color.g(), color.b(), color.lineAlpha());
    }

    /** 结构检测工具：逐个错误位置的线框（颜色走配置；超过配置的停留时间就不再画）。 */
    private void renderDetectBoxes(PoseStack poseStack, MultiBufferSource buffers, ItemStack stack) {
        if (detectOverlayExpired(stack)) return;

        BlockPos[] errors = StructureDetectBehavior.getPos(stack);
        if (errors == null || errors.length == 0) return;

        OverlayColor color = detectColor();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BlockPos p : errors) {
            if (p == null) continue;
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(p),
                    color.r(), color.g(), color.b(), color.lineAlpha());
        }
    }

    /**
     * 错误框是不是已经超过停留时间。
     *
     * <p>停留时长配在 {@code overlay.detectBoxLifetime}（秒，{@code 0} = 不自动消失）。
     * 时间戳是检测那一刻由服务端写进物品 NBT 的<b>游戏刻</b>（
     * {@link StructureDetectBehavior#getTime}），这里拿客户端自己的游戏刻相减：
     * 两边的游戏刻同步推进、暂停时都不走，所以"停留 N 秒"和玩家直觉一致。
     *
     * <p>物品上没有时间戳（老存档里的旧物品、或者手改的 NBT）按"早过期"处理，
     * 免得残留的框一直挂在世界上。
     */
    private static boolean detectOverlayExpired(ItemStack stack) {
        int seconds = GTETConfig.detectBoxLifetime();
        if (seconds <= 0) return false; // 0 = 不自动消失

        long written = StructureDetectBehavior.getTime(stack);
        if (written < 0) return true;

        var level = Minecraft.getInstance().level;
        if (level == null) return true;
        return level.getGameTime() - written > seconds * 20L;
    }

    // ── 颜色解析 ──

    /** 选区颜色（跟随配置；配置串非法时退回默认值）。 */
    private static OverlayColor writeColor() {
        String raw = GTETConfig.writeOverlayColor();
        if (!raw.equals(writeColorRawCache)) {
            writeColorRawCache = raw;
            writeColorCache = parseColor(raw, DEFAULT_WRITE_COLOR, "writeColor");
        }
        return writeColorCache;
    }

    /** 错误位置颜色（跟随配置；配置串非法时退回默认值）。 */
    private static OverlayColor detectColor() {
        String raw = GTETConfig.detectOverlayColor();
        if (!raw.equals(detectColorRawCache)) {
            detectColorRawCache = raw;
            detectColorCache = parseColor(raw, DEFAULT_DETECT_COLOR, "detectColor");
        }
        return detectColorCache;
    }

    /**
     * 解析一行颜色配置：{@code R;G;B}，后面可以按顺序再跟透明度（先是线框、后是填充）。
     *
     * <p>分量用分号或逗号分隔、取值 0~1（超范围会被夹回去）；某个分量写错了就用默认值，
     * 只有连 R;G;B 都凑不齐时才整条退回默认颜色，并记一条警告。
     */
    private static OverlayColor parseColor(String raw, OverlayColor fallback, String key) {
        if (raw == null || raw.isBlank()) return fallback;

        String[] parts = raw.trim().split("[;,]", -1);
        if (parts.length < 3) {
            LOGGER.warn("[gtetcore] overlay.{} 要写成 R;G;B（可再跟透明度），当前是 \"{}\"，已退回默认颜色",
                    key, raw);
            return fallback;
        }

        return new OverlayColor(
                component(parts[0], fallback.r()),
                component(parts[1], fallback.g()),
                component(parts[2], fallback.b()),
                parts.length > 3 ? component(parts[3], fallback.lineAlpha()) : fallback.lineAlpha(),
                parts.length > 4 ? component(parts[4], fallback.fillAlpha()) : fallback.fillAlpha());
    }

    /** 单个分量：解析失败用默认值，数值夹到 0~1。 */
    private static float component(String raw, float fallback) {
        try {
            return Math.max(0.0F, Math.min(1.0F, Float.parseFloat(raw.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 解析好的覆盖层颜色：RGB + 线框透明度 + 填充透明度。 */
    private record OverlayColor(float r, float g, float b, float lineAlpha, float fillAlpha) {}

    /** 由选区两个端点方块（含端点）得到包围盒。 */
    private static AABB blockRangeBox(BlockPos min, BlockPos max) {
        return new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
    }
}
