from __future__ import annotations

import argparse
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path


def read_aladin_ttb_key() -> str:
    root = Path(__file__).resolve().parents[1]
    local_properties_path = root / "local.properties"

    if not local_properties_path.exists():
        raise FileNotFoundError(f"local.properties 파일을 찾을 수 없습니다: {local_properties_path}")

    for line in local_properties_path.read_text(encoding="utf-8").splitlines():
        if re.match(r"^\s*ALADIN_TTB_KEY\s*=", line):
            key = line.split("=", 1)[1].strip()
            if key:
                return key

    raise ValueError("local.properties에 ALADIN_TTB_KEY가 설정되어 있지 않습니다.")


def call_aladin_api(endpoint: str, params: dict[str, object]) -> dict:
    query_string = urllib.parse.urlencode(params)
    url = f"https://www.aladin.co.kr/ttb/api/{endpoint}?{query_string}"

    with urllib.request.urlopen(url, timeout=15) as response:
        body = response.read().decode("utf-8")
        return json.loads(body)


def normalize_title(title: str) -> str:
    return re.sub(r"[^\w가-힣]", "", title.lower())


def main() -> None:
    parser = argparse.ArgumentParser(description="알라딘 API 필수 기능 테스트")
    parser.add_argument("--voice-title", default="", help="음성 인식 책 제목 테스트값")
    args = parser.parse_args()

    ttb_key = read_aladin_ttb_key()

    print("알라딘 API 테스트 시작")
    print()

    # 1. 전자책 DB 구축에 필요한 기본 정보 수집 테스트
    keywords = ["파이썬", "AI", "자바스크립트"]
    local_db: list[dict[str, str]] = []

    for keyword in keywords:
        response = call_aladin_api(
            "ItemSearch.aspx",
            {
                "ttbkey": ttb_key,
                "Query": keyword,
                "QueryType": "Keyword",
                "MaxResults": 10,
                "start": 1,
                "SearchTarget": "eBook",
                "output": "js",
                "Version": "20131101",
                "outofStockfilter": 1,
            },
        )

        for item in response.get("item", []):
            isbn13 = str(item.get("isbn13", "")).strip()
            title = str(item.get("title", "")).strip()
            author = str(item.get("author", "")).strip()
            publisher = str(item.get("publisher", "")).strip()

            if title and author and publisher and re.fullmatch(r"\d{13}", isbn13):
                local_db.append(
                    {
                        "Title": title,
                        "Author": author,
                        "Publisher": publisher,
                        "Isbn13": isbn13,
                    }
                )

    unique_books = {book["Isbn13"]: book for book in local_db}
    local_db = list(unique_books.values())

    if not local_db:
        raise RuntimeError("전자책 DB용 데이터를 하나도 수집하지 못했습니다.")

    print("[1] 전자책 DB용 데이터 수집 성공")
    print(f"수집된 고유 ISBN13 레코드 수: {len(local_db)}")
    print()

    print("[수집 레코드 전체 목록]")
    for index, book in enumerate(local_db, start=1):
        print(f"{index}. 책 제목: {book['Title']}")
        print(f"   저자: {book['Author']}")
        print(f"   출판사: {book['Publisher']}")
        print(f"   ISBN13: {book['Isbn13']}")
    print()

    # 2. 음성 인식한 책 제목으로 DB에서 ISBN 찾기 테스트
    voice_title = args.voice_title.strip() or local_db[0]["Title"]
    normalized_voice_title = normalize_title(voice_title)

    matched_book = None
    for book in local_db:
        normalized_title = normalize_title(book["Title"])
        if normalized_voice_title in normalized_title or normalized_title in normalized_voice_title:
            matched_book = book
            break

    if matched_book is None:
        raise RuntimeError(f"DB에서 '{voice_title}'에 해당하는 책을 찾지 못했습니다.")

    print("[2] 음성 제목으로 DB ISBN 조회 성공")
    print(f"입력 제목: {voice_title}")
    print(f"매칭 제목: {matched_book['Title']}")
    print(f"저자: {matched_book['Author']}")
    print(f"출판사: {matched_book['Publisher']}")
    print(f"ISBN13: {matched_book['Isbn13']}")
    print()
    print("알라딘 API 필수 기능 테스트 완료")


if __name__ == "__main__":
    main()
