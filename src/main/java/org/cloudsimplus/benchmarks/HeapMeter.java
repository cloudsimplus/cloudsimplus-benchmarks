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

import javax.management.JMException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.function.IntFunction;

/// Measures the heap retained by objects (their memory footprint).
///
/// It creates a number of objects, keeps them in an array and measures the bytes of all live objects
/// in the heap, before and after creating them. The difference divided by the number of objects
/// is the heap retained by each object (with everything it references).
/// Each measurement is repeated a number of rounds and the median is returned.
///
/// The live bytes are got from a class histogram (the same as `jcmd <pid> GC.class_histogram`),
/// which runs a full GC and counts only reachable objects. The used heap from
/// [java.lang.management.MemoryMXBean] isn't used, since it may include objects not collected yet,
/// even after calling [System#gc()]. This way, it works with any GC.
///
/// @author Manoel Campos da Silva Filho
final class HeapMeter {
    private static final String DIAGNOSTIC_COMMAND_MBEAN = "com.sun.management:type=DiagnosticCommand";
    private static final String CLASS_HISTOGRAM_OPERATION = "gcClassHistogram";

    /// A private constructor to avoid class instantiation.
    private HeapMeter(){}

    /// {@return the median heap (in bytes) retained by each object created by a given function}
    /// @param factory a function that creates an object for a given index
    /// @param objects number of objects to create in each round
    /// @param rounds number of times the measurement is repeated
    static double bytesPerObject(final IntFunction<?> factory, final int objects, final int rounds) {
        final double[] bytesPerObject = new double[rounds];
        for (int round = 0; round < rounds; round++) {
            bytesPerObject[round] = bytesPerObject(factory, objects);
        }

        return median(bytesPerObject);
    }

    /// {@return the heap (in bytes) retained by each object created by a given function in a single round}
    /// The array that keeps the objects alive is created before the first reading,
    /// so it isn't included in the measured size.
    private static double bytesPerObject(final IntFunction<?> factory, final int objects) {
        final Object[] array = new Object[objects];
        final long liveBefore = liveBytes();
        for (int i = 0; i < objects; i++) {
            array[i] = factory.apply(i);
        }

        final long liveAfter = liveBytes();
        Reference.reachabilityFence(array);
        return (liveAfter - liveBefore) / (double) objects;
    }

    /// {@return the bytes of all live objects in the heap, after a full GC}
    /// It reads the total from the last line of the class histogram,
    /// which has the format `Total <instances> <bytes>`.
    static long liveBytes() {
        final String[] lines = classHistogram().strip().split("\n");
        final String[] total = lines[lines.length - 1].trim().split("\\s+");
        return Long.parseLong(total[2]);
    }

    private static String classHistogram() {
        try {
            final Object[] params = {null};
            final String[] signature = {String[].class.getName()};
            return (String) ManagementFactory.getPlatformMBeanServer()
                    .invoke(new ObjectName(DIAGNOSTIC_COMMAND_MBEAN), CLASS_HISTOGRAM_OPERATION, params, signature);
        } catch (final JMException e) {
            throw new IllegalStateException("Couldn't get the class histogram", e);
        }
    }

    /// {@return the median of some values}
    static double median(final double[] values) {
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        final int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }
}
