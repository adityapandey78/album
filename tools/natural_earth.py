#!/usr/bin/env python3
"""Builds the offline map from Natural Earth (public domain, https://www.naturalearthdata.com).

    python3 tools/natural_earth.py        # downloads the pinned files into a temp folder
    python3 tools/natural_earth.py DIR    # uses the input files already in DIR

Inputs, all from Natural Earth's official repository at the v5.1.2 tag (the latest release, 13 May 2022), checked
against these SHA-256 before anything is read:
    10m_physical/ne_10m_land.shp                           4cad3a49bc75c1a4c2f3d7efae04f2f8e63151c96764b2658effabf524331fa6
    10m_cultural/ne_10m_admin_0_boundary_lines_land.shp    b6811b9e2f6ed40b31622e6d261210c5e6afded9b7ad71977d3658293f56373f
    10m_cultural/ne_10m_admin_0_boundary_lines_land.dbf    103d9b00b684b0380d8ded830d24a1406ed28cbb6c1f4dc3df21c50cac615403
    10m_cultural/ne_10m_admin_0_boundary_lines_disputed_areas.shp
                                                           b3294d9e15bc093091c3d1524ec120eeafcfc16a188177bc4cbe3d1489f25626
    10m_cultural/ne_10m_admin_0_boundary_lines_disputed_areas.dbf
                                                           6d1e7dcfc13474dba5f304427a97190630cd04af9ea2911d2d5711735a761e34
    10m_cultural/ne_10m_admin_1_states_provinces_lines.shp c89b8545fdb03a764df9de2efeadf0d6735169d94b48a315b5e233780e073ab5
    10m_cultural/ne_10m_admin_1_states_provinces_lines.dbf b524fd1baf59386cd4bca0d6df642ddb02be9cdc4d69f1f1d109885acf21d9ed
The same inputs always give the same output; the tool prints the SHA-256 of every output.

Borders are drawn as India draws them: Natural Earth gives each border line a class from several countries' points of
view, and this takes India's (FCLASS_IN) where it has one. A line India doesn't recognise is left out, one India sees as
a state border is drawn as a state border, and the claim lines of disputed areas are drawn only where India's view
makes them a border (India's claims in Kashmir and Aksai Chin).

Writes app/src/main/assets/map/lod0.bin, lod1.bin and lod2.bin: the same world simplified for the world, a region and a
country or closer. Coordinates are Web Mercator, the world a square from (0, 0) at 180 W, 85.05 N to (1, 1), stored on
a grid of 2^24 per side (about 2 m at the equator). Each file, all integers unsigned LEB128 varints unless noted:
    "AMAP", version (1 byte), layer count (1 byte)
    per layer: kind (1 byte: 0 land, filled; 1 country borders; 2 state borders), chunk count
      per chunk: left, top, right, bottom (its points' bounds), part count
        per part: point count, then each point as zigzag varint dx, dy from the one before (the first from left, top)
A chunk is everything in one square of a grid over the world (land is cut at the square's edges, lines are kept whole
and filed under the square their middle is in), so a phone only draws the squares on screen.
"""
import hashlib
import math
import os
import shutil
import struct
import sys
import tempfile
import urllib.request

BASE = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/"
FILES = {
    "10m_physical/ne_10m_land.shp": "4cad3a49bc75c1a4c2f3d7efae04f2f8e63151c96764b2658effabf524331fa6",
    "10m_cultural/ne_10m_admin_0_boundary_lines_land.shp": "b6811b9e2f6ed40b31622e6d261210c5e6afded9b7ad71977d3658293f56373f",
    "10m_cultural/ne_10m_admin_0_boundary_lines_land.dbf": "103d9b00b684b0380d8ded830d24a1406ed28cbb6c1f4dc3df21c50cac615403",
    "10m_cultural/ne_10m_admin_0_boundary_lines_disputed_areas.shp": "b3294d9e15bc093091c3d1524ec120eeafcfc16a188177bc4cbe3d1489f25626",
    "10m_cultural/ne_10m_admin_0_boundary_lines_disputed_areas.dbf": "6d1e7dcfc13474dba5f304427a97190630cd04af9ea2911d2d5711735a761e34",
    "10m_cultural/ne_10m_admin_1_states_provinces_lines.shp": "c89b8545fdb03a764df9de2efeadf0d6735169d94b48a315b5e233780e073ab5",
    "10m_cultural/ne_10m_admin_1_states_provinces_lines.dbf": "b524fd1baf59386cd4bca0d6df642ddb02be9cdc4d69f1f1d109885acf21d9ed",
}
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "map")
GRID = 1 << 24
MAX_LAT = 85.0511287798  # where Web Mercator's square ends
LAND, COUNTRY, STATE = 0, 1, 2

# Per level of detail: how far a simplified line may stray from the real one (world units: about a pixel on a
# 3x screen at the deepest zoom the level is drawn at, see MapData.kt), and the grid of chunks (squares per side).
# States are only drawn from a country's zoom, so the world level has none.
LODS = [
    {"tolerance": 1.6e-4, "chunks": 1, "layers": [LAND, COUNTRY]},
    {"tolerance": 2.0e-5, "chunks": 8, "layers": [LAND, COUNTRY, STATE]},
    {"tolerance": 2.5e-6, "chunks": 32, "layers": [LAND, COUNTRY, STATE]},
]
# Land cut at a square's edge reaches this many tolerances (a few pixels) into the next square, so the two fills overlap
# and anti-aliasing never shows a hairline between them. The overlap is the same land both squares have, so it never
# shows at any zoom.
OVERLAP = 4

# State borders: real ones, and France's regions, but not its departments or Britain's counties (Natural Earth says
# from which zoom each is worth showing: 2 for US states, 4.6 for Indian ones, 6 for French regions, 8 and up for the
# small ones)
STATE_CLASSES = {"Admin-1 boundary", "Admin-1 statistical boundary", "Admin-1 region boundary"}
STATE_MAX_ZOOM = 6.0


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def fetch(folder):
    for name, digest in FILES.items():
        path = os.path.join(folder, os.path.basename(name))
        if not os.path.exists(path):
            print("downloading", name)
            with urllib.request.urlopen(BASE + name) as r, open(path, "wb") as f:
                shutil.copyfileobj(r, f)
        actual = sha256(path)
        if actual != digest:
            sys.exit(f"{name}: SHA-256 {actual}, expected {digest}")


def shapes(path):
    """Every record of a polyline or polygon shapefile, as a list of parts, each a list of (lng, lat)."""
    data = open(path, "rb").read()
    at = 100
    while at < len(data):
        length = struct.unpack(">i", data[at + 4:at + 8])[0] * 2
        record = data[at + 8:at + 8 + length]
        at += 8 + length
        if struct.unpack("<i", record[:4])[0] == 0:  # a null shape
            yield []
            continue
        parts_n, points_n = struct.unpack("<ii", record[36:44])
        starts = list(struct.unpack(f"<{parts_n}i", record[44:44 + 4 * parts_n])) + [points_n]
        xy = struct.unpack(f"<{2 * points_n}d", record[44 + 4 * parts_n:44 + 4 * parts_n + 16 * points_n])
        yield [[(xy[2 * i], xy[2 * i + 1]) for i in range(starts[p], starts[p + 1])] for p in range(parts_n)]


def records(path):
    """A dBase table's rows as dicts of trimmed strings."""
    data = open(path, "rb").read()
    count, header, size = struct.unpack("<IHH", data[4:12])
    fields, at = [], 32
    while data[at] != 0x0D:
        fields.append((data[at:at + 11].split(b"\0")[0].decode(), data[at + 16]))
        at += 32
    for i in range(count):
        row, at, out = data[header + i * size + 1:header + (i + 1) * size], 0, {}
        for name, width in fields:
            out[name] = row[at:at + width].decode("utf-8", "replace").strip()
            at += width
        yield out


def mercator(lng, lat):
    lat = max(-MAX_LAT, min(MAX_LAT, lat))
    y = math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))
    return (lng + 180) / 360, (1 - y / math.pi) / 2


def simplify(points, tolerance):
    """Douglas-Peucker, without recursion. Keeps the first and last point."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    t2 = tolerance * tolerance
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = points[a], points[b]
        dx, dy = bx - ax, by - ay
        d2 = dx * dx + dy * dy
        far, far_i = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            if d2 == 0:
                e = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / d2))
                e = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if e > far:
                far, far_i = e, i
        if far > t2:
            keep[far_i] = True
            stack += [(a, far_i), (far_i, b)]
    return [p for p, k in zip(points, keep) if k]


def clip(ring, x0, y0, x1, y1):
    """Sutherland-Hodgman: a ring cut to a rectangle. Concave rings leave zero-width runs along the edge, which fill nothing."""
    def edge(points, inside, cross):
        out = []
        for i, p in enumerate(points):
            q = points[i - 1]
            if inside(p):
                if not inside(q):
                    out.append(cross(q, p))
                out.append(p)
            elif inside(q):
                out.append(cross(q, p))
        return out

    def at_x(x):
        return lambda q, p: (x, q[1] + (p[1] - q[1]) * (x - q[0]) / (p[0] - q[0]))

    def at_y(y):
        return lambda q, p: (q[0] + (p[0] - q[0]) * (y - q[1]) / (p[1] - q[1]), y)

    for inside, cross in (
        (lambda p: p[0] >= x0, at_x(x0)), (lambda p: p[0] <= x1, at_x(x1)),
        (lambda p: p[1] >= y0, at_y(y0)), (lambda p: p[1] <= y1, at_y(y1)),
    ):
        if not ring:
            break
        ring = edge(ring, inside, cross)
    return ring


def quantise(points, closed):
    out = []
    for x, y in points:
        q = (min(GRID, max(0, round(x * GRID))), min(GRID, max(0, round(y * GRID))))
        if not out or q != out[-1]:
            out.append(q)
    if closed and len(out) > 1 and out[0] == out[-1]:
        out.pop()
    return out if len(out) >= (3 if closed else 2) else None


def bounds(points):
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    return min(xs), min(ys), max(xs), max(ys)


def land(folder):
    rings = []
    for parts in shapes(os.path.join(folder, "ne_10m_land.shp")):
        rings += [[mercator(*p) for p in part] for part in parts]
    return rings


def borders(folder):
    """Country and state border lines, each a list of Mercator points, as India sees them."""
    countries, states = [], []
    for name, default in (("ne_10m_admin_0_boundary_lines_land", True), ("ne_10m_admin_0_boundary_lines_disputed_areas", False)):
        path = os.path.join(folder, name)
        for parts, row in zip(shapes(path + ".shp"), records(path + ".dbf")):
            # Claim lines are no border by default: only a point of view makes them one
            kind = row["FCLASS_IN"] or (row["FEATURECLA"] if default else "")
            if kind in ("", "Unrecognized"):
                continue
            target = states if kind in ("Admin-1 boundary", "Map unit boundary") else countries
            target += [[mercator(*p) for p in part] for part in parts]
    path = os.path.join(folder, "ne_10m_admin_1_states_provinces_lines")
    for parts, row in zip(shapes(path + ".shp"), records(path + ".dbf")):
        kind = row["FCLASS_IN"] or row["FEATURECLA"]
        if kind in STATE_CLASSES and float(row["MIN_ZOOM"] or 99) <= STATE_MAX_ZOOM:
            states += [[mercator(*p) for p in part] for part in parts]
    return countries, states


def cut(ring, cx, cy, span, n, margin, cells):
    """Files a ring that lies in the span x span squares from (cx, cy) under the squares it covers, quartering it on the
    way down, so a continent is cut a few times rather than once per square."""
    if span == 1:
        part = quantise(ring, closed=True)
        if part:
            cells.setdefault((cx, cy), []).append(part)
        return
    half = span // 2
    left, top, right, bottom = bounds(ring)
    for dx in (0, half):
        for dy in (0, half):
            x0, y0 = (cx + dx) / n - margin, (cy + dy) / n - margin
            x1, y1 = (cx + dx + half) / n + margin, (cy + dy + half) / n + margin
            if right < x0 or left > x1 or bottom < y0 or top > y1:
                continue
            inside = left >= x0 and right <= x1 and top >= y0 and bottom <= y1
            sub = ring if inside else clip(ring, x0, y0, x1, y1)
            if len(sub) >= 3:
                cut(sub, cx + dx, cy + dy, half, n, margin, cells)


def chunk_land(rings, lod):
    cells = {}
    for ring in rings:
        ring = simplify(ring, lod["tolerance"])
        if len(ring) < 4:
            continue
        left, top, right, bottom = bounds(ring)
        if (right - left) < lod["tolerance"] and (bottom - top) < lod["tolerance"]:
            continue  # an island smaller than the level can show
        n = lod["chunks"]
        cut(ring[:-1] if ring[0] == ring[-1] else ring, 0, 0, n, n, OVERLAP * lod["tolerance"], cells)
    return cells


def chunk_lines(lines, lod):
    n = lod["chunks"]
    cells = {}
    for line in lines:
        line = simplify(line, lod["tolerance"])
        # Runs of segments whose middles fall in the same square
        run, run_cell = [line[0]], None
        for p, q in zip(line, line[1:]):
            cell = (min(n - 1, int((p[0] + q[0]) / 2 * n)), min(n - 1, int((p[1] + q[1]) / 2 * n)))
            if run_cell is not None and cell != run_cell:
                part = quantise(run, closed=False)
                if part:
                    cells.setdefault(run_cell, []).append(part)
                run = [p]
            run.append(q)
            run_cell = cell
        part = quantise(run, closed=False) if run_cell is not None else None
        if part:
            cells.setdefault(run_cell, []).append(part)
    return cells


def varint(out, v):
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zigzag(v):
    return (v << 1) ^ (v >> 63)


def write_layer(out, kind, cells):
    out.append(kind)
    varint(out, len(cells))
    points = 0
    for cell in sorted(cells):
        parts = cells[cell]
        left = min(p[0] for part in parts for p in part)
        top = min(p[1] for part in parts for p in part)
        for v in (left, top, max(p[0] for part in parts for p in part), max(p[1] for part in parts for p in part)):
            varint(out, v)
        varint(out, len(parts))
        for part in parts:
            varint(out, len(part))
            x, y = left, top
            for px, py in part:
                varint(out, zigzag(px - x))
                varint(out, zigzag(py - y))
                x, y = px, py
            points += len(part)
    return points


def main(folder):
    fetch(folder)
    rings = land(folder)
    countries, states = borders(folder)
    layers = {LAND: rings, COUNTRY: countries, STATE: states}
    os.makedirs(OUT, exist_ok=True)
    for i, lod in enumerate(LODS):
        out = bytearray(b"AMAP")
        out += bytes([1, len(lod["layers"])])
        counts = []
        for kind in lod["layers"]:
            cells = chunk_land(rings, lod) if kind == LAND else chunk_lines(layers[kind], lod)
            counts.append(f"{['land', 'countries', 'states'][kind]} {write_layer(out, kind, cells):,} points")
        path = os.path.join(OUT, f"lod{i}.bin")
        with open(path, "wb") as f:
            f.write(out)
        print(f"lod{i}.bin  {len(out):,} bytes  {sha256(path)}  ({', '.join(counts)})")


if __name__ == "__main__":
    if len(sys.argv) > 1:
        main(sys.argv[1])
    else:
        with tempfile.TemporaryDirectory() as tmp:
            main(tmp)
