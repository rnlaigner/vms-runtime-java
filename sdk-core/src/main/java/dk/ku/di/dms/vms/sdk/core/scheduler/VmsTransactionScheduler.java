package dk.ku.di.dms.vms.sdk.core.scheduler;

import dk.ku.di.dms.vms.modb.api.enums.TransactionTypeEnum;
import dk.ku.di.dms.vms.modb.common.data_structure.IdentifiableNode;
import dk.ku.di.dms.vms.modb.common.schema.network.transaction.TransactionEvent;
import dk.ku.di.dms.vms.modb.common.serdes.IVmsSerdesProxy;
import dk.ku.di.dms.vms.sdk.core.event.channel.IVmsInternalChannels;
import dk.ku.di.dms.vms.sdk.core.metadata.VmsTransactionMetadata;
import dk.ku.di.dms.vms.sdk.core.operational.*;
import dk.ku.di.dms.vms.web_common.runnable.StoppableRunnable;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import static java.util.logging.Logger.getLogger;

/**
 * The brain of the virtual microservice runtime
 * It consumes events, verifies whether a data operation is ready for execution
 * and dispatches them for execution. If an operation is not ready yet, given the
 * payload dependencies, it is stored in a waiting list until pending events arrive
 */
public class VmsTransactionScheduler extends StoppableRunnable {

    protected static final Logger logger = getLogger("VmsTransactionScheduler");

    // payload that cannot execute because some dependence need to be fulfilled
    // payload A < B < C < D
    // TODO check later (after HTM impl) if I can do it with a Hash...

    // Based on the transaction id (tid), I can find the task very fast
    private final Map<Long, List<VmsTransactionTask>> waitingTasksPerTidMap;

    private final Map<Long, VmsTransactionContext> transactionContextMap;

    // offset tracking for execution
    private OffsetTracker currentOffset;

    // offset tracking. i.e., cannot issue a task if predecessor transaction is not ready yet
    private final Map<Long, OffsetTracker> offsetMap;

    // map the last tid
    private final Map<Long, Long> lastTidToTidMap;

    /**
     * Thread pool for read-only queries
     */
    private final ExecutorService readTaskPool;

    /**
     * Thread pool for write-only and read-write queries
     */
    private final ExecutorService readWriteTaskPool;

    protected final IVmsInternalChannels vmsChannels;

    // mapping
    private final Map<String, VmsTransactionMetadata> transactionMetadataMap;

    private final Map<String, Class<?>> queueToEventMap;

    private final IVmsSerdesProxy serdes;

    public VmsTransactionScheduler(ExecutorService readTaskPool,
                                   IVmsInternalChannels vmsChannels,
                                   // (input) queue to transactions map
                                   Map<String, VmsTransactionMetadata> transactionMetadataMap,
                                   Map<String, Class<?>> queueToEventMap,
                                   IVmsSerdesProxy serdes){
        super();

        // thread pools
        this.readTaskPool = readTaskPool;
        this.readWriteTaskPool = Executors.newSingleThreadExecutor();

        // infra (come from external)
        this.transactionMetadataMap = transactionMetadataMap;
        this.vmsChannels = vmsChannels;
        this.queueToEventMap = queueToEventMap;
        this.serdes = serdes;

        // operational (internal control of transactions and tasks)
        this.waitingTasksPerTidMap = new HashMap<>();
        this.transactionContextMap = new HashMap<>();
        this.offsetMap = new TreeMap<>();
        this.lastTidToTidMap = new HashMap<>();

    }

    /**
     * Another way to implement this is make this a fine-grained task.
     * that is, a pool of available tasks for receiving and processing the
     * events instead of an infinite while loop.
     * virtual threads are a good choice: https://jdk.java.net/loom/
     * BUT, "Virtual threads help to improve the throughput of typical
     * server applications precisely because such applications consist
     * of a great number of concurrent tasks that spend much of their time waiting."
     * Which is not this case... we are not doing I/O to wait
     * But virtual threads can be beneficial to transactional tasks
     */
    @Override
    public void run() {

        logger.info("Scheduler has started.");

        initializeOffset();

        while(isRunning()) {

            processNewEvent();

            // why do we have another move offset here?
            // in case we start (or restart the VM service), we need to move the pointer only when it is safe
            // we cannot position the offset to the actual next, because we may not have received the next payload yet
            moveOffsetPointerIfNecessary();

            // let's dispatch all the events ready
            dispatchReadyTasksForExecution();

            // an idea to optimize is to pass a completion handler to the thread
            // the task thread then update the task result list
            processTaskResult();

        }

    }

//    private TransactionEvent.Payload take(){
//        if(vmsChannels.transactionInputQueue().size() > 0) return vmsChannels.transactionInputQueue().poll();
//        return null;
//    }

    protected void initializeOffset(){
        currentOffset = new OffsetTracker(0, 1);
        currentOffset.signalTaskFinished();
        offsetMap.put(0L, currentOffset);
        logger.info("Offset initialized");
    }

    /**
     * TODO we have to deal with failures
     *  not only container failures but also constraints being violated
     */
    protected void processTaskResult() {

        var txCtx = this.transactionContextMap.get( this.currentOffset.tid() );
        if( txCtx == null ) return;

        List<Future<VmsTransactionTaskResult>> list = txCtx.submittedTasks;

        for(int i = list.size() - 1; i >= 0; --i){

            Future<VmsTransactionTaskResult> resultFuture = list.get(i);

            if(!resultFuture.isDone()) continue;

            VmsTransactionTaskResult res;
            try {

                res = resultFuture.get();
                txCtx.resultTasks.add(res);

                if(res.status() == VmsTransactionTaskResult.Status.SUCCESS) {

                    currentOffset.signalTaskFinished();

                    list.remove(i);

                    if (currentOffset.status() == OffsetTracker.OffsetStatus.FINISHED_SUCCESSFULLY) {

                        // now can send all to output queue (coordinator)
                        boolean atLeastOneHasPayload = false;
                        for(var result : txCtx.resultTasks) {
                            if(result.result() != null){
                                atLeastOneHasPayload = true;
                                vmsChannels.transactionOutputQueue().add(result.result());
                            }

                        }

                        // what if all the results of a tid are void?
                        if(!atLeastOneHasPayload){
                            vmsChannels.transactionOutputQueue().add( new OutboundEventResult(
                                    currentOffset.tid(),
                                    null,
                                    null,
                                    true // must be marked as terminal since there is no output
                            ));
                        }

                        this.transactionContextMap.remove(this.currentOffset.tid());

                    }

                } else {
                    currentOffset.signalError();
                    // TODO must deal with errors (i.e., abort)
                }

            } catch (InterruptedException | ExecutionException ignored) {
                logger.warning("A task supposedly done returned an exception.");
            }

        }

    }

    protected void processNewEvent(){

        TransactionEvent.Payload transactionalEvent; // take();
        try {
            transactionalEvent = vmsChannels.transactionInputQueue().poll(15000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return;
        }

        // in case there is a new payload to process
        if(transactionalEvent == null) {
            return;
        }

        // have I created the task already?
        // in other words, a previous payload for the same tid have been processed?
        if(this.waitingTasksPerTidMap.containsKey(transactionalEvent.tid())){

            List<VmsTransactionTask> notReadyTasks = this.waitingTasksPerTidMap.get( transactionalEvent.tid() );

            VmsTransactionMetadata transactionMetadata = this.transactionMetadataMap.get(transactionalEvent.event());

            VmsTransactionTask task;
            for( int i = notReadyTasks.size() - 1; i >= 0; i-- ){

                task = notReadyTasks.get(i);
                // if here means the exact parameter position

                Class<?> clazz = this.queueToEventMap.get(transactionalEvent.event());

                Object input = this.serdes.deserialize(transactionalEvent.payload(), clazz);

                task.putEventInput( transactionMetadata.signatures.get(i).id(), input );

                // check if the input is completed
                if(task.isReady()){
                    notReadyTasks.remove(i);

                    // get transaction context
                    var txContext = this.transactionContextMap.get( task.tid() );

                    switch (task.getTransactionType()){
                        case RW -> txContext.readWriteTasks.add(task);
                        case R -> txContext.readTasks.add(task);
                        default -> txContext.writeTasks.add(task); // to avoid additional checking
                    }

                }

            }

            if(notReadyTasks.isEmpty()){
                this.waitingTasksPerTidMap.remove( transactionalEvent.tid() );
            }

        } else if (this.offsetMap.get(transactionalEvent.tid()) == null) {

            // new tid: create it and put it in the payload list

            VmsTransactionMetadata transactionMetadata = transactionMetadataMap.get(transactionalEvent.event());

            // create the offset
            this.offsetMap.put( transactionalEvent.tid(),
                    new OffsetTracker(transactionalEvent.tid(), transactionMetadata.signatures.size()));

            // mark the last tid so we can get the next to execute when appropriate
            this.lastTidToTidMap.put( transactionalEvent.lastTid(), transactionalEvent.tid() );

            VmsTransactionTask task;

            // create the vms transaction context
            VmsTransactionContext txContext = new VmsTransactionContext(
                    transactionMetadata.numReadTasks,
                    transactionMetadata.numReadWriteTasks,
                    transactionMetadata.numWriteTasks);

            this.transactionContextMap.put( transactionalEvent.tid(), txContext );

            for (IdentifiableNode<VmsTransactionSignature> node : transactionMetadata.signatures) {

                VmsTransactionSignature signature = node.object();
                task = new VmsTransactionTask(
                        transactionalEvent.tid(),
                        node.object(),
                        signature.inputQueues().length
                        );

                Class<?> clazz = this.queueToEventMap.get(transactionalEvent.event());

                Object input = this.serdes.deserialize(transactionalEvent.payload(), clazz);

                // put the input event on the correct slot (the correct parameter position)
                task.putEventInput(node.id(), input);

                // fast path: in case only one payload
                if (task.isReady()) {

                    // we cannot submit now because it may not be the time for the tid
                    switch (node.object().type()){
                        case RW -> txContext.readWriteTasks.add(task);
                        case R -> txContext.readTasks.add(task);
                        default -> txContext.writeTasks.add(task); // to avoid additional checking
                        // case W -> flock.writeTasks.add(task);
                    }

                } else {
                    var list = this.waitingTasksPerTidMap.putIfAbsent
                            (task.tid(), new ArrayList<>(transactionMetadata.numTasksWithMoreThanOneInput));
                    list.add( task );
                }

            }

        } else {

            logger.warning("Analyze this case....");

        }

    }

    private void dispatchTaskList(VmsTransactionContext txCtx, List<VmsTransactionTask> tasks){

        int index = tasks.size() - 1;
        while (index >= 0) {

            VmsTransactionTask task = tasks.get(index);

            // later, we may have precedence between tasks of the same tid
            // i.e., right now any ordering is fine

            // task.setIdentifier( idx ); // arbitrary unique identifier
            if (task.getTransactionType() == TransactionTypeEnum.R) {
                // submit
                txCtx.submittedTasks.add( this.readTaskPool.submit(task) );
            } else {
                // always single thread guaranteed by the scheduler
                txCtx.submittedTasks.add( this.readWriteTaskPool.submit(task) );
            }

            tasks.remove(index);

            index--;

        }

    }

    /**
     * Tasks are dispatched respecting the single-thread model for RW tasks
     * In other words, no RW tasks for the same transaction can be scheduled
     * concurrently. One at a time. Read tasks can be scheduled concurrently.
     *
     */
    protected void dispatchReadyTasksForExecution() {

        // do we have ready tasks for the current TID?
        var txCtx = this.transactionContextMap.get( this.currentOffset.tid() );
        if( txCtx != null ){
            int numRead = txCtx.readTasks.size();
            int numReadWrite = txCtx.readWriteTasks.size();

            if(numRead > 0)
                dispatchTaskList( txCtx, txCtx.readTasks );

            if(numReadWrite > 0)
                dispatchTaskList( txCtx, txCtx.readWriteTasks );

        }

        // must store submitted tasks in case we need to re-execute (iff not PK, FK).
        //          what could go wrong?
        //           (i) a constraint not being met, would need to abort
        //           (ii) lack of machine resources. can we do something in this case?

    }

    /**
     * Assumption: we always have at least one offset in the list. of course, I could do this by design but the code guarantee that
     * Is it safe to move the offset pointer? this method takes care of that
     */
    protected void moveOffsetPointerIfNecessary(){

        // if next is the right one ---> the concept of "next" may change according to recovery from failures and aborts
        if(this.currentOffset.status() == OffsetTracker.OffsetStatus.FINISHED_SUCCESSFULLY
                && this.lastTidToTidMap.get( currentOffset.tid() ) != null ){

            var nextTid = offsetMap.get( this.lastTidToTidMap.get( this.currentOffset.tid() ) );

            // has the "next" arrived already?
            if(nextTid == null) return;

            // should be here to remove the tid 0. the tid 0 never receives a result task
            this.offsetMap.remove( currentOffset.tid() );

            // don't need anymore
            this.lastTidToTidMap.remove( currentOffset.tid() );

            this.currentOffset = nextTid;

        }

    }

}
