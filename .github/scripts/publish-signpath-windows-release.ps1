$ErrorActionPreference = 'Stop'

$tagName = $env:TAG_NAME
$releaseRepo = $env:RELEASE_REPO
$signedDir = Join-Path (Get-Location) 'signpath-signed'

if ([string]::IsNullOrWhiteSpace($tagName)) {
  throw 'TAG_NAME is required.'
}
if ([string]::IsNullOrWhiteSpace($releaseRepo)) {
  throw 'RELEASE_REPO is required.'
}
if (-not (Test-Path -LiteralPath $signedDir)) {
  throw "Signed artifact directory was not found: $signedDir"
}

$signedFiles = Get-ChildItem -Path $signedDir -Recurse -File | Where-Object { $_.Extension -in '.exe', '.msi' }
$msi = $signedFiles | Where-Object { $_.Name -like '*_x64_en-US.msi' } | Select-Object -First 1
$nsis = $signedFiles | Where-Object { $_.Name -like '*_x64-setup.exe' } | Select-Object -First 1

if (-not $msi) {
  $msi = $signedFiles | Where-Object { $_.Extension -eq '.msi' } | Select-Object -First 1
}
if (-not $nsis) {
  $nsis = $signedFiles | Where-Object { $_.Extension -eq '.exe' } | Select-Object -First 1
}
if (-not $msi -or -not $nsis) {
  throw 'Both signed MSI and NSIS installers are required.'
}

$windowsChecksumPath = Join-Path (Get-Location) 'SHA256SUMS-windows-x86_64.txt'
Remove-Item -LiteralPath $windowsChecksumPath -ErrorAction SilentlyContinue
foreach ($file in @($msi, $nsis)) {
  $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  Add-Content -LiteralPath $windowsChecksumPath -Value "$hash  $($file.Name)" -Encoding ascii
}

$releaseExists = $true
gh release view $tagName --repo $releaseRepo *> $null
if ($LASTEXITCODE -ne 0) {
  $releaseExists = $false
}
if (-not $releaseExists) {
  gh release create $tagName --repo $releaseRepo --title "OpenTypeless $tagName" --notes 'See the assets below to download and install.' --prerelease
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to create release $tagName in $releaseRepo."
  }
}

gh release upload $tagName --repo $releaseRepo --clobber `
  $msi.FullName `
  $nsis.FullName `
  $windowsChecksumPath

if ($LASTEXITCODE -ne 0) {
  throw "Failed to upload signed Windows assets for $tagName."
}
