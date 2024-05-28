package dk.ku.di.dms.vms.sdk.embed.handler;

import dk.ku.di.dms.vms.modb.common.memory.MemoryManager;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.*;
import dk.ku.di.dms.vms.modb.common.schema.network.control.ConsumerSet;
import dk.ku.di.dms.vms.modb.common.schema.network.control.Presentation;
import dk.ku.di.dms.vms.modb.common.schema.network.node.IdentifiableNode;
import dk.ku.di.dms.vms.modb.common.schema.network.node.ServerNode;
import dk.ku.di.dms.vms.modb.common.schema.network.node.VmsNode;
import dk.ku.di.dms.vms.modb.common.schema.network.transaction.TransactionAbort;
import dk.ku.di.dms.vms.modb.common.schema.network.transaction.TransactionEvent;
import dk.ku.di.dms.vms.modb.common.serdes.IVmsSerdesProxy;
import dk.ku.di.dms.vms.modb.common.transaction.ITransactionalHandler;
import dk.ku.di.dms.vms.sdk.core.metadata.VmsRuntimeMetadata;
import dk.ku.di.dms.vms.sdk.core.operational.InboundEvent;
import dk.ku.di.dms.vms.sdk.core.operational.OutboundEventResult;
import dk.ku.di.dms.vms.sdk.core.scheduler.IVmsTransactionResult;
import dk.ku.di.dms.vms.sdk.core.scheduler.handlers.ICheckpointEventHandler;
import dk.ku.di.dms.vms.sdk.embed.channel.VmsEmbeddedInternalChannels;
import dk.ku.di.dms.vms.web_common.NetworkUtils;
import dk.ku.di.dms.vms.web_common.meta.ConnectionMetadata;
import dk.ku.di.dms.vms.web_common.runnable.StoppableRunnable;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

import static dk.ku.di.dms.vms.modb.common.schema.network.Constants.*;
import static dk.ku.di.dms.vms.modb.common.schema.network.control.Presentation.*;
import static java.lang.System.Logger.Level.*;

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
public final class VmsEventHandler extends StoppableRunnable {

    private static final System.Logger LOGGER = System.getLogger(VmsEventHandler.class.getName());
    
    /** SERVER SOCKET **/
    // other VMSs may want to connect in order to send events
    private final AsynchronousServerSocketChannel serverSocket;

    private final AsynchronousChannelGroup group;

    /** INTERNAL CHANNELS **/
    private final VmsEmbeddedInternalChannels vmsInternalChannels;

    /** VMS METADATA **/
    private final VmsNode me; // this merges network and semantic data about the vms
    private final VmsRuntimeMetadata vmsMetadata;

    /** EXTERNAL VMSs **/
    private final Map<String, Deque<ConsumerVms>> eventToConsumersMap;

    // built while connecting to the consumers
    private final Map<Integer, ConnectionMetadata> consumerConnectionMetadataMap;

    // built dynamically as new producers request connection
    private final Map<Integer, ConnectionMetadata> producerConnectionMetadataMap;

    /** For checkpointing the state */
    private final ITransactionalHandler transactionalHandler;

    /** SERIALIZATION & DESERIALIZATION **/
    private final IVmsSerdesProxy serdesProxy;

    private final int networkBufferSize;

    private final int osBufferSize;

    private final int networkSendTimeout;

    /** COORDINATOR **/
    private ServerNode leader;

    private ConnectionMetadata leaderConnectionMetadata;

    // the thread responsible to send data to the leader
    private LeaderWorker leaderWorker;

    // refer to what operation must be performed
    // private final BlockingQueue<Object> leaderWorkerQueue;

    // cannot be final, may differ across time and new leaders
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private final Set<String> queuesLeaderSubscribesTo;

    /** INTERNAL STATE **/

    /*
     * When is the current batch updated to the next?
     * - When the last tid of this batch (for this VMS) finishes execution,
     *   if this VMS is a terminal in this batch, send the batch complete event to leader
     *   if this vms is not a terminal, must wait for a batch commit request from leader
     *   -- but this wait can entail low throughput (rethink that later)
     */
    private BatchContext currentBatch;

    // metadata about all non-committed batches. when a batch commit finishes, it is removed from this map
    private final Map<Long, BatchContext> batchContextMap;

    /**
     * It marks how many TIDs that the scheduler has executed.
     * The scheduler is batch-agnostic. That means in order
     * to progress with the batch in the event handler, we need to check if the
     * batch has completed using the number of TIDs executed.
     */
    public final Map<Long,Integer> numberOfTIDsProcessedPerBatch;

    private final Map<Long, Long> batchToNextBatchMap;

    private final ICheckpointEventHandler checkpointEventHandler;

    /**
     * It is necessary a way to store the tid received to a
     * corresponding dependence map.
     */
    private final Map<Long, Map<String, Long>> tidToPrecedenceMap;

    public static VmsEventHandler build(// to identify which vms this is
                                        VmsNode me,
                                        // to checkpoint private state
                                        ITransactionalHandler transactionalHandler,
                                        // for communicating with other components
                                        VmsEmbeddedInternalChannels vmsInternalChannels,
                                        // metadata about this vms
                                        VmsRuntimeMetadata vmsMetadata,
                                        // serialization/deserialization of objects
                                        IVmsSerdesProxy serdesProxy,
                                        // network
                                        int networkBufferSize,
                                        int osBufferSize,
                                        int networkThreadPoolSize,
                                        int networkSendTimeout){
        try {
            return new VmsEventHandler(me, vmsMetadata, new ConcurrentHashMap<>(),
                    transactionalHandler, vmsInternalChannels, serdesProxy,
                    networkBufferSize, osBufferSize, networkThreadPoolSize, networkSendTimeout);
        } catch (IOException e){
            throw new RuntimeException("Error on setting up event handler: "+e.getCause()+ " "+ e.getMessage());
        }
    }

    private VmsEventHandler(VmsNode me,
                            VmsRuntimeMetadata vmsMetadata,
                            Map<String, Deque<ConsumerVms>> eventToConsumersMap,
                            ITransactionalHandler transactionalHandler,
                            VmsEmbeddedInternalChannels vmsInternalChannels,
                            IVmsSerdesProxy serdesProxy,
                            int networkBufferSize,
                            int osBufferSize,
                            int networkThreadPoolSize,
                            int networkSendTimeout) throws IOException {
        super();

        // network and executor
        if(networkThreadPoolSize > 0){
            // at least two, one for acceptor and one for new events
            this.group = AsynchronousChannelGroup.withFixedThreadPool(networkThreadPoolSize, Thread.ofPlatform().factory());
            this.serverSocket = AsynchronousServerSocketChannel.open(this.group);
        } else {
            // by default, server socket creates a cached thread pool. better to avoid successive creation of threads
            this.group = null;
            this.serverSocket = AsynchronousServerSocketChannel.open(null);
        }
        this.serverSocket.bind(me.asInetSocketAddress());

        this.vmsInternalChannels = vmsInternalChannels;
        this.me = me;

        this.vmsMetadata = vmsMetadata;
        this.eventToConsumersMap = eventToConsumersMap;
        this.consumerConnectionMetadataMap = new ConcurrentHashMap<>();
        this.producerConnectionMetadataMap = new ConcurrentHashMap<>();

        this.serdesProxy = serdesProxy;

        this.currentBatch = BatchContext.buildAsStarter(me.batch, me.previousBatch, me.numberOfTIDsCurrentBatch);
        this.currentBatch.setStatus(BatchContext.BATCH_COMMITTED);
        this.batchContextMap = new ConcurrentHashMap<>();
        this.numberOfTIDsProcessedPerBatch = new HashMap<>();
        this.batchToNextBatchMap = new ConcurrentHashMap<>();
        this.tidToPrecedenceMap = new ConcurrentHashMap<>();

        this.transactionalHandler = transactionalHandler;
        this.checkpointEventHandler = new CheckpointEventHandlerImpl();

        // set leader off at the start
        this.leader = new ServerNode("localhost",0);
        this.leader.off();

        this.queuesLeaderSubscribesTo = new HashSet<>();

        this.networkBufferSize = networkBufferSize;
        this.osBufferSize = osBufferSize;
        this.networkSendTimeout = networkSendTimeout;
    }

    private static final int MAX_TIMEOUT = 1000;

    private static final boolean BLOCKING = false;

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
        List<IVmsTransactionResult> transactionResults = new ArrayList<>(1024);
        int pollTimeout = 1;
        IVmsTransactionResult txResult_;
        while(this.isRunning()){
            // can acknowledge batch completion even though no event from next batch has arrived
            // but if BLOCKING, only upon a new event such method will be invoked
            // that will jeopardize the batch process
            this.moveBatchIfNecessary();
            try {
                if(BLOCKING){
                    txResult_ = this.vmsInternalChannels.transactionOutputQueue().take();
                } else {
                    txResult_ = this.vmsInternalChannels.transactionOutputQueue().poll(pollTimeout, TimeUnit.MILLISECONDS);
                    if (txResult_ == null) {
                        pollTimeout = Math.min(pollTimeout * 2, MAX_TIMEOUT);
                        continue;
                    }
                    pollTimeout = pollTimeout > 0 ? pollTimeout / 2 : 0;
                }

                LOGGER.log(DEBUG,this.me.identifier+": New transaction result in event handler. TID = "+txResult_.tid());

                // it is better to get all the results of a given transaction instead of one by one.
                transactionResults.add(txResult_);
                this.vmsInternalChannels.transactionOutputQueue().drainTo(transactionResults);

                for(IVmsTransactionResult txResult : transactionResults) {
                    // assuming is a simple transaction result, not complex, so no need to iterate
                    OutboundEventResult outputEvent = txResult.getOutboundEventResult();

                    // scheduler can be way ahead of the last batch committed
                    this.numberOfTIDsProcessedPerBatch.put(outputEvent.batch(),
                        this.numberOfTIDsProcessedPerBatch.getOrDefault(outputEvent.batch(), 0)
                                + 1);

                    // it is a void method that executed, nothing to send
                    if (outputEvent.outputQueue() == null) continue;
                    Map<String, Long> precedenceMap = this.tidToPrecedenceMap.get(txResult.tid());
                    if (precedenceMap == null) {
                        LOGGER.log(WARNING,this.me.identifier + ": No precedence map found for TID: " + txResult.tid());
                        continue;
                    }
                    // remove ourselves (which also saves some bytes)
                    precedenceMap.remove(this.me.identifier);
                    String precedenceMapUpdated = this.serdesProxy.serializeMap(precedenceMap);
                    this.processOutputEvent(outputEvent, precedenceMapUpdated);
                }
                transactionResults.clear();
            } catch (Exception e) {
                LOGGER.log(ERROR, this.me.identifier+": Problem on handling event\n"+e);
            }

        }
    }

    private void connectToStarterConsumers() {
        if(!this.eventToConsumersMap.isEmpty()){
            // then it is received from constructor, and we must initially contact them
            Map<ConsumerVms, List<String>> consumerToEventsMap = new HashMap<>();
            // build an indirect map
            for(Map.Entry<String,Deque<ConsumerVms>> entry : this.eventToConsumersMap.entrySet()) {
                for(ConsumerVms consumer : entry.getValue()){
                    consumerToEventsMap.computeIfAbsent(consumer, (ignored) -> new ArrayList<>()).add(entry.getKey());
                }
            }
            for( var consumerEntry : consumerToEventsMap.entrySet() ) {
                this.connectToConsumerVms( consumerEntry.getValue(), consumerEntry.getKey() );
            }
        }
    }

    private void connectToReceivedConsumerSet(Map<String, List<IdentifiableNode>> receivedConsumerVms) {
        Map<IdentifiableNode, List<String>> consumerToEventsMap = new HashMap<>();
        // build an indirect map
        for(Map.Entry<String,List<IdentifiableNode>> entry : receivedConsumerVms.entrySet()) {
            for(IdentifiableNode consumer : entry.getValue()){
                consumerToEventsMap.computeIfAbsent(consumer, (ignored) -> new ArrayList<>()).add(entry.getKey());
            }
        }
        for( Map.Entry<IdentifiableNode,List<String>> consumerEntry : consumerToEventsMap.entrySet() ) {
            this.connectToConsumerVms( consumerEntry.getValue(), consumerEntry.getKey() );
        }
    }

    private static final Object DUMB_OBJECT = new Object();

    /**
     * it may be the case that, due to an abort of the last tid, the last tid changes
     * the current code is not incorporating that
     */
    private void moveBatchIfNecessary(){
        // update if current batch is done AND the next batch has already arrived
        if(this.currentBatch.isCommitted() && this.batchToNextBatchMap.containsKey( this.currentBatch.batch )){
            long nextBatchOffset = this.batchToNextBatchMap.get( this.currentBatch.batch );
            this.currentBatch = this.batchContextMap.get(nextBatchOffset);
        }

        // have we processed all the TIDs of this batch?
        if(this.currentBatch.isOpen() && this.currentBatch.numberOfTIDsBatch ==
                this.numberOfTIDsProcessedPerBatch.getOrDefault(this.currentBatch.batch, 0)){

            LOGGER.log(DEBUG,this.me.identifier+": All TIDs for the current batch ("+this.currentBatch.batch+") have been executed");

            // many outputs from the same transaction may arrive here, but can only send the batch commit once
            this.currentBatch.setStatus(BatchContext.BATCH_COMPLETED);

            // if terminal, must send batch complete
            if(this.currentBatch.terminal) {
                LOGGER.log(DEBUG,this.me.identifier+": Informing coordinator the current batch ("+this.currentBatch.batch+") completion since I am a terminal node");
                // must be queued in case leader is off and comes back online
                this.leaderWorker.queueMessage(BatchComplete.of(this.currentBatch.batch, this.me.identifier));
            }

            // this is necessary to move the batch to committed and allow the batches to progress
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
            LOGGER.log(WARNING,this.me.identifier+": Could not close socket");
        }
    }

    @Override
    public void run() {
        LOGGER.log(INFO,this.me.identifier+": Event handler has started");

        this.connectToStarterConsumers();

        // setup accept since we need to accept connections from the coordinator and other VMSs
        this.serverSocket.accept(null, new AcceptCompletionHandler());
        LOGGER.log(INFO,this.me.identifier+": Accept handler has been setup");

        // init event loop
        this.eventLoop();

        this.failSafeClose();
        LOGGER.log(INFO,this.me.identifier+": Event handler has finished execution.");
    }

    /**
     * A callback from the transaction scheduler
     * This is a way to coordinate with the transaction scheduler
     * a checkpoint signal emitted by the coordinator
     */
    private final class CheckpointEventHandlerImpl implements ICheckpointEventHandler {
        @Override
        public void checkpoint() {
            vmsInternalChannels.batchCommitCommandQueue().remove();
            log();
        }
        @Override
        public boolean mustCheckpoint() {
            return !vmsInternalChannels.batchCommitCommandQueue().isEmpty();
        }
    }

    private static final boolean INFORM_BATCH_ACK = false;

    private void log() {
        this.currentBatch.setStatus(BatchContext.LOGGING);
        // of course, I do not need to stop the scheduler on commit
        // I need to make access to the data versions data race free
        // so new transactions get data versions from the version map or the store
        this.transactionalHandler.checkpoint();

        this.currentBatch.setStatus(BatchContext.BATCH_COMMITTED);

        // it may not be necessary. the leader has already moved on at this point
        if(INFORM_BATCH_ACK) {
            this.leaderWorker.queueMessage(BatchCommitAck.of(this.currentBatch.batch, this.me.identifier));
        }
    }

    /**
     * It creates the payload to be sent downstream
     * @param outputEvent the event to be sent to the respective consumer vms
     */
    private void processOutputEvent(OutboundEventResult outputEvent, String precedenceMap){

        Class<?> clazz = this.vmsMetadata.queueToEventMap().get(outputEvent.outputQueue());
        String objStr = this.serdesProxy.serialize(outputEvent.output(), clazz);

        /*
         * does the leader consumes this queue?
        if( this.queuesLeaderSubscribesTo.contains( outputEvent.outputQueue() ) ){
            logger.log(DEBUG,me.identifier+": An output event (queue: "+outputEvent.outputQueue()+") will be queued to leader");
            this.leaderWorkerQueue.add(new LeaderWorker.Message(SEND_EVENT, payload));
        }
        */

        Deque<ConsumerVms> consumerVMSs = this.eventToConsumersMap.get(outputEvent.outputQueue());
        if(consumerVMSs == null || consumerVMSs.isEmpty()){
            LOGGER.log(DEBUG,this.me.identifier+": An output event (queue: "+outputEvent.outputQueue()+") has no target virtual microservices.");
            return;
        }

        // right now just including the original precedence map. ideally we must reduce the size by removing this vms
        // however, modifying the map incurs deserialization and serialization costs
        TransactionEvent.PayloadRaw payload = TransactionEvent.of(
                outputEvent.tid(), outputEvent.batch(), outputEvent.outputQueue(), objStr, precedenceMap );

        for(ConsumerVms consumerVms : consumerVMSs) {
            LOGGER.log(DEBUG,this.me.identifier+": An output event (queue: " + outputEvent.outputQueue() + ") will be queued to VMS: " + consumerVms.identifier);
            consumerVms.queueTransactionEvent(payload);
        }
    }

    /**
     * Responsible for making sure the handshake protocol is
     * successfully performed with a consumer VMS
     */
    private final class ConnectToConsumerVmsProtocol {

        private State state;
        private final AsynchronousSocketChannel channel;
        private final ByteBuffer buffer;
        public final CompletionHandler<Void, ConnectToConsumerVmsProtocol> completionHandler;
        private final IdentifiableNode node;

        private final List<String> outputEvents;

        public ConnectToConsumerVmsProtocol(AsynchronousSocketChannel channel, List<String> outputEvents, IdentifiableNode node) {
            this.state = State.NEW;
            this.channel = channel;
            this.completionHandler = new ConnectToConsumerVmsCompletionHandler();
            this.buffer = MemoryManager.getTemporaryDirectBuffer(networkBufferSize);
            this.outputEvents = outputEvents;
            this.node = node;
        }

        private enum State { NEW, CONNECTED, PRESENTATION_SENT }

        private class ConnectToConsumerVmsCompletionHandler implements CompletionHandler<Void, ConnectToConsumerVmsProtocol> {

            @Override
            public void completed(Void result, ConnectToConsumerVmsProtocol attachment) {

                attachment.state = State.CONNECTED;
                LOGGER.log(DEBUG,me.identifier+ ": The node "+ node.host+" "+ node.port+" status = "+attachment.state);

                ConnectionMetadata connMetadata = new ConnectionMetadata(
                        node.hashCode(),
                        ConnectionMetadata.NodeType.VMS,
                        channel
                        );

                if(producerConnectionMetadataMap.containsKey(node.hashCode())){
                    LOGGER.log(WARNING,"The node "+ node.host+" "+ node.port+" already contains a connection as a producer");
                }

                consumerConnectionMetadataMap.put(node.hashCode(), connMetadata);

                String dataSchema = serdesProxy.serializeDataSchema(me.dataSchema);
                String inputEventSchema = serdesProxy.serializeEventSchema(me.inputEventSchema);
                String outputEventSchema = serdesProxy.serializeEventSchema(me.outputEventSchema);

                attachment.buffer.clear();
                Presentation.writeVms( attachment.buffer, me, me.identifier, me.batch, 0, me.previousBatch, dataSchema, inputEventSchema, outputEventSchema );
                attachment.buffer.flip();

                // have to make sure we send the presentation before writing to this VMS, otherwise an exception can occur (two writers)
                attachment.channel.write(attachment.buffer, attachment, new CompletionHandler<>() {
                    @Override
                    public void completed(Integer result, ConnectToConsumerVmsProtocol attachment) {
                        attachment.state = State.PRESENTATION_SENT;

                        LOGGER.log(DEBUG,me.identifier+ ": The node "+ node.host+" "+ node.port+" status = "+attachment.state);

                        attachment.buffer.clear();

                        if(me.hashCode() == node.hashCode()){
                            LOGGER.log(ERROR,me.identifier+" is receiving itself as consumer: "+ node.identifier);
                            return;
                        }

                        LOGGER.log(INFO,me.identifier+ " setting up worker to send transactions to consumer VMS: "+node.identifier);
                        ConsumerVms consumerVms = new ConsumerVms(
                                node.identifier,
                                node.host,
                                node.port);

                        // add to tracked VMSs...
                        for (String outputEvent : outputEvents) {
                            LOGGER.log(INFO,me.identifier+ " adding "+outputEvent+" to consumers map with "+consumerVms.identifier);
                            eventToConsumersMap.computeIfAbsent(outputEvent, (ignored) -> new ConcurrentLinkedDeque<>());
                            eventToConsumersMap.get(outputEvent).add(consumerVms);
                        }

                        // set up consumer vms worker
                        Thread.ofPlatform().factory().newThread(
                                new ConsumerVmsWorker(me, consumerVms, connMetadata, networkBufferSize, networkSendTimeout)
                        ).start();

                        // set up read from consumer vms
                        attachment.channel.read(attachment.buffer, 0, new VmsReadCompletionHandler(node, connMetadata, attachment.buffer));
                    }

                    @Override
                    public void failed(Throwable exc, ConnectToConsumerVmsProtocol attachment) {
                        // check if connection is still online. if so, try again
                        // otherwise, retry connection in a few minutes
                        //issueQueue.add(new Issue(CANNOT_CONNECT_TO_NODE, attachment.node.hashCode()));
                        LOGGER.log(ERROR,me.identifier+ "caught an error while trying to connect to consumer VMS: "+node.identifier);
                        attachment.buffer.clear();
                    }
                });

            }

            @Override
            public void failed(Throwable exc, ConnectToConsumerVmsProtocol attachment) {
                // queue for later attempt
                // perhaps can use scheduled task
                //issueQueue.add( new Issue(CANNOT_CONNECT_TO_NODE, attachment.node.hashCode()) );
                LOGGER.log(ERROR,me.identifier+ "caught an error while trying to connect to consumer VMS: "+node.identifier);
                // node.off(); no need it is already off
            }
        }

    }

    /**
     * The leader will let each VMS aware of their dependencies,
     * to which VMSs they have to connect to
//    private void connectToConsumerVMSs(List<String> outputEvents, List<NetworkAddress> consumerSet) {
//        for(NetworkAddress vms : consumerSet) {
//            // process only the new ones
//            LockConnectionMetadata connectionMetadata = this.consumerConnectionMetadataMap.get(vms.hashCode());
//            if(connectionMetadata != null && connectionMetadata.channel != null && connectionMetadata.channel.isOpen()){
//                continue; // ignore
//            }
//            this.connectToConsumerVms(outputEvents, vms);
//        }
//    }
     */
    private void connectToConsumerVms(List<String> outputEvents, IdentifiableNode vmsNode) {
        try {
            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(this.group);
            NetworkUtils.configure(channel, osBufferSize);
            ConnectToConsumerVmsProtocol protocol = new ConnectToConsumerVmsProtocol(channel, outputEvents, vmsNode);
            channel.connect(vmsNode.asInetSocketAddress(), protocol, protocol.completionHandler);
        } catch (Exception e) {
            //this.issueQueue.add( new Issue(CANNOT_CONNECT_TO_NODE, vmsNode.hashCode()) );
            LOGGER.log(ERROR,me.identifier+" caught an error while trying to connect to "+vmsNode.identifier+": "+e);
        }
    }

    /**
     * The completion handler must execute fast
     */
    @SuppressWarnings("SequencedCollectionMethodCanBeUsed")
    private final class VmsReadCompletionHandler implements CompletionHandler<Integer, Integer> {

        // the VMS sending events to me
        private final IdentifiableNode node;
        private final ConnectionMetadata connectionMetadata;
        private final ByteBuffer readBuffer;

        public VmsReadCompletionHandler(IdentifiableNode node,
                                        ConnectionMetadata connectionMetadata,
                                        ByteBuffer byteBuffer){
            this.node = node;
            this.connectionMetadata = connectionMetadata;
            this.readBuffer = byteBuffer;
            LIST_BUFFER.add(new ArrayList<>(1024));
        }

        @Override
        public void completed(Integer result, Integer startPos) {

            if(result == -1){
                LOGGER.log(INFO,me.identifier+": VMS "+node.identifier+" has disconnected");
                try{
                    this.connectionMetadata.channel.close();
                } catch (IOException e) {
                    e.printStackTrace(System.out);
                }
                return;
            }

            if(startPos == 0){
                this.readBuffer.flip();
            }

            byte messageType = readBuffer.get();
            switch (messageType) {
                //noinspection DuplicatedCode
                case (BATCH_OF_EVENTS) -> {
                    int bufferSize = Integer.MAX_VALUE;
                    // check if we can read an integer
                    if(this.readBuffer.remaining() > Integer.BYTES) {
                        // size of the batch
                        bufferSize = this.readBuffer.getInt();
                        // discard message type and size of batch from the total size since it has already been read
                        bufferSize -= 1 + Integer.BYTES;
                    }
                    if(this.readBuffer.remaining() < bufferSize){
                        this.fetchMoreBytes(startPos);
                        return;
                    }
                    this.processBatchOfEvents(this.readBuffer);
                }
                case (EVENT) -> this.processSingleEvent(readBuffer);
                default -> LOGGER.log(ERROR,me.identifier+": Unknown message type "+messageType+" received from: "+node.identifier);
            }

            if(readBuffer.hasRemaining()){
                this.completed(result, this.readBuffer.position());
            } else {
                this.setUpNewRead();
            }
        }

        private void fetchMoreBytes(Integer startPos) {
            this.readBuffer.position(startPos);
            this.readBuffer.compact();
            this.connectionMetadata.channel.read(this.readBuffer, 0, this);
        }

        private void setUpNewRead() {
            this.readBuffer.clear();
            this.connectionMetadata.channel.read(this.readBuffer, 0, this);
        }

        private void processSingleEvent(ByteBuffer readBuffer) {
            LOGGER.log(DEBUG,me.identifier+": 1 event received from "+node.identifier);
            try {
                TransactionEvent.Payload payload = TransactionEvent.read(readBuffer);
                // send to scheduler
                if (vmsMetadata.queueToEventMap().containsKey(payload.event())) {
                    InboundEvent inboundEvent = buildInboundEvent(payload);
                    boolean added;
                    do {
                        added = vmsInternalChannels.transactionInputQueue().offer(inboundEvent);
                    } while(!added);
                }
            } catch (Exception e) {
                if(e instanceof BufferUnderflowException)
                    LOGGER.log(ERROR,me.identifier + ": Buffer underflow exception while reading event: " + e);
                else
                    LOGGER.log(ERROR,me.identifier + ": Unknown exception: " + e);
            }
        }

        private void processBatchOfEvents(ByteBuffer readBuffer) {
            List<InboundEvent> inboundEvents = LIST_BUFFER.poll();
            if(inboundEvents == null) inboundEvents = new ArrayList<>(1024);
            try {
                int count = readBuffer.getInt();
                LOGGER.log(DEBUG,me.identifier + ": Batch of [" + count + "] events received from " + node.identifier);
                TransactionEvent.Payload payload;
                int i = 0;
                while (i < count) {
                    payload = TransactionEvent.read(readBuffer);
                    LOGGER.log(DEBUG, me.identifier+": Processed TID "+payload.tid());
                    if (vmsMetadata.queueToEventMap().containsKey(payload.event())) {
                        InboundEvent inboundEvent = buildInboundEvent(payload);
                        inboundEvents.add(inboundEvent);
                    }
                    i++;
                }
                if(count != inboundEvents.size()){
                    LOGGER.log(WARNING,me.identifier + ": Batch of [" +count+ "] events != from "+inboundEvents.size()+" that will be pushed to worker " + node.identifier);
                }
                while(!inboundEvents.isEmpty()){
                    if(vmsInternalChannels.transactionInputQueue().offer(inboundEvents.get(0))){
                        inboundEvents.remove(0);
                    }
                }

                LOGGER.log(DEBUG, "Number of inputs pending processing: "+vmsInternalChannels.transactionInputQueue().size());

            } catch(Exception e){
                if (e instanceof BufferUnderflowException)
                    LOGGER.log(ERROR,me.identifier + ": Buffer underflow exception while reading batch: " + e);
                else
                    LOGGER.log(ERROR,me.identifier + ": Unknown exception: " + e);
            } finally {
                inboundEvents.clear();
                LIST_BUFFER.add(inboundEvents);
            }
        }

        @Override
        public void failed(Throwable exc, Integer carryOn) {
            LOGGER.log(ERROR,me.identifier+": Error on reading VMS message from "+node.identifier+"\n"+exc);
            exc.printStackTrace(System.out);
            this.setUpNewRead();
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

            if(result == 0){
                LOGGER.log(WARNING,me.identifier+": A node is trying to connect with an empty message!");
                try { this.channel.close(); } catch (IOException ignored) {}
                return;
            } else if(result == -1){
                LOGGER.log(WARNING,me.identifier+": A node died before sending the presentation message");
                return;
            }

            // message identifier
            byte messageIdentifier = this.buffer.get(0);
            if(messageIdentifier != PRESENTATION){
                LOGGER.log(WARNING,me.identifier+": A node is trying to connect without a presentation message");
                this.buffer.clear();
                MemoryManager.releaseTemporaryDirectBuffer(this.buffer);
                try { this.channel.close(); } catch (IOException ignored) {}
                return;
            }

            byte nodeTypeIdentifier = this.buffer.get(1);
            this.buffer.position(2);

            switch (nodeTypeIdentifier) {
                case (SERVER_TYPE) -> {
                    LOGGER.log(INFO,me.identifier+": Start processing presentation message from a node claiming to be a server");
                    if(!leader.isActive()) {
                        ConnectionFromLeaderProtocol connectionFromLeader = new ConnectionFromLeaderProtocol(this.channel, this.buffer);
                        connectionFromLeader.processLeaderPresentation();
                    } else {
                        // discard include metadata bit
                        this.buffer.get();
                        ServerNode serverNode = Presentation.readServer(this.buffer);
                        // known leader attempting additional connection?
                        if(serverNode.asInetSocketAddress().equals(leader.asInetSocketAddress())) {
                            LOGGER.log(INFO, me.identifier + ": Leader requested an additional connection");
                            this.buffer.clear();
                            channel.read(buffer, 0, new LeaderReadCompletionHandler(new ConnectionMetadata(leader.hashCode(), ConnectionMetadata.NodeType.SERVER, channel), buffer));
                        } else {
                            try {
                                LOGGER.log(WARNING,"Dropping a connection attempt from a node claiming to be leader");
                                 this.channel.close();
                            } catch (Exception ignored) {}
                        }
                    }
                }
                case (VMS_TYPE) -> {
                    LOGGER.log(INFO,me.identifier+": Start processing presentation message from a node claiming to be a VMS");
                    // then it is a vms intending to connect due to a data/event
                    // that should be delivered to this vms
                    VmsNode producerVms = readVms(this.buffer, serdesProxy);
                    this.buffer.clear();

                    ConnectionMetadata connMetadata = new ConnectionMetadata(
                            producerVms.hashCode(),
                            ConnectionMetadata.NodeType.VMS,
                            this.channel
                    );

                    // what if a vms is both producer to and consumer from this vms?
                    if(consumerConnectionMetadataMap.containsKey(producerVms.hashCode())){
                        LOGGER.log(WARNING,me.identifier+": The node "+producerVms.host+" "+producerVms.port+" already contains a connection as a consumer");
                    }

                    producerConnectionMetadataMap.put(producerVms.hashCode(), connMetadata);

                    // setup event receiving from this vms
                    LOGGER.log(INFO,me.identifier+": Setting up consumption from producer "+producerVms);
                    this.channel.read(this.buffer, 0, new VmsReadCompletionHandler(producerVms, connMetadata, this.buffer));
                }
                default -> {
                    LOGGER.log(WARNING,me.identifier+": Presentation message from unknown source:" + nodeTypeIdentifier);
                    this.buffer.clear();
                    MemoryManager.releaseTemporaryDirectBuffer(this.buffer);
                    try {
                        this.channel.close();
                    } catch (IOException ignored) { }
                }
            }
        }

        @Override
        public void failed(Throwable exc, Void void_) {
            LOGGER.log(WARNING,"Error on processing presentation message!");
        }

    }

    /**
     * Class is iteratively called by the socket pool threads.
     */
    private final class AcceptCompletionHandler implements CompletionHandler<AsynchronousSocketChannel, Void> {

        @Override
        public void completed(AsynchronousSocketChannel channel, Void void_) {
            LOGGER.log(INFO,me.identifier+": An unknown host has started a connection attempt.");
            final ByteBuffer buffer = MemoryManager.getTemporaryDirectBuffer(networkBufferSize);
            try {
                NetworkUtils.configure(channel, osBufferSize);
                // read presentation message. if vms, receive metadata, if follower, nothing necessary
                channel.read(buffer, null, new UnknownNodeReadCompletionHandler(channel, buffer));
            } catch(Exception e){
                LOGGER.log(ERROR,me.identifier+": Accept handler caught exception: "+e.getMessage());
                buffer.clear();
                MemoryManager.releaseTemporaryDirectBuffer(buffer);
            } finally {
                LOGGER.log(INFO,me.identifier+": Accept handler set up again for listening to new connections");
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

            LOGGER.log(WARNING,me.identifier+": Error on accepting connection: "+ message);
            if (serverSocket.isOpen()){
                serverSocket.accept(null, this);
            } else {
                LOGGER.log(WARNING,me.identifier+": Socket is not open anymore. Cannot set up accept again");
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

        private final class WriteCompletionHandler implements CompletionHandler<Integer,Void> {

            @Override
            public void completed(Integer result, Void attachment) {
                state = State.PRESENTATION_SENT;
                LOGGER.log(INFO,me.identifier+": Message sent to Leader successfully = "+state);

                // set up leader worker
                leaderWorker = new LeaderWorker(me, leader,
                        leaderConnectionMetadata.channel,
                        MemoryManager.getTemporaryDirectBuffer(networkBufferSize));
                Thread leaderWorkerThread = Thread.ofPlatform().factory().newThread(leaderWorker);
                leaderWorkerThread.setName("leader-worker-"+me.identifier);
                leaderWorkerThread.start();
                LOGGER.log(INFO,me.identifier+": Leader worker set up");
                buffer.clear();
                channel.read(buffer, 0, new LeaderReadCompletionHandler(leaderConnectionMetadata, buffer) );
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                LOGGER.log(INFO,me.identifier+": Failed to send presentation to Leader");
                buffer.clear();
                if(!channel.isOpen()) {
                    leaderWorker.stop();
                    leader.off();
                }
                // else what to do try again? no, let the new leader connect
            }
        }

        public void processLeaderPresentation() {
            LOGGER.log(INFO,me.identifier+": Start processing the Leader presentation");
            boolean includeMetadata = this.buffer.get() == YES;

            // leader has disconnected, or new leader
            leader = Presentation.readServer(this.buffer);

            // read queues leader is interested
            boolean hasQueuesToSubscribe = this.buffer.get() == YES;
            if(hasQueuesToSubscribe){
                queuesLeaderSubscribesTo.addAll(readQueuesToSubscribeTo(this.buffer, serdesProxy));
            }

            // only connects to all VMSs on first leader connection

            if(leaderConnectionMetadata == null) {
                // then setup connection metadata and read completion handler
                leaderConnectionMetadata = new ConnectionMetadata(
                        leader.hashCode(),
                        ConnectionMetadata.NodeType.SERVER,
                        channel
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

                Presentation.writeVms(this.buffer, me, me.identifier, me.batch, 0, me.previousBatch, vmsDataSchemaStr, vmsInputEventSchemaStr, vmsOutputEventSchemaStr);
                // the protocol requires the leader to wait for the metadata in order to start sending messages
            } else {
                Presentation.writeVms(this.buffer, me, me.identifier, me.batch, 0, me.previousBatch);
            }

            this.buffer.flip();
            this.state = State.PRESENTATION_PROCESSED;
            LOGGER.log(INFO,me.identifier+": Message successfully received from the Leader  = "+state);
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
        if(!precedenceMap.containsKey(this.me.identifier)){
            throw new IllegalStateException("Precedent tid of "+payload.tid()+" is unknown.");
        }
        this.tidToPrecedenceMap.put(payload.tid(), precedenceMap);
        return new InboundEvent( payload.tid(), precedenceMap.get(this.me.identifier),
                payload.batch(), payload.event(), clazz, input );
    }

    private static final ConcurrentLinkedDeque<List<InboundEvent>> LIST_BUFFER = new ConcurrentLinkedDeque<>();

    private final class LeaderReadCompletionHandler implements CompletionHandler<Integer, Integer> {

        private final ConnectionMetadata connectionMetadata;
        private final ByteBuffer readBuffer;

        public LeaderReadCompletionHandler(ConnectionMetadata connectionMetadata, ByteBuffer readBuffer){
            this.connectionMetadata = connectionMetadata;
            this.readBuffer = readBuffer;
            LIST_BUFFER.add(new ArrayList<>(1024));
        }

        @Override
        public void completed(Integer result, Integer startPos) {

            if(result == -1){
                LOGGER.log(INFO,me.identifier+": Leader has disconnected");
                leader.off();
                try{
                    this.connectionMetadata.channel.close();
                } catch (IOException e) {
                    e.printStackTrace(System.out);
                }
                return;
            }

            if(startPos == 0){
                // sets the position to 0 and sets the limit to the current position
                this.readBuffer.flip();
            }

            // guaranteed we always have at least one byte to read
            byte messageType = this.readBuffer.get();

            try {
                switch (messageType) {
                    //noinspection DuplicatedCode
                    case (BATCH_OF_EVENTS) -> {
                        int bufferSize = Integer.MAX_VALUE;
                        // check if we can read an integer
                        if(this.readBuffer.remaining() > Integer.BYTES) {
                            // size of the batch
                            bufferSize = this.readBuffer.getInt();
                            // discard message type and size of batch from the total size since it has already been read
                            bufferSize -= 1 + Integer.BYTES;
                        }
                        if(this.readBuffer.remaining() < bufferSize){
                            this.fetchMoreBytes(startPos);
                            return;
                        }
                        this.processBatchOfEvents(this.readBuffer);
                    }
                    case (BATCH_COMMIT_INFO) -> {
                        if(this.readBuffer.remaining() < BatchCommitInfo.SIZE){
                            this.fetchMoreBytes(startPos);
                            return;
                        }
                        // events of this batch from VMSs may arrive before the batch commit info
                        // it means this VMS is a terminal node for the batch
                        BatchCommitInfo.Payload bPayload = BatchCommitInfo.read(this.readBuffer);
                        LOGGER.log(DEBUG, me.identifier + ": Batch (" + bPayload.batch() + ") commit info received from the leader");
                        this.processNewBatchInfo(bPayload);
                    }
                    case (BATCH_COMMIT_COMMAND) -> {
                        if(this.readBuffer.remaining() < BatchCommitCommand.SIZE){
                            this.fetchMoreBytes(startPos);
                            return;
                        }
                        // a batch commit queue from next batch can arrive before this vms moves next? yes
                        BatchCommitCommand.Payload payload = BatchCommitCommand.read(this.readBuffer);
                        LOGGER.log(DEBUG, me.identifier + ": Batch (" + payload.batch() + ") commit command received from the leader");
                        this.processNewBatchCommand(payload);
                    }
                    // case (EVENT) -> processSingleEvent(readBuffer);
                    case (TX_ABORT) -> {
                        if(this.readBuffer.remaining() < TransactionAbort.SIZE){
                            this.fetchMoreBytes(startPos);
                            return;
                        }
                        TransactionAbort.Payload txAbortPayload = TransactionAbort.read(this.readBuffer);
                        LOGGER.log(WARNING, "Transaction (" + txAbortPayload.batch() + ") abort received from the leader?");
                        vmsInternalChannels.transactionAbortInputQueue().add(txAbortPayload);
                    }
                    case (BATCH_ABORT_REQUEST) -> {
                        if(this.readBuffer.remaining() < BatchAbortRequest.SIZE){
                            this.fetchMoreBytes(startPos);
                            return;
                        }
                        // some new leader request to roll back to last batch commit
                        BatchAbortRequest.Payload batchAbortReq = BatchAbortRequest.read(this.readBuffer);
                        LOGGER.log(WARNING, "Batch (" + batchAbortReq.batch() + ") abort received from the leader");
                        // vmsInternalChannels.batchAbortQueue().add(batchAbortReq);
                    }
                    case (CONSUMER_SET) -> {
                        try {
                            LOGGER.log(INFO, me.identifier + ": Consumer set received from the leader");
                            Map<String, List<IdentifiableNode>> receivedConsumerVms = ConsumerSet.read(this.readBuffer, serdesProxy);
                            if (!receivedConsumerVms.isEmpty()) {
                                connectToReceivedConsumerSet(receivedConsumerVms);
                            }
                        } catch (IOException e) {
                            LOGGER.log(ERROR, me.identifier + ": IOException while reading consumer set: " + e);
                            e.printStackTrace(System.out);
                        }
                    }
                    case (PRESENTATION) ->
                            LOGGER.log(WARNING, me.identifier + ": Presentation being sent again by the leader!?");
                    default ->
                            LOGGER.log(ERROR, me.identifier + ": Message type sent by the leader cannot be identified: " + messageType);
                }
            } catch (Exception e){
                if(e instanceof BufferUnderflowException) {
                    LOGGER.log(ERROR, "Leader: Buffer underflow caught\n"+e);
                    // this is for cases other than the batch of events
                    // this.fetchMoreBytes(startPos);
                } else {
                    LOGGER.log(ERROR, "Leader: Unknown error caught\n"+e);
                }
                e.printStackTrace(System.out);
            }

            if(this.readBuffer.hasRemaining()){
                this.completed(result, this.readBuffer.position());
            } else {
                this.setUpNewRead();
            }
        }

        /**
         * This method should be called only when strictly necessary to complete a read
         * Otherwise there would be an overhead due to the many I/Os
         */
        private void fetchMoreBytes(Integer startPos) {
            this.readBuffer.position(startPos);
            this.readBuffer.compact();
            // get the rest of the batch
            this.connectionMetadata.channel.read(this.readBuffer, 0, this);
        }

        private void setUpNewRead() {
            this.readBuffer.clear();
            // set up another read for cases of bursts of data
            this.connectionMetadata.channel.read(this.readBuffer, 0, this);
        }

        @SuppressWarnings("SequencedCollectionMethodCanBeUsed")
        private void processBatchOfEvents(ByteBuffer readBuffer) {
            List<InboundEvent> payloads = LIST_BUFFER.poll();
            if(payloads == null) payloads = new ArrayList<>(1024);
            /*
             * Given a new batch of events sent by the leader, the last message is the batch info
             */
            TransactionEvent.Payload payload;
            try {
                // to increase performance, one would buffer this buffer for processing and then read from another buffer
                int count = readBuffer.getInt();
                LOGGER.log(DEBUG,me.identifier + ": Batch of [" + count + "] events received from the leader");

                // extract events batched
                for (int i = 0; i < count; i++) {
                    payload = TransactionEvent.read(readBuffer);
                    if(payload.tid() == 99)
                        LOGGER.log(DEBUG, me.identifier+": Processed TID "+payload.tid());
                    if (vmsMetadata.queueToEventMap().containsKey(payload.event())) {
                        payloads.add(buildInboundEvent(payload));
                    }
                }

                // add after to make sure the batch context map is filled by the time the output event is generated
                while(!payloads.isEmpty()){
                    if(vmsInternalChannels.transactionInputQueue().offer(payloads.get(0))){
                        payloads.remove(0);
                    }
                }

            } catch (Exception e){
                LOGGER.log(ERROR, me.identifier +": Error while processing a batch\n"+e);
                // e.printStackTrace(System.out);
                if(e instanceof BufferUnderflowException) {
                    throw new BufferUnderflowException();
                }
            } finally {
                payloads.clear();
                LIST_BUFFER.add(payloads);
            }
        }

        /**
        private void processSingleEvent(ByteBuffer readBuffer) {
            try {
                LOGGER.log(WARNING,me.identifier + ": 1 event received from the leader");
                TransactionEvent.Payload payload = TransactionEvent.read(readBuffer);
                // send to scheduler.... drop if the event cannot be processed (not an input event in this vms)
                if (vmsMetadata.queueToEventMap().containsKey(payload.event())) {
                    InboundEvent event = buildInboundEvent(payload);
                    boolean sent = vmsInternalChannels.transactionInputQueue().offer(event);
                    while (!sent){
                        sent = vmsInternalChannels.transactionInputQueue().offer(event);
                    }
                }
            } catch (Exception e) {
                if(e instanceof BufferUnderflowException)
                    LOGGER.log(ERROR,me.identifier + ": Buffer underflow exception while reading event: " + e);
                else
                    LOGGER.log(ERROR,me.identifier + ": Unknown exception: " + e);
            }
        }
        */

        private void processNewBatchInfo(BatchCommitInfo.Payload batchCommitInfo){
            BatchContext batchContext = BatchContext.build(batchCommitInfo);
            batchContextMap.put(batchCommitInfo.batch(), batchContext);
            batchToNextBatchMap.put( batchCommitInfo.previousBatch(), batchCommitInfo.batch() );
        }

        /**
         * Context of execution of this method:
         * This is not a terminal node in this batch, which means
         * it does not know anything about the batch commit command just received.
         * If the previous batch is completed and this received batch is the next,
         * we just let the main loop update it
         */
        private void processNewBatchCommand(BatchCommitCommand.Payload batchCommitCommand){
            BatchContext batchContext = BatchContext.build(batchCommitCommand);
            batchContextMap.put(batchCommitCommand.batch(), batchContext);
            batchToNextBatchMap.put( batchCommitCommand.previousBatch(), batchCommitCommand.batch() );
        }

        @Override
        public void failed(Throwable exc, Integer carryOn) {
            LOGGER.log(ERROR,me.identifier+": Message could not be processed: "+exc);
            exc.printStackTrace(System.out);
            this.setUpNewRead();
        }
    }

    public ICheckpointEventHandler schedulerHandler() {
        return this.checkpointEventHandler;
    }

    public ITransactionalHandler transactionalHandler(){
        return this.transactionalHandler;
    }

}
