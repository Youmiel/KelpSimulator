package com.ishland.simulations.kelpsimulator;

import com.ibm.asyncutil.locks.AsyncSemaphore;
import com.ibm.asyncutil.locks.FairAsyncSemaphore;
import com.ishland.simulations.kelpsimulator.impl.SimulationResult;
import com.ishland.simulations.kelpsimulator.impl.Simulator;
import com.ishland.simulations.kelpsimulator.impl.java.JavaSimulationSession;
import com.ishland.simulations.kelpsimulator.impl.opencl.OpenCLSimulationSession;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static void main(String[] args) {
        System.out.println("KelpSimulator");
        Config.init();
        long startTime = System.currentTimeMillis();
        AsyncSemaphore semaphore = new FairAsyncSemaphore(Config.maxConcurrentTasks);
        @SuppressWarnings("unchecked")
        CompletableFuture<SimulationResult>[][] output = new CompletableFuture[Config.harvestPeriod.length][Config.heightLimit.length];
        List<CompletableFuture<SimulationResult>> futures = new ArrayList<>();
        for (int i = 0; i < Config.harvestPeriod.length; i ++) {
            int harvestPeriod = Config.harvestPeriod[i];
            for (int j = 0; j < Config.heightLimit.length; j ++) {
                int heightLimit = Config.heightLimit[j];
                final Simulator session;
                if (Config.implArgs[0].equals("java")) {
                    session = new JavaSimulationSession(Config.randomTickSpeed, Config.schedulerFirst, Config.waterFlowDelay, Config.kelpCount, Config.testLength, harvestPeriod, heightLimit);
                } else if (Config.implArgs[0].equals("opencl")) {
                    session = new OpenCLSimulationSession(Config.randomTickSpeed, Config.schedulerFirst, Config.waterFlowDelay, Config.kelpCount, Config.testLength, harvestPeriod, heightLimit);
                } else {
                    throw new IllegalArgumentException("Unknown impl");
                }
                final CompletableFuture<SimulationResult> future = semaphore.acquire().toCompletableFuture().thenCompose(unused -> session.runSimulation());
                future.exceptionally(throwable -> null).thenRun(semaphore::release);
                output[i][j] = future;
                futures.add(future);
            }
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        System.out.printf("Simulation completed after %.1fs, writing results...\n", (System.currentTimeMillis() - startTime) / 1_000.0);

        final boolean useOpenCL = Config.implArgs[0].equals("opencl");
        final DecimalFormat format = new DecimalFormat("0.###");
        try (CsvWriter writer = CsvWriter.builder()
                     .lineDelimiter(LineDelimiter.LF)
                     .build(Path.of(".", "output.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.writeRow("Kelp count ", "Time (h) ", "Height limit ", "Harvest period (s) ", "Total ", "Total / hour ", "Unit / hour ", "Total / harvest " + (useOpenCL ? "(approx.) " : ""), "Unit / harvest " + (useOpenCL ? "(approx.) " : ""));
            for (int i = 0; i < output.length; i ++)
                for (int j = 0; j < output[i].length; j ++) {
                    final int harvestPeriod = Config.harvestPeriod[i];
                    final int heightLimit = Config.heightLimit[j];
                    final double lengthHours = Config.testLength / 20.0 / 60.0 / 60.0;
                    final SimulationResult statistics = output[i][j].join();
                    if (!useOpenCL) {
                        writer.writeRow(String.valueOf(Config.kelpCount), String.valueOf(lengthHours), String.valueOf(heightLimit), format.format(harvestPeriod / 20.0), String.valueOf(statistics.total().getSum()), format.format(statistics.total().getSum() / lengthHours), format.format(statistics.total().getAverage() / lengthHours), format.format(statistics.perHarvest().getAverage() * Config.kelpCount), format.format(statistics.perHarvest().getAverage()));
                    } else {
                        final double totalEveryHarvest = statistics.total().getSum() / (Config.testLength / (double) harvestPeriod);
                        final double unitEveryHarvest = statistics.total().getAverage() / (Config.testLength / (double) harvestPeriod);
                        writer.writeRow(String.valueOf(Config.kelpCount), String.valueOf(lengthHours), String.valueOf(heightLimit), format.format(harvestPeriod / 20.0), String.valueOf(statistics.total().getSum()), format.format(statistics.total().getSum() / lengthHours), format.format(statistics.total().getAverage() / lengthHours), format.format(totalEveryHarvest), format.format(unitEveryHarvest));
                    }
                }
            System.out.println("Results saved at " + Path.of(".", "output.csv"));
        } catch (IOException e) {
            System.err.println("Error occurred while writing results: ");
            e.printStackTrace();
        }
        System.exit(0);
    }

}
