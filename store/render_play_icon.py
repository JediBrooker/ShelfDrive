#!/usr/bin/env python3
"""Render the ShelfDrive adaptive icon to a 512x512 PNG for the Play Store.

Mirrors the composition from
android/app/src/main/res/drawable/ic_shelfdrive_foreground.xml +
ic_shelfdrive_background.xml.

Run from repo root:
    python3 store/render_play_icon.py
Output: store/shelfdrive-play-icon-512.png
"""
from PIL import Image, ImageDraw

SIZE = 512
VIEWPORT = 108.0
SCALE = SIZE / VIEWPORT

ORANGE = (0xFF, 0x7E, 0x2D, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)


def s(v):
    """Scale a viewport unit to pixel."""
    return int(round(v * SCALE))


img = Image.new("RGBA", (SIZE, SIZE), ORANGE)
draw = ImageDraw.Draw(img)


def filled_circle(cx, cy, r, fill):
    draw.ellipse((s(cx - r), s(cy - r), s(cx + r), s(cy + r)), fill=fill)


def filled_polygon(pts, fill):
    draw.polygon([(s(x), s(y)) for x, y in pts], fill=fill)


def filled_rect(x0, y0, x1, y1, fill):
    draw.rectangle((s(x0), s(y0), s(x1), s(y1)), fill=fill)


# Steering wheel outer ring (white annulus, outer r=34, inner r=28).
filled_circle(54, 54, 34, WHITE)
filled_circle(54, 54, 28, ORANGE)

# Y-spokes from center hub area to inner ring edge.
# Top-left arm.
filled_polygon([(52.6, 55.4), (32.8, 35.6), (35.6, 32.8), (55.4, 52.6)], WHITE)
# Top-right arm.
filled_polygon([(55.4, 55.4), (75.2, 35.6), (72.4, 32.8), (52.6, 52.6)], WHITE)
# Bottom stem.
filled_rect(52, 54, 56, 82, WHITE)

# Book cover (orange) on top of wheel center.
filled_rect(40, 46, 68, 70, ORANGE)
# Book spine (white, left edge).
filled_rect(40, 46, 43, 70, WHITE)
# Three white page lines on the cover.
filled_rect(46, 52, 65, 53.5, WHITE)
filled_rect(46, 57, 65, 58.5, WHITE)
filled_rect(46, 62, 65, 63.5, WHITE)

# Headphone band: emulate the cubic Bezier `M34,52 C34,30 74,30 74,52`
# by sampling points along the curve and drawing a thick stroke.
def cubic_bezier(p0, p1, p2, p3, steps=120):
    out = []
    for i in range(steps + 1):
        t = i / steps
        x = (1 - t) ** 3 * p0[0] + 3 * (1 - t) ** 2 * t * p1[0] + 3 * (1 - t) * t * t * p2[0] + t ** 3 * p3[0]
        y = (1 - t) ** 3 * p0[1] + 3 * (1 - t) ** 2 * t * p1[1] + 3 * (1 - t) * t * t * p2[1] + t ** 3 * p3[1]
        out.append((s(x), s(y)))
    return out


band = cubic_bezier((34, 52), (34, 30), (74, 30), (74, 52))
draw.line(band, fill=WHITE, width=s(3.5), joint="curve")

# Ear cups (slightly oval, vertical major axis).
def ellipse(cx, cy, rx, ry, fill):
    draw.ellipse((s(cx - rx), s(cy - ry), s(cx + rx), s(cy + ry)), fill=fill)


ellipse(34, 51, 5, 6, WHITE)
ellipse(74, 51, 5, 6, WHITE)

out_path = "store/shelfdrive-play-icon-512.png"
img.save(out_path, optimize=True)
print(f"Wrote {out_path}: {SIZE}x{SIZE}")
