# Data

`polys/` holds 299 field polygons, one per `.txt` file. The file name starts with the country code of the field:
`ee` (Estonia, 71 fields), `lt` (Lithuania, 97) and `nl` (the Netherlands, 131).

## Provenance
The fields are a subset of the **Fields2Benchmark** dataset (350 fields; Mier, Casado Faulí, Valente and de Bruin,
*Smart Agricultural Technology* 12 (2025) 101156; dataset: https://zenodo.org/records/14524735), whose boundaries were
extracted from EuroCrops. We kept the **299 fields without interior holes** (obstacles) and converted each Well-Known-Text
boundary as follows:

1. The source coordinates are WGS84 longitude/latitude in degrees. They were converted to local metric coordinates with an
   equirectangular projection about the centroid of each field.
2. The coordinates were then shifted so that the minimum x and y of the bounding box are 0. The original offset is recorded
   in the second header line of every file.
3. The polygon was written as a vertex list.

Coordinates are therefore in **metres**, in a local frame; no georeference is kept.

## File format
```
# Source was WGS84 lon/lat degrees; converted to local metres (equirectangular about the field's own centroid)
# Local bbox min before re-basing to (0, 0), in metres: (-56.963, -79.133)
(94.8981, 50.1442)
(108.6142, 26.3852)
...
```
Lines starting with `#` are comments. Every other line is one vertex `(x, y)`, in boundary order.

## Licence
These derived files carry the licence of the Fields2Benchmark dataset, CC BY-SA 4.0, see `LICENSE.md`.
