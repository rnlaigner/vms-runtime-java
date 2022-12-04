package dk.ku.di.dms.vms.modb.btree.noheap;

import dk.ku.di.dms.vms.modb.common.memory.MemoryManager;
import dk.ku.di.dms.vms.modb.common.memory.MemoryRefNode;
import dk.ku.di.dms.vms.modb.definition.key.IKey;
import dk.ku.di.dms.vms.modb.storage.iterator.IRecordIterator;
import dk.ku.di.dms.vms.modb.storage.iterator.non_unique.RecordBucketIterator;
import dk.ku.di.dms.vms.modb.storage.record.AppendOnlyBuffer;
import dk.ku.di.dms.vms.modb.storage.record.OrderedRecordBuffer;

public class LeafNode implements INode {

//    public static final byte identifier = 1;

    public static final int leafEntrySize = OrderedRecordBuffer.entrySize;
//
//    public static final int numberRecordsLeafNode =
//            ( MemoryUtils.UNSAFE.pageSize() + (2 * Long.BYTES) )

    public final int pageSize;
    public final int branchingFactor;

    public final OrderedRecordBuffer buffer;

    // TODO could be encoded in the last entry of the buffer
    public LeafNode next;

    private LeafNode(int pageSize, OrderedRecordBuffer buffer){
        this.pageSize = pageSize;
        this.branchingFactor = (pageSize / leafEntrySize) - 1;
        this.buffer = buffer;
    }

    public static LeafNode leaf(int pageSize){
        MemoryRefNode memoryRefNode = MemoryManager.getTemporaryDirectMemory( pageSize );
        AppendOnlyBuffer buffer = new AppendOnlyBuffer( memoryRefNode.address, memoryRefNode.bytes );
        return new LeafNode(pageSize, new OrderedRecordBuffer( buffer) );
    }
    
    @Override
    public int lastKey() {
        return this.buffer.getLastKey();
    }

    @Override
    public INode insert(IKey key, long srcAddress) {
        if(this.buffer.size() == this.branchingFactor){
            INode newNode = overflow();
            newNode.insert(key, srcAddress);
            return newNode;
        }
        this.buffer.insert(key, srcAddress);
        return null;
    }

    @Override
    public IRecordIterator<Long> iterator() {
        return new RecordBucketIterator(this.buffer);
    }

    private INode overflow() {
        LeafNode leafNode = LeafNode.leaf( this.pageSize );

        // TODO copy records to new buffer (from half + 1 to last).
        // MemoryUtils.UNSAFE.copyMemory( this.buffer.address(), rightBuffer.address(), (long) (this.branchingFactor - half) * nonLeafEntrySize );

        leafNode.next = this.next;
        this.next = leafNode;

        return leafNode;
    }

}