package com.ishland.simulations.kelpsimulator.impl;

import java.util.Random;

public class KelpPlant {

    private static final short MIN_HEIGHT = 2;
    private static final short MAX_HEIGHT = 26;

    private static final double GROWTH_CHANCE = 0.14D;

    private final short maxHeight;
    private short height = 1;
    private long total = 0;
    private boolean grownLastTick = false;

    public KelpPlant(int heightLimit) {
        this.maxHeight = (short) Math.min(heightLimit, new Random().nextInt(MAX_HEIGHT - MIN_HEIGHT) + MIN_HEIGHT);
    }

    public void tick(Random random) {
        if (canGrow(random)) {
            height ++;
            grownLastTick = true;
        } else {
            grownLastTick = false;
        }
    }

    public void harvest() {
        total += height - 1;
        height = 1;
    }

    public long getTotal() {
        return total;
    }

    public boolean isGrownLastTick() {
        return grownLastTick;
    }

    private boolean canGrow(Random random) {
        return height < maxHeight && random.nextDouble() < GROWTH_CHANCE;
    }

}
