# flet-media-scanner

[![PyPI version](https://img.shields.io/pypi/v/flet-media-scanner)](https://pypi.org/project/flet-media-scanner/)
[![PyPI downloads](https://img.shields.io/pypi/dm/flet-media-scanner)](https://pypi.org/project/flet-media-scanner/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)

A [Flet](https://flet.dev) extension for Android **MediaStore** integration — save, list, and delete **videos**, **audio**, and **images** in the device Gallery / Music / Pictures without requiring broad storage permissions.

Built entirely with **native Kotlin** using Android's `MediaStore` APIs directly — no third-party Flutter packages required.

> **Author:** [Fazi Gondal](https://github.com/fazi-gondal) · [nextinpk@gmail.com](mailto:nextinpk@gmail.com)

---

## Requirements

| Requirement | Version |
|-------------|---------|
| **Platform** | Android only (no-op on other platforms) |
| **Android API** | 23+ (minSdk 23) |
| **Flet** | >= 0.86.0 |
| **Python** | >= 3.12 |

---

## Architecture

```
Python API  →  Dart bridge  →  Kotlin (native MediaStore)
```

Unlike typical Flet extensions that wrap Flutter packages, this extension bypasses the Flutter package ecosystem entirely and calls Android's `MediaStore` APIs directly from Kotlin — giving you a lighter, faster, and fully self-contained plugin.

---

## Supported Formats

### 🎬 Video → `Movies/<album>/`
| Extension | MIME Type |
|-----------|-----------|
| `.mp4` | `video/mp4` |
| `.mkv` | `video/x-matroska` |
| `.webm` | `video/webm` |
| `.avi` | `video/x-msvideo` |
| `.mov` | `video/quicktime` |
| `.3gp` | `video/3gpp` |
| `.ts` | `video/mp2t` |
| `.flv` | `video/x-flv` |

### 🎵 Audio → `Music/<album>/`
| Extension | MIME Type |
|-----------|-----------|
| `.mp3` | `audio/mpeg` |
| `.m4a` | `audio/mp4` |
| `.aac` | `audio/aac` |
| `.flac` | `audio/flac` |
| `.opus` | `audio/opus` |
| `.wav` | `audio/wav` |
| `.ogg` | `audio/ogg` |
| `.wma` | `audio/x-ms-wma` |

### 🖼 Image → `Pictures/<album>/`
| Extension | MIME Type |
|-----------|-----------|
| `.jpg` / `.jpeg` | `image/jpeg` |
| `.png` | `image/png` |
| `.gif` | `image/gif` |
| `.webp` | `image/webp` |
| `.bmp` | `image/bmp` |
| `.heic` | `image/heic` |
| `.heif` | `image/heif` |
| `.avif` | `image/avif` |
| `.tiff` | `image/tiff` |

> MIME type is resolved automatically from the file extension — no manual configuration needed.

---

## Installation

```bash
pip install flet-media-scanner
```

---

## Setup

Register the extension in your app's `pyproject.toml`:

```toml
[tool.flet.extensions]
flet_media_scanner = "flet_media_scanner.Extension"
```

---

## Usage

```python
import os
import flet as ft
from flet_media_scanner import MediaScanner, SaveResult

async def main(page: ft.Page):
    scanner = MediaScanner()
    storage = ft.StoragePaths()
    page.services.extend([scanner, storage])
    page.update()

    # Use ft.StoragePaths to resolve the correct cache directory (cross-device safe)
    cache_dir = await storage.get_application_cache_directory()

    # ── Video ──────────────────────────────────────────────────────────────
    result: SaveResult = await scanner.save_video(
        os.path.join(cache_dir, "video.mp4"),
        file_name="my_video.mp4",
        album="MyApp",
    )
    if result.success:
        print(f"Saved to Gallery: {result.content_uri}")

    for v in await scanner.list_videos(album="MyApp"):
        print(v["display_name"], v["content_uri"])

    # ── Audio ──────────────────────────────────────────────────────────────
    result = await scanner.save_audio(
        os.path.join(cache_dir, "song.mp3"),
        file_name="song.mp3",
        album="MyApp",
    )
    if result.success:
        print(f"Saved to Music: {result.content_uri}")

    for t in await scanner.list_audio(album="MyApp"):
        print(t["display_name"], t["mime_type"])

    # ── Image ──────────────────────────────────────────────────────────────
    result = await scanner.save_image(
        os.path.join(cache_dir, "photo.jpg"),
        file_name="photo.jpg",
        album="MyApp",
    )
    if result.success:
        print(f"Saved to Pictures: {result.content_uri}")

    for img in await scanner.list_images(album="MyApp"):
        print(img["display_name"], img["size"])

    # ── Delete (works for any media type) ──────────────────────────────────
    deleted = await scanner.delete_media("content://media/external/video/media/123")
    print("Deleted:", deleted)

ft.run(main)
```

---

## API Reference

### `MediaScanner` (Service)

Add to `page.services` before calling any methods.

---

#### 🎬 Video

| Method | Description |
|--------|-------------|
| `await save_video(file_path, file_name=None, album="MyApp") → SaveResult` | Copies a private app video into `Movies/<album>` via Android MediaStore |
| `await list_videos(album="MyApp") → list[dict]` | Returns all videos saved to the album |

#### 🎵 Audio

| Method | Description |
|--------|-------------|
| `await save_audio(file_path, file_name=None, album="MyApp") → SaveResult` | Copies a private app audio file into `Music/<album>` via Android MediaStore |
| `await list_audio(album="MyApp") → list[dict]` | Returns all audio files saved to the album |

#### 🖼 Image

| Method | Description |
|--------|-------------|
| `await save_image(file_path, file_name=None, album="MyApp") → SaveResult` | Copies a private app image into `Pictures/<album>` via Android MediaStore |
| `await list_images(album="MyApp") → list[dict]` | Returns all images saved to the album |

#### 🗑 Delete

| Method | Description |
|--------|-------------|
| `await delete_media(content_uri) → bool` | Deletes any MediaStore item (video, audio, or image) by its `content://` URI |
| `await delete_video(content_uri) → bool` | Alias for `delete_media()` — kept for backwards compatibility |

---

#### Common `save_*` parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `file_path` | `str` | — | Absolute path to the source file (use `ft.StoragePaths` to resolve) |
| `file_name` | `str \| None` | `None` | Display name in Gallery (defaults to the source file basename) |
| `album` | `str` | `"MyApp"` | Subfolder inside `Movies/`, `Music/`, or `Pictures/` |

---

### `SaveResult` (dataclass)

Returned by all `save_*` methods.

| Field | Type | Description |
|-------|------|-------------|
| `success` | `bool` | `True` if the file was saved successfully |
| `content_uri` | `str` | Android MediaStore `content://` URI |
| `display_name` | `str` | File name as shown in the Gallery/Files app |
| `mime_type` | `str` | e.g. `video/mp4`, `audio/mpeg`, `image/jpeg` |
| `relative_path` | `str` | e.g. `Movies/MyApp/` |
| `source_path` | `str` | Original source file path |
| `size` | `int` | File size in bytes |
| `error` | `str` | Error message if `success=False` |

---

### List item fields

Each dict returned by `list_videos()`, `list_audio()`, and `list_images()` contains:

`content_uri` · `display_name` · `mime_type` · `relative_path` · `size` · `date_added` · `date_modified`

---

## Changelog

| Version | Changes |
|---------|---------|
| **1.0.1** | Updated README with badges, author info, architecture overview, and full changelog |
| **1.0.0** | Stable release — removed `media_scanner` Flutter package, full native Kotlin only |
| **0.86.5** | Cleaned up `pubspec.yaml`, removed unused Flutter deps |
| **0.86.4** | Updated README to use `ft.StoragePaths` for cross-device safe paths |
| **0.86.3** | Added audio (MP3, M4A, AAC, FLAC, Opus, WAV) and image support |
| **0.86.2** | Added MKV and WebM video container support |
| **0.86.1** | Development status → Production/Stable, updated project URLs |

---

## License

MIT © [Fazi Gondal](https://github.com/fazi-gondal)
