package rain.gtetcore.gtet.mixin.GTM;

import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * GTM 样板总成上两个**私有成员**的访问桥。
 *
 * <p>只补两件本 mod 需要、而 GTM 没开的口子（都不改行为）：
 * <ul>
 * <li>{@code customName}：父类只给了 setter（Lombok {@code @Setter}），没有 getter。
 * 我们的面板要显示当前名字、AE 终端也要在「未成型」分支用上它；</li>
 * <li>{@code onPatternChange(int)}：父类私有，是**唯一的**「样板槽内容变了 → 更新
 * {@code detailsSlotMap}（AE 推送样板时的索引表）+ 取回旧槽内容 + 请求重传」入口。
 * 我们自定义的 {@code getTerminalPatternInventory()} 与面板槽都必须走到它，
 * 否则第 28 格往后放进去的样板不会出现在 {@code getAvailablePatterns()} 里、
 * {@code pushPattern} 也认不出来（配方永远拿不到货）。</li>
 * </ul>
 *
 * <p>⚠️ 用 {@code @Accessor}/{@code @Invoker} 而不是注入器：Mixin 0.8.5 的注解处理器对
 * **接口 mixin 里的注入器**是硬拒绝的（本项目 {@code MixinOverclockingLogic} 的类注释里记了这条），
 * 但 accessor/invoker 正是接口 mixin 支持的用法。两个方法名都带 {@code gtet$} 前缀，
 * 避免将来 GTM 自己加了同名成员时撞车。
 *
 * <p>{@code remap = false}：目标是 GTM 自己的成员（不是 MC 覆写点），生产环境里名字不变。
 *
 * @author rain fox
 */
@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public interface IMEPatternBufferAccess {

    /** 读父类的私有 {@code customName}（GTM 只生成了 setter）。 */
    @Accessor("customName")
    String gtet$getCustomName();

    /** 调父类的私有 {@code onPatternChange(int)}（样板槽内容变化的唯一处理入口）。 */
    @Invoker("onPatternChange")
    void gtet$onPatternChange(int index);
}
