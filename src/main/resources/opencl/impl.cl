uint randomNumber(ulong *seed_ptr) {
  ulong seed = *seed_ptr + get_global_id(0);
  seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
  uint result = seed >> 16;
  *seed_ptr = seed;
  return result;
}

__kernel void doWork(int randomTickSpeed, short schedulerFirst,
                     int waterFlowDelay, int kelpCount, ulong testLength,
                     int harvestPeriod, int heightLimit, int perHarvestSize,
                     ulong seed, __global long *totalStorage,
                     __global int *perHarvestStorage) {
  size_t id = get_global_id(0);
  // if (id >= kelpCount) return;

  __private ulong seedStorage = seed;

  // Status of kelp
  short height = 1;
  short maxHeight = randomNumber(&seedStorage) % 25 + 2;
  if (maxHeight > heightLimit)
    maxHeight = heightLimit;
  long total;
  ulong lastTick = 0;
  ulong grownLastTick = 0;

  size_t calcTime = randomNumber(&seedStorage) % 4096;
  uint harvestedCount = 0;
  ulong timeSinceLastHarvest = 0;
  for (ulong time = 0; time < testLength; time++) {
    timeSinceLastHarvest++;
    if (timeSinceLastHarvest >= harvestPeriod) {
      if (schedulerFirst && grownLastTick != 0)
        height -= grownLastTick;
      short harvestedHeight = height - 1;
      total += harvestedHeight;
      height = 1;
      grownLastTick = 0;
      if (harvestedCount + 1 >= perHarvestSize) {
        perHarvestStorage[id * perHarvestSize + perHarvestSize - 1] = 1 << 31;
      } else {
        perHarvestStorage[id * perHarvestSize + (harvestedCount++)] =
            harvestedHeight;
      }
      time += waterFlowDelay + (schedulerFirst ? 0 : -1);
      timeSinceLastHarvest = 0;
      continue;
    }
    grownLastTick = 0;
    if (time % 4096 == calcTime) {
      for (ushort i = 0; i < randomTickSpeed; i++) {
        if (height < maxHeight && randomNumber(&seedStorage) % 100 < 14) {
          height++;
          grownLastTick++;
        }
      }
    }
  }
  if (harvestedCount < perHarvestSize) {
    perHarvestStorage[id * perHarvestSize + perHarvestSize - 1] =
        harvestedCount - 1;
  }
  if (schedulerFirst) {
    total -= grownLastTick;
  }
  totalStorage[id] = total;
  totalStorage[id] = total;
  totalStorage[id] = total;
  return;
}
