package dk.ku.di.dms.vms.coordinator.server.coordinator.options;

import dk.ku.di.dms.vms.modb.common.memory.MemoryUtils;

/**
 * Rules that govern how a coordinator must behave.
 * Values initialized with default parameters.
 */
public class CoordinatorOptions {

    private boolean networkEnabled = true;

    // a slack must be considered due to network overhead
    // e.g., by the time the timeout is reached, the time
    // taken to build the payload + sending the request over
    // the network may force the followers to initiate an
    // election. the slack is a conservative way to avoid
    // this from occurring, initiating a heartbeat sending
    // before the timeout is reached
    private int heartbeatSlack = 1000;

    // the batch window. a minute by default
    private long batchWindow = 60000;

    // timeout to keep track when to send heartbeats to followers
    private long heartbeatTimeout = 20000;

    // when to get new transaction input events from http handler
    private long readTransactionInputTimeout = 10000;

    // thread pool to execute tasks, e.g., batch replication to replicas
    private int taskThreadPoolSize = 2;

    private int networkBufferSize = MemoryUtils.DEFAULT_PAGE_SIZE;

    private int networkSendTimeout = 1000;

    /**
     * thread pool for handling network events.
     * default is number of cores divided by 2
     */
    private int groupThreadPoolSize = Runtime.getRuntime().availableProcessors() / 2;

    // defines how the batch metadata is replicated across servers
    private BatchReplicationStrategy batchReplicationStrategy = BatchReplicationStrategy.NONE;

    public boolean isNetworkEnabled() {
        return this.networkEnabled;
    }

    public void withNetworkDisabled() {
        this.networkEnabled = false;
    }

    public int getGroupThreadPoolSize() {
        return this.groupThreadPoolSize;
    }

    public CoordinatorOptions withGroupThreadPoolSize(int groupThreadPoolSize) {
        this.groupThreadPoolSize = groupThreadPoolSize;
        return this;
    }

    public CoordinatorOptions withNetworkBufferSize(int networkBufferSize) {
        this.networkBufferSize = networkBufferSize;
        return this;
    }

    public int getNetworkBufferSize(){
        return this.networkBufferSize;
    }

    public CoordinatorOptions withNetworkSendTimeout(int networkSendTimeout) {
        this.networkSendTimeout = networkSendTimeout;
        return this;
    }

    public int getNetworkSendTimeout(){
        return this.networkSendTimeout;
    }

    public BatchReplicationStrategy getBatchReplicationStrategy() {
        return this.batchReplicationStrategy;
    }

    public CoordinatorOptions withBatchReplicationStrategy(BatchReplicationStrategy replicationStrategy){
        this.batchReplicationStrategy = replicationStrategy;
        return this;
    }

    public CoordinatorOptions withTaskThreadPoolSize(int scheduledTasksThreadPoolSize){
        this.taskThreadPoolSize = scheduledTasksThreadPoolSize;
        return this;
    }

    public int getTaskThreadPoolSize() {
        return this.taskThreadPoolSize;
    }

    public long getReadTransactionInputTimeout() {
        return this.readTransactionInputTimeout;
    }

    public CoordinatorOptions withReadTransactionInputTimeout(long readTransactionInputTimeout) {
        this.readTransactionInputTimeout = readTransactionInputTimeout;
        return this;
    }

    // default, no waiting for previous batch. many reasons, network congestion, node crashes, etc
    private BatchEmissionPolicy batchEmissionPolicy = BatchEmissionPolicy.BLOCKING;

    public CoordinatorOptions withBatchEmissionPolicy(BatchEmissionPolicy batchEmissionPolicy){
        this.batchEmissionPolicy = batchEmissionPolicy;
        return this;
    }

    public BatchEmissionPolicy getBatchEmissionPolicy() {
        return batchEmissionPolicy;
    }

    public int getHeartbeatSlack() {
        return heartbeatSlack;
    }

    public CoordinatorOptions withHeartbeatSlack(int heartbeatSlack) {
        this.heartbeatSlack = heartbeatSlack;
        return this;
    }

    public long getBatchWindow() {
        return this.batchWindow;
    }

    public CoordinatorOptions withBatchWindow(long batchWindow) {
        this.batchWindow = batchWindow;
        return this;
    }

    public long getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public CoordinatorOptions withHeartbeatTimeout(long heartbeatTimeout) {
        this.heartbeatTimeout = heartbeatTimeout;
        return this;
    }
}
