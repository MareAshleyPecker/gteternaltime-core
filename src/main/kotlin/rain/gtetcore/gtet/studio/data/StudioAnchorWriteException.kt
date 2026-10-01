package rain.gtetcore.gtet.studio.data

/**
 * 写回 JSON 失败 —— **文件必须保持原样**。
 *
 * 「宁可不写，也不许写坏」：模型 JSON 是**带大量注释的 JSONC**（`config/gtetstudio/clock.json` 那种），
 * 一旦用 gson 整份 `toJson` 回写，注释就全没了，等于把这些文件里的说明文档毁掉。
 * 所以写回走**文本级、字段级替换**，替换做不到（结构对不上、数组里带注释……）就抛这个异常，
 * 由调用方原样保留文件并把原因打到聊天栏。
 *
 * @author rain fox
 */
class StudioAnchorWriteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)