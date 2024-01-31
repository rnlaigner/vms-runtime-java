package dk.ku.di.dms.vms.modb.query.execution.operators;

import dk.ku.di.dms.vms.modb.common.memory.MemoryManager;
import dk.ku.di.dms.vms.modb.common.memory.MemoryRefNode;
import dk.ku.di.dms.vms.modb.query.execution.operators.join.UniqueHashJoinWithProjection;
import dk.ku.di.dms.vms.modb.query.execution.operators.min.IndexGroupByMinWithProjection;
import dk.ku.di.dms.vms.modb.query.execution.operators.scan.AbstractScan;
import dk.ku.di.dms.vms.modb.query.execution.operators.scan.FullScanWithProjection;
import dk.ku.di.dms.vms.modb.query.execution.operators.scan.IndexScanWithProjection;
import dk.ku.di.dms.vms.modb.storage.record.AppendOnlyBuffer;

/**
 * Used for simple queries.
 * This can speed up most OLTP workloads because the number of function calls
 * is reduced, since there is no data being passed along different operators.
 */
public abstract class AbstractSimpleOperator {

    // the first node (but last to be acquired) of the memory segment nodes
    protected MemoryRefNode memoryRefNode = null;

    protected AppendOnlyBuffer currentBuffer;

    protected final int entrySize;

    public AbstractSimpleOperator(int entrySize) {
        this.entrySize = entrySize;
    }

    /**
     * Just abstracts on which memory segment a result will be written to
     * Default method. Operators can create their own
     */
    protected void ensureMemoryCapacity(){
        if(this.currentBuffer != null && (this.currentBuffer.size() - (this.currentBuffer.address() - this.currentBuffer.nextOffset())) > this.entrySize){
            return;
        }
        // else, get a new memory segment
        MemoryRefNode claimed = MemoryManager.getTemporaryDirectMemory();
        claimed.next = this.memoryRefNode;
        this.memoryRefNode = claimed;
        this.currentBuffer = new AppendOnlyBuffer(claimed.address(), claimed.bytes());
    }

    /**
     * For operators that don't know the amount of records from start
     * @param size the size needed next to allocate a new tuple
     */
    protected void ensureMemoryCapacity(int size){
        if(this.currentBuffer != null && this.currentBuffer.size() - this.currentBuffer.address() > size) {
            return;
        }
        // else, get a new memory segment
        MemoryRefNode claimed = MemoryManager.getTemporaryDirectMemory(size);
        claimed.next = this.memoryRefNode;
        this.memoryRefNode = claimed;
        this.currentBuffer = new AppendOnlyBuffer(claimed.address(), claimed.bytes());
    }

    // must be overridden by the concrete operators
    public boolean isFullScan(){
        return false;
    }

    public boolean isIndexAggregationScan(){
        return false;
    }

    public boolean isIndexScan(){
        return false;
    }

    public boolean isHashJoin() { return false; }

    public IndexGroupByMinWithProjection asIndexAggregationScan(){
        throw new IllegalStateException("No index scan operator");
    }

    public IndexScanWithProjection asIndexScan(){
        throw new IllegalStateException("No index scan operator");
    }

    public FullScanWithProjection asFullScan(){
        throw new IllegalStateException("No full scan operator");
    }

    public AbstractScan asScan(){
        throw new IllegalStateException("No abstract scan operator");
    }

    public UniqueHashJoinWithProjection asHashJoin() { throw new IllegalStateException("No hash join operator"); }

}
