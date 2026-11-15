#!/usr/bin/env python3
"""Natural Earth 50m countries (GeoJSON) -> compact src/main/assets/countries.bin for the country map in the viewer (CountryMap.kt).
Simplified with Douglas-Peucker (0.015 deg), tiny islands removed; ~250 kB, ~60k points.
Format (big-endian): int16 country count; per country: int16 name length + utf-8 name, 4x int16 bbox (*100: minLon, minLat,
maxLon, maxLat), int16 ring count; per ring: int16 point count, then per point int16 lon*100, int16 lat*100.
Usage:
    python3 countries_bin.py ne_50m_admin_0_countries.geojson src/main/assets/countries.bin
GeoJSON from naturalearthdata.com (Admin 0 - Countries, 1:50m; public domain), e.g. converted with ogr2ogr or
downloaded as GeoJSON from github.com/nvkelso/natural-earth-vector (geojson/ne_50m_admin_0_countries.geojson)."""
import json, math, struct, sys


def dp(pts, tol):
    """Douglas-Peucker on an open polyline."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    st = [(0, len(pts) - 1)]
    while st:
        a, b = st.pop()
        ax, ay = pts[a]; bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        L = math.hypot(dx, dy) or 1e-12
        best, bi = -1, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = abs(dy * px - dx * py + bx * ay - by * ax) / L
            if d > best:
                best, bi = d, i
        if best > tol:
            keep[bi] = True
            st += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]


d = json.load(open(sys.argv[1]))
countries = []
for f in d["features"]:
    g = f["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    rings = []
    for poly in polys:
        r = poly[0]  # outer ring; holes (lakes) are ignored
        xs = [p[0] for p in r]; ys = [p[1] for p in r]
        if (max(xs) - min(xs)) * (max(ys) - min(ys)) < 0.002:
            continue  # tiny islands
        m = len(r) // 2
        s = dp(r[:m + 1], 0.015) + dp(r[m:], 0.015)[1:]  # closed ring: split in two halves so start == end doesn't collapse
        if len(s) >= 4:
            rings.append([round(v, 2) for p in s for v in p])
    if not rings:
        continue
    all_coords = [v for r in rings for v in r]
    xs = all_coords[0::2]; ys = all_coords[1::2]
    countries.append((f["properties"].get("NAME") or f["properties"].get("name") or "?", [min(xs), min(ys), max(xs), max(ys)], rings))

out = bytearray(struct.pack(">h", len(countries)))
points = 0
for name, bbox, rings in countries:
    n = name.encode("utf-8")
    out += struct.pack(">h", len(n)) + n
    out += struct.pack(">4h", *[round(v * 100) for v in bbox])
    out += struct.pack(">h", len(rings))
    for r in rings:
        k = len(r) // 2
        out += struct.pack(">h", k) + struct.pack(">%dh" % (2 * k), *[round(v * 100) for v in r])
        points += k
open(sys.argv[2], "wb").write(out)
print(len(countries), "countries,", points, "points,", len(out), "bytes")
