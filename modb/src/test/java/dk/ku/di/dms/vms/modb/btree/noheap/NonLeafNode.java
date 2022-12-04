package dk.ku.di.dms.vms.modb.btree.noheap;

import dk.ku.di.dms.vms.modb.common.memory.MemoryManager;
import dk.ku.di.dms.vms.modb.common.memory.MemoryRefNode;
import dk.ku.di.dms.vms.modb.common.memory.MemoryUtils;
import dk.ku.di.dms.vms.modb.definition.key.IKey;
import dk.ku.di.dms.vms.modb.storage.iterator.IRecordIterator;
import dk.ku.di.dms.vms.modb.storage.record.AppendOnlyBuffer;
import dk.ku.di.dms.vms.modb.storage.record.OrderedRecordBuffer;

/**
 * how to represent the leaf nodes? with {@link OrderedRecordBuffer}
 * how to represent the internal (and parent) nodes? a proper data structure:
 * every key node has address left, right
 * sequentially put key by key
 * { key value, left node, right node }
 * { INT, LONG, LONG }
 * To allow for fast search in the keys....
 * But if the integer are sequentially placed, it is just a matter of doing log n search
 *
 */
public class NonLeafNode implements INode {

    // to be used when writing to disk
//    public static final byte identifier = 0;

    public static final int nonLeafEntrySize = Integer.BYTES; // + (2 * Long.BYTES);

    public final int pageSize;
    public final int branchingFactor;

    public final AppendOnlyBuffer buffer;

//    private long first;
//    private long last;
//    private long half;

    public int nKeys;

    public final INode[] children;

    // public static final int deltaPrevious = Integer.BYTES;
    public static final int deltaNext = Integer.BYTES; // + Long.BYTES;

    public final boolean parent;

    private NonLeafNode(int pageSize){
        this.pageSize = pageSize;
        this.branchingFactor = (pageSize / nonLeafEntrySize) - 1;
        this.parent = true;
        MemoryRefNode memoryRefNode = MemoryManager.getTemporaryDirectMemory( pageSize );
        this.buffer = new AppendOnlyBuffer( memoryRefNode.address, memoryRefNode.bytes );
        this.nKeys = 0;
        this.children = new INode[this.branchingFactor + 1]; // + 1 because of leaf nodes
        this.children[0] = LeafNode.leaf( this.pageSize );
    }

    public static NonLeafNode parent(int pageSize){
//        NonLeafNode parent =
        return new NonLeafNode(pageSize);
        // parent.leafSize = leafSize; // spread over internal nodes
        // parent.branchingFactor = branchingFactor;
        // allocate another append only buffer and then an ordered record buffer
        // parent.children[0] = OrderedRecordBuffer
        // return parent;
    }

    private NonLeafNode(int pageSize, int nKeys, INode[] children, AppendOnlyBuffer buffer){
        this.pageSize = pageSize;
        this.branchingFactor = (pageSize / nonLeafEntrySize) - 1;
        this.buffer = buffer;
        this.parent = false;
        this.nKeys = nKeys;
        this.children = children;
    }

    public static NonLeafNode internal(int pageSize, int nKeys, INode[] children, AppendOnlyBuffer buffer){
        NonLeafNode parent = new NonLeafNode(pageSize, nKeys, children, buffer);
        return parent;
    }

    @Override
    public int lastKey() {
        return MemoryUtils.UNSAFE.getInt( this.buffer.address() * nKeys );
    }

    @Override
    public INode insert(IKey key, long srcAddress) {

        // find the node

        long currAddr = this.buffer.address();

        int i;
        for (i = 0; i < this.nKeys; i++) {

            int currKey = MemoryUtils.UNSAFE.getInt(currAddr);

            if (currKey > key.hashCode())
                break;

            currAddr = MemoryUtils.UNSAFE.getLong(currAddr + deltaNext);
        }

        INode newNode = children[i].insert(key, srcAddress);

        if (newNode != null) {

            this.children[i + 1] = newNode;
            MemoryUtils.UNSAFE.putInt( currAddr, children[i].lastKey() );
            nKeys++;

            if(this.nKeys == this.branchingFactor) {
                // overflow
                return overflow();
            }

        }

        return null;

    }

    private INode overflow() {

        int half = (int) Math.ceil((double)this.branchingFactor - 1) / 2;

        // truncate left keys
        this.nKeys = half;

        MemoryRefNode memoryRefNode = MemoryManager.getTemporaryDirectMemory( this.pageSize );
        AppendOnlyBuffer rightBuffer = new AppendOnlyBuffer( memoryRefNode.address, memoryRefNode.bytes );

        // copy keys to new buffer (from truncated offset + 1 to branching factor)
        MemoryUtils.UNSAFE.copyMemory( this.buffer.address(), rightBuffer.address(), (long) (this.branchingFactor - half) * nonLeafEntrySize );

        INode[] childrenRight = new INode[this.branchingFactor + 1];

        int ci = 0;
        for(int i = half + 1; i < this.branchingFactor; i++){
            childrenRight[ci] = this.children[i];
            ci++;
            this.children[i] = null;
        }

        return NonLeafNode.internal( this.pageSize, this.branchingFactor - half, childrenRight, rightBuffer );

    }

    /**
     * Encapsulates many iterators.
     * The iterators of the leaf nodes.
     * @return an iterator of the respective leaf nodes
     */
    public IRecordIterator<Long> iterator(){
        return this.children[1].iterator();
//        for(INode node : this.children){
//            return node.iterator();
//        }
//        return null;
    }

}