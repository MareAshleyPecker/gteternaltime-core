package rain.gtetcore.gtet.studio.data

/**
 * 工作室数据层的载入异常。
 *
 * ## 为什么单独开一个类型
 * 「载入失败要给出能直接定位问题的日志，绝不静默吞异常」是 M0 的硬要求，而
 * [StudioModelLoader] 与 [StudioLibrary] 要能把「**这份数据本身坏了**」和
 * 「IO 炸了 / 别的 bug」区分开：前者要给用户看一句人话（缺哪个文件、哪个组名不存在），
 * 后者要连堆栈一起打出来。
 *
 * @author rain fox
 */
class StudioLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)