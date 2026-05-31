#!/usr/bin/env python3
"""Render a 1024x500 Play Store feature graphic for ShelfDrive.

Reuses the icon composition from render_play_icon.py (steering wheel + book +
headphones, white on orange), placed as a rounded badge with a soft glow on a
dark gradient that matches the app theme (#11181c), plus the app name + tagline.

Play requirements: exactly 1024x500, PNG or JPEG, no transparency.

Run from repo root:
    python3 store/render_feature_graphic.py
Output: store/shelfdrive-feature-graphic-1024x500.png
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1024, 500
ORANGE = (0xFF, 0x7E, 0x2D, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)
DARK_L = (0x11, 0x18, 0x1C)   # app status-bar dark (left)
DARK_R = (0x26, 0x16, 0x0C)   # warm dark (right, toward orange)
SUBTLE = (0xC7, 0xCD, 0xD1)   # tagline gray


# ---------- icon (mirrors render_play_icon.py) ----------
def render_icon(size):
    vp = 108.0
    sc = size / vp
    def s(v): return int(round(v * sc))
    img = Image.new("RGBA", (size, size), ORANGE)
    d = ImageDraw.Draw(img)
    def circ(cx, cy, r, f): d.ellipse((s(cx - r), s(cy - r), s(cx + r), s(cy + r)), fill=f)
    def poly(pts, f): d.polygon([(s(x), s(y)) for x, y in pts], fill=f)
    def rect(x0, y0, x1, y1, f): d.rectangle((s(x0), s(y0), s(x1), s(y1)), fill=f)
    circ(54, 54, 34, WHITE); circ(54, 54, 28, ORANGE)
    poly([(52.6, 55.4), (32.8, 35.6), (35.6, 32.8), (55.4, 52.6)], WHITE)
    poly([(55.4, 55.4), (75.2, 35.6), (72.4, 32.8), (52.6, 52.6)], WHITE)
    rect(52, 54, 56, 82, WHITE)
    rect(40, 46, 68, 70, ORANGE)
    rect(40, 46, 43, 70, WHITE)
    rect(46, 52, 65, 53.5, WHITE); rect(46, 57, 65, 58.5, WHITE); rect(46, 62, 65, 63.5, WHITE)
    def bez(p0, p1, p2, p3, steps=160):
        o = []
        for i in range(steps + 1):
            t = i / steps
            x = (1 - t)**3*p0[0] + 3*(1 - t)**2*t*p1[0] + 3*(1 - t)*t*t*p2[0] + t**3*p3[0]
            y = (1 - t)**3*p0[1] + 3*(1 - t)**2*t*p1[1] + 3*(1 - t)*t*t*p2[1] + t**3*p3[1]
            o.append((s(x), s(y)))
        return o
    d.line(bez((34, 52), (34, 30), (74, 30), (74, 52)), fill=WHITE, width=s(3.5), joint="curve")
    def ell(cx, cy, rx, ry, f): d.ellipse((s(cx - rx), s(cy - ry), s(cx + rx), s(cy + ry)), fill=f)
    ell(34, 51, 5, 6, WHITE); ell(74, 51, 5, 6, WHITE)
    return img


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius=radius, fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def load_font(size, bold=True):
    cands = (
        ["/System/Library/Fonts/HelveticaNeue.ttc",
         "/System/Library/Fonts/Helvetica.ttc",
         "/System/Library/Fonts/Supplemental/Arial Bold.ttf"]
        if bold else
        ["/System/Library/Fonts/Helvetica.ttc",
         "/System/Library/Fonts/Supplemental/Arial.ttf"]
    )
    for p in cands:
        for idx in ([1, 2, 0] if bold else [0]):
            try:
                return ImageFont.truetype(p, size, index=idx)
            except Exception:
                continue
    return ImageFont.load_default()


# ---------- background: horizontal gradient ----------
row = Image.new("RGB", (W, 1))
for x in range(W):
    t = x / (W - 1)
    row.putpixel((x, 0), tuple(int(DARK_L[i] + (DARK_R[i] - DARK_L[i]) * t) for i in range(3)))
bg = row.resize((W, H)).convert("RGBA")

# soft orange glow behind the icon
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gcx, gcy = 250, H // 2
gd.ellipse((gcx - 230, gcy - 230, gcx + 230, gcy + 230), fill=(0xFF, 0x7E, 0x2D, 90))
glow = glow.filter(ImageFilter.GaussianBlur(70))
bg = Image.alpha_composite(bg, glow)

# ---------- icon badge ----------
icon_size = 300
icon = rounded(render_icon(icon_size), radius=int(icon_size * 0.22))
ix, iy = 90, (H - icon_size) // 2
# drop shadow
shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
sh = rounded(Image.new("RGBA", (icon_size, icon_size), (0, 0, 0, 160)), radius=int(icon_size * 0.22))
shadow.paste(sh, (ix + 8, iy + 14), sh)
shadow = shadow.filter(ImageFilter.GaussianBlur(18))
bg = Image.alpha_composite(bg, shadow)
bg.paste(icon, (ix, iy), icon)

# ---------- text ----------
d = ImageDraw.Draw(bg)
tx = ix + icon_size + 70   # 460
right = 985                # keep a comfortable margin from the right edge
maxw = right - tx          # available text width


def fit_font(text, start, minsize, bold):
    """Largest font (<= start, >= minsize) whose text width fits in maxw."""
    for sz in range(start, minsize - 1, -2):
        f = load_font(sz, bold=bold)
        if d.textlength(text, font=f) <= maxw:
            return f
    return load_font(minsize, bold=bold)


TAGLINE = "Your Audiobookshelf library, in the car."

kicker = load_font(26, bold=True)
title = fit_font("ShelfDrive", 92, 60, bold=True)
tag = fit_font(TAGLINE, 34, 24, bold=False)

d.text((tx, 150), "ANDROID AUTOMOTIVE OS", font=kicker, fill=(0xFF, 0x9E, 0x5C, 0xFF))
d.text((tx, 188), "ShelfDrive", font=title, fill=WHITE)
# orange underline accent
d.rectangle((tx + 2, 300, tx + 150, 308), fill=ORANGE)
d.text((tx, 330), TAGLINE, font=tag, fill=SUBTLE + (0xFF,))

out_path = "store/shelfdrive-feature-graphic-1024x500.png"
bg.convert("RGB").save(out_path, optimize=True)
print(f"Wrote {out_path}: {W}x{H}")
