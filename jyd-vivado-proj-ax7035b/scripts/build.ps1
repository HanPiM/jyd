param(
    [Parameter(Mandatory = $true)][string]$ImageName,
    [Parameter(Mandatory = $true)][string]$IromCoe,
    [Parameter(Mandatory = $true)][string]$DramCoe,
    [int]$Jobs = 6,
    [switch]$SkipPack
)

$ErrorActionPreference = 'Stop'
$vivado = 'E:\VIVADO\Vivado\2024.2\bin\vivado.bat'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent $projectDir
$inputDir = Join-Path $projectDir "build\$ImageName\input"
$logDir = Join-Path $projectDir 'logs'

if (-not (Test-Path -LiteralPath $vivado)) { throw "Vivado 2024.2 not found at $vivado" }
if ($ImageName -notmatch '^[A-Za-z0-9_.-]+$') { throw "Unsafe image name: $ImageName" }
$irom = (Resolve-Path -LiteralPath $IromCoe).Path
$dram = (Resolve-Path -LiteralPath $DramCoe).Path

if (-not $SkipPack) {
    $wslRepoRoot = (& wsl -d Ubuntu -- wslpath -a -u $repoRoot).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $wslRepoRoot) { throw "Cannot resolve repository path in WSL" }
    & wsl -d Ubuntu -- bash -lc "cd '$wslRepoRoot' && export JYD_DATA_ROOT=/mnt/e/jyd_data CCACHE_DIR=/mnt/e/jyd_data/cache/ccache CCACHE_TEMPDIR=/mnt/e/jyd_data/tmp/ccache TMPDIR=/mnt/e/jyd_data/tmp LLVM_CONFIG=llvm-config-18 && make -C npc pack-fpga"
    if ($LASTEXITCODE -ne 0) { throw "npc pack-fpga failed: $LASTEXITCODE" }
}

New-Item -ItemType Directory -Force -Path $inputDir, $logDir | Out-Null
& python (Join-Path $scriptDir 'coe_to_mem.py') $irom (Join-Path $inputDir 'irom.mem') 8192
if ($LASTEXITCODE -ne 0) { throw "IROM COE conversion failed" }
& python (Join-Path $scriptDir 'coe_to_mem.py') $dram (Join-Path $inputDir 'dram.mem') 16384
if ($LASTEXITCODE -ne 0) { throw "DRAM COE conversion failed" }

$tcl = Join-Path $scriptDir 'create_project.tcl'
$log = Join-Path $logDir "$ImageName.log"
$journal = Join-Path $logDir "$ImageName.jou"
$vivadoArgs = @(
    '-mode', 'batch',
    '-log', $log,
    '-journal', $journal,
    '-source', $tcl,
    '-tclargs', $ImageName, $inputDir, "$Jobs"
)
$process = Start-Process -FilePath $vivado -ArgumentList $vivadoArgs -Wait -PassThru -NoNewWindow
if ($process.ExitCode -ne 0) { throw "AX7035B Vivado build failed: $($process.ExitCode); see $log" }
