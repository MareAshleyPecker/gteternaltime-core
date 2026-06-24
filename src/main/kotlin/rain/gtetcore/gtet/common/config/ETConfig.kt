package rain.gtetcore.gtet.common.config

import net.minecraftforge.common.ForgeConfigSpec
import rain.gtetcore.gtet.api.annotation.ConfigLang

/**
 * GTET 模组配置文件。
 *
 * 所有 [ForgeConfigSpec.ConfigValue] 属性都标注了 [ConfigLang]，
 * 可通过 [ConfigLangHelper.extractTranslations] 提取为中英双语翻译表。
 *
 * Forge 会自动生成 TOML 配置文件：`config/gtetcore-common.toml`
 */
object ETConfig {

    // ============================================================
    //  Builder & Spec
    // ============================================================

    private val builder = ForgeConfigSpec.Builder()
    val spec: ForgeConfigSpec

    // ============================================================
    //  通用
    // ============================================================

    @ConfigLang(en = "Coil Temperature", zh = "线圈温度")
    @JvmStatic
    val coilTemperature: ForgeConfigSpec.IntValue

    @ConfigLang(en = "Energy Level", zh = "能量等级")
    @JvmStatic
    val energyLevel: ForgeConfigSpec.IntValue

    @ConfigLang(en = "Energy Discount", zh = "能量减免")
    @JvmStatic
    val energyDiscount: ForgeConfigSpec.IntValue

    // ============================================================
    //  调试
    // ============================================================

    @ConfigLang(en = "Debug Mode", zh = "调试模式")
    @JvmStatic
    val debugMode: ForgeConfigSpec.BooleanValue

    @ConfigLang(en = "Enable Experimental Features", zh = "启用实验性功能")
    @JvmStatic
    val experimentalFeatures: ForgeConfigSpec.BooleanValue

    // ============================================================
    //  Init
    // ============================================================

    init {
        builder.push("general")
        coilTemperature = builder
            .comment("线圈温度（单位：开尔文）")
            .defineInRange("coilTemperature", 114514, 0, Int.MAX_VALUE)
        energyLevel = builder
            .comment("能量等级")
            .defineInRange("energyLevel", 20, 0, 100)
        energyDiscount = builder
            .comment("能量减免百分比")
            .defineInRange("energyDiscount", 1, 0, 100)
        builder.pop()

        builder.push("debug")
        debugMode = builder
            .comment("启用调试日志与叠加层")
            .define("debugMode", false)
        experimentalFeatures = builder
            .comment("启用实验性 / 不稳定功能")
            .define("experimentalFeatures", false)
        builder.pop()

        spec = builder.build()
    }
}
