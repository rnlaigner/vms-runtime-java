package dk.ku.di.dms.vms.sdk.embed.scheduler;

import dk.ku.di.dms.vms.modb.common.serdes.IVmsSerdesProxy;
import dk.ku.di.dms.vms.modb.definition.Catalog;
import dk.ku.di.dms.vms.modb.transaction.TransactionFacade;
import dk.ku.di.dms.vms.sdk.core.metadata.VmsTransactionMetadata;
import dk.ku.di.dms.vms.sdk.core.scheduler.VmsTransactionScheduler;
import dk.ku.di.dms.vms.sdk.embed.channel.VmsEmbedInternalChannels;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * For embedded scenario, we can have access to {@link TransactionFacade}
 */
public class EmbedVmsTransactionScheduler extends VmsTransactionScheduler {

    private final Catalog catalog;

    private final VmsEmbedInternalChannels vmsChannels;

    public EmbedVmsTransactionScheduler(ExecutorService vmsAppLogicTaskPool,
                                        VmsEmbedInternalChannels vmsChannels,
                                        Map<String, VmsTransactionMetadata> eventToTransactionMap,
                                        Map<String, Class<?>> queueToEventMap,
                                        IVmsSerdesProxy serdes,
                                        Catalog catalog) {
        super(vmsAppLogicTaskPool, vmsChannels, eventToTransactionMap, queueToEventMap, serdes);
        this.catalog = catalog;
        this.vmsChannels = vmsChannels;
    }

    @Override
    public void run() {

        logger.info("Scheduler has started.");

        initializeOffset();

        while(isRunning()) {

            processNewEvent();

            moveOffsetPointerIfNecessary();

            // process batch before sending tasks to execution
            processBatchCommit();

            dispatchReadyTasksForExecution();

            processTaskResult();

        }

    }

    private void processBatchCommit() {

        if(!this.vmsChannels.batchContextQueue().isEmpty()){

            BatchContext currentBatch = this.vmsChannels.batchContextQueue().poll();

            currentBatch.setStatus(BatchContext.Status.IN_PROGRESS);

            // of course I do not need to stop the scheduler on commit
            // I need to make access to the data versions data race free
            // so new transactions get data versions from the version map or the store
            TransactionFacade.log(currentBatch.lastTid, catalog);

            currentBatch.setStatus(BatchContext.Status.COMMITTED);

        }

    }

}
