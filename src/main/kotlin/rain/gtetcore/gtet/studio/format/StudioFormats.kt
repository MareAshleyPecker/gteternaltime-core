package rain.gtetcore.gtet.studio.format
import rain.gtetcore.gtet.studio.format.StudioFormats.register

/**
 * 导入器**注册表**：按 id（JSON 的 `source.format`）或按扩展名找 [StudioFormat]。
 *
 * 加一种格式 = 写一个 [StudioFormat] + 在 [register] 里挂一行，别处一行都不用改
 * ——这就是"可插拔"的全部含义。
 *
 * ```
 * StudioFormats.byId("obj")            // JSON 写了 format 时
 * StudioFormats.forFileName("a.OBJ")   // JSON 没写 format 时按扩展名认领（大小写不敏感）
 * ```
 *
 * ## 关于 Blockbench：**M1a 不做，理由写在下面**（结论，不是待办）
 *
 * 用户拍板的导入清单里有 Blockbench（设计文档 §2 第 3 条），评估后的结论是
 * **现在不做，接口留好即可**：
 *
 * 1. **它俩不是一个东西**。Blockbench 有两种导出：
 *    - `java_block` JSON（原版方块模型）：`{ "elements": [ { "from": [..], "to": [..],
 *      "faces": { "north": { "uv": [..], "texture": "#0" } } } ], "textures": { "0": "..." } }`
 *      —— 它是**体素/立方体**模型，带每面 UV 与贴图变量，还能带 `rotation` 与 `display` 变换；
 *    - `.bbmodel`：还额外带骨骼（outliner）、动画关键帧、纹理源图。
 * 2. **塞进 `StudioMesh` 会丢信息**：转成三角形网格之后，"这是一个可以整体改尺寸的立方体"
 *    就没了 —— 而 BB 范式（M5）要的恰恰是这个（每面 UV、立方体身份、吸附到格）。
 *    真正该做的导入目标是**体素模型**，那是 M5 的内核，不是这里的三角网格。
 * 3. **现在做就是白做**：M5 之前没有任何东西消费体素模型，转换器写了也没法验收
 *    （设计文档 §5 把 BB 排到 M5，正是因为"体素网格 + 面材质分配"是另一套内核）。
 * 4. **代价与收益**：成本 = 每面 UV 映射 + 立方体膨胀（from/to 六面 12 三角形）+ 旋转元素 +
 *    贴图变量解析 + 与 MC 方块模型的坐标约定（16 像素 = 1 格、Y 向下为 0）对齐；
 *    收益 = 0（M1a 的验收标准是"Blender 导出的 OBJ 直接能用"）。
 *
 * ⇒ **接口已经留好**：M5 开工时写 `BbModelFormat`（或者直接 `JavaBlockFormat`）实现 [StudioFormat]，
 * 或者按上面第 2 条另开一个体素导入器 —— 两条路都不需要改动今天这份代码。
 * glTF 同理：它是**三角网格**、与 `StudioMesh` 天然对齐，等真有人要导入 glTF 时
 * 写一个 `GltfFormat` 挂进来即可（需要自己解 GLB/JSON + accessor，成本主要在 buffer 解析）。
 *
 * @author rain fox
 */
object StudioFormats {

    private val byId = LinkedHashMap<String, StudioFormat>()
    private val byExtension = HashMap<String, StudioFormat>()

    init {
        register(ObjFormat)
    }

    /** 登记一种格式。重复 id / 重复扩展名会覆盖前一个（并留给调用方自己判断要不要警告）。 */
    fun register(format: StudioFormat) {
        byId[format.id.lowercase()] = format
        for (ext in format.extensions) {
            byExtension[ext.lowercase()] = format
        }
    }

    /** 已登记的格式 id（诊断 / 报错时列给用户看）。 */
    fun ids(): Set<String> = byId.keys

    fun byId(id: String): StudioFormat? = byId[id.lowercase()]

    fun byExtension(extension: String): StudioFormat? = byExtension[extension.lowercase()]

    /**
     * 按文件名认领：取最后一个 `.` 之后的部分当扩展名。
     *
     * 注意**不是**按路径分隔符切（`a.b/clock` 这种目录名也算），因为我们只关心最后一段扩展名，
     * 而 OBJ 引用 MTL / 贴图时又常带目录。
     */
    fun forFileName(fileName: String): StudioFormat? {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return null
        return byExtension(fileName.substring(dot + 1))
    }
}