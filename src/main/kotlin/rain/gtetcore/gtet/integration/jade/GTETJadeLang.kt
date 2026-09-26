package rain.gtetcore.gtet.integration.jade

import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.integration.jade.GTETJadeLang.CONFIG_THREADED_RECIPE_LOGIC
import rain.gtetcore.gtet.integration.jade.GTETJadeLang.CONFIG_THREADED_RECIPE_LOGIC_DESC
import rain.gtetcore.gtet.integration.jade.provider.ThreadedRecipeLogicProvider
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * GTET 自己的 Jade 插件所需的**翻译键**（`config.jade.*`）。
 *
 * ## 为什么单独一个文件（第三方 mod 的硬性契约，必须写在这里）
 * Jade（第三方 mod，本包依赖 11.13.3+forge）对**每一个**注册进插件配置的 provider
 * 都要求一条翻译键，而且这不是「显示不好看」的问题 —— 它在 dev 环境会**直接抛断言把客户端崩掉**：
 *
 * - 校验点：`snownee.jade.JadeClient#onGui(Screen)`。字节码/源码事实：
 *   1. 三道前置闸：`translationChecked`（只查一次）、`screen instanceof TitleScreen`（只在标题界面）、
 *      `snownee.jade.util.CommonProxy.isDevEnv()`（**只在开发环境**）；
 *   2. 遍历 `snownee.jade.impl.config.PluginConfig.INSTANCE.getKeys()`（`Set<ResourceLocation>`，
 *      元素就是各 provider 的 uid）；
 *   3. 用常量 `"config.jade.plugin_%s.%s"` + `(uid.getNamespace(), uid.getPath())` 拼键，
 *      `net.minecraft.client.resources.language.I18n.exists(key)` 判存在，**缺一条就攒着**；
 *   4. 攒够非空 → `throw new AssertionError("Missing config translation: " + Joiner.on(',').join(missing))`。
 *   由此可确认：**键名拼法 = `config.jade.plugin_` + uid 的 namespace + `.` + uid 的 path**
 *   （path 逐字照抄，带点也照抄）——我们的 uid 是 `gtetcore:threaded_recipe_logic`，
 *   所以键就是 [CONFIG_THREADED_RECIPE_LOGIC]。
 *
 * - 「这条键为什么会存在」：配置键由**客户端注册**时顺手加进 `PluginConfig`。
 *   `snownee.jade.impl.WailaClientRegistration#registerBlockComponent(...)`
 *   → `tryAddConfig(provider)` → 若 `!provider.isRequired()`（`IToggleableProvider#isRequired` 默认 false）
 *   且该 uid 还没配过，就 `addConfig(uid, provider.enabledByDefault())`。
 *   注意**服务端那条注册路径不参与**：`WailaCommonRegistration#registerBlockDataProvider` 不调 `tryAddConfig`；
 *   `registerBlockIcon` / `registerEntityIcon` / `registerEntityComponent` 也会加（同样的 `tryAddConfig`）。
 *   所以「注册了几个 provider」= 「要几条键」；我们只注册了 [ThreadedRecipeLogicProvider] 一个
 *   （icon / entity 都没有），因此**必填键只有一条**。
 *
 * - `.desc` 后缀那条（[CONFIG_THREADED_RECIPE_LOGIC_DESC]）**不是**必填：
 *   `snownee.jade.gui.config.value.OptionValue#<init>` 里对 `optionName + "_desc"` 走的是
 *   `I18n.exists(...)` 判断，存在才 `appendDescription(...)`。我们照样给上，
 *   配置界面里鼠标悬停就能看到「这条开关是干什么的」。
 *
 * - 配置界面里那条键**怎么被用**（同一条键，可自行核对）：
 *   `snownee.jade.gui.PluginsConfigScreen` 把 `"plugin_" + uid.namespace` 与 `uid.path` 用 `.` 拼起来，
 *   交给 `ConfigEntry#createUI` → `OptionValue#makeTitle` → `OptionsList$Entry#makeKey`，
 *   而 `makeKey` 就是 `Util.makeDescriptionId("config", new ResourceLocation("jade", key))`
 *   —— 拼出来正是 `config.jade.plugin_<ns>.<path>`，与断言用的是同一条键。
 *   顺带：界面分组标题走 `plugin_<ns>`（即 `config.jade.plugin_gtetcore`），但它只在
 *   `ModIdentification#getModName` 认不出这个命名空间（不是已加载的 mod、也没有
 *   `modmenu.nameTranslation.gtetcore`）时才会被用到，且同样是 `I18n.exists` 软判断，**不必填**。
 *
 * ## 为什么必须挂在很早的入口、而不是挂在 [GTETJadePlugin] 里
 * 数据生成（`GatherDataEvent`）只认**那时已经登记好**的语言条目：本文件把键写进 [LangUtil.CUSTOM_LANG]，
 * 由 `rain.gtetcore.gtet.data.lang.LangHandler#autoGenCustomLang` 落盘。
 * 而 [GTETJadePlugin] 要等加载末尾 `FMLLoadCompleteEvent` 被 Jade 的注解扫描
 * （`snownee.jade.util.CommonProxy#loadComplete`）反射加载，那时数据生成早就结束了 ——
 * 键会只存在于内存、进不了数据生成产出的语言文件（`assets/gtetcore/lang/` 下的 json），断言照样炸。
 * 所以入口是 `CommonProxy#kotlinInit()`（与 `ThreadedRecipeStatus.initLang()` 同一处、同一理由）。
 *
 * @author rain fox
 */
object GTETJadeLang {

    /** Jade 拼配置键的前缀（`JadeClient#onGui` 里的字符串常量 `config.jade.plugin_%s.%s` 的前半段）。 */
    private const val PLUGIN_CONFIG_KEY_PREFIX: String = "config.jade.plugin_"

    /**
     * [ThreadedRecipeLogicProvider] 的配置开关键 —— **必填**，缺了 dev 环境启动即崩。
     *
     * 值由「前缀 + 本 mod 的命名空间 + provider 的 uid 路径」拼出：uid 路径取自
     * [ThreadedRecipeLogicProvider.UID_PATH]（`const`，编译期内联，不会因此提前加载那个类），
     * 这样键与 uid 不可能各改一半 —— 这次崩掉的就是「uid 改了、键没跟上」。
     */
    const val CONFIG_THREADED_RECIPE_LOGIC: String =
        PLUGIN_CONFIG_KEY_PREFIX + Gtetcore.MODID + "." + ThreadedRecipeLogicProvider.UID_PATH

    /** 配置开关的悬停说明（Jade 里是 `键 + "_desc"`，软判断，可缺）。 */
    const val CONFIG_THREADED_RECIPE_LOGIC_DESC: String = CONFIG_THREADED_RECIPE_LOGIC + "_desc"

    /**
     * 登记本插件的 Jade 配置键（中英双语），由 `CommonProxy#kotlinInit()` 在**数据生成之前**调用。
     *
     * `en_ud.json` 由 en_us 自动派生，不用单独给。
     */
    @JvmStatic
    fun initLang() {
        LangUtil.add(
            CONFIG_THREADED_RECIPE_LOGIC,
            "Threaded Recipe Status",
            "多线程配方状态"
        )
        LangUtil.add(
            CONFIG_THREADED_RECIPE_LOGIC_DESC,
            "Show a machine's thread usage: how many threads are running and which recipe each one is processing. Machines without a thread rack (thread limit <= 1) are skipped.",
            "显示整机的线程占用：开了多少条线程、每条线程在跑什么配方。线程上限 ≤ 1（没装线程仓）的机器不显示。"
        )
    }
}
