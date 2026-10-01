package rain.gtetcore.gtet.studio.format

/**
 * **可插拔导入器**（设计文档 §3 的 `format/` 层）的对外契约。
 *
 * ## 这一层存在的唯一理由
 * M0 的模型来源写死在 `source.model`（一个资源路径）+ Forge 的 `forge:obj` 加载器上
 * —— 用户在 Blender 里导出一个新 OBJ，**必须先丢进 `src/main/resources` 再重新编译**才能看到。
 * M1a 把"从哪读、读什么格式"拆成两件事：文件从哪来交给 `data/StudioAsset`，
 * **格式怎么解**交给这里，加一种格式 = 新写一个 [StudioFormat] 并注册进 [StudioFormats]。
 *
 * ## 纪律
 * 实现类**不许 import Minecraft / Forge / GTM**，也不许自己读文件
 * —— 输入就是一段字节，需要读同目录的兄弟文件（MTL）时通过 [StudioImportContext.openSibling] 回调，
 * 由 `data/` 层注入真正的 IO。这样 format 层可以脱离游戏直接单测
 * （见 `StudioFormatSelfCheck`）。
 *
 * @author rain fox
 */
interface StudioFormat {

    /** 格式 id，对应 JSON 里 `source.format`（例如 `"obj"`）。 */
    val id: String

    /** 支持的扩展名（小写、不带点）。`source.format` 省略时按文件扩展名认领。 */
    val extensions: List<String>

    /**
     * 解析一段内容。
     *
     * **容错是硬要求**：坏行跳过并计数，最后由 [StudioImportResult.notes] 报一条汇总，
     * 不要一行错就整体抛异常（用户手写的/Blender 导出的 OBJ 里总有点脏东西）。
     * 真正致命的情况（一个顶点都没有、一个三角形都没解出来）才抛异常。
     *
     * @param bytes 文件原始字节（UTF-8）
     * @param ctx   上下文：出错时写进消息的"这是哪份文件"、贴图别名表、flip_v、兄弟文件读取回调
     */
    fun parse(bytes: ByteArray, ctx: StudioImportContext): StudioImportResult
}