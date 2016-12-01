package com.company;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.FileSystemException;

/**
 * Created by nick on 11/22/16.
 */
public class Config {
    public final static String DEFAULT_CONFIG_FILE = "config.txt", DEFAULT_GTFS_FILE = "routes.osm";
    public static Config sharedInstance = null;
    public final String outputDirectory, cacheDirectory;

    //Tasking manager options
    public final String taskingManagerBaseUrl;

    public static Config initWithConfigFile(final String configFilePath) throws FileNotFoundException, FileSystemException {
        sharedInstance = new Config(configFilePath);
        return sharedInstance;
    }
    private Config(final String configFilePath) throws FileNotFoundException, FileSystemException {
        File configFile = new File(configFilePath);
        if(!configFile.exists()) {
            throw new FileNotFoundException(String.format("Unable to find config file “%s”", configFilePath));
        }

        //TODO load from config
        outputDirectory = "./output";
        cacheDirectory = "./cache";

        String dirs[] = {outputDirectory, cacheDirectory};
        for(final String dir : dirs) {
            File workingDir = new File("./" + dir);
            if (!workingDir.exists() && !workingDir.mkdir()) {
                throw new FileSystemException(String.format("Unable to create directory “%s” – please check permissions and try again", dir));
            }
        }

        taskingManagerBaseUrl = "https://www.meanfreepath.com/kcstops/";
    }
}
