#!/usr/bin/env bash
# =============================================================================
#  gtmt-resfix.sh —— 给「刚 build 出来的补丁 GTMThings jar」补齐 datagen 资源
#                    （Linux / macOS 版；Windows 用同目录的 gtmt-resfix.ps1，两者参数与防线一致）
#
#  背景（详见 modpatch/gtmthings-1.6.0/NOTES.md §8）：
#    GTMThings 的 build.gradle 有 `sourceSets.main.resources { srcDir 'src/generated/resources' }`，
#    而 blockstates/*.json 与 models/block|item/*.json 全是 datagen 产物；GTMThings 的检出目录里
#    src/generated/resources **不存在**，所以只跑 `gradlew build` 出来的 jar 一定缺
#    这些 json（实测 456 条目 / assets 310）。这样的 jar 进游戏会让 GTMT 的机器模型全变紫黑块
#    （Exception loading blockstate definition: ... missing model for variant）。
#    修法：拿仓内 vendored 的 release 资源参考 zip（libs/gtmt/...，见 scripts/patches.gradle）
#    把缺的条目补进去。
#
#  四条防线（缺一不可）：
#    1. ⚠️ 输入 jar 里**已经存在**的条目一律原样拷贝、绝不覆盖
#       —— 这条天然保护我们改过的 assets/gtmthings/lang/en_us.json 与 zh_cn.json；
#    2. ⚠️ 绝不从参考 zip 里带进任何 *.class —— 那会覆盖补丁类；
#    3. ⚠️ 跳过 .cache/（ForgeGradle 留在原件里的缓存垃圾）；
#    4. ⚠️ assets/gtmthings/lang/en_us.json 或 zh_cn.json 若**不在输入 jar 里**（即要从参考补回来）
#       → 直接报错退出：补丁 lang 只应该来自补丁 jar，缺了说明 build 产物本身不对，不能靠「补」。
#
#  ⚠️ 本脚本的 stdout 一律 ASCII：它由 Gradle 的 Exec 捕获，中文在这条链路上会因代码页不匹配
#     变乱码。因此打印的路径 / 条目名里的非 ASCII 字符会被替换成 '?'。文件本身是 UTF-8 文本。
#
#  ⚠️ 依赖 Python 3（标准库 zipfile）：本仓库不依赖 zip/unzip 命令行工具（Git Bash 里没有 zip，
#     最小 Linux 环境也常缺），用 zipfile 才能 1:1 对齐 ps1 的 System.IO.Compression 语义。
#     解释器按 python3 → python 顺序**实际执行**验证（`python3` 可能只是查得到、跑不起来的
#     Store 桩），都不可用时非零退出并说明替代做法。Python 程序以 heredoc 内嵌在本文件里，
#     参数从 argv 传入 —— 整个工具始终只有这一个 sh 文件。
# =============================================================================

set -u

me='gtmt-resfix'

# 非 ASCII 字节一律换成 '?'：stdout / stderr 必须全 ASCII（见文件头注释）
ascii() { printf '%s' "$1" | LC_ALL=C tr -c '\040-\176' '?'; }

err() { printf '%s: error: %s\n' "$me" "$(ascii "$1")" >&2; exit 1; }

# ---------- 参数：-PatchedJar / -ReferenceJar / -OutJar（三个必填） ----------
# 同时接受 -PatchedJar、--patched-jar、--PatchedJar 三种写法（三个参数同理），
# 这样 Gradle 侧不必为两个脚本各拼一套参数。
patched=''; reference=''; out=''
while [ $# -gt 0 ]; do
    arg="$1"; shift
    val=''
    case "$arg" in
        *=*) key="${arg%%=*}"; val="${arg#*=}" ;;
        *)   key="$arg" ;;
    esac
    # 归一化：去掉前导/中间的 '-' 并转小写 → PatchedJar / patched-jar / --PatchedJar 都变成 patchedjar
    keynorm=$(printf '%s' "$key" | tr 'A-Z' 'a-z' | tr -d '-')
    case "$keynorm" in
        patchedjar|referencejar|outjar) ;;
        *) err "unknown argument: $arg" ;;
    esac
    if [ -z "$val" ]; then
        [ $# -gt 0 ] || err "missing value for $arg"
        val="$1"; shift
    fi
    case "$keynorm" in
        patchedjar)   patched="$val" ;;
        referencejar) reference="$val" ;;
        outjar)       out="$val" ;;
    esac
done

[ -n "$patched" ]   || err 'missing required argument: -PatchedJar'
[ -n "$reference" ] || err 'missing required argument: -ReferenceJar'
[ -n "$out" ]       || err 'missing required argument: -OutJar'

[ -f "$patched" ]   || err "patched jar not found: $patched"
[ -f "$reference" ] || err "reference jar not found: $reference"

# ---------- 找 Python 3：必须实际跑起来才算数 ----------
PY=''
for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 &&
       "$c" -c 'import sys, zipfile; sys.exit(0 if sys.version_info[0] >= 3 else 1)' >/dev/null 2>&1; then
        PY="$c"
        break
    fi
done
if [ -z "$PY" ]; then
    # 两行都要自己 printf：err 会直接 exit，写在它后面就成了死代码
    printf '%s: error: %s\n' "$me" \
        'Python 3 is required but not found (tried: python3, python; a python3 on PATH may be a non-working stub)' >&2
    printf '%s:   install Python 3, or re-implement this step with "unzip" + the JDK "jar" tool\n' "$me" >&2
    exit 1
fi

# ---------- 真正的实现在下面的 Python 里（读 zip / 挑条目 / 写 zip / 报统计） ----------
"$PY" - "$patched" "$reference" "$out" <<'PYEOF'
import os
import shutil
import sys
import traceback
import zipfile

PREFIX = "gtmt-resfix"
LANG_ENTRIES = ("assets/gtmthings/lang/en_us.json", "assets/gtmthings/lang/zh_cn.json")


def _a(s):
    """stdout / stderr 一律 ASCII：非 ASCII 字符换成 '?'（见文件头注释）"""
    return str(s).encode("ascii", "replace").decode("ascii")


def note(msg):
    sys.stdout.write(_a(msg) + "\n")


def die(msg):
    sys.stderr.write("%s: error: %s\n" % (PREFIX, _a(msg)))
    sys.exit(1)


def ext_of(name):
    """对齐 .NET Path.GetExtension 的口径（'.' 打头的名字与目录条目都算没有扩展名）"""
    base = name.rsplit("/", 1)[-1]
    if "." not in base[1:]:
        return ""
    return "." + base.rsplit(".", 1)[-1]


def copy_entry(zsrc, zout, info):
    """按参考/输入里的顺序写一条：目录条目只建条目不拷内容，其余逐字节拷贝"""
    name = info.filename
    zi = zipfile.ZipInfo(name, date_time=info.date_time)
    if name.endswith("/"):
        zi.compress_type = zipfile.ZIP_STORED
        zi.external_attr = (0o40755 << 16) | 0x10          # 目录位
        zout.writestr(zi, b"")
        return
    zi.compress_type = zipfile.ZIP_DEFLATED                 # 与 ps1 的 CompressionLevel.Optimal 对应
    zi.external_attr = info.external_attr
    with zsrc.open(info) as fin, zout.open(zi, "w") as fout:
        shutil.copyfileobj(fin, fout, 1 << 16)


def main():
    if len(sys.argv) != 4:
        die("expected 3 arguments (patched jar, reference jar, out jar), got %d" % (len(sys.argv) - 1))
    patched_arg, ref_arg, out_arg = sys.argv[1], sys.argv[2], sys.argv[3]

    if not os.path.isfile(patched_arg):
        die("patched jar not found: " + patched_arg)
    if not os.path.isfile(ref_arg):
        die("reference jar not found: " + ref_arg)

    full_src = os.path.realpath(patched_arg)
    full_out = os.path.abspath(out_arg)
    # 不许把输出写到输入上（用 realpath 比一比，软链接绕过去的也拦住）
    if os.path.realpath(full_out) == full_src:
        die("refusing to write the output over the input jar: " + full_out)

    try:
        zsrc = zipfile.ZipFile(full_src, "r")
    except (OSError, zipfile.BadZipFile) as e:
        die("cannot open patched jar: " + str(e))
    try:
        zref = zipfile.ZipFile(os.path.realpath(ref_arg), "r")
    except (OSError, zipfile.BadZipFile) as e:
        zsrc.close()
        die("cannot open reference jar: " + str(e))

    try:
        run(zsrc, zref, full_src, ref_arg, full_out)
    except (OSError, zipfile.BadZipFile) as e:
        die("zip i/o failure: %s: %s" % (type(e).__name__, e))
    finally:
        zsrc.close()
        zref.close()
    return 0


def run(zsrc, zref, full_src, ref_arg, full_out):
    # 输入 jar 已有的条目名（含目录条目）
    have = set(i.filename for i in zsrc.infolist())

    # 挑出要从参考 zip 补的条目：按参考 zip 自己的顺序
    added = []
    for info in zref.infolist():
        n = info.filename
        if n in have:
            continue                                    # 防线 1：已存在就跳过（绝不覆盖）
        if n.startswith(".cache/"):
            continue                                    # 防线 3：ForgeGradle 缓存垃圾
        if n.endswith(".class"):
            die("refusing to add class entry: " + n)     # 防线 2：绝不带进 class
        if n in LANG_ENTRIES:
            # 防线 4：补丁 lang 只应该来自补丁 jar；这里缺了说明 build 产物本身不对
            die("refusing to touch patched lang entry (missing from the built jar): " + n)
        added.append(info)

    note("%s: input   = %s  (%d entries)" % (PREFIX, full_src, len(have)))
    note("%s: ref     = %s" % (PREFIX, ref_arg))
    note("%s: to add  = %d entries" % (PREFIX, len(added)))
    hist = {}
    for info in added:
        e = ext_of(info.filename)
        hist[e] = hist.get(e, 0) + 1
    for e, c in sorted(hist.items(), key=lambda kv: (-kv[1], kv[0])):
        note("   ext '%s' = %d" % (e, c))

    out_dir = os.path.dirname(full_out)
    if out_dir and not os.path.isdir(out_dir):
        os.makedirs(out_dir, exist_ok=True)
    if os.path.exists(full_out):
        os.remove(full_out)

    with zipfile.ZipFile(full_out, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as zout:
        # 1) 忠实拷贝输入 jar 的每一条（顺序不变、内容逐字节）
        for info in zsrc.infolist():
            copy_entry(zsrc, zout, info)
        # 2) 追加参考 zip 里缺的那些 datagen 资源（从参考 zip 里读，别读成输入那份）
        for info in added:
            copy_entry(zref, zout, info)

    # 报一下规模，方便调用方（scripts/patches.gradle 的 jarAssetsProbe）对账
    total = assets = classes = 0
    with zipfile.ZipFile(full_out, "r") as zchk:
        for info in zchk.infolist():
            total += 1
            if info.filename.startswith("assets/"):
                assets += 1
            if info.filename.endswith(".class"):
                classes += 1
    note("%s: wrote %s  (%d entries, assets=%d, class=%d)" % (PREFIX, full_out, total, assets, classes))


try:
    sys.exit(main())
except SystemExit:
    raise
except Exception:
    # 兜底：连 traceback 也一并 ASCII 化，别让非 ASCII 的路径把 Gradle 的捕获搅乱
    sys.stderr.write(_a(traceback.format_exc()))
    sys.stderr.write("%s: error: unexpected failure (see sanitized traceback above)\n" % PREFIX)
    sys.exit(1)
PYEOF
