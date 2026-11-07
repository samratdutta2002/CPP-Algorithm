# Licence of the data in `polys/`

The polygon files in this folder are adapted material derived from the Fields2Benchmark dataset
(G. Mier, A. M. Casado Faulí, J. Valente, S. de Bruin; https://zenodo.org/records/14524735), which in turn was
extracted from EuroCrops for the Netherlands, Estonia and Lithuania. The source-country terms listed on the
dataset record also apply.

They are shared under the **Creative Commons Attribution-ShareAlike 4.0 International** licence
(https://creativecommons.org/licenses/by-sa/4.0/).

Changes made: selection of the 299 fields without interior holes; conversion from longitude/latitude (WGS84) to local metric
coordinates (equirectangular projection about each field's centroid); translation of the bounding-box minimum to the origin;
conversion from WKT to a plain vertex list.
