/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2021 Universidade da Beira Interior (UBI, Portugal) and
 *     the Instituto Federal de Educação Ciência e Tecnologia do Tocantins (IFTO, Brazil).
 *
 *     This file is part of CloudSim Plus.
 *
 *     CloudSim Plus is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     CloudSim Plus is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with CloudSim Plus. If not, see <http://www.gnu.org/licenses/>.
 */
package org.cloudsimplus.benchmarks;

import org.cloudsimplus.util.Log;

import java.util.function.IntFunction;

import static org.cloudsimplus.benchmarks.ProcessorScenario.*;

/// Measures the memory footprint and simulation time reduction from
/// [cloudsimplus#595](https://github.com/cloudsimplus/cloudsimplus/pull/595)
/// ([#592](https://github.com/cloudsimplus/cloudsimplus/issues/592)), which replaced
/// the `List<Pe>` of Hosts and VMs (one object per CPU core) by a single
/// `Processor` that just counts the cores in each status.
///
/// It measures:
/// 1. the heap retained by each Host and VM, for different numbers of cores.
///    With a `List<Pe>`, it grows with the number of cores. With a single `Processor`, it doesn't.
/// 2. the heap retained by a whole [ProcessorScenario] (Hosts, VMs, Cloudlets, broker and Datacenter),
///    before and after running it.
/// 3. the time to run that scenario.
///
/// Each value is the median of [#ROUNDS] rounds (see [HeapMeter]). The simulation time is measured
/// after [#WARMUP_ROUNDS] runs, so that the JIT compiler has already optimized the code.
/// It's a plain `main` (not JMH), since JMH measures time and allocation rate,
/// but here we also need the memory that stays in the heap.
///
/// This version uses the current CloudSim Plus API, where each machine has a single `Processor`:
///
/// ```bash
/// ./mvnw clean package
/// java -Xmx4g -cp target/cloudsimplus-benchmarks-*-uber.jar org.cloudsimplus.benchmarks.ProcessorBenchmark
/// ```
///
/// The numbers before #595 were got by running the first version of this benchmark
/// (which used the `List<Pe>` API) with CloudSim Plus built from commit 9bb9de25c.
/// Both results are in the README.
///
/// @author Manoel Campos da Silva Filho
public final class ProcessorBenchmark {
    /// Number of times each measurement is repeated.
    private static final int ROUNDS = 5;

    /// Number of simulation runs before measuring the simulation time.
    private static final int WARMUP_ROUNDS = 2;

    /// Number of objects created in each round to measure the heap retained by each Host or VM.
    /// It's smaller than in [MemoryFootprintBenchmark], since Hosts with many cores
    /// take a lot of memory when each core is an object.
    private static final int OBJECTS = 10_000;

    /// Numbers of cores of the Hosts and VMs measured.
    private static final int[] CORES = {1, 8, 32, 128};

    private static final double MIPS = 1000;
    private static final double BYTES_PER_MEGABYTE = 1024 * 1024;
    private static final long HOST_RAM = 4096;
    private static final long HOST_BW = 10_000;
    private static final long HOST_STORAGE = 100_000;

    /// A description of the CloudSim Plus API used to create Hosts and VMs.
    private static final String API = "single Processor (cloudsimplus#595)";

    /// A private constructor to avoid class instantiation.
    private ProcessorBenchmark(){}

    public static void main(final String[] args) {
        Log.setLevel(ch.qos.logback.classic.Level.OFF);
        System.out.printf("%s - CloudSim Plus API: %s%n%n", ProcessorBenchmark.class.getSimpleName(), API);

        printMachinesFootprint();
        printScenarioFootprint();
        printSimulationTime();
    }

    private static void printMachinesFootprint() {
        System.out.printf("Heap retained by each machine (bytes), median of %d rounds with %,d objects:%n", ROUNDS, OBJECTS);
        System.out.printf("  %5s | %12s | %12s%n", "Cores", "HostSimple", "VmSimple");
        for (final int cores : CORES) {
            final double host = bytesPerObject(i -> newHost(HOST_RAM, HOST_BW, HOST_STORAGE, cores, MIPS));
            final double vm = bytesPerObject(i -> newVm(cores, MIPS));
            System.out.printf("  %5d | %,12.1f | %,12.1f%n", cores, host, vm);
        }

        System.out.println();
    }

    private static double bytesPerObject(final IntFunction<?> factory) {
        return HeapMeter.bytesPerObject(factory, OBJECTS, ROUNDS);
    }

    private static void printScenarioFootprint() {
        System.out.printf("Scenario: %,d Hosts with %d cores, %,d VMs with %d cores, %,d Cloudlets with %d cores%n",
                HOSTS, HOST_CORES, HOSTS * VMS_PER_HOST, VM_CORES,
                HOSTS * VMS_PER_HOST * CLOUDLETS_PER_VM, CLOUDLET_CORES);

        final double before = HeapMeter.bytesPerObject(i -> new ProcessorScenario(), 1, ROUNDS);
        System.out.printf("  Heap retained by the scenario before running it: %,.1f MB%n", toMegabytes(before));

        final double after = HeapMeter.bytesPerObject(i -> newFinishedScenario(), 1, ROUNDS);
        System.out.printf("  Heap retained by the scenario after running it:  %,.1f MB%n", toMegabytes(after));
    }

    /// {@return a scenario that has already run}
    /// Since it's kept, its measured size includes the objects created while the simulation runs
    /// (such as Cloudlet executions and VM allocations).
    private static ProcessorScenario newFinishedScenario() {
        final var scenario = new ProcessorScenario();
        scenario.run();
        return scenario;
    }

    private static double toMegabytes(final double bytes) {
        return bytes / BYTES_PER_MEGABYTE;
    }

    private static void printSimulationTime() {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            new ProcessorScenario().run();
        }

        final double[] seconds = new double[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            seconds[i] = new ProcessorScenario().run();
        }

        System.out.printf("  Simulation time: %.3f seconds (median of %d runs after %d warmup runs)%n",
                HeapMeter.median(seconds), ROUNDS, WARMUP_ROUNDS);
    }
}
