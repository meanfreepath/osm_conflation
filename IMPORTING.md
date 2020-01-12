# Importing GTFS Data
**Important**: before you can import data, you’ll need a combined `routes.osm` file for your service area.

## Getting Started
* Download [JOSM](https://josm.openstreetmap.de/), an open-source editor for OpenStreetMap
* Unzip the importer `.zip` file and copy the `routes.osm` file into it
* Add the included `segmentdebug.mapcss` to JOSM via the **Settings->Map Settings->Map Paint Styles** menu.

## Output
The importer imports all routes using the `public_transport:version=2` schema.
* Platforms: i.e. `highway=bus_stop`, added to the route relation as a `platform`
* Stop positions: i.e. the position on the road where the vehicle stops, added to the route relation as a `stop`
* Ways: all roads/rails/etc. between the stops that match the GTFS route’s shape are added to the route relation

### Output files
After an import is run, the following files are output:
* `gtfsroute_[route_id].osm`: a .osm file containing a GTFS route and its stops.  Can be edited to improve matching to OSM roads during import.  **Not** safely uploadable to OpenStreetMap.
* `workingspace_[route_id].osm`: a .osm file containing all downloaded data and GTFS data for the route – useful for tracking down errors during route import.  **Not** safely uploadable to OpenStreetMap.
* `relations_[route_id].osm`: an uploadable .osm file created for successfully-imported routes only.  Contains the GTFS route as matched to the OSM road network and any nodes, ways, or relations that were modified during the import. This file can be opened by JOSM, edited, and safely uploaded to OpenStreetMap.

**NOTE:** no changes are made to the OpenStreetMap database until you upload the relations file. 

## Usage
The importer runs from the command line using `java`, e.g.
```shell script
java -jar osmgtfs_importer.jar -f [path/to/routes.osm] -r [route1_id,route2_id,...]
```

## Importing a Route
### Route ids
The route ids is the value of the `route_id` column for that route in the GTFS `routes.txt` file.
You can import multiple routes simultaneously by adding a comma-separated list of route ids
after the `-r` option. 

### Import the stops first (RECOMMENDED)
While it’s possible to import a route and its stops in one action, it is *strongly recommended* that you import the stops first!
In general most transit feeds provide stop positions that are off by up to 100 feet from ground truth, which is bad
for both OSM users and for the importer’s matching algorithm.

You can import a route’s stops by specifying the `-s` option:
```shell script
java -jar osmgtfs_importer.jar -f [path/to/routes.osm] -r [route_id] -s
```
Once you’ve uploaded the stops, run the importer again without the `-s` option.

#### Matching with existing stops
The importer will attempt to match the GTFS stops with existing nearby OSM stops whenever possible.
This is done using the `gtfs:stop_id` or `ref` tags (if present).  If a nearby OSM stop has neither of these tags,
it will be marked with a `gtfs:conflict` tag (and show up as red in JOSM, if you’ve installed the stylesheet).

To fix, first take a closer look at the satellite image (or survey) and check if it’s safe to merge the two stops.
If everything looks good, select both the OSM and GTFS stop and tap the `M` key to merge them – don’t forget to delete the `gtfs:conflict` tag!

When you’ve imported all the stops, simply upload to OSM, then wait a few minutes before importing the route
to allow Overpass to catch up.

### Import the route
Run the following commend to import a route:
```shell script
java -jar osmgtfs_importer.jar -f [path/to/routes.osm] -r [route_id]
```
If you want to import multiple routes (especially if they serve the same geographical area), you can 
add multiple `route_ids`, separated by a comma.

**IMPORTANT**: if you’ve recently uploaded changes to OSM, make sure to add a `-n` to the above command.
This ensures you’re getting the latest OSM data and will help prevent conflicts.

#### Matching with existing routes
The importer makes every effort to avoid duplication of existing transit data.  When importing a route,
it tries to match to existing OSM relations by matching on the following tags:
1. `gtfs:route_id`: if an existing OSM relation has this tag, the GTFS route is assigned to it and its tags and members will replace the OSM route’s. 
2. `ref`: existing OSM routes arematched based on the route types and their `ref` tags.
In either case, if the OSM tags differ from the GTFS tags, the GTFS tags will take precedence.

**IMPORTANT**: currently the importer does not differentiate between routes from different transit agencies.
If there are multiple routes with the same `route_id`/`ref` in your area, use caution.   


## Troubleshooting
### Most common error causes
While the importer makes the best efforts to match the route to the OSM road network, it can’t
handle all error cases.   
Most errors arise from issues with the OSM road network (disconnected roads, bad angles) or
 the GTFS data (out of date road alignments, poor alignment with real-world roads, wrong directions on one-way streets, etc).
 
 **IMPORTANT:** If you upload any changes to OSM (i.e. to fix a data issue), please wait a few minutes before re-running the import.  Make sure to
  add the `-n` option to the arguments: this ensures you’re downloading the latest data from Overpass and prevents conflicts.

Here are some of the most common issues and how to resolve them: 
* Bus stops in the imported data are incorrectly positioned, confusing the matching algorithm – this is a common issue.
  * **Solution:** import the bus stops first, using the `-s` option, position them appropriately, and upload to OSM.  Then re-run the route import the route without the `-s` option.
* There are issues with the OSM road network along the route (disconnected, bad angles, out of date)
  * **Solution:** make an OSM edit that fixes the issues, the run the import again.
* The GTFS route shape is out of date or poorly matches the OSM road network.
  * **Solution:** open the `output/gtfsroute_[route_id].osm` file in JOSM and align it to the OSM road network (use the **OpenStreetMap Carto** imagery layer).  Save it (don’t upload), then re-run the import.  
* A bus stop is too close to an intersection where a turn is made
  * **Solution:** move the bus stop further away from the intersection, and run the import again.
* The importer is failing when trying to process the first or last stop on the route
  * **Solution:** open the `output/gtfsroute_[route_id].osm` file in JOSM and make sure the route line extends to the first/last stop on the route.
* “Unable to import one or more routes” error.
  * **Solution:** see the *Advanced Troubleshooting* section

### Advanced Troubleshooting
Whenever the importer can’t properly import a route, it outputs a file with the name `workingspace_[route_id].osm`.  This file contains
all the OSM data it was working with while trying to process the route, and can be opened in JOSM to examine what went wrong.

Whenever the importer can’t find a path between two stops, it will print out all the failed paths, with info on what streets it tried to
take before giving up.  You can find the place where the algorithm gave up by finding the last node on the path
(typically it’ll be something like `2908353866 to NONE`).

The number above will be the id of the last node in the path - you can find it in JOSM by
 going to JOSM’s **Edit->Search** menu and entering `id:2908353866` (change the number to the node id you’re searching for), 
 and hit **Search** to select that node.  Then hit the `3` key to zoom in to its location.
 
Once you’re looking at the last location of the failed path, check for possible issues with the GTFS line and the OSM roads
around the point.  This can be bad alignment between the GTFS and OSM data, missing roads, disconnected nodes, etc.

If there’s an issue with the GTFS line, open the `gtfsroute_[route_id].osm` file and fix it there, then save it (don’t upload).
Then re-run the import (no need for the `-n` option here) and see if that fixes the issue.

If there’s an issue with the OSM data, you can fix it in JOSM, upload it, and re-run the import after a few minutes (make sure to use the `-n` option). 
**IMPORTANT:** make sure to download the area you’re editing to a new layer and make your edits there – don’t upload the `workingspace` file to OSM!  


#### Known Issues
* The importer ignores road segments that extend beyond its first/last stops.  If there are turnarounds etc. before or after a route, they are ignored.
* Tight road loops (<100ft) are often not handled well by the importer.  You may need to handle these manually.
* Turn restrictions and `access` tags are currently ignored by the importer: it’s assumed that routes have the necessary permissions to use the roads they’re aligned with.

### Dealing with Conflicts
Most upload conflicts arise from the delay between the Overpass API and the main OSM database.  You can 
avoid these issues by following these guidelines: 
* Avoid importing routes in the same area in rapid succession: wait 10-15 minutes between uploads.  If you need to import multiple routes at the same time, make them part of the same batch.
* Locally cached data is out of date – you can get around this by deleting all the files in the `cache/` directory.
* Someone else is actively making edits to the road network in your area: wait until they finish before importing.

If you make any OSM edits prior to running the importer, please wait a few minutes for the Overpass API to incorporate them, then re-run
the import using the `-n` option.  This ensures you’re using the latest OSM data (including your edits!) and prevents conflicts.

## Technical Details
These technical details aren’t necessary for importing data, but are included for curious users.
### How GTFS is matched to OSM
**GTFS** | **OSM**
--- | ---
route | `route_master` relation
trip | `route` relation
stop | `public_transport=platform` node
shape | way (roads, rail, etc.)
agency | added to `route` and `route_master` tags
stop_times | (ignored)
calendar_dates | (ignored)

#### How Trips are mapped to OSM Routes
A GTFS route may contain up to several hundred trips, many identical.  Rather than importing
multiple duplicates, the importer
solves this by including only unique trips, also eliminating trips that stop at just
a subset of another trip’s stops.