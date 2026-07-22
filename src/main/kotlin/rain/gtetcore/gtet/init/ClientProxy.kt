package rain.gtetcore.gtet.init

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn


/**
 * 客户端专用代理 —— 仅在物理客户端加载。
 * 所有仅客户端需要初始化的逻辑放在此处。
 */
@OnlyIn(Dist.CLIENT)
open class ClientProxy : CommonProxy() {
    init { rain.gtetcore.GTET.client.StructureOverlayRenderer.register() }
}