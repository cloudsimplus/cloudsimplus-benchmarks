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

import org.cloudsimplus.placementpolicies.VmPlacementPolicyBestFit;
import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.CoreCapacity;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;

/// A simulation scenario used by [ProcessorBenchmark], where Hosts and VMs have many CPU cores.
/// It's built when the object is created and executed by [#run()].
///
/// Hosts and VMs are created only by [#newHost(long, long, long, long, double)] and [#newVm(long, double)],
/// which use the CloudSim Plus API where each machine has a single
/// [org.cloudsimplus.resources.Processor] that just counts its cores.
///
/// Cloudlets have different lengths, so they finish at different times.
/// This way, the simulation processes many events that change the number of
/// busy cores of VMs and Hosts, instead of finishing all Cloudlets at once.
///
/// @author Manoel Campos da Silva Filho
final class ProcessorScenario {
    static final int HOSTS = 1000;
    static final int HOST_CORES = 32;
    static final int VMS_PER_HOST = 4;
    static final int VM_CORES = HOST_CORES / VMS_PER_HOST;
    static final int CLOUDLETS_PER_VM = 2;
    static final int CLOUDLET_CORES = VM_CORES / CLOUDLETS_PER_VM;

    private static final double MIPS = 1000;
    private static final long HOST_RAM = 65_536;
    private static final long HOST_BW = 100_000;
    private static final long HOST_STORAGE = 1_000_000;
    private static final long VM_RAM = HOST_RAM / VMS_PER_HOST;
    private static final long VM_BW = HOST_BW / VMS_PER_HOST;
    private static final long VM_SIZE = HOST_STORAGE / VMS_PER_HOST;

    /// The length of the shortest Cloudlet (in MI).
    private static final long CLOUDLET_MIN_LENGTH = 10_000;

    /// Number of different Cloudlet lengths, which are multiples of [#CLOUDLET_MIN_LENGTH].
    private static final int CLOUDLET_LENGTHS = 20;

    private final CloudSimPlus simulation;

    ProcessorScenario() {
        simulation = new CloudSimPlus();
        final var datacenter = DatacenterSimple.of(simulation, createHosts(), new VmPlacementPolicyBestFit());
        datacenter.setSchedulingInterval(1);

        final DatacenterBroker broker = new DatacenterBrokerSimple(simulation);
        final List<Vm> vms = createVms();
        broker.submitVmList(vms);
        broker.submitCloudletList(createCloudlets());
    }

    /// Runs the simulation.
    /// @return the time (in seconds) to run the simulation
    double run() {
        final long start = System.nanoTime();
        simulation.start();
        return (System.nanoTime() - start) / 1e9;
    }

    private static List<Host> createHosts() {
        final var hosts = new ArrayList<Host>(HOSTS);
        for (int i = 0; i < HOSTS; i++) {
            final Host host = newHost(HOST_RAM, HOST_BW, HOST_STORAGE, HOST_CORES, MIPS);
            host.setVmScheduler(new VmSchedulerTimeShared());
            hosts.add(host);
        }

        return hosts;
    }

    private static List<Vm> createVms() {
        final int count = HOSTS * VMS_PER_HOST;
        final var vms = new ArrayList<Vm>(count);
        for (int i = 0; i < count; i++) {
            vms.add(newVm(VM_CORES, MIPS).setRam(VM_RAM).setBw(VM_BW).setSize(VM_SIZE));
        }

        return vms;
    }

    private static List<Cloudlet> createCloudlets() {
        final int count = HOSTS * VMS_PER_HOST * CLOUDLETS_PER_VM;
        final var cloudlets = new ArrayList<Cloudlet>(count);
        for (int i = 0; i < count; i++) {
            final long length = CLOUDLET_MIN_LENGTH * (1 + i % CLOUDLET_LENGTHS);
            cloudlets.add(CloudletSimple.of(length, CLOUDLET_CORES));
        }

        return cloudlets;
    }

    /// {@return a new Host}
    /// @param ram the RAM capacity (in Megabytes)
    /// @param bw the bandwidth capacity (in Megabits/s)
    /// @param storage the storage capacity (in Megabytes)
    /// @param cores the number of CPU cores
    /// @param mips the MIPS capacity of each core
    static Host newHost(final long ram, final long bw, final long storage, final long cores, final double mips) {
        return HostSimple.of(ram, bw, storage, new CoreCapacity(cores, mips));
    }

    /// {@return a new VM}
    /// @param cores the number of CPU cores
    /// @param mips the MIPS capacity of each core
    static Vm newVm(final long cores, final double mips) {
        return VmSimple.of(new CoreCapacity(cores, mips));
    }
}
