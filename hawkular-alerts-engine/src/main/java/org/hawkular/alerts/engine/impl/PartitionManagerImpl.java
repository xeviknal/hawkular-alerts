/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.alerts.engine.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.Local;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

import org.hawkular.alerts.api.model.data.Data;
import org.hawkular.alerts.api.model.event.Event;
import org.hawkular.alerts.api.model.trigger.Trigger;
import org.hawkular.alerts.api.services.DefinitionsService;
import org.hawkular.alerts.engine.log.MsgLogger;
import org.hawkular.alerts.engine.service.PartitionDataListener;
import org.hawkular.alerts.engine.service.PartitionManager;
import org.hawkular.alerts.engine.service.PartitionTriggerListener;
import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.jboss.logging.Logger;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

/**
 * Implementation of {@link PartitionManager} services based on Infinispan cache.
 *
 * This implementation uses a consistent hashing strategy {@see https://en.wikipedia.org/wiki/Consistent_hashing}
 * as a method to partition triggers across nodes.
 *
 * It needs three caches added into Wildfly/JBoss configuration files.
 *
 * standalone.xml:
 * [...]
 *       <cache-container name="hawkular-alerts" default-cache="triggers" statistics-enabled="true">
 *          <local-cache name="partition"/>
 *          <local-cache name="triggers"/>
 *          <local-cache name="data"/>
 *       </cache-container>
 * [...]
 *
 * standalone-ha.xml:
 * [...]
 *       <cache-container name="hawkular-alerts" default-cache="triggers" statistics-enabled="true">
 *          <transport lock-timeout="60000"/>
 *          <replicated-cache name="partition" mode="ASYNC">
 *              <transaction mode="BATCH"/>
 *          </replicated-cache>
 *          <replicated-cache name="triggers" mode="ASYNC">
 *              <transaction mode="BATCH"/>
 *          </replicated-cache>
 *          <replicated-cache name="data" mode="ASYNC">
 *              <transaction mode="BATCH"/>
 *          </replicated-cache>
 *       </cache-container>
 * [...]
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@Local(PartitionManager.class)
@Startup
@Singleton
@TransactionAttribute(value = TransactionAttributeType.NOT_SUPPORTED)
public class PartitionManagerImpl implements PartitionManager {

    public static final String BUCKETS = "buckets";
    public static final String PREVIOUS = "previousPartition";
    public static final String CURRENT = "currentPartition";

    private final MsgLogger msgLog = MsgLogger.LOGGER;
    private final Logger log = Logger.getLogger(PartitionManagerImpl.class);

    @EJB
    DefinitionsService definitionsService;

    /**
     * Indicate if the deployment is on a clustering scenario.
     * With distributed == false PartitionManager services are simply ignored.
     */
    private boolean distributed = false;

    /**
     * Access to the manager of the caches used for the partition services.
     * Main function is to manage the list of members and add listener for topology changes.
     */
    @Resource(name = "container/hawkular-alerts")
    private EmbeddedCacheManager cacheManager;

    /**
     * This cache will keep the table between nodes and buckets used for partition calculation.
     * A node is represented with the Address.hashCode meanwhile a bucket is an integer whitin range 0 - (n -1) nodes.
     *
     * This cache will also hold the partition, a map to indicate where a Trigger is distributed.
     * Internally a trigger is represented by a PartitionEntry.hashCode which will be the key of the map.
     * The value will be the Address.hashCode value after calculated the distribution.
     *
     * Partition cache is modified by cluster coordinator.
     */
    @Resource(name = "cache/partition")
    private Cache partitionCache;

    /**
     * This cache will be used to propagate a trigger across nodes.
     * It will hold listeners to notify the change.
     */
    @Resource(name = "cache/triggers")
    private Cache triggersCache;

    /**
     * This cache will be used to propagate a data or event across nodes.
     * It will hold listeners to notify the change.
     */
    @Resource(name = "cache/data")
    private Cache dataCache;

    /**
     * Representation of the current node in a cluster environment.
     * Computed from Address.hashCode,
     */
    private Integer currentNode = null;

    /**
     * Listener used to interact with the triggers partition events
     */
    private PartitionTriggerListener triggerListener;

    /**
     * Listener used to interact with the data/events partition events
     */
    private PartitionDataListener dataListener;

    @Override
    public boolean isDistributed() {
        return distributed;
    }

    @PostConstruct
    public void init() {
        /*
            Cache manager has an active transport (i.e. jgroups) when is configured on distributed mode
         */
        distributed = cacheManager.getTransport() != null;
        if (!distributed) {
            msgLog.infoPartitionManagerDisabled();
        } else {
            currentNode = cacheManager.getAddress().hashCode();
            cacheManager.addListener(new IspnListener() {
                @ViewChanged
                public void onTopologyChange(ViewChangedEvent event) {
                    /*
                        When a node is joining/leaving the cluster partition needs to be re-calculated and updated
                     */
                    if (log.isDebugEnabled()) {
                        log.debug("onTopologyChange(@ViewChange) received.");
                        log.debug("Old members: " + event.getOldMembers());
                        log.debug("New members: " + event.getNewMembers());
                    }
                    processTopologyChange();
                    invokePartitionChangeListener();
                }
            });
            triggersCache.addListener(new IspnListener() {
                @CacheEntryCreated
                public void onNewTrigger(CacheEntryCreatedEvent event) {
                    /*
                        When a trigger is added, updated or removed it should be notified on the PartitionManager.
                        PartitionManager adds an entry on "triggers" cache to fire an event that will place the trigger
                        on the partition and invoke PartitionTriggerListener previously registered to process the event.
                     */
                    NotifyTrigger newTrigger = (NotifyTrigger)triggersCache.get(event.getKey());
                    if (log.isDebugEnabled()) {
                        log.debug("onNewTrigger(@CacheEntryCreated) received.");
                        log.debug("NotifyTrigger: " + newTrigger);
                    }
                    /*
                        A trigger should be processed on the target node
                     */
                    if (newTrigger.toNode == currentNode) {
                        /*
                            Trigger entry should be consumed from cache
                         */
                        triggersCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES)
                                .removeAsync(event.getKey());
                        /*
                            Update partition
                         */
                        Map<PartitionEntry, Integer> current = (Map) partitionCache.get(CURRENT);
                        PartitionEntry newEntry = new PartitionEntry(newTrigger.getTenantId(),
                                newTrigger.getTriggerId());
                        boolean exist = current.containsKey(newEntry);
                        switch (newTrigger.getOperation()) {
                            case ADD:
                                if (!exist) {
                                    Map<PartitionEntry, Integer> newPartition= new HashMap<>(current);
                                    newPartition.put(newEntry, currentNode);
                                    partitionCache.startBatch();
                                    partitionCache.put(PREVIOUS, current);
                                    partitionCache.put(CURRENT, newPartition);
                                    partitionCache.endBatch(true);
                                    if (log.isDebugEnabled()) {
                                        log.debug("Previous: " + current);
                                        log.debug("Current: " + newPartition);
                                    }
                                }
                                break;
                            case REMOVE:
                                if (exist) {
                                    Map<PartitionEntry, Integer> newPartition= new HashMap<>(current);
                                    newPartition.remove(newEntry, currentNode);
                                    partitionCache.startBatch();
                                    partitionCache.put(PREVIOUS, current);
                                    partitionCache.put(CURRENT, newPartition);
                                    partitionCache.endBatch(true);
                                    if (log.isDebugEnabled()) {
                                        log.debug("Previous: " + current);
                                        log.debug("Current: " + newPartition);
                                    }
                                }
                                break;
                        }
                        /*
                            Finally invoke listener
                         */
                        if (triggerListener != null) {
                            triggerListener.onTriggerChange(newTrigger.getOperation(), newTrigger.getTenantId(),
                                    newTrigger.getTriggerId());
                        }
                    }
                }
            });
            dataCache.addListener(new IspnListener() {
                @CacheEntryCreated
                public void onNewData(CacheEntryCreatedEvent event) {
                    /*
                        When a new data/event is added it should be notified on the PartitionManager.
                        PartitionManager adds an entry on "data" cache to fire an event that will propagate the
                        across the nodes invoking previously registered PartitionDataListener.
                     */
                    NotifyData newData = (NotifyData)dataCache.get(event.getKey());
                    if (log.isDebugEnabled()) {
                        log.debug("onNewData(@CacheEntryCreated) received.");
                        log.debug("NotifyData: " + newData);
                    }
                    /*
                        Data entry should be consumed from cache
                     */
                    if (newData.getFromNode() == currentNode) {
                        dataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).removeAsync(event.getKey());
                    }
                    /*
                        Finally invoke listener on non-sender nodes
                     */
                    if (dataListener != null && newData.getFromNode() != currentNode) {
                        if (newData.getData() != null) {
                            dataListener.onNewData(newData.getData());
                        } else if (newData.getEvent() != null) {
                            dataListener.onNewEvent(newData.getEvent());
                        }
                    }
                }
            });
            /*
                Initial partition
             */
            processTopologyChange();
            msgLog.infoPartitionManagerEnabled();
        }
    }

    @Override
    public void notifyTrigger(Operation operation, String tenantId, String triggerId) {
        if (distributed) {
            PartitionEntry newEntry = new PartitionEntry(tenantId, triggerId);
            int toNode = calculateNewEntry(newEntry, (Map<Integer, Integer>)partitionCache.get(BUCKETS));
            NotifyTrigger nTrigger = new NotifyTrigger(currentNode, toNode, operation, tenantId, triggerId);
            Integer key = nTrigger.hashCode();
            triggersCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).putAsync(key, nTrigger);
        }
    }

    @Override
    public void registerTriggerListener(PartitionTriggerListener triggerListener) {
        this.triggerListener = triggerListener;
    }

    @Override
    public void notifyData(Data data) {
        if (distributed) {
            NotifyData nData = new NotifyData(currentNode, data);
            Integer key = nData.hashCode();
            dataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).putAsync(key, nData);
        }
    }

    @Override
    public void notifyEvent(Event event) {
        if (distributed) {
            NotifyData nEvent = new NotifyData(currentNode, event);
            Integer key = nEvent.hashCode();
            dataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).putAsync(key, nEvent);
        }
    }

    @Override
    public void registerDataListener(PartitionDataListener dataListener) {
        this.dataListener = dataListener;
    }

    /*
        Calculate a new partition based on the current topology.
        It should be invoked as a result of a topology event and it is executed by the coordinator node.
        It updated the new and old partition state on the "partition" cache.
     */
    private void processTopologyChange() {
        if (distributed && cacheManager.isCoordinator()) {
            /*
                Process nodes/buckets map
             */
            Map<Integer, Integer> oldBuckets = (Map<Integer, Integer>)partitionCache.get(BUCKETS);
            List<Integer> members = new ArrayList();
            cacheManager.getMembers().stream().forEach(a -> {
                members.add(a.hashCode());
            });
            Map<Integer, Integer> newBuckets = updateBuckets(oldBuckets, members);
            if (log.isDebugEnabled()) {
                log.debug("Processing Topology Change");
                log.debug("Old buckets: " + oldBuckets);
                log.debug("New buckets: " + newBuckets);
            }

            /*
                Process partition map
             */
            final List<PartitionEntry> entries = new ArrayList<>();
            Map<PartitionEntry, Integer> oldPartition = (Map<PartitionEntry, Integer>)partitionCache.get(CURRENT);
            Map<PartitionEntry, Integer> newPartition;
            if (oldPartition == null) {
                // Initial load of all triggers
                Collection<Trigger> triggers;
                try {
                    triggers = definitionsService.getAllTriggers();
                    triggers.stream().forEach(t -> {
                        PartitionEntry entry = new PartitionEntry(t.getTenantId(), t.getId());
                        entries.add(entry);
                    });
                } catch(Exception e) {
                    msgLog.errorCannotInitializePartitionManager(e.toString());
                }
            } else {
                oldPartition.keySet().stream().forEach(e -> {
                    entries.add(e);
                });
            }

            newPartition = calculatePartition(entries, newBuckets);
            if (log.isDebugEnabled()) {
                log.debug("Old partition: " + oldPartition);
                log.debug("New partition: " + newPartition);
            }

            partitionCache.startBatch();
            partitionCache.put(BUCKETS, newBuckets);
            if (oldPartition != null) {
                partitionCache.put(PREVIOUS, oldPartition);
            }
            partitionCache.put(CURRENT, newPartition);
            partitionCache.endBatch(true);
        }
    }

    /**
     * Update a nodes table.
     * This table is represented as a Map<Integer, Integer> where:
     *  - key is an index within [0, n-1] being n the number of nodes.
     *  - value is the code that represents a node, it is calculated from Address.hashCode
     *
     *  This method re-calculate a new table from an old table and with a new list with new members.
     *  The algorithm minimizes the changes across topology changes.
     *
     * @param oldBuckets the old table used as input for the calculation
     * @param members a new list of members
     * @return a new table of nodes
     */
    public Map<Integer, Integer> updateBuckets(Map<Integer, Integer> oldBuckets, List<Integer> members) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("newMembers must be not null");
        }
        /*
            Create a new map
         */
        if (oldBuckets == null || oldBuckets.isEmpty()) {
            Map<Integer, Integer> newBuckets = new HashMap<>();
            for (int i = 0; i < members.size(); i++) {
                newBuckets.put(i, members.get(i));
            }
            return newBuckets;
        }

        Map<Integer, Integer> newBuckets = new HashMap<>();
        int newBucket = 0;
        while (newBucket < members.size()) {
            int bucket = 0;
            boolean placed = false;
            while (bucket < oldBuckets.size() && !placed) {
                Integer oldMember = oldBuckets.get(bucket);
                if (!newBuckets.containsValue(oldMember)
                        && members.contains(oldMember)
                        && (bucket == newBucket || bucket >= members.size())) {
                    newBuckets.put(newBucket, oldMember);
                    placed = true;
                } else {
                    bucket++;
                }
            }
            if (bucket == oldBuckets.size() && !placed) {
                newBuckets.put(newBucket, members.get(newBucket));
            }
            newBucket++;
        }
        return newBuckets;
    }

    /**
     * Distribute triggers on nodes using a consistent hashing strategy.
     * This strategy allows to scale and minimize changes and re-distribution when cluster changes.
     *
     * @param entries a list of entries to distribute
     * @param buckets a table of nodes
     * @return a map of entries distributed across nodes
     */
    public Map<PartitionEntry, Integer> calculatePartition(List<PartitionEntry> entries,
                                                           Map<Integer, Integer> buckets) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must be not null");
        }
        if (buckets == null || buckets.isEmpty()) {
            throw new IllegalArgumentException("entries must be not null");
        }
        HashFunction md5 = Hashing.md5();
        int numBuckets = buckets.size();
        Map<PartitionEntry, Integer> newPartition = new HashMap<>();
        for (PartitionEntry entry : entries) {
            newPartition.put(entry, buckets.get(Hashing.consistentHash(md5.hashInt(entry.hashCode()), numBuckets)));
        }
        return newPartition;
    }

    /**
     * Distribute a new entry across buckets using a consistent hashing strategy.
     *
     * @param newEntry the new entry to distribute
     * @param buckets a table of nodes
     * @return a code of the node which the new entry is placed
     */
    public Integer calculateNewEntry(PartitionEntry newEntry, Map<Integer, Integer> buckets) {
        if (newEntry == null) {
            throw new IllegalArgumentException("newEntry must be not null");
        }
        if (buckets == null || buckets.isEmpty()) {
            throw new IllegalArgumentException("buckets must be not null");
        }
        HashFunction md5 = Hashing.md5();
        int numBuckets = buckets.size();
        return buckets.get(Hashing.consistentHash(md5.hashInt(newEntry.hashCode()), numBuckets));
    }

    /**
     * Return the entries assigned for a node into a partition.
     * The returned entries are represented by a Map<String, List<String>> where:
     *  - key is a tenantId
     *  - value is a List of triggersId assigned to the tenant
     *
     * @param partition the partition used internally
     * @param node the node to filter
     * @return a map representing the entries for given node
     */
    public Map<String, List<String>> getNodePartition(Map<PartitionEntry, Integer> partition, Integer node) {
        Map<String, List<String>> nodePartition = new HashMap<>();
        if (partition != null) {
            for (Entry<PartitionEntry, Integer> entry : partition.entrySet()) {
                if (entry.getValue().equals(node)) {
                    add(nodePartition, entry.getKey());
                }
            }
        }
        return nodePartition;
    }

    /*
        Auxiliary function to transform a PartitionEntry object into a plain map representation.
     */
    private void add(Map<String, List<String>> partition, PartitionEntry entry) {
        String tenantId = entry.getTenantId();
        String triggerId = entry.getTriggerId();
        if (partition.get(tenantId) == null) {
            partition.put(tenantId, new ArrayList<>());
        }
        partition.get(tenantId).add(triggerId);
    }

    /*
        Calculated the added and removed entries for a node given a current and a previous partition maps.
        It return a map with two fixed entries under keys "added" and "removed".
     */
    private Map<String, Map<String, List<String>>> getAddedRemovedPartition(Map<PartitionEntry, Integer> previous,
                                                                            Map<PartitionEntry, Integer> current,
                                                                            Integer node) {
        Map<String, Map<String, List<String>>> output = new HashMap<>();
        output.put("added", new HashMap<>());
        output.put("removed", new HashMap<>());

        List<PartitionEntry> previousNode = new ArrayList();
        for (Entry<PartitionEntry, Integer> entry : previous.entrySet()) {
            if (entry.getValue().equals(node)) {
                previousNode.add(entry.getKey());
            }
        }
        List<PartitionEntry> currentNode = new ArrayList();
        for (Entry<PartitionEntry, Integer> entry : current.entrySet()) {
            if (entry.getValue().equals(node)) {
                currentNode.add(entry.getKey());
            }
        }
        for (PartitionEntry entry : previousNode) {
            if (!currentNode.contains(entry)) {
                add(output.get("removed"), entry);
            }
        }
        for (PartitionEntry entry : currentNode) {
            if (!previousNode.contains(entry)) {
                add(output.get("added"), entry);
            }
        }
        return output;
    }

    /*
        Invoke PartitionTriggerListener with local, added and removed partition
     */
    private void invokePartitionChangeListener() {
        if (triggerListener != null) {
            Map<PartitionEntry, Integer> current = (Map<PartitionEntry, Integer>) partitionCache.get(CURRENT);
            Map<PartitionEntry, Integer> previous = (Map<PartitionEntry, Integer>) partitionCache.get(PREVIOUS);

            Map<String, List<String>> partition = getNodePartition(current, currentNode);
            Map<String, Map<String, List<String>>> addedRemoved =
                    getAddedRemovedPartition(previous, current, currentNode);
            if (log.isDebugEnabled()) {
                log.debug("Invoke a Change Listener");
                log.debug("Partition: " + partition);
                log.debug("Added: " + addedRemoved.get("added"));
                log.debug("Removed: " + addedRemoved.get("removed"));
            }
            triggerListener.onPartitionChange(partition, addedRemoved.get("added"), addedRemoved.get("removed"));
        }
    }

    /**
     * Auxiliary interface to add Infinispan listener to the caches
     */
    @Listener
    public interface IspnListener { }

    /**
     * Auxiliary class to store in the cache an operation for a Trigger.
     * Used internally in the context of the PartitionManager services.
     */
    public static class NotifyTrigger {
        private Integer fromNode;
        private Integer toNode;
        private Operation operation;
        private String tenantId;
        private String triggerId;

        public NotifyTrigger(Integer fromNode, Integer toNode, Operation operation, String tenantId, String triggerId) {
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.operation = operation;
            this.tenantId = tenantId;
            this.triggerId = triggerId;
        }

        public Integer getFromNode() {
            return fromNode;
        }

        public void setFromNode(Integer fromNode) {
            this.fromNode = fromNode;
        }

        public Integer getToNode() {
            return toNode;
        }

        public void setToNode(Integer toNode) {
            this.toNode = toNode;
        }

        public Operation getOperation() {
            return operation;
        }

        public void setOperation(Operation operation) {
            this.operation = operation;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getTriggerId() {
            return triggerId;
        }

        public void setTriggerId(String triggerId) {
            this.triggerId = triggerId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            NotifyTrigger that = (NotifyTrigger) o;

            if (fromNode != null ? !fromNode.equals(that.fromNode) : that.fromNode != null) return false;
            if (operation != that.operation) return false;
            if (tenantId != null ? !tenantId.equals(that.tenantId) : that.tenantId != null) return false;
            return !(triggerId != null ? !triggerId.equals(that.triggerId) : that.triggerId != null);

        }

        @Override
        public int hashCode() {
            int result = fromNode != null ? fromNode.hashCode() : 0;
            result = 31 * result + (operation != null ? operation.hashCode() : 0);
            result = 31 * result + (tenantId != null ? tenantId.hashCode() : 0);
            result = 31 * result + (triggerId != null ? triggerId.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "NotifyTrigger" + '[' +
                    "fromNode=" + fromNode +
                    ", toNode=" + toNode +
                    ", operation=" + operation +
                    ", tenantId='" + tenantId + '\'' +
                    ", triggerId='" + triggerId + '\'' +
                    ']';
        }
    }

    /**
     * Auxiliary class to store in the cache an operation for a Data/Event
     * Used internally in the context of the PartitionManager services.
     */
    public static class NotifyData {
        private Integer fromNode;
        private Data data;
        private Event event;

        public NotifyData(Integer fromNode, Data data) {
            this.fromNode = fromNode;
            this.data = data;
            this.event = null;
        }

        public NotifyData(Integer fromNode, Event event) {
            this.fromNode = fromNode;
            this.event = event;
            this.data = null;
        }

        public Integer getFromNode() {
            return fromNode;
        }

        public void setFromNode(Integer fromNode) {
            this.fromNode = fromNode;
        }

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        public Event getEvent() {
            return event;
        }

        public void setEvent(Event event) {
            this.event = event;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            NotifyData that = (NotifyData) o;

            if (fromNode != null ? !fromNode.equals(that.fromNode) : that.fromNode != null) return false;
            if (data != null ? !data.equals(that.data) : that.data != null) return false;
            return !(event != null ? !event.equals(that.event) : that.event != null);

        }

        @Override
        public int hashCode() {
            int result = fromNode != null ? fromNode.hashCode() : 0;
            result = 31 * result + (data != null ? data.hashCode() : 0);
            result = 31 * result + (event != null ? event.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "NotifyData" + '[' +
                    "fromNode=" + fromNode +
                    ", data=" + data +
                    ", event=" + event +
                    ']';
        }
    }

    /**
     * Auxiliary class to store in the cache a Trigger entry.
     * Used internally in the context of the PartitionManager services.
     */
    public static class PartitionEntry {
        private String tenantId;
        private String triggerId;

        public PartitionEntry(String tenantId, String triggerId) {
            this.tenantId = tenantId;
            this.triggerId = triggerId;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getTriggerId() {
            return triggerId;
        }

        public void setTriggerId(String triggerId) {
            this.triggerId = triggerId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PartitionEntry that = (PartitionEntry) o;

            if (tenantId != null ? !tenantId.equals(that.tenantId) : that.tenantId != null) return false;
            return !(triggerId != null ? !triggerId.equals(that.triggerId) : that.triggerId != null);

        }

        @Override
        public int hashCode() {
            int result = tenantId != null ? tenantId.hashCode() : 0;
            result = 31 * result + (triggerId != null ? triggerId.hashCode() : 0);
            return result;
        }
    }
}
