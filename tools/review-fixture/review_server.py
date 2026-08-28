#!/usr/bin/env python3
"""Deterministic Audiobookshelf-compatible server for ShelfDrive review testing.

The fixture intentionally uses only Python's standard library and synthetic data.
It is not a general Audiobookshelf implementation and must never be used with real
credentials or real library exports.
"""

from __future__ import annotations

import argparse
import copy
import json
import re
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit


HOST = "0.0.0.0"
PORT = 13378

USERNAME = "reviewer"
PASSWORD = "ShelfDriveReview!"
ACCESS_TOKEN = "review-access-token"
REFRESH_TOKEN = "review-refresh-token"
SERVER_VERSION = "2.22.0"

USER_ID = "review-user"
LIBRARY_ID = "review-library"
FOLDER_ID = "review-folder"
ITEM_ID = "review-item"
AUTHOR_ID = "review-author"
SERIES_ID = "review-series"
COLLECTION_ID = "review-collection"
SESSION_ID = "review-session"

TITLE = "ShelfDrive Sample Journey"
AUTHOR = "ShelfDrive Studio"
DURATION_SECONDS = 12.0
FIXED_TIME_MS = 1_735_689_600_000
MAX_JSON_BODY_BYTES = 1024 * 1024

ASSET_DIR = Path(__file__).resolve().parent / "assets"
COVER_PATH = ASSET_DIR / "review-cover.png"
AUDIO_PATH = ASSET_DIR / "review-audio.mp3"


def _metadata() -> dict[str, Any]:
    return {
        "title": TITLE,
        "subtitle": "A ShelfDrive review fixture",
        "authors": [{"id": AUTHOR_ID, "name": AUTHOR, "coverPath": None}],
        "narrators": [AUTHOR],
        "genres": ["Reference"],
        "publishedYear": "2026",
        "publishedDate": "2026-01-01",
        "publisher": "ShelfDrive",
        "description": "Synthetic, rights-safe media created for ShelfDrive review testing.",
        "isbn": None,
        "asin": None,
        "language": "English",
        "explicit": False,
        "authorName": AUTHOR,
        "authorNameLF": AUTHOR,
        "narratorName": AUTHOR,
        "seriesName": "Review Fixture",
        "series": [{"id": SERIES_ID, "name": "Review Fixture", "sequence": "1"}],
    }


def _track() -> dict[str, Any]:
    return {
        "index": 0,
        "startOffset": 0.0,
        "duration": DURATION_SECONDS,
        "title": TITLE,
        # ShelfDrive 2.22+ builds construct /public/session/... from id/index.
        # This legacy-compatible value remains valid for schema completeness.
        "contentUrl": "/public/session/review-session/track/0",
        "mimeType": "audio/mpeg",
        "metadata": {
            "filename": "review-audio.mp3",
            "ext": "mp3",
            "path": "/fixture/review-audio.mp3",
            "relPath": "review-audio.mp3",
            "size": AUDIO_PATH.stat().st_size,
        },
        "isLocal": False,
        "localFileId": None,
        "serverIndex": 0,
    }


def _progress(current_time: float = 3.0, finished: bool = False) -> dict[str, Any]:
    bounded = min(max(float(current_time), 0.0), DURATION_SECONDS)
    return {
        "id": "review-progress",
        "libraryItemId": ITEM_ID,
        "episodeId": None,
        "duration": DURATION_SECONDS,
        "progress": bounded / DURATION_SECONDS,
        "currentTime": bounded,
        "isFinished": bool(finished),
        "ebookLocation": None,
        "ebookProgress": None,
        "lastUpdate": FIXED_TIME_MS,
        "startedAt": FIXED_TIME_MS - 60_000,
        "finishedAt": FIXED_TIME_MS if finished else None,
    }


def _library_item(progress: dict[str, Any] | None = None) -> dict[str, Any]:
    track = _track()
    return {
        "id": ITEM_ID,
        "ino": "fixture-ino-1",
        "libraryId": LIBRARY_ID,
        "folderId": FOLDER_ID,
        "path": "/fixture/shelfdrive-sample-journey",
        "relPath": "shelfdrive-sample-journey",
        "mtimeMs": FIXED_TIME_MS,
        "ctimeMs": FIXED_TIME_MS,
        "birthtimeMs": FIXED_TIME_MS,
        "addedAt": FIXED_TIME_MS,
        "updatedAt": FIXED_TIME_MS,
        "lastScan": FIXED_TIME_MS,
        "scanVersion": "fixture-1",
        "isMissing": False,
        "isInvalid": False,
        "mediaType": "book",
        "media": {
            "metadata": _metadata(),
            "coverPath": "fixture/review-cover.png",
            "tags": ["review-fixture"],
            "audioFiles": [
                {
                    "index": 0,
                    "ino": "fixture-audio-1",
                    "metadata": track["metadata"],
                }
            ],
            "chapters": [
                {"id": 1, "start": 0.0, "end": DURATION_SECONDS, "title": TITLE}
            ],
            "tracks": [track],
            "ebookFile": None,
            "size": AUDIO_PATH.stat().st_size,
            "duration": DURATION_SECONDS,
            "numTracks": 1,
        },
        "libraryFiles": [
            {"ino": "fixture-audio-1", "metadata": track["metadata"]}
        ],
        "userMediaProgress": copy.deepcopy(progress),
        "collapsedSeries": None,
        "localLibraryItemId": None,
        "recentEpisode": None,
    }


def _library() -> dict[str, Any]:
    return {
        "id": LIBRARY_ID,
        "name": "ShelfDrive Review Library",
        "folders": [{"id": FOLDER_ID, "fullPath": "/fixture"}],
        "icon": "books-1",
        "mediaType": "book",
        "stats": {
            "totalItems": 1,
            "totalSize": AUDIO_PATH.stat().st_size,
            "totalDuration": DURATION_SECONDS,
            "numAudioFiles": 1,
        },
    }


def _author() -> dict[str, Any]:
    return {
        "id": AUTHOR_ID,
        "libraryId": LIBRARY_ID,
        "name": AUTHOR,
        "description": "Synthetic author identity for ShelfDrive review testing.",
        "imagePath": None,
        "addedAt": FIXED_TIME_MS,
        "updatedAt": FIXED_TIME_MS,
        "numBooks": 1,
        "libraryItems": None,
        "series": None,
    }


def _series(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": SERIES_ID,
        "libraryId": LIBRARY_ID,
        "name": "Review Fixture",
        "description": "Synthetic series for ShelfDrive review testing.",
        "addedAt": FIXED_TIME_MS,
        "updatedAt": FIXED_TIME_MS,
        "books": [copy.deepcopy(item)],
        "localLibraryItemId": None,
    }


def _collection(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": COLLECTION_ID,
        "libraryId": LIBRARY_ID,
        "name": "Review Collection",
        "description": "Synthetic collection for ShelfDrive review testing.",
        "books": [copy.deepcopy(item)],
    }


class FixtureState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.current_time = 3.0
        self.finished = False
        self.session_closed = False

    def progress(self) -> dict[str, Any]:
        with self.lock:
            return _progress(self.current_time, self.finished)

    def update_progress(self, payload: dict[str, Any]) -> None:
        with self.lock:
            candidate = payload.get("currentTime", self.current_time)
            if isinstance(candidate, (int, float)):
                self.current_time = min(max(float(candidate), 0.0), DURATION_SECONDS)
            progress = payload.get("progress")
            if "currentTime" not in payload and isinstance(progress, (int, float)):
                self.current_time = min(max(float(progress), 0.0), 1.0) * DURATION_SECONDS
            if isinstance(payload.get("isFinished"), bool):
                self.finished = payload["isFinished"]

    def set_session_closed(self, value: bool) -> None:
        with self.lock:
            self.session_closed = value


def _user(progress: dict[str, Any], include_tokens: bool = False) -> dict[str, Any]:
    user: dict[str, Any] = {
        "id": USER_ID,
        "username": USERNAME,
        "mediaProgress": [copy.deepcopy(progress)],
    }
    if include_tokens:
        user.update(
            {
                "accessToken": ACCESS_TOKEN,
                "refreshToken": REFRESH_TOKEN,
                "token": ACCESS_TOKEN,
            }
        )
    return user


def _playback_session(progress: dict[str, Any]) -> dict[str, Any]:
    item = _library_item(progress)
    return {
        "id": SESSION_ID,
        "userId": USER_ID,
        "libraryItemId": ITEM_ID,
        "episodeId": None,
        "mediaType": "book",
        "mediaMetadata": _metadata(),
        "deviceInfo": {
            "deviceId": "review-device",
            "manufacturer": "ShelfDrive",
            "model": "AAOS Review Fixture",
            "sdkVersion": 35,
            "clientVersion": "review-fixture",
        },
        "chapters": copy.deepcopy(item["media"]["chapters"]),
        "displayTitle": TITLE,
        "displayAuthor": AUTHOR,
        "coverPath": "fixture/review-cover.png",
        "duration": DURATION_SECONDS,
        "playMethod": 0,
        "startedAt": FIXED_TIME_MS,
        "updatedAt": FIXED_TIME_MS,
        "timeListening": 0,
        "audioTracks": copy.deepcopy(item["media"]["tracks"]),
        "currentTime": progress["currentTime"],
        "libraryItem": item,
        "localLibraryItem": None,
        "localEpisodeId": None,
        "serverConnectionConfigId": None,
        "serverAddress": None,
        "mediaPlayer": "exo-player",
    }


class ReviewFixtureHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ShelfDriveReviewFixture/1.0"

    @property
    def state(self) -> FixtureState:
        return self.server.fixture_state  # type: ignore[attr-defined]

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._route(send_body=True)

    def do_HEAD(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._route(send_body=False)

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._route(send_body=True)

    def do_PATCH(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._route(send_body=True)

    def _route(self, send_body: bool) -> None:
        parsed = urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = parse_qs(parsed.query, keep_blank_values=True)

        if path == "/ping" and self.command in {"GET", "HEAD"}:
            self._json({"success": True}, send_body=send_body)
            return

        if path == "/login" and self.command == "POST":
            payload = self._read_json()
            if payload is None:
                return
            if self.headers.get("x-return-tokens", "").lower() != "true":
                self._error(HTTPStatus.BAD_REQUEST, "x-return-tokens must be true")
                return
            if payload.get("username") != USERNAME or payload.get("password") != PASSWORD:
                self._error(HTTPStatus.UNAUTHORIZED, "Invalid fixture credentials")
                return
            progress = self.state.progress()
            self._json(
                {
                    "user": _user(progress, include_tokens=True),
                    "serverSettings": {"version": SERVER_VERSION},
                }
            )
            return

        if path == f"/api/items/{ITEM_ID}/cover" and self.command in {"GET", "HEAD"}:
            self._asset(COVER_PATH, "image/png", send_body=send_body, allow_range=False)
            return

        if path == f"/public/session/{SESSION_ID}/track/0" and self.command in {"GET", "HEAD"}:
            self._asset(AUDIO_PATH, "audio/mpeg", send_body=send_body, allow_range=True)
            return

        if path.startswith("/api/") and not self._is_authorized():
            self._error(HTTPStatus.UNAUTHORIZED, "Bearer token required")
            return

        progress = self.state.progress()
        item = _library_item(progress)

        if path == "/api/authorize" and self.command == "POST":
            if self._read_json() is None:
                return
            self._json({"user": _user(progress)})
        elif path == "/api/me" and self.command in {"GET", "HEAD"}:
            self._json(_user(progress), send_body=send_body)
        elif path == "/api/libraries" and self.command in {"GET", "HEAD"}:
            self._json({"libraries": [_library()]}, send_body=send_body)
        elif path == f"/api/libraries/{LIBRARY_ID}/personalized" and self.command in {"GET", "HEAD"}:
            shelves = [
                {
                    "id": "recently-added",
                    "label": "Recently Added",
                    "total": 1,
                    "type": "book",
                    "entities": [copy.deepcopy(item)],
                },
                {
                    "id": "discover",
                    "label": "Discover",
                    "total": 1,
                    "type": "book",
                    "entities": [copy.deepcopy(item)],
                },
            ]
            self._json(shelves, send_body=send_body)
        elif path == f"/api/libraries/{LIBRARY_ID}/items" and self.command in {"GET", "HEAD"}:
            self._json({"results": [item], "total": 1, "limit": 100}, send_body=send_body)
        elif path == f"/api/libraries/{LIBRARY_ID}/series" and self.command in {"GET", "HEAD"}:
            self._json({"results": [_series(item)], "total": 1}, send_body=send_body)
        elif path == f"/api/libraries/{LIBRARY_ID}/authors" and self.command in {"GET", "HEAD"}:
            self._json({"authors": [_author()]}, send_body=send_body)
        elif path == f"/api/libraries/{LIBRARY_ID}/collections" and self.command in {"GET", "HEAD"}:
            self._json({"results": [_collection(item)], "total": 1}, send_body=send_body)
        elif path == f"/api/libraries/{LIBRARY_ID}/search" and self.command in {"GET", "HEAD"}:
            needle = query.get("q", [""])[0].casefold()
            matches = not needle or needle in TITLE.casefold() or needle in AUTHOR.casefold()
            self._json(
                {
                    "book": [{"libraryItem": item}] if matches else [],
                    "podcast": [],
                    "series": [],
                    "authors": [],
                },
                send_body=send_body,
            )
        elif path == "/api/me/items-in-progress" and self.command in {"GET", "HEAD"}:
            in_progress = copy.deepcopy(item)
            in_progress["progressLastUpdate"] = progress["lastUpdate"]
            self._json({"libraryItems": [in_progress]}, send_body=send_body)
        elif path == f"/api/items/{ITEM_ID}" and self.command in {"GET", "HEAD"}:
            self._json(item, send_body=send_body)
        elif path == f"/api/items/{ITEM_ID}/play" and self.command == "POST":
            payload = self._read_json()
            if payload is None:
                return
            required = {"mediaPlayer", "forceDirectPlay", "forceTranscode", "deviceInfo"}
            if not required.issubset(payload):
                self._error(HTTPStatus.BAD_REQUEST, "Incomplete play request")
                return
            self.state.set_session_closed(False)
            self._json(_playback_session(self.state.progress()))
        elif path == f"/api/session/{SESSION_ID}" and self.command in {"GET", "HEAD"}:
            self._json(_playback_session(progress), send_body=send_body)
        elif path == f"/api/session/{SESSION_ID}/sync" and self.command == "POST":
            payload = self._read_json()
            if payload is None:
                return
            self.state.update_progress(payload)
            self._json({})
        elif path == f"/api/session/{SESSION_ID}/close" and self.command == "POST":
            if self._read_json(allow_empty=True) is None:
                return
            self.state.set_session_closed(True)
            self._json({})
        elif path == "/api/session/local" and self.command == "POST":
            payload = self._read_json()
            if payload is None:
                return
            self.state.update_progress(payload)
            self._json({})
        elif path == "/api/session/local-all" and self.command == "POST":
            payload = self._read_json()
            if payload is None:
                return
            sessions = payload.get("sessions")
            if not isinstance(sessions, list):
                self._error(HTTPStatus.BAD_REQUEST, "sessions must be an array")
                return
            results = [
                {"id": str(session.get("id", "")), "success": True, "progressSynced": True}
                for session in sessions
                if isinstance(session, dict)
            ]
            self._json({"results": results})
        elif path == f"/api/me/progress/{ITEM_ID}" and self.command in {"GET", "HEAD"}:
            self._json(progress, send_body=send_body)
        elif path == f"/api/me/progress/{ITEM_ID}" and self.command == "PATCH":
            payload = self._read_json()
            if payload is None:
                return
            self.state.update_progress(payload)
            self._json({})
        else:
            self._error(HTTPStatus.NOT_FOUND, "Unknown fixture endpoint")

    def _is_authorized(self) -> bool:
        return self.headers.get("Authorization") == f"Bearer {ACCESS_TOKEN}"

    def _read_json(self, allow_empty: bool = False) -> dict[str, Any] | None:
        raw_length = self.headers.get("Content-Length", "0")
        try:
            length = int(raw_length)
        except ValueError:
            self._error(HTTPStatus.BAD_REQUEST, "Invalid Content-Length")
            return None
        if length < 0 or length > MAX_JSON_BODY_BYTES:
            self._error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "JSON body too large")
            return None
        body = self.rfile.read(length)
        if not body and allow_empty:
            return {}
        try:
            value = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._error(HTTPStatus.BAD_REQUEST, "Invalid JSON body")
            return None
        if not isinstance(value, dict):
            self._error(HTTPStatus.BAD_REQUEST, "JSON body must be an object")
            return None
        return value

    def _json(
        self,
        value: Any,
        status: HTTPStatus = HTTPStatus.OK,
        *,
        send_body: bool = True,
    ) -> None:
        body = json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if send_body:
            self.wfile.write(body)

    def _error(self, status: HTTPStatus, message: str) -> None:
        self._json({"error": message}, status)

    def _asset(
        self,
        asset_path: Path,
        content_type: str,
        *,
        send_body: bool,
        allow_range: bool,
    ) -> None:
        data = asset_path.read_bytes()
        start = 0
        end = len(data) - 1
        status = HTTPStatus.OK

        range_header = self.headers.get("Range") if allow_range else None
        if range_header:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
            if not match or (not match.group(1) and not match.group(2)):
                self._range_error(len(data))
                return
            if match.group(1):
                start = int(match.group(1))
                end = int(match.group(2)) if match.group(2) else len(data) - 1
            else:
                suffix_length = int(match.group(2))
                if suffix_length <= 0:
                    self._range_error(len(data))
                    return
                start = max(len(data) - suffix_length, 0)
            end = min(end, len(data) - 1)
            if start >= len(data) or start > end:
                self._range_error(len(data))
                return
            status = HTTPStatus.PARTIAL_CONTENT

        body = data[start : end + 1]
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "public, max-age=3600")
        self.send_header("X-Content-Type-Options", "nosniff")
        if allow_range:
            self.send_header("Accept-Ranges", "bytes")
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        self.end_headers()
        if send_body:
            self.wfile.write(body)

    def _range_error(self, size: int) -> None:
        body = b""
        self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        self.send_header("Content-Range", f"bytes */{size}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()

    def log_message(self, format: str, *args: object) -> None:
        # The fixture never logs headers or request bodies (which include tokens).
        print(f"[{self.log_date_time_string()}] {self.client_address[0]} {format % args}")


class ReviewFixtureServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int]) -> None:
        super().__init__(address, ReviewFixtureHandler)
        self.fixture_state = FixtureState()


def create_server(host: str = HOST, port: int = PORT) -> ReviewFixtureServer:
    for path in (COVER_PATH, AUDIO_PATH):
        if not path.is_file():
            raise FileNotFoundError(f"Fixture asset missing: {path}")
    return ReviewFixtureServer((host, port))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=PORT, help=f"listen port (default: {PORT})")
    args = parser.parse_args()

    server = create_server(HOST, args.port)
    print(f"ShelfDrive review fixture listening on http://{HOST}:{server.server_port}")
    print(f"Android Emulator URL: http://10.0.2.2:{server.server_port}")
    print(f"Reviewer credentials: {USERNAME} / {PASSWORD}")
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        print("\nStopping review fixture")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
