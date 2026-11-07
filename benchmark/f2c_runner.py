#!/usr/bin/env python3
"""Fields2Cover batch runner for the CPP comparison (Task 2, version 2, still UNTESTED end to end).

For every polygon (and every robot width) it runs a grid of Fields2Cover configurations and writes
    <out>/<polygon>/w<width>/<config_id>.txt   waypoints, same layout as your Java output
    <out>/summary.csv                          one row per polygon x width x configuration

Robot width modes (mirror the Java batch situations):
    --width 1.0                 fixed width (situations 1/2)
    --area-percent 2.0          circle of X% of the polygon area, width = its diameter (3/4)
    --sweep 0.5 2.0 0.5         min max step, every polygon at every width (5/6)

Examples (inside WSL, in ~/work/compare):
    python3 f2c_runner.py polygon.txt --width 1.0 --overlap 0.05 --out results
    python3 f2c_runner.py polygons_folder/ --sweep 0.5 2.0 0.5 --only none__Fixed0

Conventions, taken from your Java code:
  * effective width = width * (1 - overlap) is F2C's coverage width
  * boundary sweep ON: one full loop of the polygon inset by width/2 (= a headland pass), appended LAST
    (Java: addBoundarySweep walks the shrunk polygon after the last sweep)
  * straight connectors only: no Dubins / Reeds-Shepp curves
  * "Fixed0" = horizontal swaths, the analogue of Java with optimal rotation OFF;
    the other objectives search the angle, the analogue of rotation ON
  * start point: the OR-tools planner accepts one, so it gets the polygon centroid (snapped to the
    inset region if outside it; the raw centroid stays as waypoint 0, like Java's click point).
    The other planners have no start input, so their first waypoint is the Java start.
    java_start_x / java_start_y in summary.csv is what to give the Java side.
Layouts:
    headland (default)  F2C's usual recipe: one headland pass, swaths in the field inset by a full width
    aligned             swaths in the field inset by width/2, as your Java does, then the boundary loop
"""
import argparse
import csv
import glob
import math
import os
import re
import time
import traceback

import fields2cover as f2c

DECOMPS = ["none", "trapezoidal", "boustrophedon"]
OBJECTIVES = ["Fixed0", "NSwath", "NSwathModified", "SwathLength", "FieldCoverage", "Overlaps"]  # RemArea is not a swath-angle objective in this build
SORTERS = ["boustrophedon", "snake", "spiral"]  # valid only for a single-cell swath region
LAYOUT_TAG = {"headland": "hl", "aligned": "al"}
TOL = 1e-9


# ----------------------------------------------------------------- geometry helpers
def read_polygon(path):
    pts = []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            nums = re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", line)
            pts.append((float(nums[0]), float(nums[1])))
    return pts


def signed_area(pts):
    s = 0.0
    for i, (x1, y1) in enumerate(pts):
        x2, y2 = pts[(i + 1) % len(pts)]
        s += x1 * y2 - x2 * y1
    return s / 2.0


def centroid(pts):
    a = signed_area(pts)
    cx = cy = 0.0
    for i, (x1, y1) in enumerate(pts):
        x2, y2 = pts[(i + 1) % len(pts)]
        c = x1 * y2 - x2 * y1
        cx += (x1 + x2) * c
        cy += (y1 + y2) * c
    return cx / (6.0 * a), cy / (6.0 * a)


def point_in_polygon(p, pts):
    x, y = p
    inside = False
    for i, (x1, y1) in enumerate(pts):
        x2, y2 = pts[(i + 1) % len(pts)]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def make_cells(pts):
    if signed_area(pts) < 0:  # exterior ring counter-clockwise
        pts = pts[::-1]
    ring = f2c.LinearRing()
    for x, y in pts + [pts[0]]:
        ring.addPoint(f2c.Point(x, y))
    cell = f2c.Cell()
    cell.addRing(ring)
    cells = f2c.Cells()
    cells.addGeometry(cell)
    return cells


def widths_for(args, area):
    """Robot widths to run for one polygon, following BatchRunner's three modes."""
    if args.widths:
        return list(args.widths)
    if args.sweep:
        lo, hi, step = min(args.sweep[:2]), max(args.sweep[:2]), abs(args.sweep[2])
        if step < 1e-9:
            step = (hi - lo) if hi > lo else 1.0
        n = max(1, int(math.floor((hi - lo) / step + 1e-9)) + 1)
        ws = [lo + i * step for i in range(n)]
        ws[-1] = min(ws[-1], hi)
        return ws
    if args.area_percent:
        robot_area = args.area_percent / 100.0 * area
        return [2.0 * math.sqrt(robot_area / math.pi)]
    return [args.width]


# ----------------------------------------------------------------- F2C object access
def child(obj, i):
    """i-th element of a Cells / LineString / Swaths / SwathsByCells (API varies by type)."""
    for name in ("getGeometry", "at", "get"):
        if hasattr(obj, name):
            try:
                return getattr(obj, name)(i)
            except TypeError:  # e.g. LineString.get() takes no index
                continue
    return obj[i]


def xy(p):
    return (p.getX(), p.getY())


def linestring_points(ls):
    return [xy(child(ls, i)) for i in range(ls.size())]


def iter_swaths(obj):
    """Yields single swaths from a Swath / Swaths / SwathsByCells, however nested."""
    if hasattr(obj, "startPoint") and hasattr(obj, "getPath"):
        yield obj
        return
    if isinstance(obj, (tuple, list)):  # some builds return several values as a tuple
        for item in obj:
            yield from iter_swaths(item)
        return
    if not hasattr(obj, "size"):  # not a swath container (e.g. a number in a tuple)
        return
    for i in range(obj.size()):
        yield from iter_swaths(child(obj, i))


def swath_points(sw):
    pts = linestring_points(sw.getPath())
    return pts if len(pts) >= 2 else [xy(sw.startPoint()), xy(sw.endPoint())]


def exterior_ring_points(cell):
    """Vertices of a Cell's exterior ring (accessor names differ between builds)."""
    tries = (lambda: cell.getExteriorRing(), lambda: cell.getGeometry(0), lambda: cell.get(0))
    for t in tries:
        try:
            pts = linestring_points(t())
            if len(pts) >= 3:
                return pts
        except Exception:  # noqa: BLE001
            continue
    return []


def route_points(route):
    """Full route polyline (swaths + connections); falls back to swaths only."""
    try:
        pts = linestring_points(route.asLineString())
        if len(pts) >= 2:
            return pts
    except Exception:  # noqa: BLE001
        pass
    # Route.getSwaths() in this build needs an index (Route::getSwaths(size_t)), it is not a
    # zero-arg "everything" accessor -- getVectorSwaths()/sizeVectorSwaths() is the right pair.
    out = []
    try:
        n = route.sizeVectorSwaths()
        for i in range(n):
            for sw in iter_swaths(route.getSwaths(i)):
                out.extend(swath_points(sw))
    except Exception:  # noqa: BLE001
        for sw in iter_swaths(route.getVectorSwaths()):
            out.extend(swath_points(sw))
    return out


# ----------------------------------------------------------------- waypoint helpers
def dedupe(points, tol=TOL):
    out = []
    for p in points:
        if not out or math.dist(out[-1], p) > tol:
            out.append(p)
    return out


def rotate_ring(ring, ref):
    """Start the closed ring at the vertex nearest to ref, and close it again."""
    pts = ring[:-1] if len(ring) > 1 and math.dist(ring[0], ring[-1]) < 1e-6 else list(ring)
    k = min(range(len(pts)), key=lambda i: math.dist(pts[i], ref))
    pts = pts[k:] + pts[:k]
    return pts + [pts[0]]


def write_waypoints(path, points, meta):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write("# Coverage Path Waypoints (Fields2Cover)\n")
        for k, v in meta.items():
            fh.write(f"# {k:<14}: {v}\n")
        fh.write(f"# Total waypoints: {len(points)}\n# Format: (x, y)\n#\n")
        for x, y in points:
            fh.write(f"({round(x, 6)}, {round(y, 6)})\n")


def route_start_point(cen, swaths):
    """Nearest swath ENDPOINT to the centroid: OR-tools builds its routing graph from swath
    endpoints only, so the start point handed to it must be exactly one of them, or its internal
    node lookup returns -1 and the library aborts the whole process (uncatchable from Python).
    Returns (point, True) -- 'snapped' is always True here, since the graph point is essentially
    never exactly the centroid; the raw centroid is still kept as waypoint 0 by the caller."""
    candidates = [xy(p) for sw in iter_swaths(swaths) for p in (sw.startPoint(), sw.endPoint())]
    if not candidates:
        raise ValueError("no swaths to anchor a start point to (region too small for this width?)")
    return min(candidates, key=lambda p: math.dist(p, cen)), True


def make_swaths(bf, obj_name, cov, region):
    """Fixed0 = horizontal swaths; anything else = brute-force best angle for that objective."""
    if obj_name == "Fixed0":
        return bf.generateSwaths(0.0, cov, region)
    obj = getattr(f2c, "OBJ_" + obj_name)()  # AttributeError -> logged as unavailable
    return bf.generateBestSwaths(obj, cov, region)


# ----------------------------------------------------------------- one configuration
def run_config(cells, cen, width, cov, layout, decomp, obj_name, route_name, args):
    hl = f2c.HG_Const_gen()
    bf = f2c.SG_BruteForce()

    mid = hl.generateHeadlands(cells, width / 2.0)  # region the robot centre may use
    if layout == "headland":
        if decomp == "none":
            region = hl.generateHeadlands(cells, width)
        else:
            d = f2c.DECOMP_TrapezoidalDecomp() if decomp == "trapezoidal" else f2c.DECOMP_Boustrophedon()
            region = hl.generateHeadlands(d.decompose(mid), width / 2.0)
    else:  # aligned: swaths in the field inset by width/2, as in the Java code
        if decomp == "none":
            region = mid
        else:
            d = f2c.DECOMP_TrapezoidalDecomp() if decomp == "trapezoidal" else f2c.DECOMP_Boustrophedon()
            region = d.decompose(mid)
    if region.size() == 0:
        raise ValueError("empty swath region after inset (width too large for this polygon?)")

    start_given, snapped = None, False
    if route_name == "ortools":
        swaths = make_swaths(bf, obj_name, cov, region)
        if sum(1 for _ in iter_swaths(swaths)) == 0:
            raise ValueError("no swaths generated (region too small for this width?)")
        start_given, snapped = route_start_point(cen, swaths)
        planner = f2c.RP_RoutePlannerBase()
        planner.setStartAndEndPoint(f2c.Point(*start_given))
        pts = route_points(planner.genRoute(mid, swaths))
    else:
        if region.size() != 1:
            raise ValueError(f"sorter needs a single-cell swath region, got {region.size()} cells")
        swaths = make_swaths(bf, obj_name, cov, region.getGeometry(0))
        if sum(1 for _ in iter_swaths(swaths)) == 0:
            # Same geometric infeasibility the ortools branch above already checks for
            # (robot too wide for this decomposed region at this width) -- this branch was
            # simply missing the equivalent check, so the condition fell through silently
            # until the empty-pts crash further down instead of being reported cleanly here.
            raise ValueError("no swaths generated (region too small for this width?)")
        sorter = {"boustrophedon": f2c.RP_Boustrophedon,
                  "snake": f2c.RP_Snake,
                  "spiral": lambda: f2c.RP_Spiral(args.spiral_size)}[route_name]()
        pts = []
        for sw in iter_swaths(sorter.genSortedSwaths(swaths)):
            pts.extend(swath_points(sw))
    n_swaths = sum(1 for _ in iter_swaths(swaths))

    pts = dedupe(pts)
    if not pts:
        # Defensive backstop: should be unreachable given the checks above (swath_points()
        # always returns >=2 points per swath, and dedupe() can't empty a non-empty list),
        # but fail cleanly here rather than crash with an IndexError if some other code path
        # not covered by the reasoning above ever reaches this point with zero points.
        raise ValueError("route/sorter produced zero waypoints (empty route from OR-tools, "
                          "or swaths with no usable points)")
    return_dropped = False
    if start_given is not None:
        if math.dist(pts[0], start_given) > 1e-6:
            pts.insert(0, start_given)
        if len(pts) > 2 and math.dist(pts[-1], start_given) < 1e-6:
            pts.pop()  # planner returned to its end point; that leg is not coverage
            return_dropped = True
        if snapped:
            pts.insert(0, cen)  # Java keeps the raw click point as waypoint 0

    # boundary sweep: one full loop of the width/2 inset, appended last, from the nearest vertex
    # (In this build generateHeadlandSwaths returns polygon Cells, not swaths, so the ring is taken
    #  straight from the inset polygon `mid`: the same geometry as a width-wide headland pass.)
    rings = []
    for i in range(mid.size()):
        ring = exterior_ring_points(mid.getGeometry(i))
        if len(ring) >= 3:
            rings.append(ring)
    if not rings:
        raise ValueError("could not read a boundary ring from the inset polygon")
    for ring in rings:
        pts.extend(rotate_ring(ring, pts[-1]))
    pts = dedupe(pts)

    start = cen if start_given is not None else pts[0]
    return pts, {
        "n_swaths": n_swaths,
        "start_mode": "centroid_given" if start_given is not None else "first_waypoint",
        "start_snapped": snapped,
        "java_start_x": round(start[0], 6),
        "java_start_y": round(start[1], 6),
        "return_leg_dropped": return_dropped,
        "headland_piece_count": mid.size(),  # >1 means F2C's own headland/inset step hit a
                                              # narrow neck and split, same signal as Java's
                                              # polygonWasSplit -- NOT the same as decomposition
                                              # (which always yields >1 cell on purpose)
    }


def configs(only, layouts, objectives=None, routes_none=None):
    """objectives: restrict OBJECTIVES if given. routes_none: restrict the route planners tried
    when decomposition == 'none' (SORTERS + ortools); decomposed cases always use ortools, unfiltered,
    since it's the only planner that can sequence more than one cell."""
    objs = [o for o in OBJECTIVES if objectives is None or o in objectives]
    for layout in layouts:
        for decomp in DECOMPS:
            if decomp == "none":
                routes = [r for r in SORTERS + ["ortools"] if routes_none is None or r in routes_none]
            else:
                routes = ["ortools"]
            for obj in objs:
                for route in routes:
                    cid = f"{LAYOUT_TAG[layout]}__{decomp}__{obj}__{route}"
                    if only in cid:
                        yield cid, layout, decomp, obj, route


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("polygons", nargs="+", help="polygon .txt files and/or folders of them")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--width", type=float, default=1.0, help="fixed robot width (default 1.0)")
    g.add_argument("--widths", type=lambda s: [float(x) for x in s.split(",")],
                   help="comma-separated list of exact widths, e.g. 2,4,8,12,18,24")
    g.add_argument("--area-percent", type=float, help="width = diameter of a circle of X%% of polygon area")
    g.add_argument("--sweep", type=float, nargs=3, metavar=("MIN", "MAX", "STEP"), help="width sweep")
    ap.add_argument("--overlap", type=float, default=0.05)
    ap.add_argument("--layout", choices=["headland", "aligned", "both"], default="headland")
    ap.add_argument("--out", default="results")
    ap.add_argument("--spiral-size", type=int, default=6)
    ap.add_argument("--only", default="", help="run only configs whose id contains this text")
    ap.add_argument("--objectives", type=lambda s: set(s.split(",")),
                    help="comma list restricting OBJECTIVES, e.g. Fixed0,NSwath,SwathLength")
    ap.add_argument("--routes-none", type=lambda s: set(s.split(",")),
                    help="comma list restricting route planners used when decomposition=none, e.g. snake")
    ap.add_argument("--resume", action="store_true",
                    help="reuse 'ok' rows already in <out>/summary.csv instead of recomputing them")
    args = ap.parse_args()

    files = []
    for p in args.polygons:
        files += sorted(glob.glob(os.path.join(p, "*.txt"))) if os.path.isdir(p) else [p]
    layouts = ["headland", "aligned"] if args.layout == "both" else [args.layout]

    os.makedirs(args.out, exist_ok=True)
    fields = ["polygon", "robot_width", "overlap", "polygon_area", "n_vertices", "config_id", "layout",
              "decomposition", "objective", "route_planner", "status", "n_swaths", "n_waypoints",
              "start_mode", "start_snapped", "java_start_x", "java_start_y", "centroid_inside",
              "return_leg_dropped", "headland_piece_count", "runtime_s", "waypoint_file", "error"]

    done = {}  # (polygon, round(width,6), config_id) -> row, for --resume
    summary_path = os.path.join(args.out, "summary.csv")
    if args.resume and os.path.exists(summary_path):
        with open(summary_path, newline="") as fh:
            for row in csv.DictReader(fh):
                if row.get("status") == "ok":
                    key = (row["polygon"], row["robot_width"], row["config_id"])
                    done[key] = row
        print(f"--resume: {len(done)} previously-ok rows will be reused, not recomputed")

    # Quarantine: a native crash (segfault/abort) inside Fields2Cover or OR-tools kills THIS process
    # instantly -- no Python exception, no chance to write a row. The only trace is the marker file
    # written just before each attempt. If that file is still here from a PREVIOUS run and doesn't say
    # COMPLETED, the row it names is what killed that run; record it permanently in known_crashes.csv
    # so every future run (this one included) treats it as a known-bad row and skips it, rather than
    # crashing again on the exact same input forever.
    attempt_marker = os.path.join(args.out, "_current_attempt.txt")
    known_crashes_path = os.path.join(args.out, "known_crashes.csv")
    skip_keys = set()
    if os.path.exists(known_crashes_path):
        with open(known_crashes_path, newline="") as fh:
            for row in csv.DictReader(fh):
                skip_keys.add((row["polygon"], row["robot_width"], row["config_id"]))
    if os.path.exists(attempt_marker):
        content = open(attempt_marker).read().strip()
        if content and content != "COMPLETED":
            parts = content.split("\t")
            if len(parts) == 3 and tuple(parts) not in skip_keys:
                skip_keys.add(tuple(parts))
                is_new = not os.path.exists(known_crashes_path)
                with open(known_crashes_path, "a", newline="") as fh:
                    w = csv.writer(fh)
                    if is_new:
                        w.writerow(["polygon", "robot_width", "config_id"])
                    w.writerow(parts)
                print(f"[quarantine] previous run crashed on {parts[0]} w={parts[1]} / {parts[2]}"
                      f" -- recorded in known_crashes.csv, will be skipped from now on")
    if skip_keys:
        print(f"{len(skip_keys)} row(s) in known_crashes.csv will be skipped (not re-attempted)")

    with open(summary_path, "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for f in files:
            name = os.path.splitext(os.path.basename(f))[0]
            try:
                pts = read_polygon(f)
                cells, cen = make_cells(pts), centroid(pts)
                area = abs(signed_area(pts))
                inside = point_in_polygon(cen, pts)
            except Exception as exc:  # noqa: BLE001
                print(f"[ SKIP] {name}: could not load polygon: {type(exc).__name__}: {exc}", flush=True)
                row = dict.fromkeys(fields, "")
                row.update(polygon=name, config_id="N/A", status="failed",
                           error=f"could not load polygon: {type(exc).__name__}: {exc}"[:400])
                writer.writerow(row)
                fh.flush()
                continue
            for width in widths_for(args, area):
                cov = width * (1.0 - args.overlap)
                for cid, layout, decomp, obj, route in configs(args.only, layouts, args.objectives, args.routes_none):
                    key = (name, str(round(width, 6)), cid)
                    if key in done:
                        writer.writerow(done[key])
                        fh.flush()
                        continue
                    row = dict.fromkeys(fields, "")
                    row.update(polygon=name, robot_width=round(width, 6), overlap=args.overlap,
                               polygon_area=area, n_vertices=len(pts), config_id=cid, layout=layout,
                               decomposition=decomp, objective=obj, route_planner=route,
                               centroid_inside=inside)
                    if key in skip_keys:
                        row.update(status="failed", runtime_s=0,
                                   error="skipped: this row crashed the process (segfault/abort) in an "
                                         "earlier run -- see known_crashes.csv")
                        writer.writerow(row)
                        fh.flush()
                        print(f"[ skip] {name} w={round(width, 4):g} / {cid}  (known crash)", flush=True)
                        continue
                    # Written/printed BEFORE the attempt: a native crash inside Fields2Cover/OR-tools
                    # kills the process with no Python exception and no chance to print an outcome
                    # afterwards, so this is the only way to know which row was running when it happened.
                    attempt_note = f"{name} w={round(width, 4):g} / {cid}"
                    print(f"[trying] {attempt_note}", flush=True)
                    with open(attempt_marker, "w") as af:
                        af.write("\t".join(key) + "\n")
                    t0 = time.time()
                    try:
                        wp, info = run_config(cells, cen, width, cov, layout, decomp, obj, route, args)
                        wfile = os.path.join(args.out, name, f"w{round(width, 6):g}", cid + ".txt")
                        write_waypoints(wfile, wp, {
                            "Polygon": os.path.basename(f), "Robot width": round(width, 6),
                            "Overlap ratio": args.overlap, "Config": cid,
                            "Start mode": info["start_mode"]})
                        row.update(info, status="ok", n_waypoints=len(wp), waypoint_file=wfile)
                    except Exception as exc:  # noqa: BLE001
                        loc = traceback.extract_tb(exc.__traceback__)[-1]
                        row.update(status="failed",
                                   error=f"{type(exc).__name__}: {exc} @ {loc.name}:{loc.lineno} [{loc.line}]"[:400])
                    row["runtime_s"] = round(time.time() - t0, 3)
                    writer.writerow(row)
                    fh.flush()
                    print(f"[{row['status']:>6}] {name} w={round(width, 4):g} / {cid}  {row['error']}", flush=True)

    with open(attempt_marker, "w") as af:
        af.write("COMPLETED\n")  # tells the NEXT invocation this run ended cleanly, not mid-crash


if __name__ == "__main__":
    main()
