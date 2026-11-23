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

$version = (& node scripts/validate-release-version.mjs "$tagName") -join "`n"
if ($LASTEXITCODE -ne 0) {
  throw 'Release version preflight failed before signing.'
}
$version = $version.Trim()
$selectionJson = (& node scripts/select-windows-release-artifacts.mjs "$signedDir" "$version") -join "`n"
if ($LASTEXITCODE -ne 0) {
  throw 'Windows installer selection failed before signing.'
}
$selection = $selectionJson | ConvertFrom-Json
$msi = Get-Item -LiteralPath $selection.msi
$nsis = Get-Item -LiteralPath $selection.nsis

function New-UpdaterSignature {
  param([Parameter(Mandatory = $true)][System.IO.FileInfo]$File)

  $signature = (& npx tauri signer sign --app-version "$version" "$($File.FullName)") -join "`n"
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to generate updater signature for $($File.Name)."
  }

  $signature = $signature.Trim()
  if ([string]::IsNullOrWhiteSpace($signature)) {
    throw "Updater signature for $($File.Name) is empty."
  }

  $signaturePath = "$($File.FullName).sig"
  Set-Content -LiteralPath $signaturePath -Value $signature -NoNewline -Encoding ascii
  return [System.IO.FileInfo]$signaturePath
}

$msiSig = New-UpdaterSignature -File $msi
$nsisSig = New-UpdaterSignature -File $nsis

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

$manifestUrl = "https://github.com/$releaseRepo/releases/download/$tagName/latest.json"
try {
  $existingManifest = (Invoke-WebRequest -Uri $manifestUrl -UseBasicParsing).Content | ConvertFrom-Json
} catch {
  $existingManifest = $null
}

$platforms = [ordered]@{}
if ($existingManifest -and $existingManifest.platforms) {
  foreach ($property in $existingManifest.platforms.PSObject.Properties) {
    if (-not $property.Name.StartsWith('windows-x86_64')) {
      $platforms[$property.Name] = $property.Value
    }
  }
}

$baseUrl = "https://github.com/$releaseRepo/releases/download/$tagName"
$msiEntry = [ordered]@{
  signature = (Get-Content -LiteralPath $msiSig.FullName -Raw).Trim()
  url = "$baseUrl/$($msi.Name)"
}
$nsisEntry = [ordered]@{
  signature = (Get-Content -LiteralPath $nsisSig.FullName -Raw).Trim()
  url = "$baseUrl/$($nsis.Name)"
}

$platforms['windows-x86_64'] = $msiEntry
$platforms['windows-x86_64-msi'] = $msiEntry
$platforms['windows-x86_64-nsis'] = $nsisEntry

$manifest = [ordered]@{
  version = $version
  notes = if ($existingManifest -and $existingManifest.notes) { $existingManifest.notes } else { 'See the assets below to download and install.' }
  pub_date = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
  platforms = $platforms
}

$latestJsonPath = Join-Path (Get-Location) 'latest.json'
$manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $latestJsonPath -Encoding utf8

gh release upload $tagName --repo $releaseRepo --clobber `
  $msi.FullName `
  $msiSig.FullName `
  $nsis.FullName `
  $nsisSig.FullName `
  $windowsChecksumPath `
  $latestJsonPath

if ($LASTEXITCODE -ne 0) {
  throw "Failed to upload signed Windows assets for $tagName."
}
