package rain.gtetcore.gtet.data

import com.tterrag.registrate.providers.ProviderType
import net.minecraft.data.DataGenerator
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.data.lang.LangHandler

/** 数据生成入口。 */
object GTETDatagen {

    /** mod 构造阶段调用，挂载 en_us 到 Registrate LANG provider。参照 GTCEu GregTechDatagen.initPost()。 */
    fun initRegistrate() {
        OnlyETreg.ETRegistrate.addDataGenerator(ProviderType.LANG) { LangHandler.init(it) }
    }

    /** GatherDataEvent 中调用，注册 zh_cn LanguageProvider。 */
    fun init(gen: DataGenerator) {
        gen.addProvider(true, LangHandler.ZhCNProvider(gen.packOutput))
    }
}
