# CloudSim Plus Benchmarks [![Build Status](https://github.com/cloudsimplus/cloudsimplus-benchmarks/actions/workflows/maven.yml/badge.svg)](https://github.com/cloudsimplus/cloudsimplus-benchmarks/actions/workflows/maven.yml)


Module used to assess [CloudSim Plus](https://github.com/cloudsimplus/cloudsimplus) performance.
You don't need to care about it, unless you want to investigate performance issues or contribute in this manner.

It uses the [Java Microbenchmark Harness framework (JMH)](http://openjdk.java.net/projects/code-tools/jmh/) to enable measuring critical methods of the CloudSim Plus API which have a high impact in the simulation framework performance.