#!/usr/bin/env python3
"""Exercise every ShelfDrive review-fixture endpoint using stdlib HTTP."""

from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request
from typing import Any

from review_server import (
    ACCESS_TOKEN,
    AUTHOR,
    COLLECTION_ID,
    ITEM_ID,
    LIBRARY_ID,
    PASSWORD,
    SESSION_ID,
    TITLE,
    USERNAME,
    create_server,
)


def request(
    base_url: str,
    method: str,
    path: str,
    *,
    payload: dict[str, Any] | None = None,
    authenticated: bool = False,
    headers: dict[str, str] | None = None,
) -> tuple[int, dict[str, str], bytes]:
    request_headers = dict(headers or {})
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    if authenticated:
        request_headers["Authorization"] = f"Bearer {ACCESS_TOKEN}"
    req = urllib.request.Request(
        f"{base_url}{path}", data=data, headers=request_headers, method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def json_request(*args: Any, **kwargs: Any) -> tuple[int, dict[str, str], Any]:
    status, headers, body = request(*args, **kwargs)
    parsed = json.loads(body.decode("utf-8")) if body else None
    return status, headers, parsed


def assert_ok(status: int) -> None:
    assert status == 200, f"expected HTTP 200, got {status}"


def main() -> None:
    server = create_server("127.0.0.1", 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base_url = f"http://127.0.0.1:{server.server_port}"

    try:
        status, _, body = json_request(base_url, "GET", "/ping")
        assert_ok(status)
        assert body == {"success": True}

        status, _, body = json_request(
            base_url,
            "POST",
            "/login",
            payload={"username": USERNAME, "password": "wrong"},
            headers={"x-return-tokens": "true"},
        )
        assert status == 401 and body["error"]

        status, _, login = json_request(
            base_url,
            "POST",
            "/login",
            payload={"username": USERNAME, "password": PASSWORD},
            headers={"x-return-tokens": "true"},
        )
        assert_ok(status)
        assert login["user"]["accessToken"] == ACCESS_TOKEN
        assert login["serverSettings"]["version"] == "2.22.0"

        status, _, body = json_request(base_url, "GET", "/api/me")
        assert status == 401 and body["error"]

        status, _, auth = json_request(
            base_url, "POST", "/api/authorize", payload={}, authenticated=True
        )
        assert_ok(status)
        assert auth["user"]["username"] == USERNAME

        status, _, me = json_request(base_url, "GET", "/api/me", authenticated=True)
        assert_ok(status)
        assert me["id"] == "review-user" and len(me["mediaProgress"]) == 1

        status, _, libraries = json_request(
            base_url, "GET", "/api/libraries?include=stats", authenticated=True
        )
        assert_ok(status)
        assert libraries["libraries"][0]["id"] == LIBRARY_ID

        status, _, shelves = json_request(
            base_url,
            "GET",
            f"/api/libraries/{LIBRARY_ID}/personalized",
            authenticated=True,
        )
        assert_ok(status)
        assert {shelf["id"] for shelf in shelves} == {"recently-added", "discover"}

        status, _, items = json_request(
            base_url,
            "GET",
            f"/api/libraries/{LIBRARY_ID}/items?limit=100&minified=1",
            authenticated=True,
        )
        assert_ok(status)
        assert items["results"][0]["media"]["metadata"]["title"] == TITLE

        status, _, filtered_items = json_request(
            base_url,
            "GET",
            f"/api/libraries/{LIBRARY_ID}/items?filter=authors.fixture&sort=media.metadata.title",
            authenticated=True,
        )
        assert_ok(status)
        assert filtered_items["results"][0]["id"] == ITEM_ID

        status, _, series = json_request(
            base_url,
            "GET",
            f"/api/libraries/{LIBRARY_ID}/series?minified=1&sort=name&limit=10000",
            authenticated=True,
        )
        assert_ok(status)
        assert series["results"][0]["books"][0]["id"] == ITEM_ID

        status, _, authors = json_request(
            base_url,
            "GET",
            f"/api/libraries/{LIBRARY_ID}/authors",
            authenticated=True,
        )
        assert_ok(status)
        assert authors["authors"][0]["name"] == AUTHOR

        status, _, collections = json_request(
            base_url,
            "GET",
            f"/api/libraries/{LIBRARY_ID}/collections?minified=1&sort=name&limit=1000",
            authenticated=True,
        )
        assert_ok(status)
        assert collections["results"][0]["id"] == COLLECTION_ID

        status, _, search = json_request(
            base_url,
            "GET",
            f"/api/libraries/{LIBRARY_ID}/search?q=sample%20journey",
            authenticated=True,
        )
        assert_ok(status)
        assert search["book"][0]["libraryItem"]["id"] == ITEM_ID

        status, _, empty_search = json_request(
            base_url,
            "GET",
            f"/api/libraries/{LIBRARY_ID}/search?q=no-match",
            authenticated=True,
        )
        assert_ok(status)
        assert empty_search["book"] == []

        status, _, in_progress = json_request(
            base_url, "GET", "/api/me/items-in-progress", authenticated=True
        )
        assert_ok(status)
        assert in_progress["libraryItems"][0]["progressLastUpdate"]

        status, _, item = json_request(
            base_url, "GET", f"/api/items/{ITEM_ID}?expanded=1", authenticated=True
        )
        assert_ok(status)
        assert item["media"]["numTracks"] == 1

        status, cover_headers, cover = request(
            base_url, "GET", f"/api/items/{ITEM_ID}/cover"
        )
        assert_ok(status)
        assert cover_headers["Content-Type"] == "image/png" and cover.startswith(b"\x89PNG")

        status, cover_head_headers, cover_head = request(
            base_url, "HEAD", f"/api/items/{ITEM_ID}/cover"
        )
        assert_ok(status)
        assert cover_head == b"" and int(cover_head_headers["Content-Length"]) == len(cover)

        play_payload = {
            "mediaPlayer": "exo-player",
            "forceDirectPlay": True,
            "forceTranscode": False,
            "deviceInfo": {
                "deviceId": "self-test",
                "manufacturer": "ShelfDrive",
                "model": "Fixture",
                "sdkVersion": 35,
                "clientVersion": "self-test",
            },
        }
        status, _, session = json_request(
            base_url,
            "POST",
            f"/api/items/{ITEM_ID}/play",
            payload=play_payload,
            authenticated=True,
        )
        assert_ok(status)
        assert session["id"] == SESSION_ID and session["audioTracks"][0]["index"] == 0

        status, audio_headers, audio = request(
            base_url,
            "GET",
            f"/public/session/{SESSION_ID}/track/0",
            headers={"Range": "bytes=0-31"},
        )
        assert status == 206
        assert len(audio) == 32 and audio_headers["Accept-Ranges"] == "bytes"
        assert audio_headers["Content-Range"].startswith("bytes 0-31/")

        status, audio_head_headers, audio_head = request(
            base_url, "HEAD", f"/public/session/{SESSION_ID}/track/0"
        )
        assert_ok(status)
        assert audio_head == b"" and int(audio_head_headers["Content-Length"]) > len(audio)

        status, _, fetched_session = json_request(
            base_url, "GET", f"/api/session/{SESSION_ID}", authenticated=True
        )
        assert_ok(status)
        assert fetched_session["displayTitle"] == TITLE

        status, _, sync = json_request(
            base_url,
            "POST",
            f"/api/session/{SESSION_ID}/sync",
            payload={"currentTime": 6.0, "timeListened": 3, "duration": 12.0},
            authenticated=True,
        )
        assert_ok(status)
        assert sync == {}

        status, _, progress = json_request(
            base_url, "GET", f"/api/me/progress/{ITEM_ID}", authenticated=True
        )
        assert_ok(status)
        assert progress["currentTime"] == 6.0 and progress["progress"] == 0.5

        status, _, patched = json_request(
            base_url,
            "PATCH",
            f"/api/me/progress/{ITEM_ID}",
            payload={"currentTime": 4.0, "progress": 1 / 3, "isFinished": False},
            authenticated=True,
        )
        assert_ok(status)
        assert patched == {}

        status, _, local = json_request(
            base_url,
            "POST",
            "/api/session/local",
            payload=session,
            authenticated=True,
        )
        assert_ok(status)
        assert local == {}

        status, _, local_all = json_request(
            base_url,
            "POST",
            "/api/session/local-all",
            payload={"sessions": [session], "deviceInfo": play_payload["deviceInfo"]},
            authenticated=True,
        )
        assert_ok(status)
        assert local_all["results"] == [
            {"id": SESSION_ID, "success": True, "progressSynced": True}
        ]

        status, _, closed = json_request(
            base_url,
            "POST",
            f"/api/session/{SESSION_ID}/close",
            payload={},
            authenticated=True,
        )
        assert_ok(status)
        assert closed == {}

        print("PASS: every documented fixture endpoint returned the expected contract")
        print(f"PASS: rights-safe cover and byte-range audio served from {base_url}")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


if __name__ == "__main__":
    main()
