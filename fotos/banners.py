#!/usr/bin/env python3
"""Banners (640x360) and icons (192x192) for appearance variants (activity-aliases in the manifest), in one style:
dark gradient, white-bordered photo cards, large white title. The title per language is in TITLES; each language
gets a drawable folder (drawable-nodpi = English/default, drawable-nl-nodpi, ...); icons have no text.
The main banner ("Photos") uses the textless source banners_bron/fotos_zonder_tekst.png (motif and gradient are
already drawn there); its icon (ic_fotos.png) remains as-is.
Run (requires PIL):
    python3 banners.py [src/main/res]
Original designs (no Immich logo or other brand)."""
import math, os, sys
from PIL import Image, ImageDraw, ImageFont

S = 4  # supersampling
RES = sys.argv[1] if len(sys.argv) > 1 else "src/main/res"
FONT = os.path.join(RES, "font/overpass.ttf")
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "banners_bron")

# Titles per language; "" = default folder (English), rest = language code in folder name (drawable-<lang>-nodpi)
TITLES = {
    "": {"fotos": "Photos", "album": "Photo album", "herinneringen": "Memories", "familie": "Family photos"},
    "nl": {"fotos": "Foto’s", "album": "Fotoalbum", "herinneringen": "Herinneringen", "familie": "Familiefoto’s"},
    "de": {"fotos": "Fotos", "album": "Fotoalbum", "herinneringen": "Erinnerungen", "familie": "Familienfotos"},
    "fr": {"fotos": "Photos", "album": "Album photo", "herinneringen": "Souvenirs", "familie": "Photos de famille"},
    "es": {"fotos": "Fotos", "album": "Álbum de fotos", "herinneringen": "Recuerdos", "familie": "Fotos familiares"},
}


def font(px):
    try:
        f = ImageFont.truetype(FONT, px)
        f.set_variation_by_axes([700])
        return f
    except Exception:
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", px)


def gradient(w, h, left, right, start_at=0.35):
    im = Image.new("RGB", (w, h))
    px = im.load()
    for x in range(w):
        t = min(1.0, max(0.0, (x / w - start_at) / (1 - start_at)))  # solid on left, darkens from [start_at]
        t = t * t
        c = tuple(int(left[i] * (1 - t) + right[i] * t) for i in range(3))
        for y in range(h):
            px[x, y] = c
    return im


def photo_card(d, x, y, w, h, angle, sky=(176, 206, 246), mountain=(34, 40, 48), border=14, sun=True):
    """White-bordered photo card with sky, sun, and mountains, rotated around its center (in supersampled px)."""
    layer = Image.new("RGBA", (w + 2 * border + 40, h + 2 * border + 40), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ox, oy = 20, 20
    ld.rounded_rectangle([ox, oy, ox + w + 2 * border, oy + h + 2 * border], radius=10 * S, fill=(255, 255, 255, 255))
    ld.rounded_rectangle([ox + border, oy + border, ox + border + w, oy + border + h], radius=4 * S, fill=sky + (255,))
    if sun:
        r = int(h * 0.11)
        ld.ellipse([ox + border + w * 0.72 - r, oy + border + h * 0.22 - r, ox + border + w * 0.72 + r, oy + border + h * 0.22 + r], fill=(255, 248, 225, 255))
    b = oy + border + h
    ld.polygon([(ox + border, b), (ox + border + w * 0.33, oy + border + h * 0.42), (ox + border + w * 0.62, b)], fill=mountain + (255,))
    ld.polygon([(ox + border + w * 0.4, b), (ox + border + w * 0.7, oy + border + h * 0.55), (ox + border + w, b)], fill=tuple(min(255, c + 30) for c in mountain) + (255,))
    layer = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
    return layer, (int(x - layer.width / 2), int(y - layer.height / 2))


def star(d, cx, cy, r, color):
    pts = []
    for i in range(8):
        a = math.pi / 4 * i - math.pi / 2
        rr = r if i % 2 == 0 else r * 0.38
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=color)


def heart(d, cx, cy, r, color):
    pts = []
    for i in range(200):
        t = 2 * math.pi * i / 200
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((cx + x * r / 16, cy + y * r / 16))
    d.polygon(pts, fill=color)


def title_layer(w, h, title, x):
    """Title on a transparent layer (supersampled), left-aligned at [x]; font size scaled to fit."""
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    size = 78 * S
    while True:
        f = font(int(size))
        if d.textlength(title, font=f) <= w - 24 * S - x or size < 30 * S:
            break
        size *= 0.94
    d.text((x, h / 2), title, font=f, fill=(255, 255, 255, 255), anchor="lm")
    return layer.resize((w // S, h // S), Image.LANCZOS)


def save_image(im, lang, name):
    folder = os.path.join(RES, "drawable-%snodpi" % (lang + "-" if lang else ""))
    os.makedirs(folder, exist_ok=True)
    im.convert("RGB").save(os.path.join(folder, name), optimize=True)


def banner(name, left, right, motif):
    w, h = 640 * S, 360 * S
    base = gradient(w, h, left, right).convert("RGBA")
    motif(base, w, h, cx=140 * S, cy=180 * S, scale=0.8)
    base = base.resize((640, 360), Image.LANCZOS)
    for lang, titles in TITLES.items():
        im = base.copy()
        im.alpha_composite(title_layer(w, h, titles[name], 285 * S))
        save_image(im, lang, "banner_%s.png" % name)
    # Icon (no text): same motif, centered on the solid part of the gradient
    ic = gradient(192 * S, 192 * S, left, right, start_at=0.55).convert("RGBA")
    motif(ic, 192 * S, 192 * S, cx=96 * S, cy=96 * S, scale=0.62)
    ic = ic.convert("RGB").resize((192, 192), Image.LANCZOS)
    ic.save(os.path.join(RES, "mipmap-xhdpi", "ic_%s.png" % name), optimize=True)


def main_banner():
    """banner.png (default variant "Photos"): textless source + title per language."""
    src = Image.open(os.path.join(SRC, "fotos_zonder_tekst.png")).convert("RGBA")
    for lang, titles in TITLES.items():
        im = src.copy()
        im.alpha_composite(title_layer(640 * S, 360 * S, titles["fotos"], 325 * S))
        save_image(im, lang, "banner.png")


def motif_album(im, w, h, cx, cy, scale=1.0):
    """Open album: two pages each with a photo, spine in the middle."""
    d = ImageDraw.Draw(im)
    pw, ph = int(150 * S * scale), int(190 * S * scale)
    for side, angle in ((-1, 6), (1, -6)):
        layer = Image.new("RGBA", (pw + 40, ph + 40), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        ld.rounded_rectangle([20, 20, 20 + pw, 20 + ph], radius=8 * S, fill=(250, 244, 232, 255))
        layer = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
        im.alpha_composite(layer, (int(cx + side * pw * 0.52 - layer.width / 2), int(cy - layer.height / 2)))
    for side, angle, sky in ((-1, 6, (176, 206, 246)), (1, -6, (250, 200, 150))):
        layer, pos = photo_card(d, cx + side * pw * 0.52, cy - ph * 0.1, int(pw * 0.72), int(pw * 0.55), angle, sky=sky, border=8, sun=side > 0)
        im.alpha_composite(layer, pos)
    d.rounded_rectangle([cx - 5 * S * scale, cy - ph * 0.48, cx + 5 * S * scale, cy + ph * 0.48], radius=4 * S, fill=(120, 60, 30, 255))


def motif_memories(im, w, h, cx, cy, scale=1.0):
    """Single photo card, slightly rotated, with a golden star on the corner."""
    d = ImageDraw.Draw(im)
    pw, ph = int(200 * S * scale), int(150 * S * scale)
    layer, pos = photo_card(d, cx, cy + 10 * S, pw, ph, 8, sky=(214, 196, 250), mountain=(60, 34, 90))
    im.alpha_composite(layer, pos)
    d = ImageDraw.Draw(im)
    star(d, cx + pw * 0.5, cy - ph * 0.5, 34 * S * scale, (255, 214, 102))
    star(d, cx + pw * 0.68, cy - ph * 0.15, 16 * S * scale, (255, 236, 180))


def motif_family(im, w, h, cx, cy, scale=1.0):
    """Two stacked photo cards with a red heart."""
    d = ImageDraw.Draw(im)
    pw, ph = int(190 * S * scale), int(140 * S * scale)
    layer, pos = photo_card(d, cx - 14 * S, cy - 8 * S, pw, ph, 9, sky=(200, 236, 210), mountain=(28, 70, 48), sun=False)
    im.alpha_composite(layer, pos)
    layer, pos = photo_card(d, cx + 12 * S, cy + 12 * S, pw, ph, -5, sky=(180, 220, 250), mountain=(30, 60, 70))
    im.alpha_composite(layer, pos)
    d = ImageDraw.Draw(im)
    heart(d, cx + pw * 0.42, cy + ph * 0.42, 30 * S * scale, (232, 64, 84))
    heart(d, cx + pw * 0.42, cy + ph * 0.42 - 3 * S, 24 * S * scale, (250, 90, 110))


main_banner()
banner("album", (122, 52, 18), (14, 8, 4), motif_album)
banner("herinneringen", (62, 30, 110), (10, 6, 20), motif_memories)
banner("familie", (18, 76, 48), (5, 20, 12), motif_family)
print("done")
