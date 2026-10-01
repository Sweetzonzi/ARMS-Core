<#
.SYNOPSIS
    校验 docs/ 与 AGENTS.md 里的引用坐标是否仍可定位。

.DESCRIPTION
    坐标约定见 AGENTS.md 的「文档编辑规范」，两种形式各有各自的检查：

      活引用     路径#符号         符号必须能在目标文件里被检索到
      历史快照   路径:行 @ 提交    可用 git show <提交>:<路径> 复现

    另有两类会被列出：
      · 带文件名却既无 `#符号` 也无 `@ 提交` 的裸行号引用 —— 属于无基线的活引用，应改写；
      · 无法就地校验的外部坐标（Minecraft 类等）—— 有 sources jar 时按 jar 校验。

    用法（仓库根目录）：
        pwsh -NoProfile -File tools/check_doc_refs.ps1

    存在无法定位的引用时退出码为 1，可用于提交前自检。仅读文件，不修改任何内容。
#>
[CmdletBinding()]
param(
    # 额外要扫描的 Markdown 文件（默认扫描 AGENTS.md 与 docs/ 下的全部 .md）
    [string[]]$Docs
)

$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
if (-not (Test-Path 'AGENTS.md') -or -not (Test-Path 'docs')) {
    throw '请在仓库根目录运行（需要能看到 AGENTS.md 与 docs/）。'
}

# ---- 文件名 → 候选路径索引（含三个同级源码仓库）----
$index = @{}
foreach ($r in @('src', '..\Spark-Core\src', '..\Machine-Max\src', '..\Libbulletjme\src')) {
    if (-not (Test-Path $r)) { continue }
    Get-ChildItem -Path $r -Recurse -File -Include *.java, *.kt, *.cpp, *.h -ErrorAction SilentlyContinue |
        ForEach-Object {
            if (-not $index.ContainsKey($_.Name)) { $index[$_.Name] = @() }
            $index[$_.Name] += $_.FullName
        }
}

# ---- Minecraft 源码（用于校验 net.minecraft.* 一类外部坐标；缺则记为跳过）----
# 注意 `-Filter '*sources.jar'` 也会匹配 `...minecraft-resources.jar`（它以 "sources.jar" 结尾），
# 那个 jar 里没有 .java，因此必须显式排除。
$sourcesJar = Get-ChildItem -Path 'build\moddev\artifacts' -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*-sources.jar' -and $_.Name -notlike '*client-extra*' } |
    Select-Object -First 1
if ($sourcesJar) { Add-Type -AssemblyName System.IO.Compression.FileSystem }

# 返回 $true / $false；无法判定（无 jar、无该类源码）返回 $null
function Test-ExternalSymbol {
    param([string]$DottedClass, [string]$Symbol)
    if (-not $sourcesJar) { return $null }
    $rel = ($DottedClass -replace '\.', '/') + '.java'
    $zip = [System.IO.Compression.ZipFile]::OpenRead($sourcesJar.FullName)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $rel } | Select-Object -First 1
        if (-not $entry) { return $null }
        $sr = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $sr.ReadToEnd() } finally { $sr.Close() }
        return [regex]::IsMatch($text, '(?<![\w$])' + [regex]::Escape($Symbol) + '(?![\w])')
    } finally { $zip.Dispose() }
}

# ---- 扫描 ----
if (-not $Docs) {
    $Docs = @('AGENTS.md') + (Get-ChildItem -Path 'docs' -Filter *.md -Recurse | Select-Object -ExpandProperty FullName)
}

$okCount = 0
$unresolved = New-Object System.Collections.Generic.List[string]
$skipped = New-Object System.Collections.Generic.List[string]
$baselineLess = New-Object System.Collections.Generic.List[string]

$rxPathSym = [regex]'`([\w/\.\-]+\.(?:java|kt|cpp|h))#(\w+)`|`#(\w+)`'
$rxDotted = [regex]'`([a-z][\w]*(?:\.[A-Za-z_][\w]*)+)#(\w+)`'
$rxSha = [regex]'([\w/\.\-]+\.(?:java|kt|cpp)):(\d+(?:-\d+)?) @ ([0-9a-f]{7,})'
$rxRawLine = [regex]'`([\w/\.\-]+\.(?:java|kt|cpp)):(\d+(?:-\d+)?)`(?!\s*@)'

foreach ($doc in $Docs) {
    $name = if ($doc -eq 'AGENTS.md') { 'AGENTS.md' } else { Split-Path $doc -Leaf }
    $lines = Get-Content -LiteralPath $doc
    $lastBase = $null

    for ($i = 1; $i -le $lines.Count; $i++) {
        $line = $lines[$i - 1]

        # 活引用（#符号）
        if ($line -match '#') {
            foreach ($m in $rxPathSym.Matches($line)) {
                if ($m.Groups[1].Success) {
                    $base = Split-Path $m.Groups[1].Value -Leaf; $sym = $m.Groups[2].Value; $lastBase = $base
                } else {
                    $base = $lastBase; $sym = $m.Groups[3].Value
                }
                if (-not $base) { $unresolved.Add("${name}:$i  #${sym} —— 找不到承接的路径"); continue }
                $cands = $index[$base]
                if (-not $cands) { $unresolved.Add("${name}:$i  ${base}#${sym} —— 找不到该文件"); continue }
                $hit = $false
                foreach ($c in $cands) {
                    if ([regex]::IsMatch((Get-Content -Raw -LiteralPath $c), '(?<![\w$])' + [regex]::Escape($sym) + '(?![\w])')) { $hit = $true; break }
                }
                if ($hit) { $okCount++ } else { $unresolved.Add("${name}:$i  ${base}#${sym} —— 该符号不在文件里") }
            }
            foreach ($m in $rxDotted.Matches($line)) {
                # 带源码扩展名的坐标已由上面的「路径#符号」分支处理，这里只接外部类名
                if ($m.Groups[1].Value -match '\.(java|kt|cpp|h)$') { continue }
                $r = Test-ExternalSymbol -DottedClass $m.Groups[1].Value -Symbol $m.Groups[2].Value
                if ($r -eq $true) { $okCount++ }
                elseif ($r -eq $false) { $unresolved.Add("${name}:$i  $($m.Groups[1].Value)#$($m.Groups[2].Value) —— 该符号不在源码里") }
                else { $skipped.Add("${name}:$i  $($m.Groups[1].Value)#$($m.Groups[2].Value) —— 外部类，无 sources jar 可查") }
            }
        }

        # 历史快照（@ 提交）
        foreach ($m in $rxSha.Matches($line)) {
            $p = $m.Groups[1].Value; $rev = $m.Groups[3].Value
            git cat-file -e "${rev}:$p" 2>$null
            if ($LASTEXITCODE -eq 0) { $okCount++ }
            else { $unresolved.Add("${name}:$i  ${rev}:$p —— 提交或路径不存在") }
        }

        # 无基线的裸行号引用
        foreach ($m in $rxRawLine.Matches($line)) {
            $sha = $rxSha.Matches($line) | Where-Object { $_.Groups[1].Value -eq $m.Groups[1].Value }
            if (-not $sha) { $baselineLess.Add("${name}:$i  $($m.Groups[1].Value):$($m.Groups[2].Value)") }
        }
    }
}

# ---- 汇总 ----
"可定位: $okCount"
if ($unresolved.Count) {
    ""
    "无法定位 ($($unresolved.Count)):"
    $unresolved | ForEach-Object { "  $_" }
}
if ($baselineLess.Count) {
    ""
    "活引用仍写行号、没有 `@ 提交` 基线 ($($baselineLess.Count)) —— 应改为「路径#符号」:"
    $baselineLess | ForEach-Object { "  $_" }
}
if ($skipped.Count) {
    ""
    "未能校验 ($($skipped.Count)):"
    $skipped | ForEach-Object { "  $_" }
}

if ($unresolved.Count -or $baselineLess.Count) { exit 1 }
"全部引用可定位。"
