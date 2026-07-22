package rain.gtetcore.gtet.util

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.gregtechceu.gtceu.common.item.TooltipBehavior
import com.tterrag.registrate.builders.ItemBuilder
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 链式 `.tooltips(key, en→cn, ...)` — 注册双语 lang 并注入 [TooltipBehavior] 组件使提示实际显示。
 */
fun <T : Item, R> ItemBuilder<T, R>.tooltips(
    key: String,
    vararg tips: Pair<String, String>
): ItemBuilder<T, R> {
    val langKeys = tips.mapIndexed { i, (en, cn) ->
        val langKey = "item.gtetcore.$key.tooltip.$i"
        LangUtil.add(langKey, en, cn)
        langKey
    }
    return onRegister { item ->
        if (item is ComponentItem) {
            item.attachComponents(TooltipBehavior { lines ->
                langKeys.forEach { k -> lines.add(Component.translatable(k)) }
            })
        }
    }
}
