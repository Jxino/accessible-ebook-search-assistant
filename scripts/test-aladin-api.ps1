param(
    [string[]]$SeedQueries = @("파이썬", "AI", "자바스크립트"),
    [string]$VoiceTitle = "",
    [int]$PagesPerQuery = 2,
    [int]$MaxResults = 10
)

$ErrorActionPreference = "Stop"

function Get-AladinTtbKey {
    $root = Resolve-Path (Join-Path $PSScriptRoot "..")
    $localPropertiesPath = Join-Path $root "local.properties"

    if (-not (Test-Path -LiteralPath $localPropertiesPath)) {
        throw "local.properties 파일을 찾을 수 없습니다: $localPropertiesPath"
    }

    $key = Get-Content -LiteralPath $localPropertiesPath |
        Where-Object { $_ -match "^\s*ALADIN_TTB_KEY\s*=" } |
        Select-Object -First 1 |
        ForEach-Object { ($_ -split "=", 2)[1].Trim() }

    if ([string]::IsNullOrWhiteSpace($key)) {
        throw "local.properties에 ALADIN_TTB_KEY가 설정되어 있지 않습니다."
    }

    return $key
}

function Invoke-AladinApi {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Endpoint,
        [Parameter(Mandatory = $true)]
        [hashtable]$Parameters
    )

    $queryString = ($Parameters.GetEnumerator() | ForEach-Object {
        "$([uri]::EscapeDataString($_.Key))=$([uri]::EscapeDataString([string]$_.Value))"
    }) -join "&"

    $url = "https://www.aladin.co.kr/ttb/api/$Endpoint`?$queryString"
    return Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 15
}

function ConvertTo-BookRecord {
    param(
        [Parameter(Mandatory = $true)]
        $Item,
        [Parameter(Mandatory = $true)]
        [string]$SourceQuery
    )

    if ([string]::IsNullOrWhiteSpace([string]$Item.title) -or
        [string]::IsNullOrWhiteSpace([string]$Item.author) -or
        [string]::IsNullOrWhiteSpace([string]$Item.publisher) -or
        [string]::IsNullOrWhiteSpace([string]$Item.isbn13)) {
        return $null
    }

    [pscustomobject]@{
        Title = [string]$Item.title
        Author = [string]$Item.author
        Publisher = [string]$Item.publisher
        Isbn13 = [string]$Item.isbn13
        MallType = [string]$Item.mallType
        Link = [string]$Item.link
        SourceQuery = $SourceQuery
    }
}

function Get-EbookSeedRecords {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TtbKey,
        [Parameter(Mandatory = $true)]
        [string[]]$Queries,
        [Parameter(Mandatory = $true)]
        [int]$PageCount,
        [Parameter(Mandatory = $true)]
        [int]$ResultCount
    )

    $records = New-Object System.Collections.Generic.List[object]

    foreach ($query in $Queries) {
        for ($page = 1; $page -le $PageCount; $page++) {
            $response = Invoke-AladinApi -Endpoint "ItemSearch.aspx" -Parameters @{
                ttbkey = $TtbKey
                Query = $query
                QueryType = "Keyword"
                MaxResults = $ResultCount
                start = $page
                SearchTarget = "eBook"
                output = "js"
                Version = "20131101"
                outofStockfilter = 1
            }

            if (-not $response.item) {
                continue
            }

            foreach ($item in $response.item) {
                $record = ConvertTo-BookRecord -Item $item -SourceQuery $query
                if ($record) {
                    $records.Add($record)
                }
            }
        }
    }

    return $records |
        Sort-Object Isbn13 -Unique |
        Sort-Object SourceQuery, Title
}

function Normalize-Title {
    param([string]$Text)

    return ($Text.ToLowerInvariant() -replace "\[[^\]]+\]", "" -replace "\([^)]+\)", "" -replace "[^\p{L}\p{N}]", "").Trim()
}

function Find-BookInLocalDbByVoiceTitle {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$LocalDb,
        [Parameter(Mandatory = $true)]
        [string]$SpokenTitle
    )

    $needle = Normalize-Title $SpokenTitle
    if ([string]::IsNullOrWhiteSpace($needle)) {
        throw "음성 인식 제목이 비어 있습니다."
    }

    $exact = $LocalDb | Where-Object { (Normalize-Title $_.Title) -eq $needle } | Select-Object -First 1
    if ($exact) {
        return $exact
    }

    return $LocalDb |
        Where-Object {
            $candidate = Normalize-Title $_.Title
            $candidate.Contains($needle) -or $needle.Contains($candidate)
        } |
        Select-Object -First 1
}

function Test-IsbnLookupByItemLookUp {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TtbKey,
        [Parameter(Mandatory = $true)]
        [string]$Isbn13
    )

    $response = Invoke-AladinApi -Endpoint "ItemLookUp.aspx" -Parameters @{
        ttbkey = $TtbKey
        itemIdType = "ISBN13"
        ItemId = $Isbn13
        output = "js"
        Version = "20131101"
    }

    if (-not $response.item -or $response.item.Count -eq 0) {
        throw "ItemLookUp 테스트 실패: ISBN13 '$Isbn13' 조회 결과가 없습니다."
    }

    $book = $response.item | Select-Object -First 1
    if ($book.isbn13 -ne $Isbn13) {
        throw "ItemLookUp 테스트 실패: 요청 ISBN13 '$Isbn13'와 응답 ISBN13 '$($book.isbn13)'가 다릅니다."
    }

    return $book
}

if ($PagesPerQuery -lt 1) {
    throw "PagesPerQuery는 1 이상이어야 합니다."
}

if ($MaxResults -lt 1 -or $MaxResults -gt 50) {
    throw "MaxResults는 알라딘 API 문서 기준 1 이상 50 이하로 설정해야 합니다."
}

$ttbKey = Get-AladinTtbKey

Write-Host "알라딘 Open API 필수 기능 테스트"
Write-Host "1. ItemSearch: 전자책 DB seed용 제목/저자/출판사/ISBN13 대량 수집"
Write-Host "2. 로컬 DB 조회: 음성 인식 제목으로 ISBN13 탐색"
Write-Host "3. ItemLookUp: 찾은 ISBN13이 알라딘 API에서 단일 도서로 조회되는지 검증"
Write-Host ""

$localDb = @(Get-EbookSeedRecords -TtbKey $ttbKey -Queries $SeedQueries -PageCount $PagesPerQuery -ResultCount $MaxResults)

if ($localDb.Count -lt 2) {
    throw "DB seed 테스트 실패: 수집된 전자책 레코드가 너무 적습니다. 현재 $($localDb.Count)개"
}

$titleForLookup = if ([string]::IsNullOrWhiteSpace($VoiceTitle)) {
    $localDb[0].Title
} else {
    $VoiceTitle.Trim()
}

$matchedBook = Find-BookInLocalDbByVoiceTitle -LocalDb $localDb -SpokenTitle $titleForLookup
if (-not $matchedBook) {
    throw "로컬 DB 조회 테스트 실패: '$titleForLookup'에 대응하는 도서를 찾지 못했습니다."
}

$verifiedBook = Test-IsbnLookupByItemLookUp -TtbKey $ttbKey -Isbn13 $matchedBook.Isbn13

Write-Host "테스트 성공"
Write-Host ""
Write-Host "[DB seed 수집 결과]"
Write-Host "검색 키워드: $($SeedQueries -join ', ')"
Write-Host "키워드당 페이지 수: $PagesPerQuery"
Write-Host "페이지당 요청 수: $MaxResults"
Write-Host "수집된 고유 ISBN13 레코드 수: $($localDb.Count)"
Write-Host ""
Write-Host "[음성 제목 -> DB ISBN 조회]"
Write-Host "입력 제목: $titleForLookup"
Write-Host "매칭 제목: $($matchedBook.Title)"
Write-Host "저자: $($matchedBook.Author)"
Write-Host "출판사: $($matchedBook.Publisher)"
Write-Host "ISBN13: $($matchedBook.Isbn13)"
Write-Host ""
Write-Host "[ISBN13 -> ItemLookUp 검증]"
Write-Host "검증 제목: $($verifiedBook.title)"
Write-Host "검증 ISBN13: $($verifiedBook.isbn13)"
