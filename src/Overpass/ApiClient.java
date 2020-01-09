package Overpass;

import com.company.Config;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;

/**
 * Created by nick on 11/3/15.
 * A simple Java wrapper for the OpenStreetMap Overpass API
 */
public class ApiClient {
    private final static int CONNECT_TIMEOUT = 5000, READ_TIMEOUT = 25000, MIN_QUERY_WAIT_TIME = 4000;
    private final static String ENDPOINT_URL = "http://overpass-api.de/api/interpreter";
    private final static String QUERY_TEMPLATE = "[out:%s];%sout meta;", GEOJSON_QUERY_TEMPLATE = "[out:%s];%sout body geom;";

    /**
     * The maximum age (in ms) of cached data files
     */
    private long maxCacheAge = 1800000L;

    public enum ResponseFormat {
        json, xml
    }

    public ResponseFormat responseFormat = ResponseFormat.json;
    public boolean debug = false;
    public int status;

    public ApiClient(String[] args, HashMap<String, String> kwArgs) {
        //responseFormat = kwArgs.get("responseformat");
        if(kwArgs.containsKey("debug")) {
            debug = kwArgs.get("debug").equals("1");
        }
        status = 0;

        if(debug) { //set connection debug
            /*import httplib
            import logging
            httplib.HTTPConnection.debuglevel = 1

            logging.basicConfig()
            logging.getLogger().setLevel(logging.DEBUG)
            requests_log = logging.getLogger("requests.packages.urllib3")
            requests_log.setLevel(logging.DEBUG)
            requests_log.propagate = True*/
        }
    }
    private static String generateCacheFileName(final String query) throws NoSuchAlgorithmException {
        final MessageDigest md5 = MessageDigest.getInstance("MD5");
        final byte[] digest = md5.digest(query.getBytes(StandardCharsets.UTF_8));
        return String.format("%s/cache_%s.txt", Config.sharedInstance.cacheDirectory, Base64.getUrlEncoder().encodeToString(digest));
    }
    /**
     * Pass in an Overpass query in Overpass QL
     */
    public JSONArray get(String query, boolean asGeoJSON, boolean cachingEnabled) throws Exceptions.UnknownOverpassError {
        final String fullQuery = constructQLQuery(query, asGeoJSON);

        File cacheFile = null;
        try {
            final String fileName = generateCacheFileName(fullQuery);
            //get a handle on the cache file, if any, and check its age
            cacheFile = new File(fileName);
            if (cacheFile.exists()) {
                //use the cache file if caching is enabled
                if(cachingEnabled && System.currentTimeMillis() - cacheFile.lastModified() <= maxCacheAge) {
                    final FileInputStream fStream = new FileInputStream(cacheFile.getAbsoluteFile());
                    BufferedReader in = new BufferedReader(new InputStreamReader(fStream));
                    StringBuilder contents = new StringBuilder();
                    while (in.ready()) {
                        contents.append(in.readLine());
                    }
                    JSONObject response = new JSONObject(contents.toString());
                    return response.getJSONArray("elements");
                } else {
                    cacheFile.delete();
                }
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
        }

        JSONObject response = null;
        try {
            String rawResponse = getFromOverpass(fullQuery);
            response = new JSONObject(rawResponse);

            //write the data to cache
            if(cacheFile != null) {
                BufferedWriter writer = new BufferedWriter(new FileWriter(cacheFile));
                writer.write(rawResponse);
                writer.close();
            }

            //wait a little before making the next request (avoid Overpass API query limitations)
            Thread.sleep(MIN_QUERY_WAIT_TIME);
        } catch (Exceptions.OverpassError | IOException | InterruptedException e) {
            e.printStackTrace();
        }

        if(response == null || !response.has("elements")) {
            throw new Exceptions.UnknownOverpassError("Received an invalid answer from Overpass.");
        }

        if(!asGeoJSON) {
            return response.getJSONArray("elements");
        } else {
            return asGeoJSON(response.getJSONArray("elements"));
        }
    }

    /**
     * Search for something.
     * @param featureType
     * @param regex
     */
    public void search(String featureType, boolean regex) throws Exception {
        throw new Exception("Not implemented");
    }
    private String constructQLQuery(String userQuery, boolean asGeoJSON) {
        String rawQuery = userQuery;
        if(!rawQuery.endsWith(";")) {
            rawQuery += ";";
        }

        String template;
        if(asGeoJSON) {
            template = GEOJSON_QUERY_TEMPLATE;
        } else {
            template = QUERY_TEMPLATE;
        }
        String completeQuery = String.format(template, responseFormat.toString(), rawQuery);

        if(debug) {
            System.out.println(completeQuery);
        }
        return completeQuery;
    }

    /**
     * This sends the API request to the Overpass instance and returns the raw result, or an error.
     * @param query
     */
    private String getFromOverpass(String query) throws Exceptions.OverpassSyntaxError, Exceptions.MultipleRequestsError, Exceptions.ServerLoadError, Exceptions.UnknownOverpassError, IOException {
        HashMap<String, String> payload = new HashMap<>(1);
        payload.put("data", query);
        System.out.println("Fetch " + query);

        URL endpointUrl = new URL(ENDPOINT_URL);
        HttpURLConnection connection = (HttpURLConnection) endpointUrl.openConnection();
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept-Charset", "utf-8;q=0.7,*;q=0.7");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.connect();

        OutputStream output = connection.getOutputStream();
        output.write(query.getBytes(StandardCharsets.UTF_8));

        status = connection.getResponseCode();
        switch (status) {
            case HttpURLConnection.HTTP_OK:
                InputStream response = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder();
                for (String line; (line = reader.readLine()) != null;) {
                    builder.append(line);
                }
                return builder.toString();
            case HttpURLConnection.HTTP_BAD_REQUEST: //400
                throw new Exceptions.OverpassSyntaxError(query);
            case 429:
                throw new Exceptions.MultipleRequestsError();
            case HttpURLConnection.HTTP_GATEWAY_TIMEOUT: //504
                throw new Exceptions.ServerLoadError(READ_TIMEOUT);
            default:
                throw new Exceptions.UnknownOverpassError("The request returned status code " + status);
        }
    }

    private JSONArray asGeoJSON(JSONArray elements) {
        //print 'DEB _asGeoJson elements:', elements

        JSONArray features = new JSONArray();
        JSONObject elem, outputElement;
        int elemLength = elements.length();
        for(int elemIdx=0;elemIdx<elemLength;elemIdx++) {
            elem = elements.getJSONObject(elemIdx);
            final String elemType = elem.getString("type");
            outputElement = new JSONObject();

            if(elemType.equals("node")) {
                double[] coord = {elem.getDouble("lon"), elem.getDouble("lat")};

            } else if (elemType.equals("way")) {
                JSONArray elemPoints = elem.getJSONArray("geometry");
                int elemPointLength = elemPoints.length();
                double[][] points = new double[elemPointLength][2];

                JSONObject coords;
                for(int pointIdx = 0; pointIdx<elemPointLength;pointIdx++) {
                    coords = elemPoints.getJSONObject(pointIdx);
                    double[] coord = {coords.getDouble("lon"), coords.getDouble("lat")};
                    points[pointIdx++] = coord;
                }
            } else {
                continue;
            }
            features.put(elemIdx, outputElement);
        }

        /*feature = geojson.Feature(
        id=elem["id"],
        geometry=geometry,
        properties=elem.get("tags"))
                features.append(feature)

        return geojson.FeatureCollection(features);*/
        return features;
    }
    public void setMaxCacheAge(long maxCacheAge) {
        this.maxCacheAge = maxCacheAge;
    }
}

