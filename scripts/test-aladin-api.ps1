param(
    [string]$VoiceTitle = ""
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$localPropertiesPath = Join-Path $root "local.properties"

if (-not (Test-Path -LiteralPath $localPropertiesPath)) {
    throw "local.properties 파일을 찾을 수 없습니다: $localPropertiesPath"
}

$ttbKey = Get-Content -LiteralPath $localPropertiesPath |
    Where-Object { $_ -match "^\s*ALADIN_TTB_KEY\s*=" } |
    Select-Object -First 1 |
    ForEach-Object { ($_ -split "=", 2)[1].Trim() }

if ([string]::IsNullOrWhiteSpace($ttbKey)) {
    throw "local.properties에 ALADIN_TTB_KEY가 설정되어 있지 않습니다."
}

function Invoke-AladinApi {
    param(
        [string]$Endpoint,
        [hashtable]$Params
    )

    $queryString = ($Params.GetEnumerator() | ForEach-Object {
        "$([uri]::EscapeDataString($_.Key))=$([uri]::EscapeDataString([string]$_.Value))"
    }) -join "&"

    Invoke-RestMethod -Uri "https://www.aladin.co.kr/ttb/api/$Endpoint`?$queryString" -Method Get -TimeoutSec 15
}

Write-Host "알라딘 API 테스트 시작"
Write-Host ""

# 1. 전자책 DB 구축에 필요한 기본 정보 수집 테스트
$keywords = @("파이썬", "AI", "자바스크립트")
$localDb = @()

foreach ($keyword in $keywords) {
    $response = Invoke-AladinApi -Endpoint "ItemSearch.aspx" -Params @{
        ttbkey = $ttbKey
        Query = $keyword
        QueryType = "Keyword"
        MaxResults = 10
        start = 1
        SearchTarget = "eBook"
        output = "js"
        Version = "20131101"
        outofStockfilter = 1
    }

    foreach ($item in $response.item) {
        $isbn13 = [string]$item.isbn13
        if ($item.title -and $item.author -and $item.publisher -and $isbn13 -match "^\d{13}$") {
            $localDb += [pscustomobject]@{
                Title = [string]$item.title
                Author = [string]$item.author
                Publisher = [string]$item.publisher
                Isbn13 = $isbn13
            }
        }
    }
}

$localDb = $localDb | Sort-Object Isbn13 -Unique

if ($localDb.Count -eq 0) {
    throw "전자책 DB용 데이터를 하나도 수집하지 못했습니다."
}

Write-Host "[1] 전자책 DB용 데이터 수집 성공"
Write-Host "수집된 고유 ISBN13 레코드 수: $($localDb.Count)"
Write-Host ""

# 2. 음성 인식한 책 제목으로 DB에서 ISBN 찾기 테스트
if ([string]::IsNullOrWhiteSpace($VoiceTitle)) {
    $VoiceTitle = $localDb[0].Title
}

$normalizedVoiceTitle = ($VoiceTitle.ToLowerInvariant() -replace "[^\p{L}\p{N}]", "")

$matchedBook = $localDb |
    Where-Object {
        $normalizedTitle = ($_.Title.ToLowerInvariant() -replace "[^\p{L}\p{N}]", "")
        $normalizedTitle.Contains($normalizedVoiceTitle) -or $normalizedVoiceTitle.Contains($normalizedTitle)
    } |
    Select-Object -First 1

if (-not $matchedBook) {
    throw "DB에서 '$VoiceTitle'에 해당하는 책을 찾지 못했습니다."
}

Write-Host "[2] 음성 제목으로 DB ISBN 조회 성공"
Write-Host "입력 제목: $VoiceTitle"
Write-Host "매칭 제목: $($matchedBook.Title)"
Write-Host "저자: $($matchedBook.Author)"
Write-Host "출판사: $($matchedBook.Publisher)"
Write-Host "ISBN13: $($matchedBook.Isbn13)"
Write-Host ""

Write-Host "알라딘 API 필수 기능 테스트 완료"
