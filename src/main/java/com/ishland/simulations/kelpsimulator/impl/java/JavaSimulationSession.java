package com.ishland.simulations.kelpsimulator.impl.java;

import com.ishland.simulations.kelpsimulator.impl.SimulationResult;
import com.ishland.simulations.kelpsimulator.impl.Simulator;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class JavaSimulationSession implements Simulator {

    private static final int SECTION_SIZE = 16 * 16 * 16;
    private static final int SECTION_SURFACE_SIZE = 16 * 16;
    private static final AtomicInteger GLOBAL_SERIAL = new AtomicInteger(0);

    private final int serial = GLOBAL_SERIAL.getAndIncrement();
    private final int randomTickSpeed;
    private final boolean schedulerFirst;
    private final int waterFlowDelay;
    private final int kelpCount;
    private final long testLength;
    private final int harvestPeriod;
    private final int heightLimit;

    private final Random random = new Random();

    public JavaSimulationSession(int randomTickSpeed, boolean schedulerFirst, int waterFlowDelay, int kelpCount, long testLength, int harvestPeriod, int heightLimit) {
        this.randomTickSpeed = randomTickSpeed;
        this.schedulerFirst = schedulerFirst;
        this.waterFlowDelay = waterFlowDelay;
        this.kelpCount = kelpCount;
        this.testLength = testLength;
        this.harvestPeriod = harvestPeriod;
        this.heightLimit = heightLimit;
    }

    public SimulationResult runSimulation() {
        log("Preparing simulation");
        KelpPlant[][] kelpPlants = new KelpPlant[(int) Math.ceil(kelpCount / (double) SECTION_SURFACE_SIZE)][SECTION_SURFACE_SIZE];
        {
            int spawnedKelpPlants = 0;
            for (int group = 0; group < kelpPlants.length && spawnedKelpPlants < kelpCount; group++) {
                for (int i = 0; i < SECTION_SURFACE_SIZE && spawnedKelpPlants < kelpCount; i++) {
                    kelpPlants[group][i] = new KelpPlant(heightLimit);
                    spawnedKelpPlants ++;
                }
            }
        }
        final IntStream.Builder harvestStream = IntStream.builder();
        log("Running simulation");
        long timeSinceLastHarvest = 0;
        long lastPrint = System.currentTimeMillis();
        long startTime = lastPrint;
        for (long time = 0; time < testLength; time ++) {
            timeSinceLastHarvest ++;
            if (timeSinceLastHarvest >= harvestPeriod) {
                for (KelpPlant[] group : kelpPlants) {
                    for (KelpPlant plant : group) {
                        if (plant != null) harvestStream.accept(plant.harvest(schedulerFirst));
                    }
                }
                time += waterFlowDelay + (schedulerFirst ? 0 : -1);
                timeSinceLastHarvest = 0;
            }
            for (KelpPlant[] group : kelpPlants) {
                for (int i = 0; i < randomTickSpeed; i ++) {
                    final int rn = random.nextInt(SECTION_SIZE);
                    final KelpPlant kelpPlant = rn < SECTION_SURFACE_SIZE ? group[rn] : null;
                    if (kelpPlant != null) kelpPlant.tick(time, random);
                }
            }
            final long timeMillis = System.currentTimeMillis();
            if (timeMillis - lastPrint > 5000) {
                log(String.format("Current progress: %.1f hours (%d gt), time elapsed: %.1fs", time / 20.0 / 60.0 / 60.0, time, (timeMillis - startTime) / 1000.0));
                lastPrint += 5000;
            }
        }
        log(String.format("Done. %.1f hours (%d gt), time elapsed: %.1fs", testLength / 20.0 / 60.0 / 60.0, testLength, (System.currentTimeMillis() - startTime) / 1000.0));
        return new SimulationResult(
                Arrays.stream(kelpPlants)
                        .flatMap(Arrays::stream)
                        .filter(Objects::nonNull)
                        .mapToLong(kelpPlant -> kelpPlant.getTotal() + (schedulerFirst ? -kelpPlant.getGrownLastTick() : 0))
                        .summaryStatistics(),
                harvestStream.build().summaryStatistics()
        );
    }

    private void log(String message) {
        System.out.printf("[%d] %s\n", serial, message);
    }

}
