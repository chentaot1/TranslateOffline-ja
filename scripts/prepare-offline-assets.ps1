param(
    [Parameter(Mandatory=$true)][string]$VisionCache,
    [ValidateSet('Japanese')][string]$Language='Japanese',
    [string]$FumetoDir="",
    [string]$SfxModel=""
)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrEmpty($FumetoDir)) {
    $parentBundled = Join-Path (Split-Path -Parent $repoRoot) 'bundled-models'
    if (Test-Path -LiteralPath $parentBundled) {
        $FumetoDir = $parentBundled
    } elseif (Test-Path -LiteralPath $VisionCache) {
        $FumetoDir = $VisionCache
    } else {
        $FumetoDir = Join-Path $repoRoot 'work/bundled-models'
    }
}

# Auto-stage SFX model if in VisionCache or specified
if ([string]::IsNullOrEmpty($SfxModel) -or !(Test-Path -LiteralPath $SfxModel -ErrorAction SilentlyContinue)) {
    $altSfx = Join-Path $VisionCache 'sfx-filter-v0.3.onnx'
    if (Test-Path -LiteralPath $altSfx) { $SfxModel = $altSfx }
}

# Auto-download missing Fumeto int8 models from HuggingFace
$fumetoBaseUrl = "https://huggingface.co/fumetodev/fumetotranslate-manga-ja-translator/resolve/main/int8"
$fumetoFiles = @('encoder.int8.onnx', 'decoder.int8.onnx', 'enc-vocab.json', 'dec-vocab.json', 'meta.json')

if (-not (Test-Path -LiteralPath $FumetoDir)) {
    New-Item -ItemType Directory -Path $FumetoDir -Force | Out-Null
}

foreach ($f in $fumetoFiles) {
    $dest = Join-Path $FumetoDir $f
    if ((-not (Test-Path -LiteralPath $dest)) -or ((Get-Item -LiteralPath $dest).Length -eq 0)) {
        Write-Output "Downloading $f from HuggingFace to $dest..."
        $url = "$fumetoBaseUrl/$f"
        & curl.exe -L -C - --retry 5 --retry-delay 2 -o "$dest" "$url"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to download $f from HuggingFace (curl exit code $LASTEXITCODE)"
        }
    }
}

$textDetectionSource = Join-Path $VisionCache 'text_detection.onnx'
if (-not (Test-Path -LiteralPath $textDetectionSource)) {
    $textDetectionSource = Join-Path $VisionCache 'chinese_detection.onnx'
}

$assetRoot=Join-Path $repoRoot 'offline/src/main/assets'
if((Test-Path -LiteralPath (Join-Path $assetRoot 'models')) -or (Test-Path -LiteralPath (Join-Path $assetRoot 'model-manifest.json'))){
    throw 'Move legacy combined model assets out of offline/src/main/assets before preparing separate installers.'
}
$bundles=@{
    Japanese=@(
        @{source=(Join-Path $VisionCache 'manga_panel_detector.onnx');name='manga_panel_detector.onnx'},
        @{source=$textDetectionSource;name='text_detection.onnx'},
        @{source=(Join-Path $VisionCache 'manga_ocr_nar_small_distil_int8_u8s8.onnx');name='manga_ocr_nar.onnx'},
        @{source=(Join-Path $VisionCache 'nar_vocabulary_6817.json');name='nar_vocabulary_6817.json'},
        @{source=(Join-Path $VisionCache 'manga_ocr_ar_encoder.onnx');name='manga_ocr_ar_encoder.onnx'},
        @{source=(Join-Path $VisionCache 'manga_ocr_ar_decoder.onnx');name='manga_ocr_ar_decoder.onnx'},
        @{source=(Join-Path $FumetoDir 'encoder.int8.onnx');name='encoder.int8.onnx'},
        @{source=(Join-Path $FumetoDir 'decoder.int8.onnx');name='decoder.int8.onnx'},
        @{source=(Join-Path $FumetoDir 'enc-vocab.json');name='enc-vocab.json'},
        @{source=(Join-Path $FumetoDir 'dec-vocab.json');name='dec-vocab.json'},
        @{source=(Join-Path $FumetoDir 'meta.json');name='meta.json'},
        @{source=$SfxModel;name='sfx-filter-v0.3.onnx'}
    )
}
foreach($sourceLanguage in @($Language)){
    $bundleRoot=Join-Path $repoRoot ".local/bundles/$($sourceLanguage.ToLowerInvariant())"
    $modelRoot=Join-Path $bundleRoot 'models'
    New-Item -ItemType Directory -Path $modelRoot -Force|Out-Null
    $inputs=$bundles[$sourceLanguage]
    Get-ChildItem -LiteralPath $modelRoot -File | Where-Object { $_.Name -notin $inputs.name } | Remove-Item -Force
    $unexpected=@(Get-ChildItem -LiteralPath $modelRoot -File|Where-Object {$_.Name -notin $inputs.name})
    if($unexpected.Count){throw "Unexpected files in $modelRoot; refusing to package a mixed bundle."}
    $assets=@(foreach($inputModel in $inputs){
        $source=Get-Item -LiteralPath $inputModel.source
        if($source.Length -le 0){throw "Empty model: $($source.FullName)"}
        $destination=Join-Path $modelRoot $inputModel.name
        if ((-not (Test-Path -LiteralPath $destination)) -or ((Get-Item -LiteralPath $destination).Length -ne $source.Length)) {
            Copy-Item -LiteralPath $source.FullName -Destination $destination -Force
        }
        [ordered]@{name=$inputModel.name;bytes=$source.Length;sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    $manifest=[ordered]@{version=2;sourceLanguage=$sourceLanguage.ToUpperInvariant();assets=$assets}|ConvertTo-Json -Depth 4
    [System.IO.File]::WriteAllText((Join-Path $bundleRoot 'model-manifest.json'),$manifest,[System.Text.UTF8Encoding]::new($false))
    $totalBytes=($assets|ForEach-Object {$_.bytes}|Measure-Object -Sum).Sum
    Write-Output "$sourceLanguage bundle: $($assets.Count) models; $([math]::Round($totalBytes/1GB,2)) GiB."
}
