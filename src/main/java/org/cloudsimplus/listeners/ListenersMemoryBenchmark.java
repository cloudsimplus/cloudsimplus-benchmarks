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
package org.cloudsimplus.listeners;

import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.vms.VmSimple;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/// Measures the heap retained by each VM and Host, to compare the memory used
/// before and after the listener lists became the shared `List.of()` (issue #586).
/// Before that, each VM created 6 empty `ArrayList`s and each Host created
/// 2 empty `ArrayList`s and 1 empty `HashSet`, even when no listener was added.
///
/// It's a plain `main` (not JMH), since JMH measures time and allocation rate,
/// but here we need the memory that stays in the heap after the objects are created.
/// It creates [#OBJECTS] objects, keeps them in an array and measures the used heap
/// after a GC, before and after creating them. The difference divided by the number of objects
/// is the heap retained by each object. This is repeated [#ROUNDS] times and the median is shown.
///
/// The results are only accurate with a stop-the-world GC, where [System#gc()]
/// does a full collection, so run it with `-XX:+UseSerialGC`.
/// The `scripts/listeners-memory-before-after.sh` script runs it
/// with CloudSim Plus built before and after issue #586.
///
/// @author Manoel Campos da Silva Filho
/// @see <a href="https://github.com/cloudsimplus/cloudsimplus/issues/586">CloudSim Plus issue #586</a>
/// @see <a href="https://github.com/cloudsimplus/cloudsimplus/issues/523">CloudSim Plus issue #523</a>
public final class ListenersMemoryBenchmark {
    /// Number of objects of each type created in each round.
    private static final int OBJECTS = 100_000;

    /// Number of times each measurement is repeated.
    private static final int ROUNDS = 5;

    /// Number of times [System#gc()] is called before reading the used heap,
    /// so that objects waiting for finalization or in reference queues are also collected.
    private static final int GC_CALLS = 3;

    private static final double VM_MIPS = 1000;
    private static final long VM_PES = 1;

    private static final double PE_MIPS = 1000;
    private static final long HOST_RAM = 4096;
    private static final long HOST_BW = 10_000;
    private static final long HOST_STORAGE = 100_000;

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    /// A private constructor to avoid class instantiation.
    private ListenersMemoryBenchmark(){}

    /// Runs the benchmark.
    /// @param args an optional label to identify the CloudSim Plus version being measured (e.g., "before" or "after")
    public static void main(final String[] args) {
        final String label = args.length > 0 ? args[0] : "current";
        System.out.printf("%s (%s): %,d objects per round, median of %d rounds%n",
                ListenersMemoryBenchmark.class.getSimpleName(), label, OBJECTS, ROUNDS);

        printResult("VmSimple", measure(ListenersMemoryBenchmark::newVm));
        printResult("HostSimple", measure(ListenersMemoryBenchmark::newHost));
    }

    private static VmSimple newVm(final int index) {
        return new VmSimple(VM_MIPS, VM_PES);
    }

    /// Creates a Host with a single PE.
    /// The PE is included in the measured size, but it's the same before and after the change.
    private static HostSimple newHost(final int index) {
        return new HostSimple(HOST_RAM, HOST_BW, HOST_STORAGE, List.of(new PeSimple(PE_MIPS)));
    }

    /// {@return the median heap (in bytes) retained by each object created by a given function}
    /// @param factory a function that creates an object for a given index
    private static double measure(final IntFunction<Object> factory) {
        final double[] bytesPerObject = new double[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            bytesPerObject[round] = measureRound(factory);
        }

        return median(bytesPerObject);
    }

    /// {@return the heap (in bytes) retained by each object created by a given function in a single round}
    /// The array that keeps the objects alive is created before the first reading,
    /// so it isn't included in the measured size.
    private static double measureRound(final IntFunction<Object> factory) {
        final Object[] objects = new Object[OBJECTS];
        final long usedBefore = usedHeapAfterGc();
        for (int i = 0; i < OBJECTS; i++) {
            objects[i] = factory.apply(i);
        }

        final long usedAfter = usedHeapAfterGc();
        Reference.reachabilityFence(objects);
        return (usedAfter - usedBefore) / (double) OBJECTS;
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < GC_CALLS; i++) {
            System.gc();
        }

        return MEMORY.getHeapMemoryUsage().getUsed();
    }

    private static double median(final double[] values) {
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        final int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }

    private static void printResult(final String className, final double bytesPerObject) {
        System.out.printf("  %-10s %8.1f bytes per object%n", className, bytesPerObject);
    }
}
