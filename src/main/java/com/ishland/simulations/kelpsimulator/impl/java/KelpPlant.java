package com.ishland.simulations.kelpsimulator.impl.java;

import java.util.LinkedList;
import java.util.Random;

public class KelpPlant {

    private static final short MIN_HEIGHT = 2;
    private static final short MAX_HEIGHT = 26;

    private static final double GROWTH_CHANCE = 0.14D;

    private final short maxHeight;
    private short height = 1;
    private long total = 0;
    private long lastTick = -1;
    private int grownLastTick = 0;

    public KelpPlant(int heightLimit) {
        this.maxHeight = (short) Math.min(heightLimit, new Random().nextInt(MAX_HEIGHT - MIN_HEIGHT + 1) + MIN_HEIGHT);
    }

    public void tick(long tick, Random random) {
        if (tick != lastTick) {
            lastTick = tick;
            grownLastTick = 0;
        }
        if (canGrow(random)) {
            height ++;
            grownLastTick ++;
        }
    }

    public int harvest(boolean schedulerFirst) {
        if (schedulerFirst && grownLastTick != 0) height -= grownLastTick;
        final int harvestedHeight = height - 1;
        this.total += harvestedHeight;
        height = 1;
        grownLastTick = 0;
        return harvestedHeight;
    }

    public long getTotal() {
        return total;
    }

    public int getGrownLastTick() {
        return grownLastTick;
    }

    private boolean canGrow(Random random) {
        return height < maxHeight && random.nextDouble() < GROWTH_CHANCE;
    }

}
