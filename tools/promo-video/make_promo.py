#!/usr/bin/env python3
"""Generate a promo video from a scene script.

Pipeline: promo.json -> Fish Audio TTS -> timed ASS subtitles -> ffmpeg composites.

Requires ffmpeg/ffprobe on PATH and a Fish Audio key in FISH_API_KEY (or --api-key).

Scene kinds:
  solid   - title card on a flat colour
  phone   - portrait screenshot on the right, text block on the left
  desktop - landscape screenshot centred over its own blurred backdrop

Optional per-scene keys: image, narration, tts_text (text for TTS when it differs
from the on-screen caption), heading, caption, effect (in|out|none), lead, tail,
trim (pixels to shave off the source: {top,bottom,left,right}).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

FISH_TTS_URL = "https://api.fish.audio/v1/tts"
DEFAULT_MODEL = "s2.1-pro-free"


def fail(message: str) -> "NoReturn":  # noqa: F821
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def run(cmd: list[str], cwd: Path | None = None) -> str:
    proc = subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout or "").strip()
        raise RuntimeError(f"command failed ({proc.returncode})\n  {' '.join(cmd)}\n{tail[-3000:]}")
    return proc.stdout


def require_tools() -> None:
    for tool in ("ffmpeg", "ffprobe"):
        try:
            run([tool, "-version"])
        except (RuntimeError, FileNotFoundError):
            fail(f"{tool} was not found on PATH")


def media_duration(path: Path) -> float:
    out = run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(path),
        ]
    )
    return float(out.strip())


def synthesize(text: str, dest: Path, voice: dict, api_key: str) -> None:
    import urllib.error
    import urllib.request

    body = {
        "text": text,
        "format": voice.get("format", "mp3"),
        "mp3_bitrate": voice.get("mp3_bitrate", 128),
        "sample_rate": 44100,
        "normalize": True,
        "prosody": {
            "speed": float(voice.get("speed", 1.0)),
            "normalize_loudness": True,
        },
    }
    if voice.get("language"):
        body["language"] = voice["language"]
    if voice.get("reference_id"):
        body["reference_id"] = voice["reference_id"]

    request = urllib.request.Request(
        FISH_TTS_URL,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "model": voice.get("model", DEFAULT_MODEL),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read()
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"Fish Audio returned HTTP {exc.code}: {detail[:600]}") from None
    if not payload:
        raise RuntimeError("Fish Audio returned an empty body")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(payload)


def cache_key(text: str, voice: dict) -> str:
    seed = json.dumps(
        {
            "text": text,
            "model": voice.get("model", DEFAULT_MODEL),
            "speed": voice.get("speed", 1.0),
            "format": voice.get("format", "mp3"),
            "reference_id": voice.get("reference_id"),
        },
        sort_keys=True,
        ensure_ascii=False,
    )
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()[:16]


def ass_time(seconds: float) -> str:
    total = max(0.0, seconds)
    hours, rest = divmod(total, 3600)
    minutes, secs = divmod(rest, 60)
    return f"{int(hours)}:{int(minutes):02d}:{int(secs):02d}.{int(round((secs - int(secs)) * 100)):02d}"


def ass_text(text: str) -> str:
    return text.replace("\\", "\\\\").replace("{", "(").replace("}", ")").replace("\n", "\\N")


def heading_override(kind: str, width: int, height: int, style: dict) -> str:
    if kind == "phone":
        x = int(style.get("phoneTextX", 110))
        y = int(height * float(style.get("phoneTextYRatio", 0.46)))
        size = int(style.get("phoneHeadingSize", 92))
        return f"{{\\an4\\pos({x},{y})\\fs{size}}}"
    if kind == "desktop":
        x = int(style.get("desktopTextX", 110))
        y = int(style.get("desktopTextY", 96))
        size = int(style.get("desktopHeadingSize", 84))
        return f"{{\\an7\\pos({x},{y})\\fs{size}}}"
    return ""


def build_ass(scenes: list[dict], style: dict, width: int, height: int) -> str:
    font = style.get("font", "Microsoft YaHei")
    muted = style.get("muted", "&H00B8B8B8&")
    narration_size = int(style.get("narrationSize", 60))
    title_size = int(style.get("titleSize", 104))
    subtitle_size = int(style.get("subtitleSize", 44))
    margin_v = int(style.get("marginV", 88))

    lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        f"PlayResX: {width}",
        f"PlayResY: {height}",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
        "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        f"Style: Narration,{font},{narration_size},&H00FFFFFF,&H000000FF,&H00101010,&H96000000,"
        f"0,0,0,0,100,100,1,0,1,4,2,2,150,150,{margin_v},1",
        f"Style: Heading,{font},{title_size},&H00FFFFFF,&H000000FF,&H00101010,&H96000000,"
        f"1,0,0,0,100,100,2,0,1,4,3,5,80,80,80,1",
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]

    for scene in scenes:
        start = scene["start"]
        duration = scene["duration"]
        heading = scene.get("heading")
        caption = scene.get("caption")
        override = heading_override(scene["kind"], width, height, style)

        if heading:
            text = f"{ass_text(heading)}"
            if caption:
                size = f"{{\\fs{subtitle_size}\\c{muted}}}"
                text += f"\\N{size}{ass_text(caption)}"
            lines.append(
                f"Dialogue: 0,{ass_time(start)},{ass_time(start + duration)},Heading,,0,0,0,,"
                f"{override}{text}"
            )

        narration = scene.get("narration")
        if narration:
            # A phone scene keeps the narration clear of the device crop.
            margin_r = int(
                style.get("phoneNarrationRight", 640)
                if scene["kind"] == "phone"
                else style.get("narrationMarginR", 150)
            )
            lines.append(
                f"Dialogue: 0,{ass_time(start + scene['lead'])},"
                f"{ass_time(start + scene['lead'] + scene['audio'])},"
                f"Narration,,0,{margin_r},0,,{ass_text(narration)}"
            )

    return "\n".join(lines) + "\n"


def zoom_expr(effect: str, frames: int) -> str:
    step = 0.10 / max(1, frames)
    if effect == "none":
        return "1.0"
    if effect == "out":
        return f"max(1.10-{step:.6f}*on,1.0)"
    return f"min(1.0+{step:.6f}*on,1.10)"


def trim_prefix(scene: dict) -> str:
    trim = scene.get("trim") or {}
    top = int(trim.get("top", 0))
    bottom = int(trim.get("bottom", 0))
    left = int(trim.get("left", 0))
    right = int(trim.get("right", 0))
    if not any((top, bottom, left, right)):
        return ""
    return f"crop=iw-{left + right}:ih-{top + bottom}:{left}:{top},"


def build_scene(scene: dict, cfg: dict, narration_path: Path, out_path: Path) -> None:
    width = int(cfg["width"])
    height = int(cfg["height"])
    fps = int(cfg["fps"])
    duration = scene["duration"]
    style = cfg.get("style", {})
    frames = max(2, int(round(duration * fps)))

    zoompan = (
        f"zoompan=z='{zoom_expr(scene.get('effect', 'in'), frames)}'"
        f":x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
        f":d={frames}:s={width}x{height}:fps={fps}"
    )
    lead_ms = int(round(float(scene.get("lead", 0.5)) * 1000))
    audio_filter = (
        f"aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,"
        f"adelay={lead_ms}:all=1,apad,atrim=0:{duration:.3f}"
    )
    trim = trim_prefix(scene)
    backdrop = (
        f"{trim}scale={width}:{height}:force_original_aspect_ratio=increase:flags=lanczos,"
        f"crop={width}:{height},gblur=sigma=54,eq=brightness=-0.46:saturation=0.5,"
        f"{zoompan},setsar=1"
    )
    kind = scene["kind"]
    image = scene.get("image")

    if kind == "solid":
        color = style.get("solidColor", "0x101418")
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", f"color=c={color}:s={width}x{height}:r={fps}:d={duration:.3f}",
            "-i", str(narration_path),
            "-filter_complex",
            f"[0:v]{zoompan},setsar=1,format=yuv420p[vout];[1:a]{audio_filter}[aout]",
        ]
    elif kind == "desktop":
        fg_h = int(style.get("desktopHeight", 800))
        video = (
            f"[0:v]{backdrop}[bg];"
            f"[1:v]{trim}scale=-2:{fg_h}:flags=lanczos[fg];"
            f"[bg][fg]overlay=(W-w)/2:{(height - fg_h) // 2 + 60}:eof_action=repeat,"
            f"setsar=1,format=yuv420p[vout];"
            f"[2:a]{audio_filter}[aout]"
        )
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(image), "-i", str(image), "-i", str(narration_path),
            "-filter_complex", video,
        ]
    elif kind == "phone":
        phone_h = int(style.get("phoneHeight", 920))
        right = int(style.get("phoneRightMargin", 150))
        video = (
            f"[0:v]{backdrop}[bg];"
            f"[1:v]{trim}scale=-2:{phone_h}:flags=lanczos[fg];"
            f"[bg][fg]overlay={width}-w-{right}:(H-h)/2:eof_action=repeat,"
            f"setsar=1,format=yuv420p[vout];"
            f"[2:a]{audio_filter}[aout]"
        )
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(image), "-i", str(image), "-i", str(narration_path),
            "-filter_complex", video,
        ]
    else:
        raise RuntimeError(f"unknown scene kind: {kind}")

    cmd += [
        "-map", "[vout]", "-map", "[aout]",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", str(cfg.get("crf", 18)),
        "-pix_fmt", "yuv420p", "-r", str(fps),
        "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
        "-t", f"{duration:.3f}",
        str(out_path),
    ]
    run(cmd)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the CodexRM promo video.")
    parser.add_argument("--config", default=str(Path(__file__).with_name("promo.json")),
                        help="scene script (default: promo.json beside this script)")
    parser.add_argument("--repo", default=None, help="repository root (default: two levels above)")
    parser.add_argument("--build", default=".work/promo-video/build", help="intermediate directory")
    parser.add_argument("--output", default=None, help="override the output video path")
    parser.add_argument("--api-key", default=None, help="Fish Audio key (default: FISH_API_KEY)")
    parser.add_argument("--only", default=None, help="comma separated scene ids to build")
    parser.add_argument("--reuse-tts", action="store_true",
                        help="reuse cached narration audio when the text is unchanged")
    parser.add_argument("--skip-final", action="store_true", help="stop before the subtitle burn pass")
    args = parser.parse_args()

    require_tools()

    config_path = Path(args.config).resolve()
    if not config_path.is_file():
        fail(f"scene script not found: {config_path}")
    cfg = json.loads(config_path.read_text(encoding="utf-8"))

    repo = Path(args.repo).resolve() if args.repo else Path(__file__).resolve().parents[2]
    build_dir = (repo / args.build).resolve()
    build_dir.mkdir(parents=True, exist_ok=True)

    api_key = args.api_key or os.environ.get("FISH_API_KEY", "").strip()
    if not api_key:
        fail("set FISH_API_KEY or pass --api-key for Fish Audio TTS")

    voice = cfg.get("voice", {})
    scenes = cfg.get("scenes", [])
    if not scenes:
        fail("the scene script has no scenes")

    only = {item.strip() for item in args.only.split(",")} if args.only else None
    tts_dir = build_dir / "tts"
    tts_dir.mkdir(parents=True, exist_ok=True)

    timeline: list[dict] = []
    cursor = 0.0
    print(f"script: {config_path.name}  scenes: {len(scenes)}")

    for scene in scenes:
        scene_id = scene["id"]
        if only and scene_id not in only:
            continue

        if scene.get("image"):
            image_path = (repo / scene["image"]).resolve()
            if not image_path.is_file():
                fail(f"scene {scene_id}: image not found: {image_path}")
            scene["image"] = str(image_path)

        narration_text = scene.get("tts_text") or scene.get("narration") or ""
        narration_path = tts_dir / f"{scene_id}.mp3"

        if narration_text:
            stamp = tts_dir / f"{scene_id}.{cache_key(narration_text, voice)}.mp3"
            if args.reuse_tts and stamp.is_file():
                print(f"  [{scene_id}] tts cached")
            else:
                print(f"  [{scene_id}] tts {len(narration_text)} chars")
                synthesize(narration_text, stamp, voice, api_key)
            if narration_path != stamp:
                if narration_path.exists():
                    narration_path.unlink()
                stamp.replace(narration_path)
            audio_seconds = media_duration(narration_path)
        else:
            run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
                "-t", "2.0", "-c:a", "libmp3lame", "-q:a", "4", str(narration_path),
            ])
            audio_seconds = 2.0

        lead = float(scene.get("lead", 0.5))
        tail = float(scene.get("tail", 0.9))
        duration = round(lead + audio_seconds + tail, 3)

        scene["start"] = round(cursor, 3)
        scene["lead"] = lead
        scene["audio"] = round(audio_seconds, 3)
        scene["duration"] = duration
        cursor = round(cursor + duration, 3)
        timeline.append(scene)
        print(f"  [{scene_id}] {audio_seconds:.2f}s narration -> {duration:.2f}s scene")

    if not timeline:
        fail("no scenes selected")

    print(f"total: {cursor:.2f}s ({cursor / 60:.2f} min)")

    ass_path = build_dir / "subs.ass"
    ass_path.write_text(
        build_ass(timeline, cfg.get("style", {}), int(cfg["width"]), int(cfg["height"])),
        encoding="utf-8",
    )
    print(f"subtitles: {ass_path}")

    for stale in build_dir.glob("scene_*.mp4"):
        stale.unlink()

    segments: list[Path] = []
    for index, scene in enumerate(timeline):
        segment = build_dir / f"scene_{index:02d}_{scene['id']}.mp4"
        print(f"  render {scene['id']} ({scene['duration']:.2f}s)")
        build_scene(scene, cfg, tts_dir / f"{scene['id']}.mp3", segment)
        segments.append(segment)

    listing = build_dir / "concat.txt"
    listing.write_text("".join(f"file '{seg.name}'\n" for seg in segments), encoding="utf-8")
    joined = build_dir / "joined.mp4"
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(joined)])

    if args.skip_final:
        print(f"joined (no subtitles): {joined}")
        return

    output = Path(args.output).resolve() if args.output else (repo / cfg["output"]).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    print(f"final pass -> {output}")
    run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-i", str(joined),
         "-vf", "subtitles=subs.ass",
         "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
         "-c:v", "libx264", "-preset", "medium", "-crf", str(cfg.get("finalCrf", 20)),
         "-pix_fmt", "yuv420p", "-r", str(int(cfg["fps"])),
         "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
         "-movflags", "+faststart",
         str(output)],
        cwd=build_dir,
    )
    print(f"done: {output}  {output.stat().st_size / (1024 * 1024):.1f} MB  "
          f"{media_duration(output):.1f}s")


if __name__ == "__main__":
    main()
