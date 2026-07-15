[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$toolsDirectory = Join-Path $root ".tools"
$cacheDirectory = Join-Path $toolsDirectory "cache"
$vendorDirectory = Join-Path $root ".vendor\llama.cpp"
$vendorPatch = Join-Path $root "patches\llama-vulkan-host-ninja.patch"

function Assert-Success {
    param([Parameter(Mandatory)][string] $Operation)

    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE."
    }
}

function Assert-ChildPath {
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][string] $Parent
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($fullParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify path outside $fullParent`: $fullPath"
    }
}

function Ensure-ArchiveTool {
    param(
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][string] $Url,
        [Parameter(Mandatory)][string] $ArchiveName,
        [Parameter(Mandatory)][string] $Sha256,
        [Parameter(Mandatory)][string] $ArchiveRoot,
        [Parameter(Mandatory)][string] $TargetName,
        [Parameter(Mandatory)][string[]] $Markers
    )

    $target = Join-Path $toolsDirectory $TargetName
    $targetMarkers = @($Markers | ForEach-Object { Join-Path $target $_ })
    $missingMarkers = @($targetMarkers | Where-Object { -not (Test-Path -LiteralPath $_) })
    if ($missingMarkers.Count -eq 0) {
        Write-Host "$Name is already available."
        return
    }

    if (Test-Path -LiteralPath $target) {
        throw "$Name has an incomplete directory at $target. Remove it and run this script again."
    }

    $archive = Join-Path $cacheDirectory $ArchiveName
    if (Test-Path -LiteralPath $archive) {
        $existingHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        if ($existingHash -ne $Sha256) {
            Assert-ChildPath -Path $archive -Parent $toolsDirectory
            Remove-Item -LiteralPath $archive
        }
    }

    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host "Downloading $Name..."
        Invoke-WebRequest -Uri $Url -OutFile $archive
    }

    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    if ($actualHash -ne $Sha256) {
        throw "$Name archive checksum mismatch. Expected $Sha256, received $actualHash."
    }

    $extractDirectory = Join-Path $toolsDirectory ("extract-" + [guid]::NewGuid().ToString("N"))
    Assert-ChildPath -Path $extractDirectory -Parent $toolsDirectory
    try {
        Expand-Archive -LiteralPath $archive -DestinationPath $extractDirectory
        $extractedRoot = Join-Path $extractDirectory $ArchiveRoot
        if (-not (Test-Path -LiteralPath $extractedRoot)) {
            throw "$Name archive did not contain the expected $ArchiveRoot directory."
        }
        Move-Item -LiteralPath $extractedRoot -Destination $target
    } finally {
        if (Test-Path -LiteralPath $extractDirectory) {
            Assert-ChildPath -Path $extractDirectory -Parent $toolsDirectory
            Remove-Item -LiteralPath $extractDirectory -Recurse -Force
        }
    }

    $missingMarkers = @($targetMarkers | Where-Object { -not (Test-Path -LiteralPath $_) })
    if ($missingMarkers.Count -gt 0) {
        throw "$Name was extracted, but required files are missing: $($missingMarkers -join ', ')"
    }
    Write-Host "$Name is ready."
}

New-Item -ItemType Directory -Force -Path $toolsDirectory, $cacheDirectory | Out-Null

Write-Host "Initializing the pinned PrismML llama.cpp submodule..."
& git -C $root submodule update --init --recursive
Assert-Success -Operation "Submodule initialization"

if (-not (Test-Path -LiteralPath $vendorPatch)) {
    throw "Required vendor patch is missing: $vendorPatch"
}

& git -C $vendorDirectory apply --check --whitespace=nowarn $vendorPatch 2>$null
if ($LASTEXITCODE -eq 0) {
    & git -C $vendorDirectory apply --whitespace=nowarn $vendorPatch
    Assert-Success -Operation "Applying the Vulkan host Ninja patch"
    Write-Host "Applied the Vulkan host Ninja patch."
} else {
    & git -C $vendorDirectory apply --reverse --check --whitespace=nowarn $vendorPatch 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "The pinned llama.cpp source does not accept patches/llama-vulkan-host-ninja.patch."
    }
    Write-Host "The Vulkan host Ninja patch is already applied."
}

Ensure-ArchiveTool `
    -Name "LLVM-MinGW 20260616" `
    -Url "https://github.com/mstorsjo/llvm-mingw/releases/download/20260616/llvm-mingw-20260616-ucrt-x86_64.zip" `
    -ArchiveName "llvm-mingw-20260616-ucrt-x86_64.zip" `
    -Sha256 "B9B68A4D276E16FA25802AABA458E4638F64B3884C290AACCDC2D87083B6CA35" `
    -ArchiveRoot "llvm-mingw-20260616-ucrt-x86_64" `
    -TargetName "llvm-mingw" `
    -Markers @(
        "bin\x86_64-w64-mingw32-clang.exe"
        "bin\x86_64-w64-mingw32-clang++.exe"
    )

Ensure-ArchiveTool `
    -Name "Vulkan-Hpp 1.3.275" `
    -Url "https://github.com/KhronosGroup/Vulkan-Hpp/archive/refs/tags/v1.3.275.zip" `
    -ArchiveName "Vulkan-Hpp-1.3.275.zip" `
    -Sha256 "BCF8C207FBFD6694378462E51094F119B58E026A3559A23B4202789DCA152B55" `
    -ArchiveRoot "Vulkan-Hpp-1.3.275" `
    -TargetName "Vulkan-Hpp" `
    -Markers @("vulkan\vulkan.hpp")

Write-Host 'Native source dependencies are ready. Build with .\gradlew.bat :app:assembleDebug "-Pbonsai.native.buildFromSource=true"'
