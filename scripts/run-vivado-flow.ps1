param(
  [string]$ProjectRoot = ".",
  [Parameter(Mandatory = $true)][string]$PackZip,
  [Parameter(Mandatory = $true)][string]$CoeDir,
  [Parameter(Mandatory = $true)][string]$Sample,
  [ValidateSet("synth", "impl", "bitstream")][string]$Mode = "bitstream",
  [string]$Vivado = $env:VIVADO_BIN,
  [string]$ResultDir = ""
)

$ErrorActionPreference = "Stop"

function Resolve-RequiredPath([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) {
    throw "required path not found: $Path"
  }
  return (Resolve-Path -LiteralPath $Path).Path
}

function Find-Vivado {
  if ($Vivado) {
    return (Resolve-RequiredPath $Vivado)
  }

  $cmd = Get-Command vivado.bat -ErrorAction SilentlyContinue
  if ($cmd) {
    return $cmd.Source
  }

  $cmd = Get-Command vivado -ErrorAction SilentlyContinue
  if ($cmd) {
    return $cmd.Source
  }

  throw "Vivado executable not found; pass -Vivado or set VIVADO_BIN"
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-RequiredPath $ProjectRoot
$ProjectFile = Join-Path $ProjectRoot "digital_twin.xpr"
$PackZip = Resolve-RequiredPath $PackZip
$CoeSampleDir = Resolve-RequiredPath (Join-Path $CoeDir $Sample)
$FlowTcl = Resolve-RequiredPath (Join-Path $ScriptDir "vivado_flow.tcl")
$PreHook = Resolve-RequiredPath (Join-Path $ScriptDir "vivado_pre_hook.tcl")
$VivadoExe = Find-Vivado

if (-not $ResultDir) {
  $ResultDir = Join-Path (Join-Path $ProjectRoot "result") $Sample
}

$PackDest = Join-Path $ProjectRoot "digital_twin.srcs/sources_1/imports/pack-fpga"
$CoeDest = Join-Path $ProjectRoot "digital_twin.srcs/sources_1/imports/ci-coe/$Sample"
$IromCoe = Resolve-RequiredPath (Join-Path $CoeSampleDir "irom.coe")
$DramCoe = Resolve-RequiredPath (Join-Path $CoeSampleDir "dram.coe")

if (-not (Test-Path -LiteralPath $ProjectFile)) {
  throw "required path not found: $ProjectFile"
}

Remove-Item -LiteralPath $PackDest -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $PackDest -Force | Out-Null
Expand-Archive -LiteralPath $PackZip -DestinationPath $PackDest -Force

Remove-Item -LiteralPath $CoeDest -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $CoeDest -Force | Out-Null
New-Item -ItemType Directory -Path $ResultDir -Force | Out-Null
Copy-Item -LiteralPath $IromCoe -Destination (Join-Path $CoeDest "irom.coe") -Force
Copy-Item -LiteralPath $DramCoe -Destination (Join-Path $CoeDest "dram.coe") -Force

$IromDest = Resolve-RequiredPath (Join-Path $CoeDest "irom.coe")
$DramDest = Resolve-RequiredPath (Join-Path $CoeDest "dram.coe")
$ResultDir = (Resolve-Path -LiteralPath $ResultDir).Path

Write-Host "Project root: $ProjectRoot"
Write-Host "Mode: $Mode"
Write-Host "Sample: $Sample"
Write-Host "Vivado: $VivadoExe"
Write-Host "Result dir: $ResultDir"

& $VivadoExe -mode batch -source $FlowTcl -tclargs $Mode $ProjectFile $PreHook $ResultDir $IromDest $DramDest
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

foreach ($logFile in @("vivado.log", "vivado.jou")) {
  $path = Join-Path $ProjectRoot $logFile
  if (Test-Path -LiteralPath $path) {
    Copy-Item -LiteralPath $path -Destination $ResultDir -Force
  }
}
