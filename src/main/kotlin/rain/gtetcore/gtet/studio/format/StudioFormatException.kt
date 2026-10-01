package rain.gtetcore.gtet.studio.format

/**
 * 导入格式本身读不通（连一个三角形都解不出来）。
 *
 * 与 `kernel.StudioMeshException`（几何不成立）、`data.StudioLoadException`（这份文件读不出来）
 * 三者分工明确，互不 import。
 *
 * @author rain fox
 */
class StudioFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)