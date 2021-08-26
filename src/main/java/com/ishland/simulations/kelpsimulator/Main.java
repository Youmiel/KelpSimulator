package com.ishland.simulations.kelpsimulator;

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

public class Main {

    public static void main(String[] args) {
        System.out.println("KelpSimulator");
        Config.init();
        SimulationResult[][] output = new SimulationResult[Config.harvestPeriod.length][Config.heightLimit.length];
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < Config.harvestPeriod.length; i ++) {
            int harvestPeriod = Config.harvestPeriod[i];
            for (int j = 0; j < Config.heightLimit.length; j ++) {
                int heightLimit = Config.heightLimit[j];
                int finalI = i;
                int finalJ = j;
                final Simulator session;
                if (Config.implArgs[0].equals("java")) {
                    session = new JavaSimulationSession(Config.randomTickSpeed, Config.schedulerFirst, Config.waterFlowDelay, Config.kelpCount, Config.testLength, harvestPeriod, heightLimit);
                } else if (Config.implArgs[0].equals("opencl")) {
                    session = new OpenCLSimulationSession(Config.randomTickSpeed, Config.schedulerFirst, Config.waterFlowDelay, Config.kelpCount, Config.testLength, harvestPeriod, heightLimit, Integer.parseInt(Config.implArgs[1]), Integer.parseInt(Config.implArgs[2]));
                } else {
                    throw new IllegalArgumentException("Unknown impl");
                }
                output[finalI][finalJ] = session.runSimulation();
            }
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        System.out.println("Simulation completed, writing results...");

        final DecimalFormat format = new DecimalFormat("0.###");
        try (CsvWriter writer = CsvWriter.builder()
                     .lineDelimiter(LineDelimiter.LF)
                     .build(Path.of(".", "output.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.writeRow("Kelp count ", "Time (h) ", "Height limit ", "Harvest period (s) ", "Total ", "Total / hour ", "Unit / hour ", "Total / harvest ", "Unit / harvest ");
            for (int i = 0; i < output.length; i ++)
                for (int j = 0; j < output[i].length; j ++) {
                    final int harvestPeriod = Config.harvestPeriod[i];
                    final int heightLimit = Config.heightLimit[j];
                    final double lengthHours = Config.testLength / 20.0 / 60.0 / 60.0;
                    final SimulationResult statistics = output[i][j];
                    writer.writeRow(String.valueOf(Config.kelpCount), String.valueOf(lengthHours), String.valueOf(heightLimit), format.format(harvestPeriod / 20.0), String.valueOf(statistics.total().getSum()), format.format(statistics.total().getSum() / lengthHours), format.format(statistics.total().getAverage() / lengthHours), format.format(statistics.perHarvest().getAverage() * Config.kelpCount), format.format(statistics.perHarvest().getAverage()));
                }
            System.out.println("Results saved at " + Path.of(".", "output.csv"));
        } catch (IOException e) {
            System.err.println("Error occurred while writing results: ");
            e.printStackTrace();
        }
    }

}
