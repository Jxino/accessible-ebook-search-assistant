param(
    [string]$Query = "파이썬",
    [string]$Isbn13 = ""
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

function Test-ItemSearch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TtbKey,
        [Parameter(Mandatory = $true)]
        [string]$Keyword
    )

    $response = Invoke-AladinApi -Endpoint "ItemSearch.aspx" -Parameters @{
        ttbkey = $TtbKey
        Query = $Keyword
        QueryType = "Title"
        MaxResults = 5
        start = 1
        SearchTarget = "Book"
        output = "js"
        Version = "20131101"
        outofStockfilter = 1
    }

    if (-not $response.item -or $response.item.Count -eq 0) {
        throw "ItemSearch 테스트 실패: '$Keyword' 검색 결과가 없습니다."
    }

    $book = $response.item |
        Where-Object {
            -not [string]::IsNullOrWhiteSpace($_.isbn13) -and
            -not [string]::IsNullOrWhiteSpace($_.title) -and
            -not [string]::IsNullOrWhiteSpace($_.author) -and
            -not [string]::IsNullOrWhiteSpace($_.publisher)
        } |
        Select-Object -First 1

    if (-not $book) {
        throw "ItemSearch 테스트 실패: 제목, 저자, 출판사, ISBN13이 모두 있는 결과를 찾지 못했습니다."
    }

    return $book
}

function Test-ItemLookUp {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TtbKey,
        [Parameter(Mandatory = $true)]
        [string]$LookupIsbn13
    )

    $response = Invoke-AladinApi -Endpoint "ItemLookUp.aspx" -Parameters @{
        ttbkey = $TtbKey
        itemIdType = "ISBN13"
        ItemId = $LookupIsbn13
        output = "js"
        Version = "20131101"
        OptResult = "ebookList"
    }

    if (-not $response.item -or $response.item.Count -eq 0) {
        throw "ItemLookUp 테스트 실패: ISBN13 '$LookupIsbn13' 조회 결과가 없습니다."
    }

    $book = $response.item | Select-Object -First 1
    if ($book.isbn13 -ne $LookupIsbn13) {
        throw "ItemLookUp 테스트 실패: 요청 ISBN13 '$LookupIsbn13'와 응답 ISBN13 '$($book.isbn13)'가 다릅니다."
    }

    foreach ($field in @("title", "author", "publisher", "isbn13", "link")) {
        if ([string]::IsNullOrWhiteSpace([string]$book.$field)) {
            throw "ItemLookUp 테스트 실패: 응답 필드 '$field'가 비어 있습니다."
        }
    }

    return $book
}

$ttbKey = Get-AladinTtbKey

Write-Host "알라딘 Open API 필수 기능 테스트"
Write-Host "1. ItemSearch: 음성 검색어/도서명으로 ISBN13 후보 탐색"
Write-Host "2. ItemLookUp: 자체 DB가 보유한 ISBN13으로 단일 도서 조회"
Write-Host ""

$searchBook = Test-ItemSearch -TtbKey $ttbKey -Keyword $Query
$lookupIsbn13 = if ([string]::IsNullOrWhiteSpace($Isbn13)) { $searchBook.isbn13 } else { $Isbn13.Trim() }
$lookupBook = Test-ItemLookUp -TtbKey $ttbKey -LookupIsbn13 $lookupIsbn13

Write-Host "테스트 성공"
Write-Host ""
Write-Host "[ItemSearch 결과]"
Write-Host "제목: $($searchBook.title)"
Write-Host "저자: $($searchBook.author)"
Write-Host "출판사: $($searchBook.publisher)"
Write-Host "ISBN13: $($searchBook.isbn13)"
Write-Host ""
Write-Host "[ItemLookUp 결과]"
Write-Host "제목: $($lookupBook.title)"
Write-Host "저자: $($lookupBook.author)"
Write-Host "출판사: $($lookupBook.publisher)"
Write-Host "ISBN13: $($lookupBook.isbn13)"
Write-Host "링크: $($lookupBook.link)"
