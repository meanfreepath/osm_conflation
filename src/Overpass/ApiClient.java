package Overpass;

import com.oracle.javafx.jmx.json.JSONFactory;
import com.oracle.javafx.jmx.json.JSONReader;
import com.sun.deploy.net.HttpRequest;
import jdk.nashorn.internal.parser.JSONParser;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * Created by nick on 11/3/15.
 * A simple Java wrapper for the OpenStreetMap Overpass API
 */
public class ApiClient {
    public final static int CONNECT_TIMEOUT = 5000, READ_TIMEOUT = 25000;
    public final static String ENDPOINT_URL = "http://overpass-api.de/api/interpreter";
    private final static String QUERY_TEMPLATE = "[out:%s];%sout body;", GEOJSON_QUERY_TEMPLATE = "[out:%s];%sout body geom;";

    public enum ResponseFormat {
        json, xml
    }

    public ResponseFormat responseFormat = ResponseFormat.json;
    public boolean debug = false;
    public int status;

    public ApiClient(String[] args, HashMap<String, String> kwArgs) {
        //responseFormat = kwArgs.get("responseformat");
        debug = kwArgs.get("debug").equals("1");
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
    /**
     * Pass in an Overpass query in Overpass QL
     */
    public Object get(String query, boolean asGeoJSON) throws Exceptions.UnknownOverpassError {
        String fullQuery = constructQLQuery(query, asGeoJSON);
        try {
            String rawResponse = getFromOverpass(fullQuery);
            System.out.println(rawResponse);
            //JSONObject f;
            JSONParser parser = JSONFactory.instance().makeReader() new JSONParser();
            parser.parse(rawResponse);
        } catch (Exceptions.OverpassError e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        HashMap<String, String> response =  null;//json.loads(raw_response)

        if(!response.containsKey("elements")) {
            throw new Exceptions.UnknownOverpassError("Received an invalid answer from Overpass.");
        }

        if(!asGeoJSON) {
            return response;
        } else {
            return asGeoJSON(response.get("elements"));
        }
    }

    /**
     * Search for something.
     * @param featureType
     * @param regex
     */
    public void search(String featureType, boolean regex) {
        throw new NotImplementedException();
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

        Charset charset = Charset.forName("UTF-8"); //use UTF-8 for all requests
        URL endpointUrl = new URL(ENDPOINT_URL);
        HttpURLConnection connection = (HttpURLConnection) endpointUrl.openConnection();
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept-Charset", "utf-8;q=0.7,*;q=0.7");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.connect();

        OutputStream output = connection.getOutputStream();
        output.write(query.getBytes(charset));

        status = connection.getResponseCode();
        switch (status) {
            case HttpURLConnection.HTTP_OK:
                InputStream response = connection.getInputStream();
                Json.createParser(new StringReader("booger"));
                JSONReader reader = new JSONReader(new InputStreamReader(response, "UTF-8"));


                BufferedReader reader = new BufferedReader(new InputStreamReader(response, charset));
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

    private String asGeoJSON(String elements) {
        //print 'DEB _asGeoJson elements:', elements

        /*features = []
                for elem in elements:
        elem_type = elem["type"]
                if elem_type == "node":
        geometry = geojson.Point((elem["lon"], elem["lat"]))
        elif elem_type == "way":
        points = []
                for coords in elem["geometry"]:
                points.append((coords["lon"], coords["lat"]))
        geometry = geojson.LineString(points)
                else:
                continue

        feature = geojson.Feature(
        id=elem["id"],
        geometry=geometry,
        properties=elem.get("tags"))
                features.append(feature)

        return geojson.FeatureCollection(features);*/
        return null;
    }
}

