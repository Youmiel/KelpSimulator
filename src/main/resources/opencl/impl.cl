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
  if (kelpCount < 256) {
    if (partId > 0) return;
    existKelps = kelpCount;
  }
  if ((partId + 1) * 256 > kelpCount) {
    existKelps = kelpCount - (256 * (partId));
  }
  long total = 0;
  __private short kelpHeight[256] = {0};
  __private short kelpMaxHeight[256] = {0};
  for (uint i = 0; i < existKelps; i++) {
    int maxHeight = nextInt(&seedStorage, 25) + 2;
    if (maxHeight > heightLimit)
      maxHeight = heightLimit;
    kelpMaxHeight[i] = maxHeight;
    kelpHeight[i] = 1;
  }
  // note that incomplete period is dropped
  long actualTickLength =
      schedulerFirst != 0 ? harvestPeriod - 1 : harvestPeriod;
  long tickCount = actualTickLength * randomTickSpeed;
  for (long time = 0; time < tickCount; time++) {
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
