# =============================================================================
#  gtmt-resfix.ps1 —— 给「刚 build 出来的补丁 GTMThings jar」补齐 datagen 资源
#
#  背景（详见 modpatch/gtmthings-1.6.0/NOTES.md §8）：
#    GTMThings 的 build.gradle 有 `sourceSets.main.resources { srcDir 'src/generated/resources' }`，
#    而 blockstates/*.json 与 models/block|item/*.json 全是 datagen 产物；D:\java\GTMThings-1.6.0
#    这个检出里 src/generated/resources **不存在**，所以只跑 `gradlew build` 出来的 jar 一定缺
#    这些 json（实测 456 条目 / assets 310）。这样的 jar 进游戏会让 GTMT 的机器模型全变紫黑块
#    （Exception loading blockstate definition: ... missing model for variant）。
#    修法：拿仓内 vendored 的 release 资源参考 zip（libs/gtmt/...，见下面的默认值）把缺的条目补进去。
#
#  三条硬约束（缺一不可，都写在下面）：
#    1. ⚠️ 输入 jar 里**已经存在**的条目一律原样拷贝、绝不覆盖
#       —— 这条天然保护我们改过的 assets/gtmthings/lang/en_us.json 与 zh_cn.json；
#    2. ⚠️ 绝不从参考 zip 里带进任何 *.class —— 那会覆盖补丁类；
#    3. ⚠️ 跳过 .cache/（ForgeGradle 留在原件里的缓存垃圾）。
#
#  ⚠️ 本脚本的 stdout 一律 ASCII：它由 Gradle 的 Exec 捕获，中文在这条链路上会因代码页不匹配变乱码。
#     （脚本自身的注释是中文，文件带 UTF-8 BOM，Windows PowerShell 5.1 才能正确读取；别去掉 BOM。）
#
#  ⚠️ 局部变量不能重名成参数名（$PatchedJar/$ReferenceJar/$OutJar）：PowerShell 里 [string] 类型的
#     参数会把之后同名赋值全部强制转成 String，`$x = ZipFile::OpenRead(...)` 会静默变成字符串，
#     后面所有循环都跑 0 次。
# =============================================================================
param(
    [Parameter(Mandatory = $true)][string]$PatchedJar,    # 刚 build 出来的补丁 jar（可能缺 datagen 资源）
    [Parameter(Mandatory = $true)][string]$ReferenceJar,  # 仓内 vendored 的 release 资源参考 zip
    [Parameter(Mandatory = $true)][string]$OutJar         # 输出：补齐后的补丁 jar
)
$ErrorActionPreference = 'Stop'
# PS 5.1 需要显式加载；PS 7 里 ZipFile 是内置的，Add-Type 那个名字反而会报错，所以吞掉异常
try { Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop } catch { }

if (-not (Test-Path -LiteralPath $PatchedJar)) { throw "patched jar not found: $PatchedJar" }
if (-not (Test-Path -LiteralPath $ReferenceJar)) { throw "reference jar not found: $ReferenceJar" }
$fullSrc = (Resolve-Path -LiteralPath $PatchedJar).Path
$fullOut = [System.IO.Path]::GetFullPath($OutJar)
if ($fullSrc -eq $fullOut) { throw "refusing to write the output over the input jar: $fullOut" }

$zipSrc = [System.IO.Compression.ZipFile]::OpenRead($fullSrc)
$zipRef = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ReferenceJar).Path)

# 输入 jar 已有的条目名（含目录条目）
$have = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($e in $zipSrc.Entries) { [void]$have.Add($e.FullName) }

# 挑出要从参考 zip 补的条目：参考 zip 自己的顺序
$added = New-Object 'System.Collections.Generic.List[System.IO.Compression.ZipArchiveEntry]'
foreach ($e in $zipRef.Entries) {
    $n = $e.FullName
    if ($have.Contains($n)) { continue }                            # 约束 1：已存在就跳过（绝不覆盖）
    if ($n.StartsWith('.cache/')) { continue }                      # 约束 3：ForgeGradle 缓存垃圾
    if ($n -like '*.class') { throw "refusing to add class entry: $n" }   # 约束 2：绝不带进 class
    # 我们的补丁 lang 只应该来自补丁 jar；这里缺了说明 build 产物本身不对，直接报错而不是"补"回来
    if ($n -eq 'assets/gtmthings/lang/en_us.json' -or $n -eq 'assets/gtmthings/lang/zh_cn.json') {
        throw "refusing to touch patched lang entry (missing from the built jar): $n"
    }
    $added.Add($e)
}

"gtmt-resfix: input   = $fullSrc  ($($zipSrc.Entries.Count) entries)"
"gtmt-resfix: ref     = $ReferenceJar"
"gtmt-resfix: to add  = $($added.Count) entries"
$added | ForEach-Object { [System.IO.Path]::GetExtension($_.FullName) } |
    Group-Object | Sort-Object Count -Descending |
    ForEach-Object { "   ext '$($_.Name)' = $($_.Count)" }

$outDir = [System.IO.Path]::GetDirectoryName($fullOut)
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
if (Test-Path -LiteralPath $fullOut) { Remove-Item -LiteralPath $fullOut -Force }

$dstStream = [System.IO.File]::Open($fullOut, [System.IO.FileMode]::CreateNew)
$zipDst = New-Object System.IO.Compression.ZipArchive($dstStream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    # 1) 忠实拷贝输入 jar 的每一条（顺序不变，内容逐字节）
    foreach ($e in $zipSrc.Entries) {
        $ne = $zipDst.CreateEntry($e.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        if ($e.FullName.EndsWith('/')) { continue }               # 目录条目：没有内容可拷
        $is = $e.Open(); $os = $ne.Open(); $is.CopyTo($os); $os.Close(); $is.Close()
    }
    # 2) 追加参考 zip 里缺的那些 datagen 资源
    foreach ($e in $added) {
        $ne = $zipDst.CreateEntry($e.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        if ($e.FullName.EndsWith('/')) { continue }
        $is = $e.Open(); $os = $ne.Open(); $is.CopyTo($os); $os.Close(); $is.Close()
    }
} finally {
    $zipDst.Dispose(); $dstStream.Dispose()
}

# 报一下规模，方便调用方（scripts/patches.gradle 的 jarAssetsProbe）对账
$zipCheck = [System.IO.Compression.ZipFile]::OpenRead($fullOut)
$total = 0; $assets = 0; $classes = 0
foreach ($e in $zipCheck.Entries) {
    $total++
    if ($e.FullName.StartsWith('assets/')) { $assets++ }
    if ($e.FullName.EndsWith('.class')) { $classes++ }
}
$zipCheck.Dispose()
$zipSrc.Dispose(); $zipRef.Dispose()

"gtmt-resfix: wrote $fullOut  ($total entries, assets=$assets, class=$classes)"
