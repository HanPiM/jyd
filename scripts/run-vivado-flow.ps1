param(
  [string]$ProjectRoot = ".",
  [Parameter(Mandatory = $true)][string]$PackZip,
  [Parameter(Mandatory = $true)][string]$CoeDir,
  [Parameter(Mandatory = $true)][string]$Sample,
  [ValidateSet("synth", "impl", "bitstream")][string]$Mode = "bitstream",
  [string]$Vivado = $env:VIVADO_BIN,
  [string]$ResultDir = "",
  [switch]$SkipProjectUpdate
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
$FlowTcl = Resolve-RequiredPath (Join-Path $ScriptDir "vivado_flow.tcl")
$PreHook = Resolve-RequiredPath (Join-Path $ScriptDir "vivado_pre_hook.tcl")
$VivadoExe = Find-Vivado

if (-not $ResultDir) {
  $ResultDir = Join-Path (Join-Path $ProjectRoot "result") $Sample
}

if (-not (Test-Path -LiteralPath $ProjectFile)) {
  throw "required path not found: $ProjectFile"
}

New-Item -ItemType Directory -Path $ResultDir -Force | Out-Null

$ResultDir = (Resolve-Path -LiteralPath $ResultDir).Path

if (-not $SkipProjectUpdate) {
  & (Join-Path $ScriptDir "run-vivado-project-update.ps1") `
    -ProjectRoot $ProjectRoot `
    -PackZip $PackZip `
    -CoeDir $CoeDir `
    -Sample $Sample `
    -Vivado $VivadoExe
  if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
  }
}

Write-Host "Project root: $ProjectRoot"
Write-Host "Mode: $Mode"
Write-Host "Sample: $Sample"
Write-Host "Vivado: $VivadoExe"
Write-Host "Result dir: $ResultDir"

& $VivadoExe -mode batch -source $FlowTcl -tclargs $Mode $ProjectFile $PreHook $ResultDir
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

foreach ($logFile in @("vivado.log", "vivado.jou")) {
  $path = Join-Path $ProjectRoot $logFile
  if (Test-Path -LiteralPath $path) {
    Copy-Item -LiteralPath $path -Destination $ResultDir -Force
  }
}
