package com.ishland.simulations.kelpsimulator.impl;

import java.util.IntSummaryStatistics;
import java.util.LongSummaryStatistics;
import java.util.Objects;

public final class SimulationResult {
    private final LongSummaryStatistics total;
    private final IntSummaryStatistics perHarvest;

    public SimulationResult(LongSummaryStatistics total, IntSummaryStatistics perHarvest) {
        this.total = total;
        this.perHarvest = perHarvest;
    }

    public LongSummaryStatistics total() {
        return total;
    }

    public IntSummaryStatistics perHarvest() {
        return perHarvest;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (SimulationResult) obj;
        return Objects.equals(this.total, that.total) &&
                Objects.equals(this.perHarvest, that.perHarvest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(total, perHarvest);
    }

    @Override
    public String toString() {
        return "SimulationResult[" +
                "total=" + total + ", " +
                "perHarvest=" + perHarvest + ']';
    }

}
