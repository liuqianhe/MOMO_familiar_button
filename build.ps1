#Requires -Version 5.1
<#
    MoMoRev - build without Gradle
    aapt2 -> javac -> d8 -> repack -> zipalign -> apksigner
#>
param(
    [string]$JdkHome = "C:\AndroidDev\jdk-17",
    [string]$SdkRoot = "C:\AndroidDev\sdk",
    [string]$OutDir = (Join-Path $PSScriptRoot "build"),
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$AppDir = Join-Path $PSScriptRoot "app"
$StubDir = Join-Path $PSScriptRoot "stubs"

# 版本号只在 AndroidManifest.xml 里维护，这里读出来传给 aapt2，避免两处不一致
$manifestRaw = Get-Content "$AppDir\AndroidManifest.xml" -Raw
$versionCode = if ($manifestRaw -match 'android:versionCode="(\d+)"') { $Matches[1] } else { "1" }
$versionName = if ($manifestRaw -match 'android:versionName="([^"]+)"') { $Matches[1] } else { "1.0" }

if (-not (Test-Path "$JdkHome\bin\javac.exe")) {
    $cand = Get-ChildItem "C:\AndroidDev" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk*" } | Select-Object -First 1
    if ($cand) { $JdkHome = $cand.FullName }
}
if (-not (Test-Path "$JdkHome\bin\javac.exe")) { throw "JDK not found: $JdkHome" }

$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;" + $env:Path

$BT = Join-Path $SdkRoot "build-tools\34.0.0"
$AJAR = Join-Path $SdkRoot "platforms\android-34\android.jar"
foreach ($p in @("$BT\aapt2.exe", "$BT\d8.bat", "$BT\zipalign.exe", "$BT\apksigner.bat", "$BT\lib\d8.jar", $AJAR)) {
    if (-not (Test-Path $p)) { throw "missing tool: $p" }
}

Remove-Item $OutDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$OutDir\gen", "$OutDir\obj", "$OutDir\dex" | Out-Null

Write-Host "==> 1/7 aapt2 compile" -ForegroundColor Cyan
& "$BT\aapt2.exe" compile --dir "$AppDir\res" -o "$OutDir\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> 2/7 aapt2 link" -ForegroundColor Cyan
& "$BT\aapt2.exe" link -o "$OutDir\base.apk" -I $AJAR `
    --manifest "$AppDir\AndroidManifest.xml" `
    -R "$OutDir\res.zip" --auto-add-overlay `
    -A "$AppDir\assets" `
    --java "$OutDir\gen" `
    --min-sdk-version 24 --target-sdk-version 34 `
    --version-code $versionCode --version-name $versionName
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> 3/7 javac" -ForegroundColor Cyan
$sources = @(Get-ChildItem "$AppDir\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem $StubDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem "$OutDir\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$JdkHome\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -classpath $AJAR -d "$OutDir\obj" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "==> 4/7 d8 (xposed api stubs excluded)" -ForegroundColor Cyan
$classes = @(Get-ChildItem "$OutDir\obj" -Recurse -Filter *.class |
    Where-Object { $_.FullName -notmatch '\\de\\robv\\android\\xposed\\' } |
    ForEach-Object { $_.FullName })
Write-Host ("    classes: " + $classes.Count)
& "$BT\d8.bat" --lib $AJAR --min-api 24 --output "$OutDir\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "==> 5/7 pack classes.dex" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OutDir\base.apk", "Update")
$dex = "$OutDir\dex\classes.dex"
if (-not (Test-Path $dex)) { $zip.Dispose(); throw "no classes.dex generated" }
$existing = $zip.Entries | Where-Object { $_.FullName -eq "classes.dex" }
if ($existing) { $existing.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex, "classes.dex") | Out-Null
$zip.Dispose()

Write-Host "==> 6/7 zipalign" -ForegroundColor Cyan
& "$BT\zipalign.exe" -f 4 "$OutDir\base.apk" "$OutDir\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "==> 7/7 sign" -ForegroundColor Cyan
$ks = Join-Path $PSScriptRoot "debug.keystore"
if (-not (Test-Path $ks)) {
    $src = "C:\Users\Carrot\Documents\deepseek-harness\default-workspace\momotool\debug.keystore"
    if (Test-Path $src) { Copy-Item $src $ks } else {
        & "$JdkHome\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android `
            -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" `
            -keyalg RSA -keysize 2048 -validity 10000 | Out-Null
    }
}
& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$OutDir\MoMoRev.apk" "$OutDir\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$BT\apksigner.bat" verify --print-certs "$OutDir\MoMoRev.apk" | Select-Object -First 3

$apk = Get-Item "$OutDir\MoMoRev.apk"
Write-Host ""
Write-Host ("Build OK: {0} ({1:N0} bytes)" -f $apk.FullName, $apk.Length) -ForegroundColor Green

if ($Install) {
    & adb install -r $apk.FullName
}
