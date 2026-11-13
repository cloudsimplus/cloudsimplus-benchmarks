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

import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.CoreCapacity;
import org.cloudsimplus.util.Log;
import org.cloudsimplus.utilizationmodels.BootModel;
import org.cloudsimplus.utilizationmodels.UtilizationModel;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.function.IntFunction;

import static org.cloudsimplus.benchmarks.ProcessorScenario.*;

/// Measures the memory footprint reduction from
/// [cloudsimplus#603](https://github.com/cloudsimplus/cloudsimplus/issues/603),
/// where configuration values such as [CoreCapacity] and [BootModel] are immutable,
/// so a single instance can be safely shared by many machines.
///
/// It compares machines that share one [CoreCapacity] and one [BootModel]
/// with machines that get their own instances, measuring:
/// 1. the heap retained by each Host and VM just created;
/// 2. the heap retained by a whole [ProcessorScenario] while its VMs are running
///    (when each VM has a MIPS share allocated).
///
/// Each value is the median of [#ROUNDS] rounds (see [HeapMeter]).
/// The code compiles with the CloudSim Plus API before and after #603, so run it for each version:
///
/// ```bash
/// ./mvnw clean package
/// java -Xmx4g -cp target/cloudsimplus-benchmarks-*-uber.jar org.cloudsimplus.benchmarks.SharedConfigBenchmark before
/// ```
///
/// @author Manoel Campos da Silva Filho
public final class SharedConfigBenchmark {
    /// Number of times each measurement is repeated.
    private static final int ROUNDS = 5;

    /// Number of machines created in each round to measure the heap retained by each one.
    private static final int OBJECTS = 100_000;

    /// The simulation time (in seconds) to measure the heap retained by the scenario.
    /// It's before the first Cloudlet finishes, so all VMs are still running.
    private static final double RUNNING_TIME = 5;

    private static final double MIPS = 1000;
    private static final long HOST_RAM = 4096;
    private static final long HOST_BW = 10_000;
    private static final long HOST_STORAGE = 100_000;
    private static final double BYTES_PER_KILOBYTE = 1024;

    private static final CoreCapacity SHARED_HOST_CAPACITY = new CoreCapacity(HOST_CORES, MIPS);
    private static final CoreCapacity SHARED_VM_CAPACITY = new CoreCapacity(VM_CORES, MIPS);

    /// The CPU and RAM percentages a VM uses during boot.
    /// Since the [UtilizationModelDynamic]s have no update function, they are stateless and can be shared.
    private static final double BOOT_CPU_UTILIZATION = 0.5;
    private static final double BOOT_RAM_UTILIZATION = 0.3;
    private static final BootModel SHARED_BOOT_MODEL = newBootModel();

    /// A private constructor to avoid class instantiation.
    private SharedConfigBenchmark(){}

    /// Runs the benchmark.
    /// @param args an optional label to identify the CloudSim Plus version being measured (e.g., "before" or "after")
    public static void main(final String[] args) {
        Log.setLevel(ch.qos.logback.classic.Level.OFF);
        final String label = args.length > 0 ? args[0] : "current";
        System.out.printf("%s (%s): median of %d rounds%n%n", SharedConfigBenchmark.class.getSimpleName(), label, ROUNDS);

        printMachinesFootprint();
        printScenarioFootprint();
    }

    private static void printMachinesFootprint() {
        System.out.printf("Heap retained by each machine just created (bytes), %,d objects per round:%n", OBJECTS);
        System.out.printf("  %-30s | %10s | %10s%n", "CoreCapacity (and VM BootModel)", "HostSimple", "VmSimple");
        printMachinesFootprint("new instances for each machine",
            i -> HostSimple.of(HOST_RAM, HOST_BW, HOST_STORAGE, new CoreCapacity(HOST_CORES, MIPS)),
            SharedConfigBenchmark::newVmWithOwnConfig);
        printMachinesFootprint("shared instances",
            i -> HostSimple.of(HOST_RAM, HOST_BW, HOST_STORAGE, SHARED_HOST_CAPACITY),
            SharedConfigBenchmark::newVmWithSharedConfig);
        System.out.println();
    }

    private static void printMachinesFootprint(final String config, final IntFunction<?> hostFactory, final IntFunction<?> vmFactory) {
        final double host = HeapMeter.bytesPerObject(hostFactory, OBJECTS, ROUNDS);
        final double vm = HeapMeter.bytesPerObject(vmFactory, OBJECTS, ROUNDS);
        System.out.printf("  %-30s | %,10.1f | %,10.1f%n", config, host, vm);
    }

    private static void printScenarioFootprint() {
        final int vms = HOSTS * VMS_PER_HOST;
        System.out.printf("Heap retained by a scenario with %,d Hosts, %,d VMs and %,d Cloudlets, at time %.0f s (VMs running):%n",
                HOSTS, vms, vms * CLOUDLETS_PER_VM, RUNNING_TIME);
        printScenarioFootprint("new instances for each VM", SharedConfigBenchmark::newVmWithOwnConfig);
        printScenarioFootprint("shared instances", SharedConfigBenchmark::newVmWithSharedConfig);
    }

    private static void printScenarioFootprint(final String config, final IntFunction<Vm> vmFactory) {
        final double[] bytes = new double[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            bytes[round] = runningScenarioBytes(vmFactory);
        }

        System.out.printf("  CoreCapacity and BootModel: %-26s %,10.1f KB%n", config, HeapMeter.median(bytes) / BYTES_PER_KILOBYTE);
    }

    /// {@return the heap (in bytes) retained by a scenario while its VMs and Cloudlets are running}
    /// It's measured during the simulation, since VMs are destroyed when it finishes.
    /// This way, it includes the MIPS shares allocated to VMs.
    private static double runningScenarioBytes(final IntFunction<Vm> vmFactory) {
        final long liveBefore = HeapMeter.liveBytes();
        final long[] liveWhileRunning = {0};
        new ProcessorScenario(vmFactory).run(RUNNING_TIME, () -> liveWhileRunning[0] = HeapMeter.liveBytes());
        return liveWhileRunning[0] - liveBefore;
    }

    /// Creates a VM with its own [CoreCapacity] and [BootModel] (including its [UtilizationModel]s),
    /// as needed when a [BootModel] is mutable and can't be safely shared.
    private static Vm newVmWithOwnConfig(final int index) {
        return VmSimple.of(new CoreCapacity(VM_CORES, MIPS)).setBootModel(newBootModel());
    }

    private static Vm newVmWithSharedConfig(final int index) {
        return VmSimple.of(SHARED_VM_CAPACITY).setBootModel(SHARED_BOOT_MODEL);
    }

    private static BootModel newBootModel() {
        return new BootModel(new UtilizationModelDynamic(BOOT_CPU_UTILIZATION), new UtilizationModelDynamic(BOOT_RAM_UTILIZATION));
    }
}
