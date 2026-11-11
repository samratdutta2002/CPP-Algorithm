#!/usr/bin/env python3
"""Convert Fields2Benchmark WKT files to the project's .txt vertex format.

    pip install shapely
    python3 wkt_to_txt.py fields2benchmark/wkt --out polygons_txt

Keeps a polygon only if it:
  - parses as a WKT POLYGON (not MULTIPOLYGON, not a Polygon Z with a 3rd coordinate)
  - has no interior rings (no holes / obstacles)
  - is simple (no self-intersections) and has a non-zero area
  - has at least 4 vertices before closing the ring

Coordinates are typically UTM metres (large numbers), so each kept polygon is re-based:
translated so its own bounding-box minimum sits at (0, 0), matching the scale of your
existing sample files. The original bounding-box corner is recorded in the output header
in case you ever need to map back.

Writes:
    <out>/<name>.txt        one file per kept polygon, in your "(x, y)" format
    <out>/report.txt        counts and a per-file reason for every skip
    <out>/kept.csv          name, n_vertices, area, width, height for every kept polygon
"""
import argparse
import csv
import glob
import math
import os
import re

from shapely import wkt as shapely_wkt
from shapely.geometry import Polygon
from shapely.validation import explain_validity

# Metres per degree of latitude / longitude, at a reference latitude (WGS84 sphere approximation).
# Good to well under 1% for this purpose: fields are a few hundred metres across, not continent-sized.
_M_PER_DEG_LAT = 110_574.0


def looks_geographic(geom):
    """True if the coordinates look like WGS84 lon/lat degrees rather than a projected (metre) CRS."""
    minx, miny, maxx, maxy = geom.bounds
    return -180.0 <= minx <= 180.0 and -180.0 <= maxx <= 180.0 and -90.0 <= miny <= 90.0 and -90.0 <= maxy <= 90.0 \
        and (maxx - minx) < 1.0 and (maxy - miny) < 1.0  # a field is not 1 degree (~100 km) wide


def to_local_metres(geom):
    """Equirectangular projection about the polygon's own centroid: good enough for a single field."""
    lat0 = geom.centroid.y
    m_per_deg_lon = _M_PER_DEG_LAT * math.cos(math.radians(lat0))
    coords = [((x - geom.centroid.x) * m_per_deg_lon, (y - lat0) * _M_PER_DEG_LAT)
              for x, y in geom.exterior.coords]
    return Polygon(coords)


def load_wkt(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read().strip()
    # Some exports prefix the geometry with "SRID=xxxx;"
    text = re.sub(r"^SRID=\d+;", "", text, flags=re.IGNORECASE).strip()
    return shapely_wkt.loads(text)


def classify(path):
    """Returns (polygon or None, was_geographic, reason_if_skipped)."""
    try:
        geom = load_wkt(path)
    except Exception as exc:  # noqa: BLE001
        return None, False, f"unparsable WKT: {exc}"

    if geom.geom_type == "MultiPolygon":
        return None, False, f"MultiPolygon with {len(geom.geoms)} parts"
    if geom.geom_type != "Polygon":
        return None, False, f"not a polygon ({geom.geom_type})"
    if len(geom.interiors) > 0:
        return None, False, f"has {len(geom.interiors)} hole(s)"
    if not geom.is_valid:
        return None, False, f"invalid geometry: {explain_validity(geom)}"
    if geom.area <= 0:
        return None, False, "zero area"
    n = len(geom.exterior.coords) - 1  # coords repeats the first point at the end
    if n < 4:
        return None, False, f"only {n} vertices"
    geographic = looks_geographic(geom)
    if geographic:
        geom = to_local_metres(geom)
        if not geom.is_valid or geom.area <= 0:
            return None, False, "degenerate after lon/lat -> metres conversion"
    return geom, geographic, None


def write_polygon(path, geom, was_geographic):
    minx, miny, maxx, maxy = geom.bounds
    coords = list(geom.exterior.coords)[:-1]  # drop the repeated closing point
    if Polygon(coords).exterior.is_ccw is False:
        coords = coords[::-1]  # keep exteriors counter-clockwise, like your sample file
    with open(path, "w") as fh:
        if was_geographic:
            fh.write("# Source was WGS84 lon/lat degrees; converted to local metres "
                      "(equirectangular about the field's own centroid)\n")
        fh.write(f"# Local bbox min before re-basing to (0, 0), in metres: ({minx:.3f}, {miny:.3f})\n")
        for x, y in coords:
            fh.write(f"({x - minx:.4f}, {y - miny:.4f})\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wkt_dir", help="folder of .wkt files (Fields2Benchmark's wkt/ folder)")
    ap.add_argument("--out", default="polygons_txt")
    ap.add_argument("--pattern", default="*.wkt")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.wkt_dir, args.pattern)))
    if not files:
        print(f"No files matching {args.pattern} in {args.wkt_dir}")
        return
    os.makedirs(args.out, exist_ok=True)

    kept, skipped = [], []
    n_geo = 0
    for path in files:
        name = os.path.splitext(os.path.basename(path))[0]
        geom, geographic, reason = classify(path)
        if geom is None:
            skipped.append((name, reason))
            continue
        n_geo += geographic
        write_polygon(os.path.join(args.out, name + ".txt"), geom, geographic)
        minx, miny, maxx, maxy = geom.bounds
        kept.append((name, len(geom.exterior.coords) - 1, geom.area, maxx - minx, maxy - miny, geographic))

    with open(os.path.join(args.out, "kept.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["name", "n_vertices", "area_m2", "width_m", "height_m", "was_lonlat_converted"])
        w.writerows(kept)

    with open(os.path.join(args.out, "report.txt"), "w") as fh:
        fh.write(f"Fields2Benchmark WKT -> .txt conversion\n{'=' * 40}\n")
        fh.write(f"Input files      : {len(files)}\n")
        fh.write(f"Kept (no holes)  : {len(kept)}\n")
        fh.write(f"  of which converted from lon/lat degrees to local metres: {n_geo}\n")
        fh.write(f"Skipped          : {len(skipped)}\n\n")
        reasons = {}
        for _, r in skipped:
            key = r.split(":")[0]
            reasons[key] = reasons.get(key, 0) + 1
        fh.write("Skip reasons (by category):\n")
        for k, v in sorted(reasons.items(), key=lambda kv: -kv[1]):
            fh.write(f"  {v:4d}  {k}\n")
        fh.write("\nPer-file skip list:\n")
        for name, r in skipped:
            fh.write(f"  {name}: {r}\n")

    print(f"Kept {len(kept)} / {len(files)} polygons -> {args.out}")
    print(f"See {os.path.join(args.out, 'report.txt')} for why the rest were skipped.")


if __name__ == "__main__":
    main()