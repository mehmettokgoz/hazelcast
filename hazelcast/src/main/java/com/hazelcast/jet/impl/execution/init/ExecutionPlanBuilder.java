/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.execution.init;

import com.hazelcast.cluster.Address;
import com.hazelcast.internal.cluster.MemberInfo;
import com.hazelcast.internal.partition.IPartitionService;
import com.hazelcast.jet.config.EdgeConfig;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.function.RunnableEx;
import com.hazelcast.jet.impl.JetServiceBackend;
import com.hazelcast.jet.impl.JobClassLoaderService;
import com.hazelcast.jet.impl.execution.init.Contexts.MetaSupplierCtx;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.spi.impl.NodeEngineImpl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.security.auth.Subject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.hazelcast.internal.util.ConcurrencyUtil.CALLER_RUNS;
import static com.hazelcast.jet.config.JobConfigArguments.KEY_REQUIRED_PARTITIONS;
import static com.hazelcast.jet.impl.util.ExceptionUtil.peel;
import static com.hazelcast.jet.impl.util.ExceptionUtil.sneakyThrow;
import static com.hazelcast.jet.impl.util.PrefixedLogger.prefix;
import static com.hazelcast.jet.impl.util.PrefixedLogger.prefixedLogger;
import static com.hazelcast.jet.impl.util.Util.checkSerializable;
import static com.hazelcast.jet.impl.util.Util.doWithClassLoader;
import static com.hazelcast.jet.impl.util.Util.toList;
import static com.hazelcast.spi.impl.executionservice.ExecutionService.JOB_OFFLOADABLE_EXECUTOR;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.stream.Collectors.toMap;

public final class ExecutionPlanBuilder {

    private ExecutionPlanBuilder() {
    }

    @SuppressWarnings({"checkstyle:ParameterNumber", "rawtypes"})
    public static CompletableFuture<Map<MemberInfo, ExecutionPlan>> createExecutionPlans(
            NodeEngineImpl nodeEngine,
            List<MemberInfo> memberInfos,
            DAG dag,
            long jobId,
            long executionId,
            JobConfig jobConfig,
            long lastSnapshotId,
            boolean isLightJob,
            Subject subject
    ) {
        final VerticesIdAndOrder verticesIdAndOrder = VerticesIdAndOrder.assignVertexIds(dag);
        final int defaultParallelism = nodeEngine.getConfig().getJetConfig().getCooperativeThreadCount();
        final EdgeConfig defaultEdgeConfig = nodeEngine.getConfig().getJetConfig().getDefaultEdgeConfig();
        Set<Integer> requiredPartitions = jobConfig.getArgument(KEY_REQUIRED_PARTITIONS);
        final Map<MemberInfo, ExecutionPlan> plans = new HashMap<>();
        int memberIndex = 0;

        final Map<MemberInfo, int[]> partitionsByMember = getPartitionAssignment(nodeEngine, memberInfos, requiredPartitions);
        final Map<Address, int[]> partitionsByAddress = partitionsByMember
                .entrySet()
                .stream()
                .collect(toMap(en -> en.getKey().getAddress(), Entry::getValue));
        final int clusterSize = partitionsByAddress.size();
        final boolean isJobDistributed = clusterSize > 1;

        for (MemberInfo member : partitionsByMember.keySet()) {
            plans.put(member, new ExecutionPlan(partitionsByAddress, jobConfig, lastSnapshotId, memberIndex++,
                    clusterSize, isLightJob, subject, verticesIdAndOrder.count()));
        }

        final List<Address> addresses = toList(partitionsByMember.keySet(), MemberInfo::getAddress);
        ExecutorService initOffloadExecutor = nodeEngine.getExecutionService().getExecutor(JOB_OFFLOADABLE_EXECUTOR);
        CompletableFuture[] futures = new CompletableFuture[verticesIdAndOrder.count()];
        for (VertexIdPos entry : verticesIdAndOrder) {
            final Vertex vertex = dag.getVertex(entry.vertexName);
            assert vertex != null;
            final ProcessorMetaSupplier metaSupplier = vertex.getMetaSupplier();
            final int vertexId = entry.vertexId;
            // The local parallelism determination here is effective only
            // in jobs submitted as DAG. Otherwise, in jobs submitted as
            // pipeline, we are already doing this determination while
            // converting it to DAG and there is no vertex left with LP=-1.
            final int localParallelism = vertex.determineLocalParallelism(defaultParallelism);
            final int totalParallelism = localParallelism * clusterSize;
            final List<EdgeDef> inbound = toEdgeDefs(dag.getInboundEdges(vertex.getName()), defaultEdgeConfig,
                    e -> verticesIdAndOrder.idByName(e.getSourceName()), isJobDistributed);
            final List<EdgeDef> outbound = toEdgeDefs(dag.getOutboundEdges(vertex.getName()), defaultEdgeConfig,
                    e -> verticesIdAndOrder.idByName(e.getDestName()), isJobDistributed);
            String prefix = prefix(jobConfig.getName(), jobId, vertex.getName(), "#PMS");
            ILogger logger = prefixedLogger(nodeEngine.getLogger(metaSupplier.getClass()), prefix);

            RunnableEx action = () -> {
                JetServiceBackend jetBackend = nodeEngine.getService(JetServiceBackend.SERVICE_NAME);
                JobClassLoaderService jobClassLoaderService = jetBackend.getJobClassLoaderService();
                ClassLoader processorClassLoader = jobClassLoaderService.getClassLoader(jobId);
                try {
                    doWithClassLoader(processorClassLoader, () ->
                            metaSupplier.init(new MetaSupplierCtx(nodeEngine, jobId, executionId,
                                    jobConfig, logger, vertex.getName(), localParallelism, totalParallelism, clusterSize,
                                    isLightJob, partitionsByAddress, subject, processorClassLoader)));
                } catch (Exception e) {
                    throw sneakyThrow(peel(e));
                }

                Function<? super Address, ? extends ProcessorSupplier> procSupplierFn =
                        doWithClassLoader(processorClassLoader, () -> metaSupplier.get(addresses));
                for (Entry<MemberInfo, ExecutionPlan> e : plans.entrySet()) {
                    final ProcessorSupplier processorSupplier =
                            doWithClassLoader(processorClassLoader, () -> procSupplierFn.apply(e.getKey().getAddress()));
                    if (!isLightJob) {
                        // We avoid the check for light jobs - the user will get the error anyway, but maybe with less
                        // information. And we can recommend the user to use normal job to have more checks.
                        checkSerializable(processorSupplier, "ProcessorSupplier in vertex '" + vertex.getName() + '\'');
                    }
                    final VertexDef vertexDef = new VertexDef(vertexId, vertex.getName(), processorSupplier, localParallelism);
                    vertexDef.addInboundEdges(inbound);
                    vertexDef.addOutboundEdges(outbound);
                    e.getValue().setVertex(entry.requiredPosition, vertexDef);
                }
            };
            Executor executor = metaSupplier.initIsCooperative() ? CALLER_RUNS : initOffloadExecutor;
            futures[entry.requiredPosition] = runAsync(action, executor);
        }
        return CompletableFuture.allOf(futures)
                .thenCompose(r -> completedFuture(plans));
    }

    /**
     * Basic vertex data wrapper:
     * - id
     * - name
     * - position
     */
    private static final class VerticesIdAndOrder implements Iterable<VertexIdPos> {
        private final LinkedHashMap<String, Integer> vertexIdMap;
        private final HashMap<Integer, Integer> vertexPosById;

        private VerticesIdAndOrder(LinkedHashMap<String, Integer> vertexIdMap) {
            this.vertexIdMap = vertexIdMap;
            int index = 0;
            vertexPosById = new LinkedHashMap<>(vertexIdMap.size());
            for (Integer vertexId : vertexIdMap.values()) {
                vertexPosById.put(vertexId, index++);
            }
        }

        private Integer idByName(String vertexName) {
            return vertexIdMap.get(vertexName);
        }

        private static VerticesIdAndOrder assignVertexIds(DAG dag) {
            LinkedHashMap<String, Integer> vertexIdMap = new LinkedHashMap<>();
            final int[] vertexId = {0};
            dag.forEach(v -> vertexIdMap.put(v.getName(), vertexId[0]++));
            return new VerticesIdAndOrder(vertexIdMap);
        }

        private int count() {
            return vertexIdMap.size();
        }

        @Nonnull
        @Override
        public Iterator<VertexIdPos> iterator() {
            return vertexIdMap.entrySet().stream()
                    .map(e -> new VertexIdPos(e.getValue(), e.getKey(), vertexPosById.get(e.getValue())))
                    .iterator();
        }
    }

    private static final class VertexIdPos {
        private final int vertexId;
        private final String vertexName;

        /**
         * Position on vertices list that vertex with this id/name should occupy.
         * {@link ExecutionPlan#getVertices()} order matters, it must be the same as DAG iteration order,
         * otherwise some functions in further processing won't give good results.
         */
        private final int requiredPosition;

        private VertexIdPos(int vertexId, String vertexName, int position) {
            this.vertexId = vertexId;
            this.vertexName = vertexName;
            this.requiredPosition = position;
        }
    }

    private static List<EdgeDef> toEdgeDefs(
            List<Edge> edges, EdgeConfig defaultEdgeConfig,
            Function<Edge, Integer> oppositeVtxId, boolean isJobDistributed
    ) {
        List<EdgeDef> list = new ArrayList<>(edges.size());
        for (Edge edge : edges) {
            list.add(new EdgeDef(edge, edge.getConfig() == null ? defaultEdgeConfig : edge.getConfig(),
                    oppositeVtxId.apply(edge), isJobDistributed));
        }
        return list;
    }

    /**
     * Assign the partitions to their owners. Partitions whose owner isn't in
     * the {@code memberList}, are assigned to one of the members in a
     * round-robin way.
     */
    public static Map<MemberInfo, int[]> getPartitionAssignment(
            NodeEngine nodeEngine, List<MemberInfo> memberList, @Nullable Set<Integer> requiredPartitions) {
        IPartitionService partitionService = nodeEngine.getPartitionService();
        Map<Address, MemberInfo> membersByAddress = new HashMap<>();
        for (MemberInfo memberInfo : memberList) {
            membersByAddress.put(memberInfo.getAddress(), memberInfo);
        }

        Map<MemberInfo, FixedCapacityIntArrayList> partitionsForMember = new HashMap<>();
        int partitionCount = partitionService.getPartitionCount();
        int memberIndex = 0;

        for (int partitionId : requiredPartitions != null
                ? requiredPartitions
                : IntStream.range(0, partitionCount).boxed().collect(Collectors.toList())) {
            Address address = partitionService.getPartitionOwnerOrWait(partitionId);
            MemberInfo member = membersByAddress.get(address);
            if (member == null) {
                // if the partition owner isn't in the current memberList, assign to one of the other members in
                // round-robin fashion
                member = memberList.get(memberIndex++ % memberList.size());
            }
            partitionsForMember.computeIfAbsent(member, ignored -> new FixedCapacityIntArrayList(partitionCount))
                    .add(partitionId);
        }

        Map<MemberInfo, int[]> partitionAssignment = new HashMap<>();
        for (Entry<MemberInfo, FixedCapacityIntArrayList> memberWithPartitions : partitionsForMember.entrySet()) {
            partitionAssignment.put(memberWithPartitions.getKey(), memberWithPartitions.getValue().asArray());
        }
        return partitionAssignment;
    }

    static class FixedCapacityIntArrayList {
        private int[] elements;
        private int size;

        FixedCapacityIntArrayList(int capacity) {
            elements = new int[capacity];
        }

        void add(int element) {
            elements[size++] = element;
        }

        int[] asArray() {
            int[] result = size == elements.length ? elements : Arrays.copyOfRange(elements, 0, size);
            elements = null;
            return result;
        }
    }
}
