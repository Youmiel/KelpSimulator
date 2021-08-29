int randomNumber(long *seed_ptr, ushort bits) {
  long seed = *seed_ptr;
  seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
  int result = seed >> (48 - bits);
  *seed_ptr = seed;
  return result;
}

int nextInt(long *seed_ptr, int bound) {
  int r = randomNumber(seed_ptr, 31);
  int m = bound - 1;
  if ((bound & m) == 0) // i.e., bound is a power of 2
    r = (int)((bound * (long)r) >> 31);
  else {
    for (int u = r; u - (r = u % bound) + m < 0; u = randomNumber(seed_ptr, 31))
      ;
  }
  return r;
}

__kernel void doWork(int randomTickSpeed, int schedulerFirst,
                     int waterFlowDelay, int kelpCount, ulong testLength,
                     int harvestPeriod, int heightLimit, long seed,
                     int harvestSize, __global long *sectionedTotalStorage) {
  size_t partId = get_global_id(0);
  size_t harvestId = get_global_id(1);
  long seedStorage = seed + partId * harvestSize + harvestId;

  uint existKelps = 256;
  if ((partId + 1) * 256 > kelpCount) {
    existKelps = kelpCount - (256 * (partId));
  }
  long total = 0;
  uint kelpHeight[256] = {0};
  uint kelpMaxHeight[256] = {0};
  for (uint i = 0; i < existKelps; i++) {
    short maxHeight = nextInt(&seedStorage, 25) + 2;
    if (maxHeight > heightLimit) maxHeight = heightLimit;
    kelpMaxHeight[i] = maxHeight;
    kelpHeight[i] = 1;
  }
  // note that incomplete period is dropped
  uint tickCount =
      (schedulerFirst ? harvestPeriod - 1 : harvestPeriod) * randomTickSpeed;
  for (uint t = 0; t < tickCount; t++) {
    if (nextInt(&seedStorage, 100) < 14) {
      int ticked = nextInt(&seedStorage, 4096);
      if (ticked < existKelps && kelpHeight[ticked] < kelpMaxHeight[ticked]) {
        kelpHeight[ticked]++;
        total++;
      }
    }
  }
  sectionedTotalStorage[partId * harvestSize + harvestId] = total;
}
