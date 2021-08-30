# KelpSimulator

## Config
- `randomTickSpeed`: the same as vanillas `randomTickSpeed` game rule
- `schedulerFirst`: runs tick scheduler before doing randomTick
  enable for `<1.14` and `=1.17.1` behaviors  
  disable for `1.14-1.17` behaviors
- `waterFlowDelay`: duration in gt between kelp being cut and water source being restored
- `testLength`: full test duration in gt
- `harvestPeriod`: interval between harvest operations (allows range definition, see below)
- `heightLimit`: maximum height in blocks of a full kelp plant (allows range definition, see below)

- `implArgs`: specify which simulation implementation to be used and its extra arguments
  - `java` specify to use java software simulator
  - `opencl,<platformid>,<deviceid>` specify to use OpenCL hardware accelerated simulator (default)
    - `<platformid>` specifies the OpenCL platform id
    - `<deviceid>` specifies the OpenCL device id under platform
    - Note: this information will be printed to console when bootstrapping OpenCL hardware acceleration
- `maxConcurrentTasks`: specify how many simulation tasks can be run at the same time
  - For Java software simulator, it specifies how many threads can be used by the simulator  
    Note that values higher than cpu threads count has no effect
  - For OpenCL hardware accelerated simulator, it specifies how many tasks can be in device queue  
    Note that higher values can *probably* get higher throughput but higher latency and VRAM usage
- `oclReductionMode`: (OpenCL hardware accelerated simulator only) specify reduction mode
  - `NONE`: Full Simulation, produces best result but slower
  - `REDUCED_KELP_COUNT`: Half simulation, produces approximate results but faster
  - `REDUCED_TEST_LENGTH` and `REDUCED_BOTH`: DON'T USE: HIGH INACCURACY

