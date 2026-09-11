package rain.gtetcore.gtet.util

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.gregtechceu.gtceu.common.item.TooltipBehavior
import com.tterrag.registrate.builders.ItemBuilder
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 链式 `.tooltips(key, en to cn, ...)` — 显式给键名 + 一对对中英（旧写法，保留兼容）。
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

/**
 * 链式 `.tooltips("中文1", "中文2", "English1", "English2")` —— 位置式双语提示，**前半中文、后半英文**。
 *
 * 行数规则：
 * - 偶数行：正好对半 —— `("中", "英")` 1+1；`("中1", "中2", "英1", "英2")` 2+2；
 * - 奇数行：中间那行中英共用 —— `("中1", "中2", "英1")` 得到中文 [中1, 中2] / 英文 [中2, 英1]。
 *
 * lang 键按物品注册名自动生成：`item.gtetcore.<注册名>.tooltip.<i>`，
 * 由 [rain.gtetcore.gtet.data.lang.LangHandler] 同时写进 `zh_cn` 与 `en_us`，
 * 因此**游戏会自动按当前语言选**，不需要运行时判断语言。
 *
 * ```kotlin
 * .tooltips("右键方块选区", "Shift+右键清除", "Right-click to select", "Shift+right-click to clear")
 * .tooltips("只有一个提示时", "Single tooltip")
 * ```
 */
fun <T : Item, R> ItemBuilder<T, R>.tooltips(
    first: String,
    second: String,
    vararg rest: String
): ItemBuilder<T, R> {
    val lines = arrayOf(first, second, *rest)
    // 偶数：正好对半；奇数：中间那行两边共用
    val half = (lines.size + 1) / 2
    val cnLines = lines.take(half)
    val enLines = lines.takeLast(half)

    val langKeys = cnLines.indices.map { i ->
        val langKey = "item.gtetcore.$name.tooltip.$i"
        LangUtil.add(langKey, enLines[i], cnLines[i])
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
