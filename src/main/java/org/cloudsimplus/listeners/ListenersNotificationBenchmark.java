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

import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/// Measures the time and memory to notify listeners stored in:
/// - an immutable [List] created by `List.of(array)` (as CloudSim Plus does) and
/// - a plain array, which is the alternative being assessed.
///
/// The lists are created directly by `List.of(array)`, instead of using the CloudSim Plus class that stores listeners,
/// so that the benchmark compiles against different CloudSim Plus versions.
///
/// A for-each loop over a [List] calls [List#iterator()], which creates an object,
/// unless the JIT removes it using escape analysis. A for-each loop over an array creates no object.
/// Run with the GC profiler (`-prof gc`) and check `gc.alloc.rate.norm` (bytes per operation)
/// to see if the iterator is created.
///
/// The listeners just increment a counter, and all of them get the same [EventInfo],
/// so only the iteration is measured (the framework creates an [EventInfo] for each listener).
///
/// There are 2 kinds of benchmarks:
/// - `single*`: notifies one list (or array) with [SingleSize#listenersCount] listeners.
///   Since each param value runs in its own fork, the loop only sees one [List] class
///   (`List.of()` lists with 0 or 3+ elements are `ListN`, with 1 or 2 elements are `List12`).
/// - `mixed*`: notifies lists with 0 to [MixedSizes#MAX_LISTENERS] listeners in each operation,
///   like a simulation with many VMs that have different numbers of listeners.
///   This way, the loop sees both [List] classes, as it happens in a real simulation.
///
/// @author Manoel Campos da Silva Filho
/// @see <a href="https://github.com/cloudsimplus/cloudsimplus/issues/586">CloudSim Plus issue #586</a>
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ListenersNotificationBenchmark {
    /// The [EventInfo] given to all listeners.
    private static final EventInfo EVENT_INFO = EventInfo.of(info -> {}, 0);

    /// Counts how many times listeners were called, so that the JIT can't remove the calls.
    @State(Scope.Thread)
    public static class Counter {
        long calls;
    }

    /// Stores a given number of listeners, both in a list and in an array.
    @State(Scope.Thread)
    public static class SingleSize {
        @Param({"0", "1", "2", "3"})
        int listenersCount;

        @SuppressWarnings("NullAway.Init") // initialized in setup()
        List<EventListener<EventInfo>> list;

        @SuppressWarnings("NullAway.Init") // initialized in setup()
        EventListener<EventInfo>[] array;

        @Setup(Level.Trial)
        public void setup(final Counter counter) {
            array = newListeners(listenersCount, counter);
            list = List.of(array);
        }
    }

    /// Stores lists (and arrays) with 0 to [#MAX_LISTENERS] listeners.
    @State(Scope.Thread)
    public static class MixedSizes {
        static final int MAX_LISTENERS = 3;

        @SuppressWarnings("NullAway.Init") // initialized in setup()
        List<EventListener<EventInfo>>[] lists;

        @SuppressWarnings("NullAway.Init") // initialized in setup()
        EventListener<EventInfo>[][] arrays;

        @Setup(Level.Trial)
        @SuppressWarnings("unchecked")
        public void setup(final Counter counter) {
            lists = new List[MAX_LISTENERS + 1];
            arrays = new EventListener[MAX_LISTENERS + 1][];
            for (int count = 0; count <= MAX_LISTENERS; count++) {
                arrays[count] = newListeners(count, counter);
                lists[count] = List.of(arrays[count]);
            }
        }
    }

    @Benchmark
    public long singleList(final SingleSize state, final Counter counter) {
        notifyListeners(state.list);
        return counter.calls;
    }

    @Benchmark
    public long singleArray(final SingleSize state, final Counter counter) {
        notifyListeners(state.array);
        return counter.calls;
    }

    @Benchmark
    public long mixedList(final MixedSizes state, final Counter counter) {
        for (final var list : state.lists) {
            notifyListeners(list);
        }

        return counter.calls;
    }

    @Benchmark
    public long mixedArray(final MixedSizes state, final Counter counter) {
        for (final var array : state.arrays) {
            notifyListeners(array);
        }

        return counter.calls;
    }

    private static void notifyListeners(final List<EventListener<EventInfo>> listeners) {
        for (final var listener : listeners) {
            listener.update(EVENT_INFO);
        }
    }

    private static void notifyListeners(final EventListener<EventInfo>[] listeners) {
        for (final var listener : listeners) {
            listener.update(EVENT_INFO);
        }
    }

    /// Creates an array with the given number of listeners.
    /// Each listener is a different lambda instance, since it captures its own id.
    /// The callers create a list with `List.of(array)`, which copies the array, so the list and the array don't share storage.
    @SuppressWarnings("unchecked")
    private static EventListener<EventInfo>[] newListeners(final int listenersCount, final Counter counter) {
        final EventListener<EventInfo>[] listeners = new EventListener[listenersCount];
        for (int i = 0; i < listenersCount; i++) {
            final int id = i + 1;
            listeners[i] = info -> counter.calls += id;
        }

        return listeners;
    }
}
