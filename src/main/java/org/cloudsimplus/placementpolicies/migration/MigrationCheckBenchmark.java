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
package org.cloudsimplus.placementpolicies.migration;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.placementpolicies.VmPlacementPolicy;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.resources.CoreCapacity;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.selectionpolicies.VmSelectionPolicyMinimumUtilization;
import org.cloudsimplus.util.Log;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/// Measures one VM migration check: a call to
/// [VmPlacementPolicyMigrationAbstract#getOptimizedPlacementMap(List)],
/// which finds the VMs to migrate from overloaded and underloaded Hosts and their target Hosts.
///
/// It's the baseline for [cloudsimplus#94](https://github.com/cloudsimplus/cloudsimplus/issues/94),
/// where the check creates and destroys VMs in their Hosts to compute the migration map
/// and then restores the previous placement,
/// and for [cloudsimplus#648](https://github.com/cloudsimplus/cloudsimplus/issues/648),
/// the quadratic costs of the placement algorithm itself.
///
/// The Datacenter has [#hosts] Hosts with [#HOST_CORES] cores each:
/// - 1 in [#HOST_TYPES] is overloaded: its VMs use 100% of its CPU;
/// - 1 in [#HOST_TYPES] is underloaded: a single VM requests 1 core;
/// - the others use half of their CPU, so they can receive VMs.
///
/// Migrations are disabled in the Datacenter, so no migration is ever started.
/// While cloudsimplus#94 isn't fixed, the first check changes the placement:
/// VMs planned to leave underloaded Hosts are left in their target Hosts too,
/// and some VMs are removed from the broker's list of running VMs.
/// The state stops changing after the first check, so the warmup iterations
/// make every measured check start from the same placement.
///
/// Run it with the GC profiler to also get the memory allocated by each check:
///
/// ```bash
/// ./mvnw clean package
/// java -jar target/cloudsimplus-benchmarks-*-uber.jar MigrationCheckBenchmark -prof gc
/// ```
///
/// @author Manoel Campos da Silva Filho
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
public class MigrationCheckBenchmark {
    /// Hosts are created in groups of this size: one overloaded, one underloaded and the others half used.
    private static final int HOST_TYPES = 10;
    private static final int HOST_CORES = 8;
    private static final int MIPS = 1000;
    private static final long HOST_RAM = 100_000;
    private static final long HOST_BW = 100_000;
    private static final long HOST_STORAGE = 1_000_000;
    private static final double HOST_MAX_POWER = 200;
    private static final double HOST_STATIC_POWER = 100;

    /// Cores of each VM in overloaded and half used Hosts.
    private static final int VM_CORES = 2;
    private static final long VM_RAM = 1000;
    private static final long VM_BW = 1000;
    private static final long VM_SIZE = 1000;

    private static final double OVER_UTILIZATION_THRESHOLD = 0.8;
    private static final double UNDER_UTILIZATION_THRESHOLD = 0.2;

    /// Cloudlets are long enough to keep running while the check is measured.
    private static final long CLOUDLET_LENGTH = 1_000_000_000L;

    /// Simulation time to run before measuring, so that VMs are created and Cloudlets are running.
    private static final double STARTUP_TIME = 10;

    /// The Host index, the number of cores and the Cloudlet CPU utilization of a VM.
    private record VmSpec(int hostIndex, int cores, double cpuUtilization) { }

    @Param({"100", "500", "1000", "2000"})
    private int hosts;

    private VmPlacementPolicyMigrationAbstract policy;
    private List<Vm> vms;

    @Setup(Level.Trial)
    public void setup() {
        Log.setLevel(ch.qos.logback.classic.Level.OFF);
        final var simulation = new CloudSimPlus();
        final var vmSpecs = new ArrayList<VmSpec>();
        final var hostList = new ArrayList<Host>(hosts);
        for (int i = 0; i < hosts; i++) {
            hostList.add(createHost());
            addVmSpecs(i, vmSpecs);
        }

        policy = new VmPlacementPolicyMigrationStaticThreshold(new VmSelectionPolicyMinimumUtilization(), OVER_UTILIZATION_THRESHOLD);
        policy.setUnderUtilizationThreshold(UNDER_UTILIZATION_THRESHOLD);
        policy.setFindHostForVmFunction(initialPlacement(vmSpecs));
        DatacenterSimple.of(simulation, hostList, policy).disableMigrations();

        final var broker = new DatacenterBrokerSimple(simulation);
        vms = vmSpecs.stream().map(MigrationCheckBenchmark::createVm).toList();
        broker.submitVmList(vms);
        broker.submitCloudletList(createCloudlets(broker, vms, vmSpecs));

        simulation.startSync();
        // Each call processes the events of a single time
        while (simulation.clock() < STARTUP_TIME) {
            simulation.runFor(STARTUP_TIME - simulation.clock());
        }

        policy.setFindHostForVmFunction(null);
        final int createdVms = broker.getVmCreatedList().size();
        if (createdVms != vms.size()) {
            throw new IllegalStateException("Not all VMs were placed: %d of %d".formatted(createdVms, vms.size()));
        }
    }

    /// {@return the migration map, so that the JIT compiler doesn't remove the check}
    @Benchmark
    public Map<Vm, Host> migrationCheck() {
        return policy.getOptimizedPlacementMap(vms);
    }

    /// Adds the specs of the VMs for the Host with a given index.
    /// VMs in underloaded Hosts have a single core, so the Host requests less than the under utilization threshold.
    private static void addVmSpecs(final int hostIndex, final List<VmSpec> vmSpecs) {
        switch (hostIndex % HOST_TYPES) {
            case 0 -> addVmSpecs(vmSpecs, HOST_CORES / VM_CORES, new VmSpec(hostIndex, VM_CORES, 1.0));
            case 1 -> addVmSpecs(vmSpecs, 1, new VmSpec(hostIndex, 1, 0.5));
            default -> addVmSpecs(vmSpecs, HOST_CORES / VM_CORES / 2, new VmSpec(hostIndex, VM_CORES, 1.0));
        }
    }

    private static void addVmSpecs(final List<VmSpec> vmSpecs, final int count, final VmSpec spec) {
        for (int i = 0; i < count; i++) {
            vmSpecs.add(spec);
        }
    }

    /// {@return a function that places each VM into the Host index given for it}
    /// The broker gives VMs sequential ids from 0, in the order they are submitted.
    private static BiFunction<VmPlacementPolicy, Vm, Optional<Host>> initialPlacement(final List<VmSpec> vmSpecs) {
        return (placementPolicy, vm) -> Optional.of(placementPolicy.getHostList().get(vmSpecs.get((int) vm.getId()).hostIndex()));
    }

    private static Host createHost() {
        final var host = HostSimple.of(HOST_RAM, HOST_BW, HOST_STORAGE, new CoreCapacity(HOST_CORES, MIPS));
        host.setVmScheduler(new VmSchedulerTimeShared());
        host.setPowerModel(new PowerModelHostSimple(HOST_MAX_POWER, HOST_STATIC_POWER));
        return host;
    }

    private static Vm createVm(final VmSpec spec) {
        final var vm = VmSimple.of(new CoreCapacity(spec.cores(), MIPS));
        vm.setRam(VM_RAM).setBw(VM_BW).setSize(VM_SIZE).setCloudletScheduler(new CloudletSchedulerTimeShared());
        return vm;
    }

    private static List<Cloudlet> createCloudlets(
        final DatacenterBrokerSimple broker, final List<Vm> vms, final List<VmSpec> vmSpecs)
    {
        final var list = new ArrayList<Cloudlet>(vms.size());
        for (int i = 0; i < vms.size(); i++) {
            final var vm = vms.get(i);
            final var spec = vmSpecs.get(i);
            final var cloudlet = CloudletSimple.of(CLOUDLET_LENGTH, spec.cores())
                .setUtilizationModelCpu(new UtilizationModelDynamic(spec.cpuUtilization()))
                .setUtilizationModelRam(new UtilizationModelFull())
                .setUtilizationModelBw(new UtilizationModelFull());
            broker.bindCloudletToVm(cloudlet, vm);
            list.add(cloudlet);
        }

        return list;
    }
}
