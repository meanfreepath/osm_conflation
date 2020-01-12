# Introduction
The GTFS importer takes the processed GTFS data (created using a separate program) from an OSM file ("routes.osm") and conflates and merges it with the existing OpenStreetMap data, using the Overpass API (http://overpass-api.de/).  Each GTFS route's data consists of a number of stop platforms (highway=bus_stop etc), and a shapeline (highway=road) that is used to calculate the route path.

The importer matches the shapeline to OpenStreetMap by breaking it up into segments, each with an origin and destination stop.  It then "travels" the shapeline a few meters at a time, selecting the best-matched OpenStreetMap way at each step.

The GTFS importer will process the routes you choose (via the "-r" option), and output several files:
* **gtfsroute_(routeid).osm**: the GTFS data for the given route, including the stop platforms and the route shape (represented as a highway=road way).  If you're experiencing issues with matching due to inaccurate GTFS data, you can edit this file, then rerun the GTFS importer.  Do not upload this file to OpenStreetMap!
* **routestops_(routeids).osm**: this file is created when you use the "-s" or "--stopsonly" options: it contains all the stop platforms on the provided route(s), merged with OpenStreetMap data.  The included .mapcss file will mark the platforms as green (already on OpenStreetMap), yellow (new), and red (stop is near to an existing OSM stop).
* **workingspace_(routeids).osm**: This file is a debug file, which includes all the data downloaded from Overpass, and the merged data from the selected routes.  This file can be reviewed to find the source of any matching errors - it is not safe to upload to OpenStreetMap!
* **relations_(routeids).osm**: If the route processed successfully, this file will be the result - this is the file to upload to OpenStreetMap!  Please review the file in JOSM first, and check there are no gaps in the route (via the JOSM validator).

# Initial Setup
## Prerequisites
* Java 7+
* **JOSM** editor
* Basic Command Line experience

Please add the included `segmentdebug.mapcss` file to your JOSM preferences (go to the Map Settings section in JOSM Preferences, find the Map Paint Styles tab, and click the [+] button to add, then find the segmentdebug.mapcss file to add it.

# Usage
## First Step: importing stops
First, determine the route you want to import.  You can process multiple routes simultaneously (by adding them to the -r option, separated by commas) to save download time.  I strongly recommend you run with the -s option first, so you can review the stop positioning, as the values provided by the agencies are often inaccurate.

For this example, I'm using route 373 (gtfs id 100215), which is a typical route with some minor issues:

```shell script
java -jar LineConflation.jar -s -r 100215
```

Then open the `routestops_100215.osm` file and review the stops.  If you've added the included .mapcss file in JOSM, you'll see the existing stops (green - these don't need to be updated), new stops (yellow - recommend double-checking their positions with the Bing Aerial layer), and conflicting stops (red).  You can review these by downloading the surrounding area in JOSM, as you normally would (this is completely safe and will not result in conflicts), then reviewing the nearby stops.  In this example, the stops at University Way NE & NE 55th St conflict with some already-present stops - you can safely merge the GTFS stops (select both stops, hit the "M" key) with them in this case - remember to delete the "gtfs:conflict" tags afterward!  Once you've reviewed the stops, you can upload the file to OpenStreetMap, as you would any typical JOSM edit.

IMPORTANT: before running the route matching step, please wait 5-10 minutes!  There is a delay from the time you upload to OpenStreetMap to the time your edit will show up in the Overpass API.  A quick way to test if your edit made it to Overpass is to re-run the stops download with the -n (delete cache) option:

```shell script
java -jar LineConflation.jar -s -r 100215 -n
```

Then reopen the routestops_100215.osm file - if all the stops show up as green, then you're good to go.  If not, wait a few minutes and try again.

## Next Step: matching route to ways
Once the stops have been updated on Overpass, you can run the full route match algorithm:

```shell script
java -jar LineConflation.jar -r 100215
```

It will take 1-2 minutes to download the data from Overpass.  If you get any exceptions, just re-run the same command to resume the download.  After data is downloaded, the matching process will begin.  If all goes well, the program will output a relations_100215.osm file, which you can review and upload if everything looks good.  If not, try to find where the importer couldn't match (look for the segment of the route that didn't complete - it'll output "FAILED" and a list of the paths it tried to find).

NOTE: In this example (route 373), the GTFS shapeline has an error near 175th st and Meridian Ave: you can fix it by editing the gtfsroute_100215.osm file and re-running the importer.

If the route doesn't match, there are a few possible causes:
- the provided stop positions are wrong: review their locations in JOSM (in a new layer), and upload.  Wait a few minutes, and rerun the importer with the "-n" option, to ensure you're getting fresh data.
- the provided shapeline for the route is wrong (King County Metro issue): this can be fixed by reviewing the gtfsroute_*.osm file with the Mapnik layer in JOSM, and adjusting the shape so it more closely matches OpenStreetMap data.  Save the file (but don't upload), then rerun the importer.
- OpenStreetMap is missing the road that a stop is on (i.e. a driveway in a transit center): edit in JOSM (in a new layer) as you would normally, upload, then wait a few minutes and rerun the importer with the "-n" option, to ensure you're getting fresh data.
- OpenStreetMap and the GTFS path differ in how they represent a street, i.e. OpenStreetMap has a dual carriageway but GTFS doesn't or vice versa.  Or at complicated intersections where OpenStreetMap may have primary_links by the GTFS doesn't.  Either fix the gtfsroute_*.osm file, or edit OpenStreetMap, depending on which is the best option.
- There's a bug in the importer (see Known Issues).  If you run into this, send me a message and I'll have a look at it.

###Caching
All downloads from Overpass are cached for 15 minutes.  If you upload any changes in a route's area to OpenStreetMap, I recommend waiting several minutes, then re-run using the "-n" option to avoid getting stale data and possible conflicts.  There's no need to clear the cache when you edit the gtfsroute_*.osm files.

#Tips
To save download and waiting time, you can process several routes using a comma-separated list (-r 100215,100224).  This is best done for routes that are in the same geographic area.

#Known Issues
The program doesn't properly handle splitting of looping ways (i.e. roundabouts, circular driveways, Route 62 is an example).
While it's technically possible to use this program for streetcar and light rail routes, it hasn't been tested yet.
Routes that use a ferry (i.e. the Vashon Island route) haven't been tested
The King County Metro data's shape lines are out of date for some routes: routes using the SR520 bridge, Highway 99 near Centurylink Field, and Highway 99 north of Denny Way may need to be fixed by editing the gtfsroute_*.osm files.
Some King County Metro routes have errors in their shapelines (route 44, route 373, etc), which can be fixed by editing the gtfsroute_*.osm files and re-running the program.



