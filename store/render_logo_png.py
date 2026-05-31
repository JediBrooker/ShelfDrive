#!/usr/bin/env python3
"""Render static/Logo.png (432x432) from the ShelfDrive icon composition.

Matches static/shelfdrive-logo.svg and the Android launcher icon: white
steering-wheel + book + headphones on an orange rounded square.

Run from repo root:
    python3 store/render_logo_png.py
"""
from PIL import Image, ImageDraw, ImageFilter

SIZE = 432
ORANGE = (0xFF, 0x7E, 0x2D, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)


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


icon = render_icon(SIZE)
# Rounded-square mask (~22% radius, matches the svg's rx).
mask = Image.new("L", (SIZE, SIZE), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), radius=int(SIZE * 0.22), fill=255)
out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
out.paste(icon, (0, 0), mask)
out.save("static/Logo.png", optimize=True)
print(f"Wrote static/Logo.png: {SIZE}x{SIZE}")
