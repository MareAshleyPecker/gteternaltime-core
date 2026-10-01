package rain.gtetcore.gtet.studio.kernel

/**
 * 网格数据本身不合法（顶点数、索引越界、NaN……）。
 *
 * 与 `data/StudioLoadException` 的分工：那个是「**这份文件**读不出来」，
 * 这个是「**这份几何**不成立」——后者是纯内核概念，不该依赖 data 层。
 *
 * @author rain fox
 */
class StudioMeshException(message: String) : IllegalArgumentException(message)