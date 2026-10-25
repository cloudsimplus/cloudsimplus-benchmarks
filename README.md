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
