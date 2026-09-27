package rain.gtetcore.gtet.util

import net.minecraftforge.fml.ModList
import java.util.UUID

/**
 * 「同队」判定 —— **FTB Teams 的软依赖出口**（设定 §2.4：装了 FTB Teams 时同队视为同一人）。
 *
 * ## 为什么拆成两层
 * FTB Teams **没有**写进 `mods.toml` 的 `mandatory = true`（只声明成可选依赖），玩家完全可以不装。
 * 而 JVM 是**惰性解析**的：只要 [sameTeam] 里直接出现 `FTBTeamsAPI` 这个名字，这个方法第一次被调用
 * 时就会去加载那个类 —— 没装 FTB Teams 时直接 `NoClassDefFoundError`，整个 tick 崩掉。
 *
 * 所以这里分两层：
 * - 外层 [sameTeam] 只碰 `ModList`（Forge 本体，恒在），先判 mod 在不在；
 * - 真正的 FTB 调用关在 [FtbTeamsCompat] 这个**独立类**里。它只有在 `isLoaded` 为真时才会被
 *   第一次引用，进而才被类加载器加载 —— 没装 FTB Teams 时这个类**永远不会被加载**，
 *   方法体里的那些类型也就永远不会被解析。
 *
 * ⚠️ **别把 FTB 的类型提到外层**（哪怕只是当参数类型 / 局部变量声明），那会把惰性解析破坏掉。
 *
 * ## API 实证（javap，非记忆）
 * 对着依赖缓存里的 `ftb-teams-forge-…-mapped_official_1.20.1.jar` 实测，用到的三条：
 * ```
 * public static dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API api();          // 方法体只有 getstatic+areturn
 * public abstract boolean isManagerLoaded();                              // FTBTeamsAPI$API
 * public abstract boolean arePlayersInSameTeam(java.util.UUID, java.util.UUID);  // TeamManager
 * ```
 * 两条踩点：
 * 1. `api()` **不判空**（字节码见上），mod 没起来时返回 `null` ⇒ 必须自己判；
 * 2. `TeamManager` 上**没有** `isMember(UUID)`（那是实现类 `AbstractTeamBase` 才有的方法），
 *    所以同队判定用 `arePlayersInSameTeam` 最省事、也不会编译不过。
 * 3. 同队判定本身是安全的：`arePlayersInSameTeam` 的字节码是
 *    `getTeamForPlayerID(a).map(t -> …(b) == t).orElse(false)` —— 玩家没上线只是返回 `false`，不抛异常。
 *
 * @author rain fox
 */
object ETTeamAccess {

    /** FTB Teams 的 modId（实测自它 jar 内的 `META-INF/mods.toml`：`modId = "ftbteams"`）。 */
    private const val FTB_TEAMS_MOD_ID: String = "ftbteams"

    /**
     * 两个玩家 UUID 是不是「同一个人」——同一个 UUID、或（装了 FTB Teams 时）同一支队伍。
     *
     * 这是本对象**唯一**的对外入口（见类注释的惰性加载约定）。
     */
    @JvmStatic
    fun sameTeam(a: UUID, b: UUID): Boolean {
        if (a == b) return true
        if (!ModList.get().isLoaded(FTB_TEAMS_MOD_ID)) return false
        return FtbTeamsCompat.sameTeam(a, b)
    }

    /**
     * FTB Teams 的真实调用处 —— **必须保持成独立类**，理由见类注释。
     *
     * `private`（Kotlin 顶层嵌套 object 编译成独立的包私有类）：
     * 除了 [ETTeamAccess.sameTeam] 谁都不该直接引用它，免得有人顺手在别处 new 一个，
     * 把「没装就不加载」这条约定破坏掉。
     */
    private object FtbTeamsCompat {

        /** 走 FTB Teams 的队伍管理器判同队；管理器没起来（客户端 / 还没 init）时返回 `false`。 */
        fun sameTeam(a: UUID, b: UUID): Boolean {
            // ⚠️ `api()` 可能返回 null（字节码里没有判空），这里必须自己挡
            val api = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api() ?: return false
            if (!api.isManagerLoaded) return false
            return api.manager.arePlayersInSameTeam(a, b)
        }
    }
}
