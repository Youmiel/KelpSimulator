package com.ishland.simulations.kelpsimulator.impl;

import java.util.IntSummaryStatistics;
import java.util.LongSummaryStatistics;

public record SimulationResult(LongSummaryStatistics total, IntSummaryStatistics perHarvest) {
}
