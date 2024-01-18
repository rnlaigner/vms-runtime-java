package dk.ku.di.dms.vms.coordinator.server.coordinator.runnable;

import dk.ku.di.dms.vms.modb.common.memory.MemoryManager;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.BatchCommitAck;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.BatchCommitCommand;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.BatchCommitInfo;
import dk.ku.di.dms.vms.modb.common.schema.network.batch.BatchComplete;
import dk.ku.di.dms.vms.modb.common.schema.network.control.ConsumerSet;
import dk.ku.di.dms.vms.modb.common.schema.network.control.Presentation;
import dk.ku.di.dms.vms.modb.common.schema.network.meta.NetworkAddress;
import dk.ku.di.dms.vms.modb.common.schema.network.node.ServerIdentifier;
import dk.ku.di.dms.vms.modb.common.schema.network.node.VmsNode;
import dk.ku.di.dms.vms.modb.common.schema.network.transaction.TransactionAbort;
import dk.ku.di.dms.vms.modb.common.schema.network.transaction.TransactionEvent;
import dk.ku.di.dms.vms.modb.common.serdes.IVmsSerdesProxy;
import dk.ku.di.dms.vms.modb.common.utils.BatchUtils;
import dk.ku.di.dms.vms.web_common.runnable.StoppableRunnable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.logging.Logger;

import static dk.ku.di.dms.vms.coordinator.server.coordinator.runnable.IVmsWorker.State.*;
import static dk.ku.di.dms.vms.modb.common.schema.network.Constants.*;
import static dk.ku.di.dms.vms.web_common.meta.NetworkConfigConstants.DEFAULT_BUFFER_SIZE;
import static java.lang.Thread.sleep;
import static java.net.StandardSocketOptions.SO_KEEPALIVE;
import static java.net.StandardSocketOptions.TCP_NODELAY;

final class VmsWorker extends StoppableRunnable implements IVmsWorker {

    private final Logger logger;

    private final ServerIdentifier me;

    // the vms this worker is responsible for
    private final NetworkAddress consumerVms;

    // defined after presentation being sent by the actual vms
    private VmsNode vmsNode = new VmsNode("", 0, "unknown", 0, 0, 0, null, null, null);

    private State state;

    private final IVmsSerdesProxy serdesProxy;

    private final ByteBuffer readBuffer;

    private final BlockingDeque<ByteBuffer> writeBufferPool;

    /**
     * Queues to inform coordinator about an event
     */

    private final BlockingQueue<Coordinator.Message> coordinatorQueue;

    private final AsynchronousChannelGroup group;

    private AsynchronousSocketChannel channel;

    // DTs particular to this vms worker
    private final Map<Long, BlockingDeque<TransactionEvent.Payload>> transactionEventsPerBatch = new ConcurrentHashMap<>();

    private final BlockingQueue<Message> workerQueue = new LinkedBlockingQueue<>();

     private final BlockingQueue<Byte> WRITE_SYNCHRONIZER = new ArrayBlockingQueue<>(1);

    private static final Byte DUMB = 1;

    static VmsWorker buildAsStarter(// coordinator reference
                                    ServerIdentifier me,
                                    // the vms this thread is responsible for
                                    NetworkAddress consumerVms,
                                    // shared data structure to communicate messages to coordinator
                                    BlockingQueue<Coordinator.Message> coordinatorQueue,
                                    // the group for the socket channel
                                    AsynchronousChannelGroup group,
                                    IVmsSerdesProxy serdesProxy) {
        return new VmsWorker(me, consumerVms, coordinatorQueue, null, group,
                MemoryManager.getTemporaryDirectBuffer(DEFAULT_BUFFER_SIZE), serdesProxy);
    }

    static VmsWorker build(
            ServerIdentifier me,
            NetworkAddress consumerVms,
            BlockingQueue<Coordinator.Message> coordinatorQueue,
            // the socket channel already established
            AsynchronousSocketChannel channel,
            AsynchronousChannelGroup group,
            ByteBuffer readBuffer, // to continue reading presentation
            IVmsSerdesProxy serdesProxy) {
        return new VmsWorker(me, consumerVms, coordinatorQueue, channel, group, readBuffer, serdesProxy);
    }

    private VmsWorker(// coordinator reference
                      ServerIdentifier me,
                      // the vms this thread is responsible for
                      NetworkAddress consumerVms,
                      // events to share with coordinator
                      BlockingQueue<Coordinator.Message> coordinatorQueue,
                      // the group for socket channel
                      AsynchronousSocketChannel channel,
                      AsynchronousChannelGroup group,
                      ByteBuffer readBuffer,
                      IVmsSerdesProxy serdesProxy) {
        this.me = me;
        this.state = State.NEW;
        this.consumerVms = consumerVms;

        // shared by many vms workers
        this.coordinatorQueue = coordinatorQueue;

        // this.vmsMetadata = vmsMetadata;
        this.channel = channel;
        this.group = group;

        // initialize the write buffer
        this.writeBufferPool = new LinkedBlockingDeque<>();
        this.writeBufferPool.addFirst( buildByteBuffer() );
        this.writeBufferPool.addFirst( buildByteBuffer() );

        this.readBuffer = readBuffer;
        this.serdesProxy = serdesProxy;

        this.logger = Logger.getLogger("vms-worker-"+consumerVms.toString());
        this.logger.setUseParentHandlers(true);

        // to allow the first thread to write
         this.WRITE_SYNCHRONIZER.add(DUMB);
    }

    public void initHandshakeProtocol(){

        // a vms has tried to connect
        if(this.channel != null) {
            this.state = CONNECTION_ESTABLISHED;
            this.processVmsIdentifier();
            this.readBuffer.clear();
            this.channel.read( this.readBuffer, null, new VmsReadCompletionHandler() );
            return;
        }

        // connect to starter vms
        logger.info("Attempting connection to "+consumerVms);
        try {
            this.channel = AsynchronousSocketChannel.open(this.group);
            this.channel.setOption(TCP_NODELAY, true);
            this.channel.setOption(SO_KEEPALIVE, true);

            try {
                this.channel.connect(this.consumerVms.asInetSocketAddress()).get();
            } catch (Exception e){
                logger.severe("Error on connecting: "+e.getMessage());
                return;
            }
            this.state = CONNECTION_ESTABLISHED;

            ByteBuffer writeBuffer = this.retrieveByteBuffer();
            try {
                // write presentation
                Presentation.writeServer(writeBuffer, this.me, true);
                writeBuffer.flip();
                this.WRITE_SYNCHRONIZER.take();
                this.channel.write(writeBuffer, writeBuffer, this.writeCompletionHandler);
            } catch(Exception e){
                logger.severe("Error on writing presentation: "+e.getMessage());
                return;
            }

            this.state = State.LEADER_PRESENTATION_SENT;

            // set read handler here
            this.channel.read( this.readBuffer, null, new VmsReadCompletionHandler() );

        } catch (Exception e) {
            this.logger.warning("Failed to connect to a known VMS: " + consumerVms);
            if (this.state == State.NEW) {
                // forget about it, let the vms connect then...

                this.state = State.CONNECTION_FAILED;
            } else if(this.state == CONNECTION_ESTABLISHED) {
                this.state = LEADER_PRESENTATION_SEND_FAILED;
                // check if connection is still online. if so, try again
                // otherwise, retry connection in a few minutes
                if(this.channel.isOpen()){
                    // try again? what is he problem?
                    logger.warning("It was not possible to send a presentation message, although the channel is open. The connection will be closed now.");
                    try { this.channel.close(); } catch (IOException ignored) { }
                } else {
                    this.logger.warning("It was not possible to send a presentation message and the channel is not open. Check the consumer VMS: " + consumerVms);
                }
            } else {
                this.logger.warning("Cannot find the root problem. Please have a look: "+e.getMessage());
            }

            // important for consistency of state (if debugging, good to see the code controls the thread state)
            this.stop();

        }

    }

    @Override
    public void run() {
        this.initHandshakeProtocol();
        this.eventLoop();
    }

    /**
     * Event loop. Put in another method to avoid a long run method
     */
    private void eventLoop() {
        while (this.isRunning()){
            try {
                Message workerMessage = this.workerQueue.take();
                switch (workerMessage.type()){
                    // in order of probability
                    case SEND_BATCH_OF_EVENTS -> this.sendBatchOfEvents(workerMessage, false);
                    case SEND_BATCH_OF_EVENTS_WITH_COMMIT_INFO -> this.sendBatchOfEvents(workerMessage, true);
                    case SEND_BATCH_COMMIT_COMMAND -> this.sendBatchCommitRequest(workerMessage);
                    case SEND_TRANSACTION_ABORT -> this.sendTransactionAbort(workerMessage);
                    case SEND_CONSUMER_SET -> this.sendConsumerSet(workerMessage);
                }
            } catch (InterruptedException e) {
                logger.warning("This thread has been interrupted. Cause: "+e.getMessage());
                this.stop();
            }
        }
    }

    @Override
    public BlockingDeque<TransactionEvent.Payload> transactionEventsPerBatch(long batch){
        return this.transactionEventsPerBatch.computeIfAbsent(batch, (x) -> new LinkedBlockingDeque<>());
    }

    @Override
    public BlockingQueue<Message> queue() {
        return this.workerQueue;
    }

    private void sendTransactionAbort(Message workerMessage) {
        TransactionAbort.Payload tidToAbort = workerMessage.asTransactionAbort();

        try {
            ByteBuffer writeBuffer = retrieveByteBuffer();
            TransactionAbort.write(writeBuffer, tidToAbort);
            writeBuffer.flip();
            this.WRITE_SYNCHRONIZER.take();
            this.channel.write(writeBuffer, writeBuffer, this.writeCompletionHandler);
            this.logger.warning("Transaction abort sent to: " + this.vmsNode.vmsIdentifier);
        } catch (InterruptedException e){
            if(channel.isOpen()){
                this.logger.warning("Transaction abort write has failed but channel is open. Trying to write again to: "+consumerVms+" in a while");
                this.workerQueue.add(workerMessage);
            } else {
                this.logger.warning("Transaction abort write has failed and channel is closed: "+this.consumerVms);
                this.stop(); // no reason to continue the loop
            }
        }

    }

    private void sendBatchCommitRequest(Message workerMessage) {
        BatchCommitCommand.Payload commitRequest = workerMessage.asBatchCommitCommand();
        try {
            ByteBuffer writeBuffer = retrieveByteBuffer();
            BatchCommitCommand.write(writeBuffer, commitRequest);
            writeBuffer.flip();
            this.WRITE_SYNCHRONIZER.take();
            this.channel.write(writeBuffer, writeBuffer, this.writeCompletionHandler);
            this.logger.info("Commit request sent to: " + this.vmsNode.vmsIdentifier);
        } catch (Exception e){
            if(channel.isOpen()){
                this.logger.warning("Commit request write has failed but channel is open. Trying to write again to: "+this.vmsNode.vmsIdentifier+" in a while");
                this.workerQueue.add(workerMessage);
            } else {
                this.logger.warning("Commit request write has failed and channel is closed: "+this.vmsNode.vmsIdentifier);
                this.stop(); // no reason to continue the loop
            }
        }
    }

    private void sendConsumerSet(Message workerMessage) {
        // the first or new information
        if(this.state == VMS_PRESENTATION_PROCESSED) {
            this.state = CONSUMER_SET_READY_FOR_SENDING;
            this.logger.info("Leader/"+vmsNode.vmsIdentifier+": Consumer set will be established for the first time: "+consumerVms);
        } else if(this.state == CONSUMER_EXECUTING){
            this.logger.info("Leader/"+vmsNode.vmsIdentifier+"Consumer set is going to be updated for: "+consumerVms);
        } else if(this.state == CONSUMER_SET_SENDING_FAILED){
            this.logger.info("Leader/"+vmsNode.vmsIdentifier+"Consumer set, another attempt to write to: "+consumerVms);
        } // else, nothing...

        String vmsConsumerSet = workerMessage.asVmsConsumerSet();
        try {
            ByteBuffer writeBuffer = this.retrieveByteBuffer();
            ConsumerSet.write(writeBuffer, vmsConsumerSet);
            writeBuffer.flip();
            this.WRITE_SYNCHRONIZER.take();
            this.channel.write(writeBuffer, writeBuffer, this.writeCompletionHandler);
            if (this.state == CONSUMER_SET_READY_FOR_SENDING) // or != CONSUMER_EXECUTING
                this.state = CONSUMER_EXECUTING;

        } catch (InterruptedException e){
            this.state = CONSUMER_SET_SENDING_FAILED;
            if (channel.isOpen()) {
                this.logger.warning("Write has failed but channel is open. Trying to write again to: " + consumerVms + " in a while");
                // just queue again
                this.workerQueue.add(workerMessage);
            } else {
                this.logger.warning("Write has failed and channel is closed: " + consumerVms);
                this.stop(); // no reason to continue the loop
            }
        }

    }

    /**
     * Reuses the thread from the socket thread pool, instead of assigning a specific thread
     * Removes thread context switching costs.
     * This thread should not block.
     * The idea is to decode the message and deliver back to socket loop as soon as possible
     * This thread must be set free as soon as possible, should not do long-running computation
     */
    private class VmsReadCompletionHandler implements CompletionHandler<Integer, Object> {

        // is it an abort, a commit response?
        // it cannot be replication because have opened another channel for that

        @Override
        public void completed(Integer result, Object connectionMetadata) {

            // decode message by getting the first byte
            byte type = readBuffer.get(0);
            readBuffer.position(1);

            switch (type) {

                case PRESENTATION -> {
                    if(vmsNode != null && !vmsNode.vmsIdentifier.contentEquals("unknown")){
                        // in the future it can be an update of the vms schema
                        logger.warning("Presentation already received from this VMS.");
                    } else {
                       state = VMS_PRESENTATION_RECEIVED;// for the first time
                    }
                    processVmsIdentifier();
                    state = VMS_PRESENTATION_PROCESSED;
                }

                // from all terminal VMSs involved in the last batch
                case BATCH_COMPLETE -> {
                    // don't actually need the host and port in the payload since we have the attachment to this read operation...
                    BatchComplete.Payload response = BatchComplete.read(readBuffer);
                    // must have a context, i.e., what batch, the last?
                    coordinatorQueue.add( new Coordinator.Message( Coordinator.Type.BATCH_COMPLETE, response));
                    // if one abort, no need to keep receiving
                    // actually it is unclear in which circumstances a vms would respond no... probably in case it has not received an ack from an aborted commit response?
                    // because only the aborted transaction will be rolled back
                }
                case BATCH_COMMIT_ACK -> {
                    BatchCommitAck.Payload response = BatchCommitAck.read(readBuffer);
                    logger.info("Just logging it, since we don't necessarily need to wait for that. "+response);
                    coordinatorQueue.add( new Coordinator.Message( Coordinator.Type.BATCH_COMMIT_ACK, response));
                }
                case TX_ABORT -> {
                    // get information of what
                    TransactionAbort.Payload response = TransactionAbort.read(readBuffer);
                    coordinatorQueue.add( new Coordinator.Message( Coordinator.Type.TRANSACTION_ABORT, response));
                }
                case EVENT ->
                        logger.info("New event received from VMS");
                case BATCH_OF_EVENTS -> //
                        logger.info("New batch of events received from VMS");
                default ->
                        logger.warning("Unknown message received.");

            }
            readBuffer.clear();
            channel.read( readBuffer, null, this );
        }

        @Override
        public void failed(Throwable exc, Object attachment) {
            if(state == LEADER_PRESENTATION_SENT){
                state = VMS_PRESENTATION_RECEIVE_FAILED;
                if(channel.isOpen()){
                    logger.warning("It was not possible to receive a presentation message, although the channel is open.");
                }
                logger.warning("It was not possible to receive a presentation message and the channel is not open. Check the consumer VMS: "+consumerVms);
            } else {
                if (channel.isOpen()) {
                    logger.warning("Read has failed but channel is open. Trying to read again from: " + consumerVms);

                } else {
                    logger.warning("Read has failed and channel is closed: " + consumerVms);
                }
            }

            readBuffer.clear();
            channel.read(readBuffer, null, this);
        }

    }

    private void processVmsIdentifier() {
        // always a vms
        this.readBuffer.position(2);
        this.vmsNode = Presentation.readVms(readBuffer, serdesProxy);
        this.state = State.VMS_PRESENTATION_PROCESSED;
        // let coordinator aware this vms worker already has the vms identifier
        this.coordinatorQueue.add(new Coordinator.Message( Coordinator.Type.VMS_IDENTIFIER, new VmsIdentifier(this.vmsNode, this)));
    }

    /**
     * Need to send the last batch too so the vms can safely start the new batch
     */
    private void sendBatchOfEvents(Message message, boolean includeCommitInfo) {
        BatchCommitInfo.Payload batchCommitInfo = message.asBatchOfEventsRequest();
        BlockingDeque<TransactionEvent.Payload> eventsToSend = this.transactionEventsPerBatch(batchCommitInfo.batch());
        boolean thereAreEventsToSend = eventsToSend != null && !eventsToSend.isEmpty();
        if(thereAreEventsToSend){
            if(includeCommitInfo){
                this.sendBatchedEventsWithCommitInfo(eventsToSend, batchCommitInfo);
            } else {
                this.sendBatchedEvents(eventsToSend);
            }
        } else if(includeCommitInfo){
            this.sendBatchCommitInfo(batchCommitInfo);
        }
    }

    private final List<TransactionEvent.Payload> events = new ArrayList<>();

    private void sendBatchCommitInfo(BatchCommitInfo.Payload batchCommitInfo){
        // then send only the batch commit info
        try {
            ByteBuffer writeBuffer = retrieveByteBuffer();
            BatchCommitInfo.write(writeBuffer, batchCommitInfo);
            writeBuffer.flip();
             this.WRITE_SYNCHRONIZER.take();
            this.channel.write(writeBuffer,writeBuffer, this.writeCompletionHandler);
        } catch (Exception e) {
            if(!this.channel.isOpen()) {
                this.vmsNode.off();
            }
            
        }
    }

    private ByteBuffer retrieveByteBuffer() throws InterruptedException {
        ByteBuffer bb = this.writeBufferPool.poll();
        if(bb != null) return bb;
        logger.info("New ByteBuffer will be created");
        return buildByteBuffer();
    }

    private static ByteBuffer buildByteBuffer(){
         return MemoryManager.getTemporaryDirectBuffer(DEFAULT_BUFFER_SIZE);
         // leads to several bugs =(
         // return ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
    }

    /**
     * While a write is in progress, it must wait for completion and then submit the next write.
     */
    private void sendBatchedEvents(BlockingDeque<TransactionEvent.Payload> eventsToSendToVms){
        eventsToSendToVms.drainTo(this.events);

        int count = this.events.size();
        int remaining = count;

        ByteBuffer writeBuffer;

        while(true) {
            try {
                writeBuffer = this.retrieveByteBuffer();
                remaining = BatchUtils.assembleBatchPayload(remaining, this.events, writeBuffer);

                writeBuffer.flip();
                // without this async handler, the bytebuffer arriving in the VMS can be corrupted
                // relying on future.get() yields corrupted buffer in the consumer
                this.logger.info("Leader: Submitting "+(count - remaining)+" events to "+vmsNode.vmsIdentifier);
                this.WRITE_SYNCHRONIZER.take();
                this.channel.write(writeBuffer, writeBuffer, this.writeCompletionHandler);
                count = remaining;

                if(remaining == 0) break;

                sleep_();

            } catch (Exception e) {
                logger.severe("Leader: Error on submitting "+count+" events to "+vmsNode.vmsIdentifier);
                // return events to the deque
                for(TransactionEvent.Payload event : this.events) {
                    eventsToSendToVms.offerFirst(event);
                }
                if(!this.channel.isOpen()){
                    this.vmsNode.off();
                    remaining = 0; // force exit loop
                }
            }

        }
    }

    private final Random random = new Random();

    /**
     * For some reason without sleeping, the bytebuffer gets corrupted in the consumer
     */
    private void sleep_(){
        // logger.info("Leader: Preparing another submission to: "+vmsNode.vmsIdentifier+" by thread "+Thread.currentThread().getId());
        // necessary to avoid buggy behavior: corrupted byte buffer. reason is unknown. maybe something related to operating system?
        try { sleep(random.nextInt(100) + 100); } catch (InterruptedException ignored) {}
    }

    /**
     * If the target VMS is a terminal in the current batch,
     * then the batch commit info must be appended
     */
    private void sendBatchedEventsWithCommitInfo(BlockingDeque<TransactionEvent.Payload> eventsToSendToVms, BatchCommitInfo.Payload batchCommitInfo){
        eventsToSendToVms.drainTo(this.events);
        int remaining = this.events.size();
        ByteBuffer writeBuffer;
        while(true) {
            try {
                writeBuffer = this.retrieveByteBuffer();
                remaining = BatchUtils.assembleBatchPayload(remaining, this.events, writeBuffer);

                if (remaining == 0) {
                    // now must append the batch commit info
                    // do we have space in the buffer?
                    if (writeBuffer.remaining() < BatchCommitInfo.size) {
                        // if not, send what we can for now
                        writeBuffer.flip();
                        this.WRITE_SYNCHRONIZER.take();
                        this.channel.write(writeBuffer,  writeBuffer, this.writeCompletionHandler);
                        // avoid corrupted buffer in consumer...
                        sleep_();

                        // get a new bb
                        writeBuffer = this.retrieveByteBuffer();
                        BatchCommitInfo.write(writeBuffer, batchCommitInfo);
                    } else {
                        BatchCommitInfo.write(writeBuffer, batchCommitInfo);

                        // update number of events
                        writeBuffer.mark();
                        int currCount = writeBuffer.getInt(1);
                        currCount++;
                        writeBuffer.putInt(1, currCount);
                        writeBuffer.reset();
                    }
                    writeBuffer.flip();
                    this.WRITE_SYNCHRONIZER.take();
                    this.channel.write(writeBuffer,  writeBuffer, this.writeCompletionHandler);
                    sleep_();
                    break;
                }

                writeBuffer.flip();
                this.WRITE_SYNCHRONIZER.take();
                this.channel.write(writeBuffer,  writeBuffer, this.writeCompletionHandler);
                sleep_();

            } catch (Exception e) {
                // return events to the deque
                for(TransactionEvent.Payload event : this.events) {
                    eventsToSendToVms.offerFirst(event);
                }
                if(!this.channel.isOpen()){
                    this.vmsNode.off();
                    break; // force exit loop
                }
            }
        }

    }

    private final WriteCompletionHandler writeCompletionHandler = new WriteCompletionHandler();

    private class WriteCompletionHandler implements CompletionHandler<Integer, ByteBuffer> {
        @Override
        public void completed(Integer result, ByteBuffer attachment) {
            logger.info("Leader: Message with size "+result+" has been sent to: "+vmsNode.vmsIdentifier+" by thread "+Thread.currentThread().getId());
            WRITE_SYNCHRONIZER.add(DUMB);
            attachment.clear();
            writeBufferPool.addLast(attachment);
        }
        @Override
        public void failed(Throwable exc, ByteBuffer attachment) {
            logger.severe("Leader: ERROR on writing batch of events to: "+vmsNode.vmsIdentifier);
            WRITE_SYNCHRONIZER.add(DUMB);
            attachment.clear();
            writeBufferPool.addLast(attachment);
        }
    }

}