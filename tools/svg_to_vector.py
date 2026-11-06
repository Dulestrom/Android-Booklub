"""Converts the desktop's 16 px symbolic SVG icons into Android vector drawables.

usage: python3 -I tools/svg_to_vector.py <icons-dir> <res/drawable>
Handles the shapes those icons use: path, rect, and circle, filled or stroked.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"


def rect_path(e):
    x, y = float(e.get("x", 0)), float(e.get("y", 0))
    w, h = float(e.get("width")), float(e.get("height"))
    r = min(float(e.get("rx", 0) or 0), w / 2, h / 2)
    if r == 0:
        return f"M{x},{y}h{w}v{h}h{-w}z"
    return (f"M{x + r},{y}h{w - 2 * r}a{r},{r} 0 0 1 {r},{r}v{h - 2 * r}a{r},{r} 0 0 1 {-r},{r}"
            f"h{-(w - 2 * r)}a{r},{r} 0 0 1 {-r},{-r}v{-(h - 2 * r)}a{r},{r} 0 0 1 {r},{-r}z")


def circle_path(e):
    cx, cy, r = float(e.get("cx")), float(e.get("cy")), float(e.get("r"))
    return f"M{cx - r},{cy}a{r},{r} 0 1 0 {2 * r},0a{r},{r} 0 1 0 {-2 * r},0z"


def convert(svg):
    root = ET.parse(svg).getroot()
    out = []
    for e in root.iter():
        tag = e.tag.replace(NS, "")
        if tag == "path":
            d = e.get("d")
        elif tag == "rect":
            d = rect_path(e)
        elif tag == "circle":
            d = circle_path(e)
        else:
            continue
        attrs = [f'android:pathData="{d}"']
        fill, stroke = e.get("fill"), e.get("stroke")
        if fill and fill != "none":
            attrs.append('android:fillColor="#FF000000"')
            if e.get("fill-rule") == "evenodd":
                attrs.append('android:fillType="evenOdd"')
        if stroke and stroke != "none":
            attrs.append('android:strokeColor="#FF000000"')
            attrs.append(f'android:strokeWidth="{e.get("stroke-width", "1")}"')
            if e.get("stroke-linecap"):
                attrs.append(f'android:strokeLineCap="{e.get("stroke-linecap")}"')
        out.append("    <path " + " ".join(attrs) + " />")
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated from the desktop icon by tools/svg_to_vector.py. Tinted at use. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp" android:viewportWidth="16" android:viewportHeight="16">\n'
            + "\n".join(out) + "\n</vector>\n")


src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
count = 0
for name in sorted(os.listdir(src)):
    m = re.fullmatch(r"booklub-(.+)-symbolic\.svg", name)
    if not m:
        continue
    target = os.path.join(dst, "ic_" + m.group(1).replace("-", "_") + ".xml")
    with open(target, "w", encoding="utf-8") as f:
        f.write(convert(os.path.join(src, name)))
    count += 1
print(f"converted {count} icons")
