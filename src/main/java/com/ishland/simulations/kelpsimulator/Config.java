package com.ishland.simulations.kelpsimulator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Properties;

public class Config {

    static final int randomTickSpeed;
    static final boolean schedulerFirst;
    static final int waterFlowDelay;
    static final int kelpCount;
    static final long testLength;
    static final int[] harvestPeriod;
    static final int[] heightLimit;
    static final String[] implArgs;

    static {
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(".", "config.properties"))) {
            properties.load(reader);
        } catch (IOException e) {
            System.out.println("Error reading config file (" + e + "), creating one...");
        }

        randomTickSpeed = Integer.parseInt(getProperty(properties, "randomTickSpeed", "3"));
        schedulerFirst = Boolean.parseBoolean(getProperty(properties, "schedulerFirst", "true"));
        waterFlowDelay = Integer.parseInt(getProperty(properties, "waterFlowDelay", "8"));
        kelpCount = Integer.parseInt(getProperty(properties, "kelpCount", String.valueOf(Short.MAX_VALUE)));
        testLength = Long.parseLong(getProperty(properties, "testLength", String.valueOf(72_000_000)));
        harvestPeriod = Arrays.stream(getProperty(properties, "harvestPeriod", "600,1200,1800,2400,3000,3600").split(","))
                .mapToInt(Integer::parseInt)
                .toArray();
        heightLimit = Arrays.stream(getProperty(properties, "heightLimit", "8,12,16,20,24").split(","))
                .mapToInt(Integer::parseInt)
                .toArray();
        implArgs = getProperty(properties, "implArgs", "java").split(",");

        try (Writer writer = Files.newBufferedWriter(Path.of(".", "config.properties"), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            properties.store(writer, "KelpSimulator");
        } catch (IOException e) {
            System.out.println("Unable to save config file: " + e);
        }
    }

    private static String getProperty(Properties properties, String key, String def) {
        if (properties.containsKey(key)) {
            return properties.getProperty(key);
        } else {
            properties.setProperty(key, def);
            return def;
        }
    }

    public static void init() {
        // intentionally empty
    }

}
