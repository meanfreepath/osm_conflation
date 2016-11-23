package com.company;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.FileSystemException;

/**
 * Created by nick on 11/22/16.
 */
public class Config {
    public static Config sharedInstance = null;
    public final String outputDirectory, cacheDirectory, debugDirectory;

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
        debugDirectory = "./debug";

        String dirs[] = {outputDirectory, cacheDirectory, debugDirectory};
        for(final String dir : dirs) {
            File workingDir = new File("./" + dir);
            if (!workingDir.exists() && !workingDir.mkdir()) {
                throw new FileSystemException(String.format("Unable to create directory “%s” – please check permissions and try again", dir));
            }
        }

        taskingManagerBaseUrl = "https://www.meanfreepath.com/kcstops/";
    }
}
