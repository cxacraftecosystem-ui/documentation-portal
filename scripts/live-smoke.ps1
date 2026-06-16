param(
  [string]$BaseUrl = "http://127.0.0.1:8000/api",
  [string]$AdminEmail = "",
  [string]$EnvFile = "backend/.env"
)

$ErrorActionPreference = "Stop"

$adminPassword = "ChangeMe123!"
if (Test-Path $EnvFile) {
  foreach ($line in Get-Content $EnvFile) {
    if (-not $AdminEmail -and $line -match "^MASTER_ADMIN_EMAIL=(.*)$") {
      $AdminEmail = $Matches[1].Trim('"')
    }
    if ($line -match "^ADMIN_PASSWORD=(.*)$") {
      $adminPassword = $Matches[1].Trim('"')
    }
  }
}

if (-not $AdminEmail) {
  throw "AdminEmail was not provided and MASTER_ADMIN_EMAIL was not found in $EnvFile."
}

function Invoke-JsonPost($path, $body, $token = $null) {
  $headers = @{}
  if ($token) {
    $headers.Authorization = "Bearer $token"
  }
  Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl$path" `
    -Headers $headers `
    -ContentType "application/json" `
    -Body ($body | ConvertTo-Json -Depth 30) `
    -TimeoutSec 30
}

function Invoke-ApiGet($path, $token) {
  Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl$path" `
    -Headers @{ Authorization = "Bearer $token" } `
    -TimeoutSec 30
}

function Invoke-ApiDelete($path, $token) {
  try {
    Invoke-RestMethod `
      -Method Delete `
      -Uri "$BaseUrl$path" `
      -Headers @{ Authorization = "Bearer $token" } `
      -TimeoutSec 30 | Out-Null
  } catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -ne 204 -and $statusCode -ne 404) {
      throw
    }
  }
}

$cleanup = New-Object System.Collections.Generic.List[object]
$token = $null

try {
  $login = Invoke-JsonPost "/auth/login" @{
    email = $AdminEmail
    password = $adminPassword
  }
  $token = $login.accessToken
  if (-not $token) {
    throw "Login did not return an access token."
  }

  $me = Invoke-ApiGet "/auth/me" $token
  if ($me.email -ne $AdminEmail -or $me.role -ne "MASTER_ADMIN") {
    throw "Unexpected identity: $($me.email) / $($me.role)."
  }

  $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

  $craft = Invoke-JsonPost "/crafts" @{
    name = "Smoke Craft $stamp"
    category = "Smoke"
    description = "Automated verification craft"
    place = "Verification"
  } $token
  $cleanup.Add(@("crafts", $craft.id)) | Out-Null

  $artisan = Invoke-JsonPost "/artisans" @{
    name = "Smoke Artisan $stamp"
    place = "Verification"
    craftId = $craft.id
    notes = "Automated verification artisan"
    location = @{
      latitude = 28.6139
      longitude = 77.2090
      accuracy = 8
      placeName = "Delhi smoke point"
    }
  } $token
  $cleanup.Add(@("artisans", $artisan.id)) | Out-Null
  if ($artisan.craftId -ne $craft.id) {
    throw "Artisan was not linked to the craft."
  }

  $product = Invoke-JsonPost "/products" @{
    craftName = $craft.name
    place = "Verification"
    artisanName = $artisan.name
    productName = "Smoke Product $stamp"
    artisanId = $artisan.id
    craftId = $craft.id
    productType = "OTHER"
    marketDemand = "UNKNOWN"
    lengthInches = 12.5
    breadthInches = 8.25
  } $token
  $cleanup.Add(@("products", $product.id)) | Out-Null
  if ([decimal]$product.lengthInches -ne [decimal]12.5 -or [decimal]$product.breadthInches -ne [decimal]8.25) {
    throw "Product measurement fields did not persist."
  }

  $tool = Invoke-JsonPost "/tools" @{
    craftName = $craft.name
    place = "Verification"
    artisanName = $artisan.name
    toolkitName = "Smoke Tool $stamp"
    artisanId = $artisan.id
    craftId = $craft.id
    maker = "UNKNOWN"
    traditionType = "UNKNOWN"
    lengthInches = 5.5
    breadthInches = 2.25
  } $token
  $cleanup.Add(@("tools", $tool.id)) | Out-Null
  if ([decimal]$tool.lengthInches -ne [decimal]5.5 -or [decimal]$tool.breadthInches -ne [decimal]2.25) {
    throw "Tool measurement fields did not persist."
  }

  $questions = Invoke-ApiGet "/questionnaire/questions" $token
  if ($questions.Count -lt 250) {
    throw "Questionnaire seed looks incomplete: $($questions.Count) questions."
  }

  $interview = Invoke-JsonPost "/questionnaire/interviews" @{
    title = "Smoke Interview $stamp"
    place = "Verification"
    language = "English"
    artisanIds = @($artisan.id)
    responses = @(
      @{
        questionId = $questions[0].id
        answerText = "Smoke-test answer"
        notes = "Automated verification"
      }
    )
  } $token
  $cleanup.Add(@("questionnaire/interviews", $interview.id)) | Out-Null
  if ($interview.artisans.Count -lt 1 -or $interview.responses.Count -lt 1) {
    throw "Questionnaire interview did not link artisan and response."
  }

  $presign = Invoke-JsonPost "/media/presign" @{
    filename = "smoke-$stamp.txt"
    mimeType = "text/plain"
    mediaType = "DOCUMENT"
    sizeBytes = 18
    linkedRecordType = "product"
    linkedRecordId = $product.id
  } $token
  if (-not $presign.objectKey -or -not $presign.uploadUrl) {
    throw "Presign did not return upload data."
  }

  $media = Invoke-JsonPost "/media/complete" @{
    originalFilename = "smoke-$stamp.txt"
    mediaType = "AUDIO"
    mimeType = "audio/wav"
    sizeBytes = 18
    objectKey = $presign.objectKey
    caption = "Smoke media"
    transcriptText = "Already transcribed smoke text"
    transcriptSummary = "Smoke summary"
    transcriptStatus = "COMPLETED"
    linkedRecordType = "product"
    linkedRecordId = $product.id
    processingRequests = @()
  } $token
  $cleanup.Add(@("media", $media.id)) | Out-Null
  if ($media.transcriptStatus -ne "COMPLETED" -or $media.linkedRecordId -ne $product.id) {
    throw "Media transcript/link fields did not persist."
  }

  $audioPath = Join-Path $env:TEMP "smoke-audio-$stamp.wav"
  [IO.File]::WriteAllBytes($audioPath, [byte[]](82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86, 69))
  $transcribeRaw = & curl.exe -s -X POST "$BaseUrl/media/transcribe" -H "Authorization: Bearer $token" -F "file=@$audioPath;type=audio/wav;filename=smoke.wav"
  if ($LASTEXITCODE -ne 0) {
    throw "curl transcribe call failed."
  }
  $transcribe = $transcribeRaw | ConvertFrom-Json
  if (-not $transcribe.status) {
    throw "Unexpected transcribe response: $transcribeRaw"
  }

  $pngPath = Join-Path $env:TEMP "smoke-image-$stamp.png"
  [IO.File]::WriteAllBytes($pngPath, [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p94AAAAASUVORK5CYII="))
  $measurementRaw = & curl.exe -s -X POST "$BaseUrl/media/analyze-measurement" -H "Authorization: Bearer $token" -F "file=@$pngPath;type=image/png;filename=smoke.png"
  if ($LASTEXITCODE -ne 0) {
    throw "curl measurement call failed."
  }
  $measurement = $measurementRaw | ConvertFrom-Json
  if (-not $measurement.status) {
    throw "Unexpected measurement response: $measurementRaw"
  }

  "SMOKE_OK role=$($me.role) questions=$($questions.Count) craft=$($craft.id) artisan=$($artisan.id) product=$($product.id) tool=$($tool.id) interview=$($interview.id) media=$($media.id) transcribe=$($transcribe.status) measurement=$($measurement.status)"
} finally {
  if ($token) {
    for ($i = $cleanup.Count - 1; $i -ge 0; $i--) {
      $item = $cleanup[$i]
      try {
        Invoke-ApiDelete "/$($item[0])/$($item[1])" $token
      } catch {
        Write-Warning "Cleanup failed for $($item[0])/$($item[1]): $($_.Exception.Message)"
      }
    }
  }
}
