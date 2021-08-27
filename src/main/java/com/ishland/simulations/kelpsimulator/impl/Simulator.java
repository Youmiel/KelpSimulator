package com.ishland.simulations.kelpsimulator.impl;

import java.util.concurrent.CompletableFuture;

public interface Simulator {

    CompletableFuture<SimulationResult> runSimulation();

}
