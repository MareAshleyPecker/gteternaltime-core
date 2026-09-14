package rain.gtetcore.gtet.data.lang

import net.minecraftforge.common.data.LanguageProvider

object Lang {

    /**
     * GTM 在「一格部件不许被两个多方块共享」时用的那个错误键。
     *
     * 出现路径（GTM 7.5.3 源码）：
     * `BlockPattern#checkPatternAt` 逐格匹配，若该格方块是 `IMultiPart` 且
     * `part.isFormed() && !part.canShared() && !part.hasController(worldState.controllerPos)`，
     * 该格直接判失败并把错误设成 `PatternStringError("multiblocked.pattern.error.share")`；
     * 错误最终由 `PatternStringError#getErrorInfo()` 变成 `Component.translatable(键)`。
     *
     * ⚠️ 这个键 GTM 自己的 7 个语言文件里**一个都没有**（实测：解出 gtceu-1.20.1-7.5.3.jar 的
     * `assets/gtceu/lang/` 下那 7 份 json，全库 grep `multiblocked` 零命中），LDLib、GTMThings 的语言文件里
     * 也没有；键名带着别的命名空间（Multiblocked，LDLib 同作者的那套多方块框架），GTM 只是引用了它。
     * 所以一旦有界面把它渲染出来，玩家看到的就是键名本身。
     *
     * ⚠️ 另有一条**当前状态**要写清楚：在 GTM 7.5.3 里 `PatternStringError#getErrorInfo()`
     * **没有任何调用点**（把 7.5.3 产物 jar 里 2183 个 class 全量扫过，只有那 3 个错误类自己声明了它，
     * 没有任何类引用它），也就是说这个错误串今天并不会被 GTM 自己画到界面上 —— 成型失败这件事是真的
     * （判定在 `checkPatternAt` 里），只是玩家看到的是控制器 UI 里那句通用的
     * `gtceu.multiblock.invalid_structure`。补上这条翻译是为了：**哪天有渲染层（GTM 后续版本、
     * Jade/TOP 一类的提示、别的整合包自己的界面）把它取出来用时，玩家看到的是人话而不是键名**。
     *
     * 同族的另两条键（`multiblocked.pattern.error.chunk` = `MultiblockState.UNLOAD_ERROR`、
     * `multiblocked.pattern.error.init` = `MultiblockState.UNINIT_ERROR`）同样缺失、同样没有渲染点，
     * 本次**没有**一并补 —— 与本次的仓室隔离没有关系，需要时再按同一套写法加。
     *
     * ⚠️ 能不能在 `gtetcore` 自己的语言文件里定义别的命名空间的键：**能**。客户端
     * `ClientLanguage` 与 Forge 服务端的 `LanguageHook` 都是「遍历 resourceManager 里的**所有**
     * 命名空间、把 `lang/<locale>.json` 合并进同一张表」（两支的字节码都核过），
     * 键名不要求与文件所在命名空间一致，这是各 mod 互相补齐翻译的常规做法。
     */
    const val SHARE_ERROR_KEY = "multiblocked.pattern.error.share"

    private const val SHARE_ERROR_EN = "This block is already part of another formed multiblock"

    private const val SHARE_ERROR_CN = "该方块已被另一个已成型的多方块占用"

    /** 由 [LangHandler] 在数据生成时对 en_us / zh_cn 各调一次（两边键数保持相等）。 */
    fun init(provider: LanguageProvider, locale: String) {
        provider.add(SHARE_ERROR_KEY, if (locale == "zh_cn") SHARE_ERROR_CN else SHARE_ERROR_EN)
    }
}
