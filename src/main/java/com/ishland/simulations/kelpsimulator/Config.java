package com.ishland.simulations.kelpsimulator;

import com.ishland.simulations.kelpsimulator.impl.opencl.OpenCLSimulationSession;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.IntStream;

public class Config {

    static final int randomTickSpeed;
    static final boolean schedulerFirst;
    static final int waterFlowDelay;
    static final int kelpCount;
    static final long testLength;
    static final long maxConcurrentTasks;
    static final int[] harvestPeriod;
    static final int[] heightLimit;
    public static final String[] implArgs;

    public static final OpenCLSimulationSession.ReductionMode oclReductionMode;

    static {
        final Properties properties = new Properties();
        boolean created = false;
        try (Reader reader = Files.newBufferedReader(Path.of(".", "config.properties"))) {
            properties.load(reader);
        } catch (IOException e) {
            System.out.println("Error reading config file (" + e + "), creating one...");
            created = true;
        }

        randomTickSpeed = Integer.parseInt(getProperty(properties, "randomTickSpeed", "3"));
        schedulerFirst = Boolean.parseBoolean(getProperty(properties, "schedulerFirst", "true"));
        waterFlowDelay = Integer.parseInt(getProperty(properties, "waterFlowDelay", "5"));
        kelpCount = Integer.parseInt(getProperty(properties, "kelpCount", String.valueOf(2048)));
        testLength = Long.parseLong(getProperty(properties, "testLength", String.valueOf(72_000_000)));
        harvestPeriod = readRange(getProperty(properties, "harvestPeriod", "r:10,72000,10"));
        heightLimit = readRange(getProperty(properties, "heightLimit", "r:2,26,1"));
        implArgs = getProperty(properties, "implArgs", "opencl,0,0").split(",");
        maxConcurrentTasks = Integer.parseInt(getProperty(properties, "maxConcurrentTasks", String.valueOf(Math.min(Runtime.getRuntime().availableProcessors(), 8))));

        oclReductionMode = OpenCLSimulationSession.ReductionMode.valueOf(getProperty(properties, "oclReductionMode", OpenCLSimulationSession.ReductionMode.NONE.name()));

        try (Writer writer = Files.newBufferedWriter(Path.of(".", "config.properties"), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            properties.store(writer, "KelpSimulator");
        } catch (IOException e) {
            System.out.println("Unable to save config file: " + e);
        }
        if (created) System.exit(0);
    }

    private static int[] readRange(String property) {
        if (property.startsWith("r:")) {
            String selectorString = property.substring("r:".length());
            final int[] selectors = Arrays.stream(selectorString.split(","))
                    .mapToInt(Integer::parseInt)
                    .toArray();
            int start = selectors[0];
            int end = selectors[1];
            int step = selectors[2];
            final IntStream.Builder builder = IntStream.builder();
            for (int i = start; i <= end; i += step) {
                builder.accept(i);
            }
            return builder.build().toArray();
        } else {
            return Arrays.stream(property.split(","))
                    .mapToInt(Integer::parseInt)
                    .toArray();
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
