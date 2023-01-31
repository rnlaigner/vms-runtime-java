package dk.ku.di.dms.vms.sdk.embed.handler;

import dk.ku.di.dms.vms.modb.common.memory.MemoryManager;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.BatchCommitAck;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.BatchCommitInfo;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.BatchCommitCommand;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.BatchComplete;
import dk.ku.di.dms.vms.modb.common.schema.network.control.ConsumerSet;
import dk.ku.di.dms.vms.modb.common.schema.network.control.Presentation;
import dk.ku.di.dms.vms.modb.common.schema.network.meta.ConsumerVms;
import dk.ku.di.dms.vms.modb.common.schema.network.meta.ServerIdentifier;
import dk.ku.di.dms.vms.modb.common.schema.network.meta.VmsIdentifier;
import dk.ku.di.dms.vms.modb.common.schema.network.transaction.TransactionAbort;
import dk.ku.di.dms.vms.modb.common.schema.network.transaction.TransactionEvent;
import dk.ku.di.dms.vms.modb.common.serdes.IVmsSerdesProxy;
import dk.ku.di.dms.vms.modb.transaction.CheckpointingAPI;
import dk.ku.di.dms.vms.sdk.core.metadata.VmsRuntimeMetadata;
import dk.ku.di.dms.vms.sdk.core.operational.InboundEvent;
import dk.ku.di.dms.vms.sdk.core.operational.OutboundEventResult;
import dk.ku.di.dms.vms.sdk.core.scheduler.ISchedulerHandler;
import dk.ku.di.dms.vms.sdk.core.scheduler.VmsTransactionResult;
import dk.ku.di.dms.vms.sdk.embed.channel.VmsEmbedInternalChannels;
import dk.ku.di.dms.vms.sdk.embed.ingest.BulkDataLoader;
import dk.ku.di.dms.vms.web_common.meta.Issue;
import dk.ku.di.dms.vms.web_common.meta.LockConnectionMetadata;
import dk.ku.di.dms.vms.web_common.runnable.SignalingStoppableRunnable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static dk.ku.di.dms.vms.modb.common.schema.network.Constants.*;
import static dk.ku.di.dms.vms.modb.common.schema.network.control.Presentation.*;
import static dk.ku.di.dms.vms.web_common.meta.Issue.Category.CANNOT_CONNECT_TO_NODE;
import static java.net.StandardSocketOptions.SO_KEEPALIVE;
import static java.net.StandardSocketOptions.TCP_NODELAY;

/**
 * This default event handler connects direct to the coordinator
 * So in this approach it bypasses the sidecar. In this way,
 * the DBMS must also be run within this code.
 * -
 * The virtual microservice don't know who is the coordinator. It should be passive.
 * The leader and followers must share a list of VMSs.
 * Could also try to adapt to JNI:
 * <a href="https://nachtimwald.com/2017/06/17/calling-java-from-c/">...</a>
 */
public final class EmbeddedVmsEventHandler extends SignalingStoppableRunnable {

    static final int DEFAULT_DELAY_FOR_BATCH_SEND = 5000;

    private final ExecutorService executorService;

    /** SERVER SOCKET **/
    // other VMSs may want to connect in order to send events
    private final AsynchronousServerSocketChannel serverSocket;

    private final AsynchronousChannelGroup group;

    /** INTERNAL CHANNELS **/
    private final VmsEmbedInternalChannels vmsInternalChannels;

    /** VMS METADATA **/
    private final VmsIdentifier me; // this merges network and semantic data about the vms
    private final VmsRuntimeMetadata vmsMetadata;

    /** EXTERNAL VMSs **/

    private final List<ConsumerVms> consumerVMSs;
    private final Map<String, List<ConsumerVms>> eventToConsumersMap; // sent by coordinator

    // built while connecting to the consumers
    private final Map<Integer, LockConnectionMetadata> consumerConnectionMetadataMap;

    // built dynamically as new producers request connection
    private final Map<Integer, LockConnectionMetadata> producerConnectionMetadataMap;

    /** For checkpointing the state */
    private final CheckpointingAPI checkpointingAPI;

    /** SERIALIZATION & DESERIALIZATION **/
    private final IVmsSerdesProxy serdesProxy;

    /** COORDINATOR **/
    private ServerIdentifier leader;
    private LockConnectionMetadata leaderConnectionMetadata;

    // the thread responsible to send data to the leader
    private LeaderWorker leaderWorker;

    // refer to what operation must be performed
    private final BlockingQueue<LeaderWorker.Message> leaderWorkerQueue;

    // cannot be final, may differ across time and new leaders
    private Set<String> queuesLeaderSubscribesTo;

    // set of events to send to leader
    public final BlockingDeque<TransactionEvent.Payload> eventsToSendToLeader;

    /** INTERNAL STATE **/

    /*
     * When is the current batch updated to the next?
     * - When the last tid of this batch (for this VMS) finishes execution,
     *   if this VMS is a terminal in this batch, send the batch complete event to leader
     *   if this vms is not a terminal, must wait for a batch commit request from leader
     *   -- but this wait can entail low throughput (rethink that later)
     */
    private BatchContext currentBatch;

    /**
     * It just marks the last tid that the scheduler has executed.
     * The scheduler is batch-agnostic. That means in order
     * to progress with the batch here, we need to check if the
     * batch has completed using the last tid executed.
     */
    private long lastTidFinished;

    // metadata about all non-committed batches. when a batch commit finishes, it is removed from this map
    private final Map<Long, BatchContext> batchContextMap;

    private final Map<Long, Long> batchToNextBatchMap;

    public final ISchedulerHandler schedulerHandler;

    /*
     * It is necessary a way to store the tid received to a
     * corresponding dependence map.
     */
    private final Map<Long, Map<String, Long>> tidToPrecedenceMap;

    public static EmbeddedVmsEventHandler build(// to identify which vms this is
                                                VmsIdentifier me,
                                                // the VMSs to send data to
                                                List<ConsumerVms> consumerVMSs,
                                                // map event to VMSs
                                                Map<String, List<ConsumerVms>> eventToConsumersMap,
                                                // to checkpoint private state
                                                CheckpointingAPI checkpointingAPI,
                                                // for communicating with other components
                                                VmsEmbedInternalChannels vmsInternalChannels,
                                                // metadata about this vms
                                                VmsRuntimeMetadata vmsMetadata,
                                                // serialization/deserialization of objects
                                                IVmsSerdesProxy serdesProxy,
                                                // for recurrent and continuous tasks
                                                ExecutorService executorService) throws Exception {
        try {
            return new EmbeddedVmsEventHandler(me, vmsMetadata, consumerVMSs, eventToConsumersMap, checkpointingAPI, vmsInternalChannels, serdesProxy, executorService);
        } catch (IOException e){
            throw new Exception("Error on setting up event handler: "+e.getCause()+ " "+ e.getMessage());
        }
    }

    private EmbeddedVmsEventHandler(VmsIdentifier me,
                                    VmsRuntimeMetadata vmsMetadata,
                                    List<ConsumerVms> consumerVMSs,
                                    Map<String, List<ConsumerVms>> eventToConsumersMap,
                                    CheckpointingAPI checkpointingAPI,
                                    VmsEmbedInternalChannels vmsInternalChannels,
                                    IVmsSerdesProxy serdesProxy,
                                    ExecutorService executorService) throws IOException {
        super();

        // network and executor
        this.group = AsynchronousChannelGroup.withThreadPool(executorService);
        this.serverSocket = AsynchronousServerSocketChannel.open(this.group);
        this.serverSocket.bind(me.asInetSocketAddress());

        this.executorService = executorService;

        this.vmsInternalChannels = vmsInternalChannels;
        this.me = me;

        this.vmsMetadata = vmsMetadata;
        this.consumerVMSs = consumerVMSs == null ? new ArrayList<>() : consumerVMSs;
        this.eventToConsumersMap = eventToConsumersMap == null ? new ConcurrentHashMap<>() : eventToConsumersMap;
        this.consumerConnectionMetadataMap = new ConcurrentHashMap<>(10);
        this.producerConnectionMetadataMap = new ConcurrentHashMap<>(10);

        this.checkpointingAPI = checkpointingAPI;

        this.serdesProxy = serdesProxy;

        this.currentBatch = BatchContext.build(me.batch, me.previousBatch, me.lastTidOfBatch);
        this.lastTidFinished = me.lastTidOfBatch;
        this.currentBatch.setStatus(BatchContext.Status.BATCH_COMMITTED);
        this.batchContextMap = new ConcurrentHashMap<>(3);
        this.batchToNextBatchMap = new ConcurrentHashMap<>();
        this.tidToPrecedenceMap = new ConcurrentHashMap<>();

        this.schedulerHandler = new EmbeddedSchedulerHandler();

        // set leader off
        this.leader = new ServerIdentifier("localhost",0);
        this.leader.off();

        this.leaderWorkerQueue = new LinkedBlockingDeque<>();
        this.queuesLeaderSubscribesTo = Set.of();
        this.eventsToSendToLeader = new LinkedBlockingDeque<>();
    }

    /**
     * A thread that basically writes events to other VMSs and the Leader
     * Retrieves data from all output queues
     * -
     * All output queues must be read in order to send their data
     * -
     * A batch strategy for sending would involve sleeping until the next timeout for batch,
     * send and set up the next. Do that iteratively
     */

    private void eventLoop(){

        logger.info("Event handler has started running.");

        if(!this.consumerVMSs.isEmpty()){
            // then it is received from constructor, and we must initially contact them
            this.connectToConsumerVMSs(this.consumerVMSs);
        }

        // setup accept since we need to accept connections from the coordinator and other VMSs
        this.serverSocket.accept( null, new AcceptCompletionHandler());
        this.logger.info("Accept handler has been setup.");

        while(this.isRunning()){

            try {

//                if(!vmsInternalChannels.transactionAbortOutputQueue().isEmpty()){
//                    // TODO handle. if this can be handled by leader worker
                      //  this thread can wait on the transactionOutputQueue
//                }

                // it is better to get all the results of a given transaction instead of one by one. it must be atomic anyway

                // TODO poll for some time

                if(!this.vmsInternalChannels.transactionOutputQueue().isEmpty()){

                    VmsTransactionResult txResult = this.vmsInternalChannels.transactionOutputQueue().take();

                    this.logger.info("New transaction result in event handler. TID = "+txResult.tid);

                    this.lastTidFinished = txResult.tid;

                    Map<String, Long> precedenceMap = this.tidToPrecedenceMap.get(this.lastTidFinished);
                    // remove ourselves
                    precedenceMap.remove(this.me.getIdentifier());

                    String precedenceMapUpdated = this.serdesProxy.serializeMap(precedenceMap);

                    // just send events to appropriate targets
                    for(OutboundEventResult outputEvent : txResult.resultTasks){
                        this.processOutputEvent(outputEvent, precedenceMapUpdated);
                    }

                }

                moveBatchIfNecessary();

            } catch (Exception e) {
                this.logger.log(Level.SEVERE, "Problem on handling event on event handler:"+e.getMessage());
            }

        }

        failSafeClose();
        this.logger.info("Event handler has finished execution.");

    }

    private static final Object DUMB_OBJECT = new Object();

    /**
     * it may be the case that, due to an abort of the last tid, the last tid changes
     * the current code is not incorporating that
     */
    private void moveBatchIfNecessary(){

        // update if current batch is done AND the next batch has already arrived
        if(this.currentBatch.isCommitted() && this.batchToNextBatchMap.get( this.currentBatch.batch ) != null){
            long nextBatchOffset = this.batchToNextBatchMap.get( this.currentBatch.batch );
            this.currentBatch = batchContextMap.get(nextBatchOffset);
        }

        // have we processed all the TIDs of this batch?
        if(this.currentBatch.isOpen() && this.currentBatch.lastTid <= this.lastTidFinished){
            // we need to alert the scheduler...
            this.logger.info("The last TID for the current batch has arrived. Time to inform the coordinator about the completion if I am a terminal node.");

            // many outputs from the same transaction may arrive here, but can only send the batch commit once
            this.currentBatch.setStatus(BatchContext.Status.BATCH_COMPLETED);

            // if terminal, must send batch complete
            if(this.currentBatch.terminal) {
                // must be queued in case leader is off and comes back online
                this.leaderWorkerQueue.add(new LeaderWorker.Message(LeaderWorker.Command.SEND_BATCH_COMPLETE,
                        BatchComplete.of(this.currentBatch.batch, this.me.getIdentifier())));
            }
            this.vmsInternalChannels.batchCommitCommandQueue().add( DUMB_OBJECT );
        }

    }

    /**
     * From <a href="https://docs.oracle.com/javase/tutorial/networking/sockets/clientServer.html">...</a>
     * "The Java runtime automatically closes the input and output streams, the client socket,
     * and the server socket because they have been created in the try-with-resources statement."
     * Which means that different tests must bind to different addresses
     */
    private void failSafeClose(){
        // safe close
        try { if(this.serverSocket.isOpen()) this.serverSocket.close(); } catch (IOException ignored) {
            logger.warning("Could not close socket");
        }
    }

    @Override
    public void run() {
        this.eventLoop();
    }

    private final class EmbeddedSchedulerHandler implements ISchedulerHandler {
        @Override
        public Future<?> run() {
            vmsInternalChannels.batchCommitCommandQueue().remove();
            return executorService.submit(EmbeddedVmsEventHandler.this::log);
        }
        @Override
        public boolean conditionHolds() {
            return !vmsInternalChannels.batchCommitCommandQueue().isEmpty();
        }
    }

    private void log() {
        this.currentBatch.setStatus(BatchContext.Status.LOGGING);
        // of course, I do not need to stop the scheduler on commit
        // I need to make access to the data versions data race free
        // so new transactions get data versions from the version map or the store
        this.checkpointingAPI.checkpoint();

        this.currentBatch.setStatus(BatchContext.Status.BATCH_COMMITTED);
        this.leaderWorkerQueue.add( new LeaderWorker.Message( LeaderWorker.Command.SEND_BATCH_COMMIT_ACK,
                BatchCommitAck.of(this.currentBatch.batch, this.me.getIdentifier()) ));
    }

    /**
     * It creates the payload to be sent downstream
     * @param outputEvent the event to be sent to the respective consumer vms
     */
    private void processOutputEvent(OutboundEventResult outputEvent, String precedenceMap){

        if(outputEvent.outputQueue() == null) return; // it is a void method that executed, nothing to send

        Class<?> clazz = this.vmsMetadata.queueToEventMap().get(outputEvent.outputQueue());
        String objStr = this.serdesProxy.serialize(outputEvent.output(), clazz);

        // right now just including the original precedence map. ideally we must reduce the size by removing this vms
        TransactionEvent.Payload payload = TransactionEvent.of(
                outputEvent.tid(), outputEvent.batch(), outputEvent.outputQueue(), objStr, precedenceMap );

        // does the leader consumes this queue?
        if( this.queuesLeaderSubscribesTo.contains( outputEvent.outputQueue() ) ){
            this.logger.info("An output event (queue: "+outputEvent.outputQueue()+") will be queued to leader");
            this.eventsToSendToLeader.add(payload);
        }

        List<ConsumerVms> consumerVMSs = this.eventToConsumersMap.get(outputEvent.outputQueue());
        if(consumerVMSs == null || consumerVMSs.isEmpty()){
            this.logger.warning(
                    "An output event (queue: "+outputEvent.outputQueue()+") has no target virtual microservices.");
            return;
        }

        for(ConsumerVms consumerVms : consumerVMSs) {
            this.logger.info("An output event (queue: " + outputEvent.outputQueue() + ") will be queued to vms: " + consumerVms);

            // concurrency issue if add to a list
            consumerVms.transactionEventsPerBatch.computeIfAbsent(outputEvent.batch(), (x) -> new LinkedBlockingDeque<>()).add(payload);
        }
    }

    private final class ConnectToExternalVmsProtocol {

        private State state;
        private final AsynchronousSocketChannel channel;
        private final ByteBuffer buffer;
        public final CompletionHandler<Void, ConnectToExternalVmsProtocol> connectCompletionHandler;
        private final ConsumerVms node;

        public ConnectToExternalVmsProtocol(AsynchronousSocketChannel channel, ConsumerVms node) {
            this.state = State.NEW;
            this.channel = channel;
            this.connectCompletionHandler = new ConnectToVmsCompletionHandler();
            this.buffer = MemoryManager.getTemporaryDirectBuffer();
            this.node = node;
        }

        private enum State { NEW, CONNECTED, PRESENTATION_SENT }

        private class ConnectToVmsCompletionHandler implements CompletionHandler<Void, ConnectToExternalVmsProtocol> {

            @Override
            public void completed(Void result, ConnectToExternalVmsProtocol attachment) {

                attachment.state = State.CONNECTED;

                final LockConnectionMetadata connMetadata = new LockConnectionMetadata(
                        node.hashCode(),
                        LockConnectionMetadata.NodeType.VMS,
                        attachment.buffer,
                        MemoryManager.getTemporaryDirectBuffer(),
                        channel,
                        null);

                if(producerConnectionMetadataMap.containsKey(node.hashCode())){
                    logger.warning("The node "+node.host+" "+node.port+" already contains a connection as a producer");
                }

                consumerConnectionMetadataMap.put(node.hashCode(), connMetadata);

                String dataSchema = serdesProxy.serializeDataSchema(me.dataSchema);
                String inputEventSchema = serdesProxy.serializeEventSchema(me.inputEventSchema);
                String outputEventSchema = serdesProxy.serializeEventSchema(me.outputEventSchema);

                attachment.buffer.clear();
                Presentation.writeVms( attachment.buffer, me, me.vmsIdentifier, me.batch, me.lastTidOfBatch, me.previousBatch, dataSchema, inputEventSchema, outputEventSchema );
                attachment.buffer.flip();

                // have to make sure we send the presentation before writing to this VMS, otherwise an exception can occur (two writers)
                attachment.channel.write(attachment.buffer, attachment, new CompletionHandler<>() {
                    @Override
                    public void completed(Integer result, ConnectToExternalVmsProtocol attachment) {
                        attachment.state = State.PRESENTATION_SENT;
                        attachment.buffer.clear();

                        logger.info("Setting up VMS worker ");

                        // set up event sender timer task
                        node.timer = new Timer("vms-sender-timer", true);
                        node.timer.scheduleAtFixedRate(new ConsumerVmsWorker(node, connMetadata), 0, DEFAULT_DELAY_FOR_BATCH_SEND );

                        attachment.channel.read(attachment.buffer, connMetadata, new VmsReadCompletionHandler());
                    }

                    @Override
                    public void failed(Throwable exc, ConnectToExternalVmsProtocol attachment) {
                        // check if connection is still online. if so, try again
                        // otherwise, retry connection in a few minutes
                        issueQueue.add(new Issue(CANNOT_CONNECT_TO_NODE, attachment.node.hashCode()));
                        attachment.buffer.clear();
                    }
                });

            }

            @Override
            public void failed(Throwable exc, ConnectToExternalVmsProtocol attachment) {
                // queue for later attempt
                // perhaps can use scheduled task
                issueQueue.add( new Issue(CANNOT_CONNECT_TO_NODE, attachment.node.hashCode()) );
                // node.off(); no need it is already off
            }
        }

    }

    /**
     * The leader will let each VMS aware of their dependencies,
     * to which VMSs they have to connect to
     */
    private void connectToConsumerVMSs(List<ConsumerVms> consumerSet) {
        for(ConsumerVms vms : consumerSet) {
            // process only the new ones
            LockConnectionMetadata connectionMetadata = this.consumerConnectionMetadataMap.get(vms.hashCode());
            if(connectionMetadata != null && connectionMetadata.channel != null && connectionMetadata.channel.isOpen()){
                    continue; // ignore
            }
            try {
                AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(group);
                channel.setOption(TCP_NODELAY, true);
                channel.setOption(SO_KEEPALIVE, true);
                ConnectToExternalVmsProtocol protocol = new ConnectToExternalVmsProtocol(channel, vms);
                channel.connect(vms.asInetSocketAddress(), protocol, protocol.connectCompletionHandler);
            } catch (IOException ignored) {
                this.issueQueue.add( new Issue(CANNOT_CONNECT_TO_NODE, vms.hashCode()) );
            }
        }
    }

    /**
     * Should we only submit the events from the current batch?
     */
    private final class VmsReadCompletionHandler implements CompletionHandler<Integer, LockConnectionMetadata> {

        @Override
        public void completed(Integer result, LockConnectionMetadata connectionMetadata) {

            byte messageType = connectionMetadata.readBuffer.get(0);

            switch (messageType) {
                case (BATCH_OF_EVENTS) -> {
                    connectionMetadata.readBuffer.position(1);
                    int count = connectionMetadata.readBuffer.getInt();
                    TransactionEvent.Payload payload;
                    for(int i = 0; i < count; i++){
                        payload = TransactionEvent.read(connectionMetadata.readBuffer);
                        if (vmsMetadata.queueToEventMap().get(payload.event()) != null) {
                            vmsInternalChannels.transactionInputQueue().add(buildInboundEvent(payload));
                        }
                    }
                }
                case (EVENT) -> {
                    // can only be event, skip reading the message type
                    connectionMetadata.readBuffer.position(1);
                    // data dependence or input event
                    TransactionEvent.Payload payload = TransactionEvent.read(connectionMetadata.readBuffer);
                    // send to scheduler
                    if (vmsMetadata.queueToEventMap().get(payload.event()) != null) {
                        vmsInternalChannels.transactionInputQueue().add(buildInboundEvent(payload));
                    }
                }
                default ->
                    logger.warning("Unknown message type received from another VMS: "+messageType);
            }
            connectionMetadata.readBuffer.clear();
            connectionMetadata.channel.read(connectionMetadata.readBuffer, connectionMetadata, this);
        }

        @Override
        public void failed(Throwable exc, LockConnectionMetadata connectionMetadata) {
            if (connectionMetadata.channel.isOpen()){
                connectionMetadata.channel.read(connectionMetadata.readBuffer, connectionMetadata, this);
            } // else no nothing. upon a new connection this metadata can be recycled
        }
    }

    /**
     * On a connection attempt, it is unknown what is the type of node
     * attempting the connection. We find out after the first read.
     */
    private final class UnknownNodeReadCompletionHandler implements CompletionHandler<Integer, Void> {

        private final AsynchronousSocketChannel channel;
        private final ByteBuffer buffer;

        public UnknownNodeReadCompletionHandler(AsynchronousSocketChannel channel, ByteBuffer buffer) {
            this.channel = channel;
            this.buffer = buffer;
        }

        @Override
        public void completed(Integer result, Void void_) {

            logger.info("Starting process for processing presentation message");

            // message identifier
            byte messageIdentifier = this.buffer.get(0);
            if(messageIdentifier != PRESENTATION){
                logger.warning("A node is trying to connect without a presentation message");
                this.buffer.clear();
                MemoryManager.releaseTemporaryDirectBuffer(this.buffer);
                try { this.channel.close(); } catch (IOException ignored) {}
                return;
            }

            byte nodeTypeIdentifier = this.buffer.get(1);
            this.buffer.position(2);

            switch (nodeTypeIdentifier) {

                case (SERVER_TYPE) -> {

                    if(!leader.isActive()) {
                        ConnectionFromLeaderProtocol connectionFromLeader = new ConnectionFromLeaderProtocol(this.channel, this.buffer);
                        connectionFromLeader.processLeaderPresentation();
                    } else {
                        logger.warning("Dropping a connection attempt from a node claiming to be leader");
                        try { this.channel.close(); } catch (IOException ignored) {}
                    }
                }
                case (VMS_TYPE) -> {

                    // then it is a vms intending to connect due to a data/event
                    // that should be delivered to this vms
                    VmsIdentifier producerVms = Presentation.readVms(this.buffer, serdesProxy);
                    this.buffer.clear();

                    ByteBuffer writeBuffer = MemoryManager.getTemporaryDirectBuffer();

                    LockConnectionMetadata connMetadata = new LockConnectionMetadata(
                            producerVms.hashCode(),
                            LockConnectionMetadata.NodeType.VMS,
                            this.buffer,
                            writeBuffer,
                            this.channel,
                            new Semaphore(1)
                    );

                    // what if a vms is both producer to and consumer from this vms?
                    if(consumerConnectionMetadataMap.containsKey(producerVms.hashCode())){
                        logger.warning("The node "+producerVms.host+" "+producerVms.port+" already contains a connection as a consumer");
                    }

                    producerConnectionMetadataMap.put(producerVms.hashCode(), connMetadata);

                    // setup event receiving for this vms
                    this.channel.read(this.buffer, connMetadata, new VmsReadCompletionHandler());

                }
                case CLIENT -> {
                    // used for bulk data loading for now (maybe used for tests later)

                    String tableName = Presentation.readClient(this.buffer);
                    this.buffer.clear();

                    LockConnectionMetadata connMetadata = new LockConnectionMetadata(
                            tableName.hashCode(),
                            LockConnectionMetadata.NodeType.CLIENT,
                            this.buffer,
                            null,
                            this.channel,
                            null
                    );

                    BulkDataLoader bulkDataLoader = (BulkDataLoader) vmsMetadata.loadedVmsInstances().get("data_loader");
                    if(bulkDataLoader != null) {
                        bulkDataLoader.init(tableName, connMetadata);
                    } else {
                        logger.warning("Data loader is not loaded in the runtime.");
                    }
                }
                default -> {
                    logger.warning("Presentation message from unknown source:" + nodeTypeIdentifier);
                    this.buffer.clear();
                    MemoryManager.releaseTemporaryDirectBuffer(this.buffer);
                    try {
                        this.channel.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        @Override
        public void failed(Throwable exc, Void void_) {
            logger.warning("Error on processing presentation message!");
        }

    }

    /**
     * Class is iteratively called by the socket pool threads.
     */
    private final class AcceptCompletionHandler implements CompletionHandler<AsynchronousSocketChannel, Void> {

        @Override
        public void completed(AsynchronousSocketChannel channel, Void void_) {
            logger.info("An unknown host has started a connection attempt.");
            final ByteBuffer buffer = MemoryManager.getTemporaryDirectBuffer(1024);
            try {
                logger.info("Remote address: "+channel.getRemoteAddress().toString());
                channel.setOption(TCP_NODELAY, true);
                channel.setOption(SO_KEEPALIVE, true);
                // read presentation message. if vms, receive metadata, if follower, nothing necessary
                channel.read( buffer, null, new UnknownNodeReadCompletionHandler(channel, buffer) );
                logger.info("Read handler for unknown node has been setup: "+channel.getRemoteAddress());
            } catch(Exception e){
                logger.info("Accept handler for unknown node caught exception: "+e.getMessage());
                MemoryManager.releaseTemporaryDirectBuffer(buffer);
            } finally {
                logger.info("Accept handler set up again for listening.");
                // continue listening
                serverSocket.accept(null, this);
            }
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            String message = exc.getMessage();
            if(message == null){
                if (exc.getCause() instanceof ClosedChannelException){
                    message = "Connection is closed";
                } else if ( exc instanceof AsynchronousCloseException || exc.getCause() instanceof AsynchronousCloseException) {
                    message = "Event handler has been stopped?";
                } else {
                    message = "No cause identified";
                }
            }

            logger.warning("Error on accepting connection: "+ message);
            if (serverSocket.isOpen()){
                serverSocket.accept(null, this);
            } else {
                logger.warning("Socket is not open anymore. Cannot set up accept again");
            }
        }

    }

    private final class ConnectionFromLeaderProtocol {
        private State state;
        private final AsynchronousSocketChannel channel;
        private final ByteBuffer buffer;
        public final CompletionHandler<Integer, Void> writeCompletionHandler;

        public ConnectionFromLeaderProtocol(AsynchronousSocketChannel channel, ByteBuffer buffer) {
            this.state = State.PRESENTATION_RECEIVED;
            this.channel = channel;
            this.writeCompletionHandler = new WriteCompletionHandler();
            this.buffer = buffer;
        }

        private enum State {
            PRESENTATION_RECEIVED,
            PRESENTATION_PROCESSED,
            PRESENTATION_SENT
        }

        /**
         * Should be void or ConnectionFromLeaderProtocol??? only testing to know...
         */
        private class WriteCompletionHandler implements CompletionHandler<Integer,Void> {

            @Override
            public void completed(Integer result, Void attachment) {
                state = State.PRESENTATION_SENT;
                // set up leader worker
                leaderWorker = new LeaderWorker(leader,leaderConnectionMetadata,
                        eventsToSendToLeader, leaderWorkerQueue);
                new Thread(leaderWorker).start();
                logger.info("Leader worker set up");
                buffer.clear();
                channel.read(buffer, leaderConnectionMetadata, new LeaderReadCompletionHandler() );
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                buffer.clear();
                if(!channel.isOpen()) {
                    leader.off();
                }
                // else what to do try again? no, let the new leader connect
            }
        }

        public void processLeaderPresentation() {

            boolean includeMetadata = this.buffer.get() == YES;

            // leader has disconnected, or new leader
            leader = Presentation.readServer(this.buffer);

            // read queues leader is interested
            boolean hasQueuesToSubscribe = this.buffer.get() == YES;
            if(hasQueuesToSubscribe){
                queuesLeaderSubscribesTo = Presentation.readQueuesToSubscribeTo(this.buffer, serdesProxy);
            }

            // only connects to all VMSs on first leader connection

            if(leaderConnectionMetadata == null) {
                // then setup connection metadata and read completion handler
                leaderConnectionMetadata = new LockConnectionMetadata(
                        leader.hashCode(),
                        LockConnectionMetadata.NodeType.SERVER,
                        buffer,
                        MemoryManager.getTemporaryDirectBuffer(),
                        channel,
                        null
                );
            } else {
                // considering the leader has replicated the metadata before failing
                // so no need to send metadata again. but it may be necessary...
                // what if the tid and batch id is necessary. the replica may not be
                // sync with last leader...
                leaderConnectionMetadata.channel = channel; // update channel
            }
            leader.on();
            this.buffer.clear();

            if(includeMetadata) {
                String vmsDataSchemaStr = serdesProxy.serializeDataSchema(me.dataSchema);
                String vmsInputEventSchemaStr = serdesProxy.serializeEventSchema(me.inputEventSchema);
                String vmsOutputEventSchemaStr = serdesProxy.serializeEventSchema(me.outputEventSchema);

                Presentation.writeVms(this.buffer, me, me.vmsIdentifier, me.batch, me.lastTidOfBatch, me.previousBatch, vmsDataSchemaStr, vmsInputEventSchemaStr, vmsOutputEventSchemaStr);
                // the protocol requires the leader to wait for the metadata in order to start sending messages
            } else {
                Presentation.writeVms(this.buffer, me, me.vmsIdentifier, me.batch, me.lastTidOfBatch, me.previousBatch);
            }

            this.buffer.flip();
            this.state = State.PRESENTATION_PROCESSED;
            logger.info("Leader presentation processed");
            this.channel.write( this.buffer, null, this.writeCompletionHandler );

        }

    }

    private InboundEvent buildInboundEvent(TransactionEvent.Payload payload){
        Class<?> clazz = this.vmsMetadata.queueToEventMap().get(payload.event());
        Object input = this.serdesProxy.deserialize(payload.payload(), clazz);
        Map<String, Long> precedenceMap = this.serdesProxy.deserializeDependenceMap(payload.precedenceMap());
        if(precedenceMap == null){
            throw new IllegalStateException("Precedence map is null.");
        }
        if(!precedenceMap.containsKey(this.me.getIdentifier())){
            throw new IllegalStateException("Precedent tid of "+payload.tid()+" is unknown.");
        }
        this.tidToPrecedenceMap.put(payload.tid(), precedenceMap);
        return new InboundEvent( payload.tid(), precedenceMap.get(this.me.getIdentifier()),
                payload.batch(), payload.event(), clazz, input );
    }

    private final class LeaderReadCompletionHandler implements CompletionHandler<Integer, LockConnectionMetadata> {

        @Override
        public void completed(Integer result, LockConnectionMetadata connectionMetadata) {

            connectionMetadata.readBuffer.position(0);
            byte messageType = connectionMetadata.readBuffer.get();

            logger.info("Leader has sent a message type: "+messageType);

            // receive input events
            switch (messageType) {
                /*
                 * Given a new batch of events sent by the leader, the last message is the batch info
                 */
                case (BATCH_OF_EVENTS) -> {
                    // to increase performance, one would buffer this buffer for processing and then read from another buffer
                    int count = connectionMetadata.readBuffer.getInt();
                    List<InboundEvent> payloads = new ArrayList<>(count);
                    TransactionEvent.Payload payload;
                    // extract events batched
                    for (int i = 0; i < count - 1; i++) {
                        // move offset to discard message type
                        connectionMetadata.readBuffer.get();
                        payload = TransactionEvent.read(connectionMetadata.readBuffer);
                        if (vmsMetadata.queueToEventMap().get(payload.event()) != null) {
                            payloads.add(buildInboundEvent(payload));
                        }
                    }

                    // batch commit info always come last
                    byte eventType = connectionMetadata.readBuffer.get();
                    if (eventType == BATCH_COMMIT_INFO) {
                        // it means this VMS is a terminal node in sent batch
                        BatchCommitInfo.Payload bPayload = BatchCommitInfo.read(connectionMetadata.readBuffer);
                        processNewBatchInfo(bPayload);
                    } else { // then it is still event
                        payload = TransactionEvent.read(connectionMetadata.readBuffer);
                        if (vmsMetadata.queueToEventMap().get(payload.event()) != null)
                            payloads.add(buildInboundEvent(payload));
                    }
                    // add after to make sure the batch context map is filled by the time the output event is generated
                    vmsInternalChannels.transactionInputQueue().addAll(payloads);
                }
                case (BATCH_COMMIT_INFO) -> {
                    // events of this batch from VMSs may arrive before the batch commit info
                    // it means this VMS is a terminal node for the batch
                    logger.info("Batch commit info received from the leader");
                    BatchCommitInfo.Payload bPayload = BatchCommitInfo.read(connectionMetadata.readBuffer);
                    processNewBatchInfo(bPayload);
                }
                case (BATCH_COMMIT_COMMAND) -> {
                    logger.info("Batch commit command received from the leader");
                    // a batch commit queue from next batch can arrive before this vms moves next? yes
                    BatchCommitCommand.Payload payload = BatchCommitCommand.read(connectionMetadata.readBuffer);
                    processNewBatchInfo(payload);
                }
                case (EVENT) -> {
                    TransactionEvent.Payload payload = TransactionEvent.read(connectionMetadata.readBuffer);
                    // send to scheduler.... drop if the event cannot be processed (not an input event in this vms)
                    if (vmsMetadata.queueToEventMap().get(payload.event()) != null) {
                        vmsInternalChannels.transactionInputQueue().add(buildInboundEvent(payload));
                    }
                }
                case (TX_ABORT) -> {
                    TransactionAbort.Payload transactionAbortReq = TransactionAbort.read(connectionMetadata.readBuffer);
                    vmsInternalChannels.transactionAbortInputQueue().add(transactionAbortReq);
                }
//            else if (messageType == BATCH_ABORT_REQUEST){
//                // some new leader request to roll back to last batch commit
//                BatchAbortRequest.Payload batchAbortReq = BatchAbortRequest.read( connectionMetadata.readBuffer );
//                vmsInternalChannels.batchAbortQueue().add(batchAbortReq);
//            }
                case (CONSUMER_SET) -> {

                    // the
                    Map<String, List<ConsumerVms>> receivedConsumerVms = ConsumerSet.read(connectionMetadata.readBuffer, serdesProxy);

                    if (receivedConsumerVms != null) {
                        eventToConsumersMap.putAll(receivedConsumerVms);
                        connectToConsumerVMSs( eventToConsumersMap.values()
                                .stream()
                                .flatMap(Collection::stream)
                                .collect(Collectors.toList()));
                    }

                }
                case (PRESENTATION) -> logger.warning("Presentation being sent again by the producer!?");
                default -> logger.warning("Message type sent by leader cannot be identified: "+messageType);
            }

            connectionMetadata.readBuffer.clear();
            connectionMetadata.channel.read(connectionMetadata.readBuffer, connectionMetadata, this);
        }

        @Override
        public void failed(Throwable exc, LockConnectionMetadata connectionMetadata) {
            if (connectionMetadata.channel.isOpen()){
                connectionMetadata.channel.read(connectionMetadata.readBuffer, connectionMetadata, this);
            } else {
                leader.off();
                leaderWorker.stop();
            }
        }

    }

    private void processNewBatchInfo(BatchCommitInfo.Payload batchCommitInfo){
        BatchContext batchContext = BatchContext.build(batchCommitInfo);
        this.batchContextMap.put(batchCommitInfo.batch(), batchContext);
        this.batchToNextBatchMap.put( batchCommitInfo.previousBatch(), batchCommitInfo.batch() );
    }

    /**
     * Context of execution of this method:
     * This is not a terminal node in this batch, which means
     * it does not know anything about the batch commit command just received.
     * If the previous batch is completed and this received batch is the next,
     * we just let the main loop update it
     */
    private void processNewBatchInfo(BatchCommitCommand.Payload batchCommitCommand){
        BatchContext batchContext = BatchContext.build(batchCommitCommand);
        this.batchContextMap.put(batchCommitCommand.batch(), batchContext);
        this.batchToNextBatchMap.put( batchCommitCommand.previousBatch(), batchCommitCommand.batch() );
    }

}