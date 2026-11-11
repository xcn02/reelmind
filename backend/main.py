"""ReelMind extraction-only optional Render service. No account cookies or AI keys."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel

app = FastAPI(title="ReelMind extraction API", version="0.5.0")
URL_PATH = re.compile(r"^/(?:reel|reels|p|tv)/[a-zA-Z0-9_-]+/?$")
MAX_AUDIO = 16 * 1024 * 1024
MAX_IMAGES = 8

class ExtractionRequest(BaseModel):
    url: str
    audio: bool = True
    images: bool = True

def canonical_url(raw: str) -> str:
    url = urlsplit(raw)
    if url.scheme != "https" or url.hostname not in {"instagram.com", "www.instagram.com"} or not URL_PATH.fullmatch(url.path):
        raise HTTPException(400, "Seules les publications HTTPS instagram.com sont admises")
    if url.username or url.password or url.port:
        raise HTTPException(400, "URL incorrecte")
    return f"https://www.instagram.com{url.path.rstrip('/')}"

def run(cmd, timeout=110):
    try:
        completed = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "Extraction trop longue")
    if completed.returncode != 0:
        raise HTTPException(422, f"Extraction indisponible : {completed.stderr[-240:]}")
    return completed.stdout

@app.get("/health")
def health():
    return {"ok": True}

@app.get("/version")
def version():
    import yt_dlp
    return {"api": "0.5.0", "yt_dlp": yt_dlp.version.__version__}

@app.post("/extract")
def extract(request: ExtractionRequest, authorization: str | None = Header(default=None)):
    token = os.environ.get("REELMIND_BACKEND_TOKEN", "")
    if not token:
        raise HTTPException(503, "Configure REELMIND_BACKEND_TOKEN sur Render")
    import secrets
    if not authorization or not secrets.compare_digest(authorization, f"Bearer {token}"):
        raise HTTPException(401, "Jeton invalide")
    url = canonical_url(request.url)
    with tempfile.TemporaryDirectory(prefix="reelmind_") as directory:
        root = Path(directory)
        raw = run(["yt-dlp", "--ignore-config", "--no-playlist", "--skip-download", "--dump-single-json", url], timeout=75)
        try:
            metadata = json.loads(raw)
        except ValueError:
            raise HTTPException(422, "Métadonnées illisibles")
        entries = metadata.get("entries") or []
        kind = "carousel" if len(entries) > 1 or urlsplit(url).path.startswith('/p/') else "video"
        response = {
            "title": str(metadata.get("title") or "")[:180],
            "caption": str(metadata.get("description") or "")[:20000],
            "author": str(metadata.get("uploader") or metadata.get("channel") or "")[:120],
            "thumbnail": str(metadata.get("thumbnail") or ""),
            "kind": kind,
            "audio_base64": "",
            "images_base64": [],
        }
        if request.audio and kind == "video":
            # Download audio only; --max-filesize prevents unnecessary large downloads.
            run(["yt-dlp", "--ignore-config", "--no-playlist", "--max-filesize", "35M", "-f", "bestaudio/best",
                 "-x", "--audio-format", "mp3", "--audio-quality", "7", "-o", str(root / "track.%(ext)s"), url], timeout=115)
            paths = list(root.glob("track.*"))
            if paths and paths[0].stat().st_size <= MAX_AUDIO:
                response["audio_base64"] = base64.b64encode(paths[0].read_bytes()).decode("ascii")
        if request.images and kind == "carousel":
            # gallery-dl can handle public image posts, though access is not guaranteed.
            try:
                run(["gallery-dl", "--config-ignore", "--directory", str(root / "images"),
                     "--range", "1-8", url], timeout=85)
            except HTTPException:
                pass
            files = [x for x in (root / "images").rglob("*") if x.suffix.lower() in {".jpg", ".jpeg", ".png"}]
            for path in sorted(files)[:MAX_IMAGES]:
                if path.stat().st_size <= 3 * 1024 * 1024:
                    # JPEG only; GIF/PNG are excluded for Gemini MIME correctness.
                    if path.suffix.lower() not in {".jpg", ".jpeg"}:
                        continue
                    response["images_base64"].append(base64.b64encode(path.read_bytes()).decode("ascii"))
        return response
