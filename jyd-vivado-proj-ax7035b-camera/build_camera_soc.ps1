param([int]$Jobs = 6, [switch]$SkipSoftware, [switch]$SkipPack)

$ErrorActionPreference = 'Stop'
$vivado = 'E:\VIVADO\Vivado\2024.2\bin\vivado.bat'
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $projectDir
$inputDir = Join-Path $projectDir 'build\input'
$logDir = Join-Path $projectDir 'logs'
$rtBuild = Join-Path $repoRoot 'jyd-tests\rtthread-nano\build'
$iromCoe = Join-Path $rtBuild 'rtthread-nano-riscv32-jyd.text.coe'
$dramCoe = Join-Path $rtBuild 'rtthread-nano-riscv32-jyd.data.coe'
$elf = Join-Path $rtBuild 'rtthread-nano-riscv32-jyd.elf'

if (-not (Test-Path -LiteralPath $vivado)) { throw "Vivado 2024.2 not found at $vivado" }
New-Item -ItemType Directory -Force -Path $inputDir, $logDir | Out-Null

if ($repoRoot -notmatch '^([A-Za-z]):\\(.*)$') { throw "Cannot translate repository path to WSL: $repoRoot" }
$wslDrive = $Matches[1].ToLowerInvariant()
$wslTail = $Matches[2].Replace('\', '/')
$wslRepoRoot = "/mnt/$wslDrive/$wslTail"
$jydEnv = "export JYD_HOME='$wslRepoRoot' JYD_AM_HOME='$wslRepoRoot/abstract-machine' JYD_NPC_HOME='$wslRepoRoot/npc' JYD_DATA_ROOT=/mnt/e/jyd_data CCACHE_DIR=/mnt/e/jyd_data/cache/ccache CCACHE_TEMPDIR=/mnt/e/jyd_data/tmp/ccache TMPDIR=/mnt/e/jyd_data/tmp LLVM_CONFIG=llvm-config-18"

if (-not $SkipSoftware) {
    $softwareCommand = "cd '$wslRepoRoot' && $jydEnv && make -C jyd-tests/rtthread-nano clean ARCH=riscv32-jyd CAMERA_DEMO=1 && make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd CAMERA_DEMO=1 image"
    & wsl -d Ubuntu -- bash -lc $softwareCommand
    if ($LASTEXITCODE -ne 0) { throw "RT-Thread camera image build failed: $LASTEXITCODE" }
}
if (-not (Test-Path -LiteralPath $elf)) { throw "RT-Thread ELF not found: $elf" }

& wsl -d Ubuntu -- bash -lc "cd '$wslRepoRoot' && riscv64-linux-gnu-size 'jyd-tests/rtthread-nano/build/rtthread-nano-riscv32-jyd.elf'"
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect RT-Thread ELF size' }

if (-not $SkipPack) {
    & wsl -d Ubuntu -- bash -lc "cd '$wslRepoRoot' && $jydEnv && make -C npc pack-fpga"
    if ($LASTEXITCODE -ne 0) { throw "npc pack-fpga failed: $LASTEXITCODE" }
}

& python (Join-Path $projectDir 'scripts\coe_to_mem.py') $iromCoe (Join-Path $inputDir 'irom.mem') 4096
if ($LASTEXITCODE -ne 0) { throw 'IROM COE conversion failed' }
& python (Join-Path $projectDir 'scripts\coe_to_mem.py') $dramCoe (Join-Path $inputDir 'dram.mem') 12288
if ($LASTEXITCODE -ne 0) { throw 'DRAM COE conversion failed' }

$log = Join-Path $logDir 'camera_soc.log'
$journal = Join-Path $logDir 'camera_soc.jou'
$tcl = Join-Path $projectDir 'scripts\build_camera_soc.tcl'
$vivadoArgs = @('-mode', 'batch', '-log', $log, '-journal', $journal,
                '-source', $tcl, '-tclargs', $inputDir, "$Jobs")
$process = Start-Process -FilePath $vivado -ArgumentList $vivadoArgs -Wait -PassThru -NoNewWindow
if ($process.ExitCode -ne 0) { throw "Camera SoC Vivado build failed: $($process.ExitCode); see $log" }

Write-Host "Bitstream: $(Join-Path $projectDir 'output\ax7035b_jyd_ov5640_soc.bit')"
