param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$ja = 'https://huggingface.co/artsaraiva/manga-ocr-2025-onnx-past/resolve/151898756903f03a2682fa6be4507dfeff71fb7e'
$models = @(
    @('bubble_detection.onnx','https://huggingface.co/Kiuyha/Manga-Bubble-YOLO/resolve/fb646500455e8a8a3a807fd27b855c8e4fc63766/onnx/yolo26n.onnx','b45c2e12cf0c3c1d2abfbbb9123c9f96f040f2ac36a0842382ecd9d859c851c7'),
    @('manga_ocr_encoder.onnx',"$ja/encoder_model.onnx",'2398ca8799bcc30a6240f2f309b3f9a1b697b5192afa9231338d2f69c3f866ef'),
    @('manga_ocr_decoder.onnx',"$ja/decoder_model_merged.onnx",'ac0ac6f48fa89bbfff1ab8d53ffcaa76839a72b145fe20a70923dc59b0b9349e'),
    @('vocab.txt',"$ja/vocab.txt",'344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d'),
    @('chinese_detection.onnx','https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.onnx','d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e'),
    @('chinese_recognition.onnx','https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513/inference.onnx','5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634')
)
foreach ($model in $models) {
    $path = Join-Path $Destination $model[0]
    if ((Test-Path -LiteralPath $path) -and (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -eq $model[2]) { continue }
    $part = "$path.part"
    Invoke-WebRequest -Uri $model[1] -OutFile $part
    if ((Get-FileHash -LiteralPath $part -Algorithm SHA256).Hash.ToLowerInvariant() -ne $model[2]) { throw "Checksum failed: $($model[0])" }
    Move-Item -LiteralPath $part -Destination $path -Force
}
$dictionary = Join-Path $PSScriptRoot 'ocr/chinese_characters.json'
if ((Get-FileHash -LiteralPath $dictionary -Algorithm SHA256).Hash.ToLowerInvariant() -ne '15d77eb7bb02d9f03bfd63929922894e5cde92b037c3a82f8d4de423e226b6b2') { throw 'Chinese dictionary checksum failed' }
Copy-Item -LiteralPath $dictionary -Destination (Join-Path $Destination 'chinese_characters.json') -Force
Write-Output 'Pinned vision models and matching 18,709-character dictionary are ready.'
