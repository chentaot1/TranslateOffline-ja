param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$models = @(
    @('text_detection.onnx','https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.onnx','d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e')
)
foreach ($model in $models) {
    $path = Join-Path $Destination $model[0]
    if ((Test-Path -LiteralPath $path) -and (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -eq $model[2]) { continue }
    $part = "$path.part"
    Invoke-WebRequest -Uri $model[1] -OutFile $part
    if ((Get-FileHash -LiteralPath $part -Algorithm SHA256).Hash.ToLowerInvariant() -ne $model[2]) { throw "Checksum failed: $($model[0])" }
    Move-Item -LiteralPath $part -Destination $path -Force
}
Write-Output 'Pinned scene text detector (text_detection.onnx) is ready.'
