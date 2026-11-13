# CloudSim Plus Benchmarks [![Build Status](https://github.com/cloudsimplus/cloudsimplus-benchmarks/actions/workflows/maven.yml/badge.svg)](https://github.com/cloudsimplus/cloudsimplus-benchmarks/actions/workflows/maven.yml)

Module used to assess [CloudSim Plus](https://github.com/cloudsimplus/cloudsimplus) performance.
You don't need to care about it, unless you want to investigate performance issues or contribute in this manner.

It uses the [Java Microbenchmark Harness framework (JMH)](http://openjdk.java.net/projects/code-tools/jmh/) to enable measuring critical methods of the CloudSim Plus API which have a high impact in the simulation framework performance.

## Build the Project

In order to build the jar file to run the tool, you need JDK 17+ installed.
You can use any IDE of your choice or open a terminal at the project root directory and type one of the following commands:

on Linux/macOS

```bash
./mvnw clean install
```

on Windows

```bash
mvnw.cmd clean install
```

## Results of `ProcessorBenchmark` (cloudsimplus#595)

`ProcessorBenchmark` measures the memory footprint and simulation time reduction from [cloudsimplus#595](https://github.com/cloudsimplus/cloudsimplus/pull/595), which replaced the `List<Pe>` of Hosts (one object per CPU core) by a single `Processor` that just counts the cores.

- **Before:** CloudSim Plus built from commit `9bb9de25c`, running the first version of the benchmark (using the `List<Pe>` API).
- **After:** CloudSim Plus with #595 (`79eb0fb09`).
- JDK 25.0.4, default GC, `-Xmx4g`. Each value is the median of 5 rounds. The simulation time is measured after 2 warmup runs.

### Heap retained by each machine (bytes)

| Cores | Host before | Host after | Reduction | VM before | VM after | Reduction |
| ----: | ----------: | ---------: | --------: | --------: | -------: | --------: |
|     1 |       940.6 |      812.6 |       14% |     883.3 |    795.3 |       10% |
|     8 |     1,807.1 |      808.1 |       55% |     891.9 |    795.5 |       11% |
|    32 |     4,804.7 |      821.4 |       83% |     883.6 |    795.6 |       10% |
|   128 |    16,722.2 |      811.2 |       95% |     883.1 |    804.5 |        9% |

Before #595, a Host grew about 123 bytes per core. Now its size doesn't depend on the number of cores. VMs were already using a single `Processor` before #595, so their size didn't depend on the number of cores either.

### Whole simulation

Scenario: 1,000 Hosts with 32 cores, 4,000 VMs with 8 cores and 8,000 Cloudlets with 4 cores.

| Measure                      |  Before |   After | Reduction |
| ---------------------------- | ------: | ------: | --------: |
| Heap retained before running |  9.6 MB |  5.5 MB |       43% |
| Heap retained after running  | 12.2 MB |  8.6 MB |       30% |
| Simulation time              | 1.691 s | 0.776 s |       54% |

## Results of `SharedConfigBenchmark` (cloudsimplus#603)

`SharedConfigBenchmark` measures the memory footprint reduction from [cloudsimplus#603](https://github.com/cloudsimplus/cloudsimplus/issues/603), where configuration values are immutable, so one instance can be safely shared by many machines:

- `ProcessorSimple` keeps the `CoreCapacity` given to it, and `CpuCap.limit()` returns that object while the VM requests its full capacity. Before, each VM kept a new `CoreCapacity` as its MIPS share.
- `BootModel` is an immutable record, so one instance can be shared by all VMs. Before, it had setters, so each VM needed its own instance.

It compares machines that get their own `CoreCapacity` and `BootModel` with machines that share them. Each VM's `BootModel` has a `UtilizationModelDynamic` for CPU (50%) and another for RAM (30%), as `VmBootTimeAndOverheadExample` used to create for each VM. These models have no update function, so they are stateless and can be shared (see cloudsimplus#650).

- **Before:** CloudSim Plus `master` at `dc0816a29`.
- **After:** CloudSim Plus with #603.
- JDK 25, default GC, `-Xmx4g`. Each value is the median of 5 rounds. The values change up to about 15 KB between runs.

### Heap retained by each machine just created (bytes)

| `CoreCapacity` (and `BootModel` for VMs)          |  Host |    VM | VM reduction vs. usual case before |
| ------------------------------------------------- | ----: | ----: | ---------------------------------: |
| New instances for each machine, before (usual case) | 625.4 | 888.2 |                                  — |
| Shared instances, before (possible, but not safe) | 625.8 | 736.8 |                    151.4 B (17.0%) |
| New instances for each machine, after             | 649.7 | 912.2 |                    −24.0 B (−2.7%) |
| **Shared instances, after**                       | **617.7** | **729.1** |                **159.1 B (17.9%)** |

Since a processor now keeps the given `CoreCapacity` (32 bytes) instead of copying its MIPS, each machine created with its own instance takes about 24 bytes more. With a shared instance, each machine takes about 8 bytes less.

### Whole simulation while VMs are running

Scenario: 1,000 Hosts with 32 cores, 4,000 VMs with 8 cores and 8,000 Cloudlets with 4 cores, measured at 5 seconds of simulation, when all VMs have a MIPS share allocated.

Each configuration is compared with the usual case before #603: a new `CoreCapacity` and `BootModel` for each VM, since a mutable `BootModel` couldn't be safely shared.

| `CoreCapacity` and `BootModel`                     |       Heap | Reduction (heap) | Reduction per VM |
| -------------------------------------------------- | ---------: | ---------------: | ---------------: |
| New instances for each VM, before (usual case)     | 7,930.3 KB |                — |                — |
| Shared instances, before (possible, but not safe)  | 7,247.4 KB |  682.9 KB (8.6%) |            175 B |
| New instances for each VM, after                   | 7,901.9 KB |   28.4 KB (0.4%) |              7 B |
| **Shared instances, after**                        | **7,114.6 KB** | **815.7 KB (10.3%)** |        **209 B** |

Of the 209 bytes saved per VM, 175 come from sharing the `BootModel` and its two `UtilizationModel`s, which #603 makes safe, and 34 come from #603 itself (shared before → shared after), since a VM's MIPS share is no longer a new `CoreCapacity`.
