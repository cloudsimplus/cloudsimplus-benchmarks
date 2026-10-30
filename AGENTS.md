# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Overview

JMH (Java Microbenchmark Harness) benchmarks that measure the performance of critical [CloudSim Plus](https://github.com/cloudsimplus/cloudsimplus) APIs. It is only needed when investigating performance issues of the framework. Requires JDK 17+ (enforced by `maven-enforcer-plugin`).

## Commands

```bash
./mvnw clean install        # build (Windows: mvnw.cmd clean install)
./mvnw -B package           # what CI runs (.github/workflows/maven.yml)
```

`package` runs `maven-shade-plugin` and produces `target/cloudsimplus-benchmarks-<version>-uber.jar`, whose main class is `org.openjdk.jmh.Main`.

```bash
# Run all JMH benchmarks (standard JMH CLI options apply)
java -jar target/cloudsimplus-benchmarks-8.5.1-uber.jar

# Run a single benchmark class or method (regex)
java -jar target/cloudsimplus-benchmarks-8.5.1-uber.jar SortedSetVsLinkedList
java -jar target/cloudsimplus-benchmarks-8.5.1-uber.jar 'CloudletToVmMappingSolutionBenchmark.testGetCost.*'

# List available benchmarks
java -jar target/cloudsimplus-benchmarks-8.5.1-uber.jar -l
```

Alternative entry points (run from the IDE or with `exec`/`java -cp`):
- `org.cloudsimplus.benchmarks.Run` runs every JMH benchmark matching `org.cloudsimplus.` with preset options (1 fork, 5 warmup, 25 measurement iterations, ms, 1 thread).
- `org.cloudsimplus.benchmarks.SimulationTimeBenchmark` is a plain `main` (not JMH): it runs a full simulation several times, growing Hosts/VMs/Cloudlets each run, and prints execution time.
- `org.cloudsimplus.benchmarks.MemoryFootprintBenchmark` is a plain `main` (not JMH): it prints the heap retained by each VM, Host and Cloudlet, i.e., their memory footprint (run it with `-XX:+UseSerialGC`). To compare with CloudSim Plus 8.5.1 (before issues #586 and #587), build with `./mvnw clean package -Pcloudsimplus-8`, run it, then build without the profile and run it again (see its Javadoc).

There are no unit tests in this project.

## Architecture

- **Version coupling:** the project `<version>` (currently `8.5.1`) is also the version of the `org.cloudsimplus:cloudsimplus` dependency (`${project.version}`). It must match a published CloudSim Plus release (or one installed locally). To benchmark local framework changes, build/install `../cloudsimplus` first (currently `9.0.0-SNAPSHOT` there) and bump this project's version to match; API changes between versions may break these sources.
- **Package placement:** benchmark classes live in the same packages as the framework classes they measure (`org.cloudsimplus.heuristics`, `org.cloudsimplus.collections`), so they can reach package-private members of CloudSim Plus.
- JMH benchmark classes use `@State(Scope.Thread)` with `@Setup` methods and `@BenchmarkMode({AverageTime, Throughput})`. JMH code is generated at compile time by `jmh-generator-annprocess`, so a rebuild is required after editing benchmarks.
- `license-maven-plugin` expects a header file at `../COPYRIGHT`, which does not exist in the sibling layout (it is at `../cloudsimplus/COPYRIGHT`); the plugin is not bound to a phase, so it only matters when invoked explicitly. Source files carry the GPLv3 CloudSim Plus header.
- Known issue: in `CloudletToVmMappingSolutionBenchmark.doSetup()` a local `heuristic` variable shadows the field, so the field stays `null` (`testCreateNeighbor` fails and solutions are built with a null heuristic).
