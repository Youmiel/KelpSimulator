package com.ishland.simulations.kelpsimulator;

import com.ishland.simulations.kelpsimulator.impl.SimulationSession;

import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CompletableFuture;

public class Main {

    public static void main(String[] args) {
        System.out.println("KelpSimulator");
        Config.init();
        double output[][] = new double[Config.harvestPeriod.length][Config.heightLimit.length];
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < Config.harvestPeriod.length; i ++) {
            int harvestPeriod = Config.harvestPeriod[i];
            for (int j = 0; j < Config.heightLimit.length; j ++) {
                int heightLimit = Config.heightLimit[j];
                int finalI = i;
                int finalJ = j;
                futures.add(CompletableFuture.runAsync(() -> {
                    final SimulationSession session = new SimulationSession(Config.randomTickSpeed, Config.schedulerFirst, Config.waterFlowDelay, Config.kelpCount, Config.testLength, harvestPeriod, heightLimit);
                    output[finalI][finalJ] = session.runSimulation().getAverage();
                }));
            }
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        for (int i = 0; i < output.length; i ++)
            for (int j = 0; j < output[i].length; j ++)
                System.out.printf("period=%d,limit=%d,average=%.3f\n", Config.harvestPeriod[i], Config.heightLimit[j], output[i][j]);

    }

}
