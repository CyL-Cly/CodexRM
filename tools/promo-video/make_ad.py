#!/usr/bin/env python3
"""Render a vertical launch ad (1280x2560) from real adb screenshots.

Every frame is composed in PIL so the motion uses real easing curves, camera
pushes into UI regions, mask reveals and parallax - the things a filter graph
cannot express. Frames stream straight into ffmpeg.

Usage:
    set FISH_API_KEY
    python tools/promo-video/make_ad.py                  # full render
    python tools/promo-video/make_ad.py --no-audio --stills 3,14,25
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import subprocess
import sys
import urllib.error
import urllib.request
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1280, 2560
FPS = 30

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / ".work" / "promo-ad"
SCREENS = WORK / "screens"
BUILD = WORK / "build"
OUT = WORK / "codexrm-ad.mp4"

FONT_DIR = Path(os.environ.get("WINDIR", r"D:\Windows")) / "Fonts"
FONT_DISPLAY = FONT_DIR / "msyhbd.ttc"
FONT_BODY = FONT_DIR / "msyh.ttc"

INK = (8, 10, 14)
AMBER = (240, 162, 60)
BLUE = (76, 125, 255)
WHITE = (255, 255, 255)
MUTED = (152, 160, 174)

# The device lives in this vertical band; text owns the space above and below.
BAND_TOP, BAND_BOTTOM, BAND_FADE = 452, 2306, 150


# ---------------------------------------------------------------- easing ----

def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if v < lo else hi if v > hi else v


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def ease_out_expo(t: float) -> float:
    t = clamp(t)
    return 1.0 if t >= 1.0 else 1.0 - 2.0 ** (-10.0 * t)


def ease_out_cubic(t: float) -> float:
    t = clamp(t)
    return 1.0 - (1.0 - t) ** 3


def ease_in_out_cubic(t: float) -> float:
    t = clamp(t)
    return 4 * t ** 3 if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2


def ease_out_back(t: float, s: float = 1.55) -> float:
    t = clamp(t) - 1.0
    return 1.0 + (s + 1.0) * t ** 3 + s * t ** 2


def span(t: float, start: float, dur: float) -> float:
    if dur <= 0:
        return 1.0 if t >= start else 0.0
    return clamp((t - start) / dur)


# --------------------------------------------------------------- sprites ----

class TextSprite:
    _cache: dict = {}

    @classmethod
    def get(cls, value: str, fnt: ImageFont.FreeTypeFont, fill, tracking: int = 0) -> Image.Image:
        key = (value, id(fnt), fill, tracking)
        hit = cls._cache.get(key)
        if hit is not None:
            return hit
        ascent, descent = fnt.getmetrics()
        if tracking:
            adv = [fnt.getlength(ch) + tracking for ch in value]
            width = int(sum(adv) - tracking) + 8
            img = Image.new("RGBA", (max(width, 1), ascent + descent + 8), (0, 0, 0, 0))
            draw = ImageDraw.Draw(img)
            x = 4.0
            for ch, step in zip(value, adv):
                draw.text((x, 4), ch, font=fnt, fill=fill + (255,))
                x += step
        else:
            width = int(fnt.getlength(value)) + 8
            img = Image.new("RGBA", (max(width, 1), ascent + descent + 8), (0, 0, 0, 0))
            ImageDraw.Draw(img).text((4, 4), value, font=fnt, fill=fill + (255,))
        cls._cache[key] = img
        return img


_fonts: dict = {}


def font(path: Path, size: int) -> ImageFont.FreeTypeFont:
    key = (str(path), size)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(str(path), size)
    return _fonts[key]


def blit(
    canvas: Image.Image,
    value: str,
    xy: tuple[float, float],
    fnt: ImageFont.FreeTypeFont,
    fill,
    alpha: float = 1.0,
    anchor: str = "lt",
    tracking: int = 0,
) -> None:
    """anchor: l/c/r for x, t/m/b for y."""
    if alpha <= 0.004 or not value:
        return
    sprite = TextSprite.get(value, fnt, fill, tracking)
    ox = {"l": 0.0, "c": 0.5, "m": 0.5, "r": 1.0}[anchor[0]]
    oy = {"t": 0.0, "m": 0.5, "c": 0.5, "b": 1.0}[anchor[1]]
    x = xy[0] - sprite.width * ox
    y = xy[1] - sprite.height * oy
    if alpha < 1.0:
        sprite = sprite.copy()
        sprite.putalpha(sprite.getchannel("A").point(lambda v: int(v * alpha)))
    # Sprites carry 4px of padding so glyph overhang is not clipped.
    canvas.alpha_composite(sprite, (int(x - 4), int(y - 4)))


# ------------------------------------------------------------ background ----

_BG_SCALE = 4
_bg_w, _bg_h = W // _BG_SCALE, H // _BG_SCALE
_gy, _gx = np.mgrid[0:_bg_h, 0:_bg_w].astype(np.float32)
_gx /= _bg_w
_gy /= _bg_h
_rng = np.random.default_rng(7)
_grain = _rng.normal(0.0, 1.5, (_bg_h, _bg_w, 1)).astype(np.float32)


def background(t: float) -> Image.Image:
    ax = 0.24 + 0.07 * math.sin(t * 0.31)
    ay = 0.15 + 0.04 * math.cos(t * 0.27)
    d1 = np.sqrt((_gx - ax) ** 2 + (_gy - ay) ** 2)
    pool1 = np.clip(1.0 - d1 / 0.58, 0.0, 1.0) ** 2.2

    bx = 0.80 + 0.05 * math.cos(t * 0.23 + 1.1)
    by = 0.88 + 0.04 * math.sin(t * 0.19)
    d2 = np.sqrt((_gx - bx) ** 2 + (_gy - by) ** 2)
    pool2 = np.clip(1.0 - d2 / 0.52, 0.0, 1.0) ** 2.6

    dx, dy = _gx - 0.5, _gy - 0.5
    vignette = np.clip(1.0 - (dx * dx + dy * dy) * 1.30, 0.0, 1.0) ** 1.6

    base = np.array(INK, dtype=np.float32)
    rgb = np.empty((_bg_h, _bg_w, 3), dtype=np.float32)
    for i in range(3):
        rgb[..., i] = base[i] + pool1 * AMBER[i] * 0.34 + pool2 * BLUE[i] * 0.20
    rgb *= (0.34 + 0.66 * vignette)[..., None]
    rgb += _grain
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").resize((W, H), Image.BILINEAR)


# ----------------------------------------------------------------- phone ----

_phone_source: dict[str, Image.Image] = {}
_shadow_base: Image.Image | None = None
CORNER = 56
BORDER = 3

_band_ramp_cache: dict = {}


def band_ramp(top: int, height: int) -> np.ndarray:
    key = (top, height)
    hit = _band_ramp_cache.get(key)
    if hit is not None:
        return hit
    ys = np.arange(top, top + height, dtype=np.float32)
    f = np.ones_like(ys)
    f[ys < BAND_TOP] = 0.0
    top_in = (ys >= BAND_TOP) & (ys < BAND_TOP + 110)
    f[top_in] = (ys[top_in] - BAND_TOP) / 110.0
    bottom_fade = ys > BAND_BOTTOM - BAND_FADE
    f[bottom_fade] = np.clip((BAND_BOTTOM - ys[bottom_fade]) / BAND_FADE, 0.0, 1.0)
    f[ys > BAND_BOTTOM] = 0.0
    ramp = f[:, None]
    _band_ramp_cache[key] = ramp
    return ramp


def load_phone(name: str) -> Image.Image:
    if name in _phone_source:
        return _phone_source[name]
    src = Image.open(SCREENS / f"{name}.png").convert("RGB")
    body = Image.new("RGBA", (src.width + BORDER * 2, src.height + BORDER * 2), (0, 0, 0, 0))
    body.paste(src, (BORDER, BORDER))
    draw = ImageDraw.Draw(body)
    draw.rounded_rectangle((0, 0, body.width - 1, body.height - 1),
                           radius=CORNER, outline=(126, 138, 158, 96), width=BORDER)
    mask = Image.new("L", body.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, body.width - 1, body.height - 1),
                                           radius=CORNER, fill=255)
    body.putalpha(mask)
    _phone_source[name] = body
    return body


def phone_shadow() -> Image.Image:
    global _shadow_base
    if _shadow_base is None:
        pad = 170
        img = Image.new("L", (W + pad * 2, H + pad * 2), 0)
        ImageDraw.Draw(img).rounded_rectangle((pad, pad, pad + 900, pad + 1900),
                                              radius=CORNER + 18, fill=135)
        _shadow_base = img.filter(ImageFilter.GaussianBlur(62))
    return _shadow_base


def place_phone(
    canvas: Image.Image,
    name: str,
    focus: tuple[float, float],
    height: float,
    anchor: tuple[float, float],
    alpha: float = 1.0,
    reveal: float = 1.0,
) -> tuple[float, float, float]:
    """Returns (left, top, scale) so callers can map source points to canvas."""
    body = load_phone(name)
    scale = height / body.height
    width = body.width * scale
    left = anchor[0] - (focus[0] + BORDER) * scale
    top = anchor[1] - (focus[1] + BORDER) * scale
    if alpha <= 0.004:
        return left, top, scale

    sprite = body.resize((max(1, int(round(width))), max(1, int(round(height)))), Image.BILINEAR)

    alpha_np = np.array(sprite.getchannel("A"), dtype=np.float32)
    if reveal < 1.0:
        visible = sprite.height * clamp(reveal)
        y0 = (sprite.height - visible) / 2
        rows = np.arange(sprite.height, dtype=np.float32)[:, None]
        alpha_np *= np.clip((rows - y0) / 1.5, 0, 1) * np.clip((y0 + visible - rows) / 1.5, 0, 1)
    if alpha < 1.0:
        alpha_np *= alpha
    alpha_np *= band_ramp(int(round(top)), sprite.height)
    sprite.putalpha(Image.fromarray(np.clip(alpha_np, 0, 255).astype(np.uint8), "L"))

    shadow = phone_shadow()
    sh = shadow.resize((max(1, int(shadow.width * scale)), max(1, int(shadow.height * scale))),
                       Image.BILINEAR)
    sh_np = np.array(sh, dtype=np.float32) * (0.9 * alpha if alpha < 1.0 else 0.9)
    sh_np *= band_ramp(int(round(top - (sh.height - sprite.height) / 2)), sh.height)
    shadow_img = Image.fromarray(np.clip(sh_np, 0, 255).astype(np.uint8), "L")

    canvas.paste((0, 0, 0), (int(left - (sh.width - sprite.width) / 2),
                             int(top - (sh.height - sprite.height) / 2)), shadow_img)
    canvas.paste(sprite, (int(left), int(top)), sprite)
    return left, top, scale


# --------------------------------------------------------------- overlays ----

def ring(canvas: Image.Image, box, color=AMBER, width: int = 6, alpha: float = 1.0, radius: int = 24) -> None:
    if alpha <= 0.004:
        return
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    draw.rounded_rectangle(box, radius=radius, outline=color + (int(215 * alpha),), width=width)
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(15)))
    canvas.alpha_composite(layer)


_scrim_cache: dict = {}


def bottom_scrim(alpha: float) -> Image.Image:
    key = round(alpha, 2)
    if key in _scrim_cache:
        return _scrim_cache[key]
    height = 420
    ramp = np.linspace(0.0, 1.0, height, dtype=np.float32) ** 1.7
    rgba = np.zeros((height, W, 4), dtype=np.uint8)
    rgba[..., 3] = (ramp[:, None] * 255 * 0.92 * alpha).astype(np.uint8)
    img = Image.fromarray(rgba, "RGBA")
    _scrim_cache[key] = img
    return img


_wrap_cache: dict = {}


def wrap(value: str, fnt: ImageFont.FreeTypeFont, max_width: float) -> list[str]:
    key = (value, id(fnt), max_width)
    hit = _wrap_cache.get(key)
    if hit is not None:
        return hit
    lines, cur = [], ""
    for ch in value:
        if cur and fnt.getlength(cur + ch) > max_width:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    _wrap_cache[key] = lines
    return lines


def caption(canvas: Image.Image, value: str, alpha: float, rise: float) -> None:
    if alpha <= 0.004 or not value:
        return
    fnt = font(FONT_BODY, 44)
    lines = wrap(value, fnt, 1040)
    canvas.alpha_composite(bottom_scrim(alpha), (0, H - 420))
    total = len(lines) * 62
    y = 2420 - total / 2 - rise
    for line in lines:
        blit(canvas, line, (W / 2, y), fnt, WHITE, alpha, anchor="mt")
        y += 62


def kicker(canvas: Image.Image, label: str, index: str, alpha: float, rise: float) -> None:
    if alpha <= 0.004:
        return
    y = 158 - rise
    blit(canvas, index, (100, y), font(FONT_DISPLAY, 32), AMBER, alpha, tracking=3)
    blit(canvas, label, (176, y + 1), font(FONT_BODY, 32), MUTED, alpha)
    bar_w = int(84 * ease_out_expo(alpha))
    if bar_w > 2:
        bar = Image.new("RGBA", (bar_w, 3), AMBER + (int(210 * alpha),))
        canvas.alpha_composite(bar, (100, int(y + 52)))


def headline(canvas: Image.Image, lines: list[tuple[str, tuple]], t: float, base_y: float = 226) -> None:
    fnt = font(FONT_DISPLAY, 88)
    y = base_y
    for i, (line, color) in enumerate(lines):
        p = span(t, 0.10 + i * 0.10, 0.58)
        if p <= 0:
            continue
        e = ease_out_expo(p)
        blit(canvas, line, (100, y + (1 - e) * 48), fnt, color, clamp(p * 1.8))
        y += 110


# ------------------------------------------------------------------ beats ----

def beat_intro(t: float, dur: float) -> Image.Image:
    canvas = background(t).convert("RGBA")

    p = span(t, 0.15, 0.8)
    e = ease_out_back(p, 1.15)
    baseline = 1120 + (1 - e) * 90
    blit(canvas, "CodexRM", (W / 2, baseline), font(FONT_DISPLAY, 158), WHITE,
         clamp(p * 2.4), anchor="mt", tracking=4)

    p2 = span(t, 0.60, 0.6)
    if p2 > 0:
        blit(canvas, "把电脑上的 Codex 装进口袋", (W / 2, baseline + 216), font(FONT_BODY, 46), MUTED,
             clamp(p2 * 1.8), anchor="mt")

    p3 = span(t, 1.05, 0.7)
    if p3 > 0:
        width = int(320 * ease_out_expo(p3)) or 1
        bar = Image.new("RGBA", (width, 4), AMBER + (int(225 * p3),))
        canvas.alpha_composite(bar, (int(W / 2 - width / 2), int(baseline + 380)))

    # Device rises from the bottom edge just before the cut.
    p4 = span(t, dur - 3.0, 1.7)
    if p4 > 0:
        drop = 1.0 - ease_out_expo(p4)
        place_phone(canvas, "workspaces", (540, 1132), 1900,
                    (W / 2, 2760 + drop * 900), alpha=clamp(p4 * 1.4) * 0.95)
    return canvas


def beat_screen(beat: dict, t: float) -> Image.Image:
    canvas = background(t).convert("RGBA")
    enter = ease_out_expo(span(t, 0.0, 0.66))

    push = ease_in_out_cubic(span(t, beat.get("push_at", 2.0), 2.6))
    height = lerp(beat["height_wide"], beat["height_zoom"], push)
    focus = (lerp(beat["focus_wide"][0], beat["focus_zoom"][0], push),
             lerp(beat["focus_wide"][1], beat["focus_zoom"][1], push))

    height *= 1.0 + 0.010 * ease_in_out_cubic(span(t, 0, beat["duration"]))
    anchor = (W / 2, 1400 + (1 - enter) * 300)

    left, top, scale = place_phone(canvas, beat["image"], focus, height, anchor,
                                   alpha=clamp(enter * 1.7), reveal=clamp(enter * 1.3))

    ring_p = span(t, beat.get("ring_at", 2.9), 0.55)
    if ring_p > 0:
        box = beat["ring_box"]
        grow = ease_out_back(ring_p, 1.9)
        cx = left + (box[0] + box[2]) / 2 * scale
        cy = top + (box[1] + box[3]) / 2 * scale
        hw = (box[2] - box[0]) / 2 * scale * grow
        hh = (box[3] - box[1]) / 2 * scale * grow
        ring(canvas, (cx - hw, cy - hh, cx + hw, cy + hh), AMBER, 6,
             alpha=clamp(ring_p * 1.6), radius=max(6, int(22 * grow)))

    kicker(canvas, beat["kicker"], beat["index"],
           clamp(span(t, 0.28, 0.55) * 1.7), 38 * (1 - ease_out_expo(span(t, 0.28, 0.55))))
    headline(canvas, beat["head"], t)
    return canvas


def beat_outro(t: float, dur: float) -> Image.Image:
    canvas = background(t).convert("RGBA")

    # A faint device keeps the stage from going empty behind the wordmark.
    ghost = span(t, 0.0, 1.6)
    if ghost > 0:
        place_phone(canvas, "workspaces", (540, 1132), 1980,
                    (W / 2, 1900 + (1 - ease_out_expo(ghost)) * 260),
                    alpha=0.16 * ease_out_expo(ghost))

    p = span(t, 0.10, 0.8)
    e = ease_out_expo(p)
    blit(canvas, "CodexRM", (W / 2, 1010 - (1 - e) * 50), font(FONT_DISPLAY, 152), WHITE,
         clamp(p * 2.2), anchor="mt", tracking=4)

    blit(canvas, "开源 · 端到端加密", (W / 2, 1230), font(FONT_BODY, 48), MUTED,
         clamp(span(t, 0.55, 0.6) * 1.8), anchor="mt")

    p3 = span(t, 1.05, 0.7)
    if p3 > 0:
        pill_w, pill_h = 760, 106
        rise = (1 - ease_out_expo(p3)) * 28
        layer = Image.new("RGBA", (pill_w, pill_h), (0, 0, 0, 0))
        ImageDraw.Draw(layer).rounded_rectangle((0, 0, pill_w - 1, pill_h - 1),
                                                radius=pill_h // 2, fill=AMBER + (int(232 * p3),))
        canvas.alpha_composite(layer, (int(W / 2 - pill_w / 2), int(1400 + rise)))
        blit(canvas, "github.com/CyL-Cly/CodexRM", (W / 2, 1400 + rise + pill_h / 2),
             font(FONT_BODY, 38), (20, 15, 6), p3, anchor="mm")
    return canvas


# ------------------------------------------------------------------ build ----

def build_plan() -> list[dict]:
    return [
        {"id": "intro", "kind": "intro", "duration": 7.2, "pad": 2.8,
         "vo": "CodexRM，把电脑上的 Codex，装进口袋。"},
        {
            "id": "work", "kind": "screen", "image": "workspaces",
            "index": "01", "kicker": "工作区与会话",
            "head": [("所有项目", WHITE), ("一屏管住", AMBER)],
            "focus_wide": (540, 1132), "height_wide": 1560,
            "focus_zoom": (540, 1120), "height_zoom": 2180,
            "push_at": 1.9, "ring_at": 3.0,
            "ring_box": (24, 960, 1056, 1370),
            "duration": 10.8,
            "vo": "所有项目，一个 App 管住。工作区自动分组。",
        },
        {
            "id": "chat", "kind": "screen", "image": "chat",
            "index": "02", "kicker": "对话驱动",
            "head": [("打开就能", WHITE), ("对话", AMBER)],
            "focus_wide": (540, 1132), "height_wide": 1560,
            "focus_zoom": (540, 700), "height_zoom": 2600,
            "push_at": 1.9, "ring_at": 3.0,
            "ring_box": (48, 540, 1032, 870),
            "duration": 10.8,
            "vo": "打开就能对话，回复实时流到手机。",
        },
        {
            "id": "config", "kind": "screen", "image": "config",
            "index": "03", "kicker": "模型与强度",
            "head": [("模型 强度 工作区", WHITE), ("随时切换", AMBER)],
            "focus_wide": (540, 1132), "height_wide": 1560,
            "focus_zoom": (540, 1695), "height_zoom": 2520,
            "push_at": 1.8, "ring_at": 2.9,
            "ring_box": (48, 1460, 1032, 1935),
            "duration": 10.8,
            "vo": "模型、思考强度、工作区，一屏就能改。",
        },
        {
            "id": "share", "kind": "screen", "image": "share",
            "index": "04", "kicker": "系统分享",
            "head": [("任何文字", WHITE), ("一键送达", AMBER)],
            "focus_wide": (540, 1330), "height_wide": 1980,
            "focus_zoom": (540, 1520), "height_zoom": 2680,
            "push_at": 1.8, "ring_at": 2.9,
            "ring_box": (48, 1330, 1032, 1640),
            "duration": 10.8,
            "vo": "看到任何文字，一键分享给 Codex。",
        },
        {"id": "outro", "kind": "outro", "duration": 9.6,
         "vo": "CodexRM。开源，端到端加密。"},
    ]


def tts(text_value: str, dest: Path, api_key: str, voice: dict) -> None:
    body = {
        "text": text_value,
        "format": "mp3",
        "mp3_bitrate": 192,
        "sample_rate": 44100,
        "normalize": True,
        "language": "zh-CN",
        "prosody": {"speed": float(voice["speed"]), "normalize_loudness": True},
    }
    request = urllib.request.Request(
        "https://api.fish.audio/v1/tts",
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "model": voice["model"],
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read()
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"Fish Audio HTTP {exc.code}: "
                           f"{exc.read().decode('utf-8', 'replace')[:400]}") from None
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(payload)


def probe_duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
        capture_output=True, text=True, check=True,
    )
    return float(out.stdout.strip())


def decode_mono(path: Path, rate: int = 48000) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le",
         "-ac", "1", "-ar", str(rate), "-"],
        capture_output=True, check=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def music_bed(total: float, rate: int = 48000) -> np.ndarray:
    n = int(total * rate)
    t = np.arange(n, dtype=np.float32) / rate
    out = np.zeros(n, dtype=np.float32)

    chords = [(110.00, 164.81, 261.63), (87.31, 130.81, 220.00),
              (130.81, 196.00, 329.63), (98.00, 146.83, 246.94)]
    bar = 2.4
    for i in range(int(math.ceil(total / bar)) + 1):
        start = i * bar
        root, third, fifth = chords[i % len(chords)]
        seg = np.zeros(n, dtype=np.float32)
        for freq, gain in ((root, 0.40), (third, 0.20), (fifth, 0.24)):
            seg += gain * np.sin(2 * math.pi * freq * t)
            seg += gain * 0.28 * np.sin(2 * math.pi * freq * 2 * t)
        env = np.clip((t - start) / 0.85, 0, 1) * np.clip((start + bar + 0.85 - t) / 0.85, 0, 1)
        out += seg * env

    beat = 60.0 / 100.0
    for i in range(int(total / (beat / 2)) + 1):
        idx = int(i * beat / 2 * rate)
        if idx >= n:
            break
        length = min(int(0.20 * rate), n - idx)
        seg = np.arange(length, dtype=np.float32) / rate
        out[idx:idx + length] += 0.09 * np.sin(2 * math.pi * 220 * seg) * np.exp(-seg * 26)

    out = np.tanh(out * 0.55)
    out *= 0.30 / (np.abs(out).max() + 1e-6)
    return out


def build_audio(plan: list[dict], offsets: list[float], total: float, api_key: str) -> Path:
    rate = 48000
    n = int((total + 1.5) * rate)
    bed_total = total + 1.5
    mix = music_bed(bed_total, rate)
    vo = np.zeros(n, dtype=np.float32)
    voice = {"model": "s2.1-pro-free", "speed": 1.05}

    for beat, offset in zip(plan, offsets):
        text_vo = beat.get("vo")
        if not text_vo:
            continue
        clip = decode_mono(BUILD / "vo" / f"{beat['id']}.mp3", rate)
        start = int(offset * rate)
        end = min(start + clip.size, n)
        if end > start:
            vo[start:end] += clip[: end - start]

    gate = (np.abs(vo) > 1e-4).astype(np.float32)
    window = int(0.28 * rate)
    duck = 1.0 - 0.42 * np.convolve(gate, np.ones(window, dtype=np.float32) / window, mode="same")
    left = mix * 0.55 * duck + vo
    peak = np.abs(left).max()
    if peak > 0.97:
        left *= 0.97 / peak
    stereo = np.stack([left, left], axis=1)

    raw = BUILD / "ad-audio-raw.wav"
    with wave.open(str(raw), "wb") as handle:
        handle.setnchannels(2)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes((stereo * 32767).astype("<i2").tobytes())

    # Land the mix near -16 LUFS so it plays at ad volume without clipping.
    out = BUILD / "ad-audio.wav"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(raw),
                    "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
                    "-c:a", "pcm_s16le", str(out)], check=True)
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stills", default=None)
    parser.add_argument("--api-key", default=os.environ.get("FISH_API_KEY", "").strip())
    parser.add_argument("--no-audio", action="store_true")
    args = parser.parse_args()

    if not SCREENS.is_dir():
        print("screens missing; run prep.py first", file=sys.stderr)
        raise SystemExit(1)
    BUILD.mkdir(parents=True, exist_ok=True)

    plan = build_plan()
    voice = {"model": "s2.1-pro-free", "speed": 1.05}

    if not args.no_audio:
        if not args.api_key:
            print("FISH_API_KEY missing", file=sys.stderr)
            raise SystemExit(1)
        for beat in plan:
            text_vo = beat.get("vo", "")
            digest = hashlib.sha256(
                (text_vo + voice["model"] + str(voice["speed"])).encode()).hexdigest()[:12]
            probe = BUILD / "vo" / f"{beat['id']}-{digest}.mp3"
            if not probe.is_file():
                print(f"  tts {beat['id']}")
                tts(text_vo, probe, args.api_key, voice)
            final = BUILD / "vo" / f"{beat['id']}.mp3"
            if not final.is_file() or final.read_bytes() != probe.read_bytes():
                if final.exists():
                    final.unlink()
                final.write_bytes(probe.read_bytes())
            beat["vo_len"] = probe_duration(probe)
            beat["duration"] = max(beat["duration"], beat["vo_len"] + beat.get("pad", 3.6))

    total = sum(beat["duration"] for beat in plan)
    starts, acc = [], 0.0
    for beat in plan:
        starts.append(acc)
        acc += beat["duration"]
    offsets = [start + 1.1 for start in starts]

    print(f"timeline {total:.2f}s  " +
          "  ".join(f"{b['id']}={b['duration']:.1f}s" for b in plan))

    def frame_at(mark: float) -> tuple[Image.Image, dict, float]:
        idx = max(i for i, s in enumerate(starts) if s <= mark + 1e-9)
        beat = plan[idx]
        local = mark - starts[idx]
        if beat["kind"] == "intro":
            return beat_intro(local, beat["duration"]), beat, local
        if beat["kind"] == "outro":
            return beat_outro(local, beat["duration"]), beat, local
        return beat_screen(beat, local), beat, local

    if args.stills:
        for mark in [float(x) for x in args.stills.split(",")]:
            frame, beat, local = frame_at(mark)
            caption(frame, beat.get("vo", ""), 1.0, 0)
            out = WORK / f"still-{mark:05.1f}.png"
            frame.convert("RGB").save(out)
            print(f"still {mark:.1f}s [{beat['id']}] -> {out.name}")
        return

    audio_path = None
    if not args.no_audio:
        audio_path = build_audio(plan, offsets, total, args.api_key)

    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-"]
    if audio_path:
        cmd += ["-i", str(audio_path), "-c:a", "aac", "-b:a", "192k", "-shortest"]
    cmd += ["-c:v", "libx264", "-preset", "slow", "-crf", "19",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(OUT)]

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    frames = int(total * FPS)
    for i in range(frames):
        t = i / FPS
        idx = max(j for j, start in enumerate(starts) if start <= t + 1e-9)
        beat = plan[idx]
        local = t - starts[idx]
        if beat["kind"] == "intro":
            frame = beat_intro(local, beat["duration"])
        elif beat["kind"] == "outro":
            frame = beat_outro(local, beat["duration"])
        else:
            frame = beat_screen(beat, local)

        # A short key-light flash marks every cut after the open.
        if idx > 0 and local < 0.22:
            flash = 1.0 - span(local, 0, 0.22)
            if flash > 0.01:
                frame.alpha_composite(Image.new("RGBA", (W, H), AMBER + (int(40 * flash),)))

        vo_alpha = clamp(span(local, 0.40, 0.45) * 1.6) * \
                   (1.0 - clamp(span(local, beat["duration"] - 0.6, 0.55)))
        caption(frame, beat.get("vo", ""), vo_alpha,
                (1 - ease_out_expo(span(local, 0.40, 0.5))) * 24)

        proc.stdin.write(frame.convert("RGB").tobytes())
        if i % 120 == 0:
            print(f"  frame {i}/{frames}")
    proc.stdin.close()
    proc.wait()
    print(f"done {OUT}  {OUT.stat().st_size / 1024 / 1024:.1f} MB  {total:.1f}s")


if __name__ == "__main__":
    main()
