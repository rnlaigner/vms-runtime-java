package dk.ku.di.dms.vms.modb.transaction.multiversion.index;

import dk.ku.di.dms.vms.modb.definition.key.IKey;
import dk.ku.di.dms.vms.modb.definition.key.KeyUtils;
import dk.ku.di.dms.vms.modb.definition.key.SimpleKey;
import dk.ku.di.dms.vms.modb.index.interfaces.ReadWriteIndex;
import dk.ku.di.dms.vms.modb.transaction.multiversion.WriteType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper of a non unique index for multi versioning concurrency control
 */
public final class NonUniqueSecondaryIndex implements IMultiVersionIndex {

    // pointer to primary index
    // necessary because of transaction, concurrency control
    private final PrimaryIndex primaryIndex;

    // a non-unique hash index
    private final ReadWriteIndex<IKey> underlyingIndex;

    // key: formed by secondary indexed columns
    // value: the corresponding pks
    private final Map<IKey, Set<IKey>> writesCache;

    private static class WriteNode {
        public IKey secKey;
        public IKey pk;
        public WriteNode next;
        public WriteType type; // FIXME if always writes on append cache, no need for type
        public WriteNode(IKey secKey, IKey pk, WriteType type) {
            this.secKey = secKey;
            this.pk = pk;
            this.type = type;
        }
    }

    private final ThreadLocal<WriteNode> KEY_WRITES = new ThreadLocal<>();

     private static final Deque<WriteNode> writeNodeBuffer = new ArrayDeque<>();

    public NonUniqueSecondaryIndex(PrimaryIndex primaryIndex, ReadWriteIndex<IKey> underlyingIndex) {
        this.primaryIndex = primaryIndex;
        this.underlyingIndex = underlyingIndex;
        this.writesCache = new ConcurrentHashMap<>();

        // initialize write node buffer with 10 elements
        IKey key = SimpleKey.of(0);
        for(int i = 0; i < 10; i++){
            writeNodeBuffer.add( new WriteNode(key,key,WriteType.INSERT) );
        }

    }

    public ReadWriteIndex<IKey> getUnderlyingIndex(){
        return this.underlyingIndex;
    }

//    @Override
//    public IRecordIterator<IKey> iterator(IKey[] keys) {
//
//    }

    /**
     * The semantics of this method:
     * The bucket must have at least one record
     * Writers must read their writes.
     */
//    @Override
//    public boolean exists(IKey key) {
//
//        // find record in secondary index
//        if(this.writesCache.get(key) == null){
//            return this.underlyingSecondaryIndex.exists(key);
//        }
//
//        return true;
//
//        // retrieve PK from record
//
//        // make sure record exists in PK
//
//        // if not, delete from sec idx and return false
//
//        // if so, return yes
//
//    }

    /**
     * Called by the primary key index
     * In this method, the secondary key is formed
     * and then cached for later retrieval.
     * A secondary key point to several primary keys in the primary index
     * @param primaryKey may have many secIdxKey associated
     */
    public void appendDelta(IKey primaryKey, Object[] record){
        IKey secIdxKey = KeyUtils.buildRecordKey( this.underlyingIndex.columns(), record );
        WriteNode writeNode = getWriteNode(secIdxKey, primaryKey, WriteType.INSERT);
        updateTransactionWriteSet(writeNode);
        Set<IKey> pkSet = this.writesCache
                .computeIfAbsent(secIdxKey, k -> ConcurrentHashMap.newKeySet());
        pkSet.add(primaryKey);
    }

    private void updateTransactionWriteSet(WriteNode writeNode) {
        WriteNode latest = KEY_WRITES.get();
        if( latest != null ) {
            writeNode.next = latest;
            KEY_WRITES.set(writeNode);
        }
    }

    private static WriteNode getWriteNode(IKey secIdxKey, IKey primaryKey, WriteType type) {
        WriteNode writeNode = writeNodeBuffer.poll();
        if(writeNode == null) {
            writeNode = new WriteNode(secIdxKey, primaryKey, type );
        } else {
            writeNode.secKey = secIdxKey;
            writeNode.pk = primaryKey;
            writeNode.type = type;
            writeNode.next = null;
        }
        return writeNode;
    }

    @Override
    public void undoTransactionWrites(){
        WriteNode currentNode = KEY_WRITES.get();
        while (currentNode != null){
            if(currentNode.type == WriteType.INSERT)
                this.writesCache.get(currentNode.secKey).remove(currentNode.pk);
            currentNode = currentNode.next;
        }
    }

    @Override
    public void installWrites() {
        // TODO finish must consider inserts and deletes
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean insert(IKey key, Object[] record) {
        this.underlyingIndex.insert(key, record);
        // throw new RuntimeException("Not implemented");
        return true;
    }

    @Override
    public boolean update(IKey key, Object[] record) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean remove(IKey key) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Object[] lookupByKey(IKey key) {
        throw new RuntimeException("Not implemented");
    }

    /**
     * An iterator over a secondary index must consider the PK
     */
    public void delete(Object[] record) {
        IKey secIdxKey = KeyUtils.buildRecordKey( this.underlyingIndex.columns(), record );
        //this.writesCache.remove(secIdxKey);
        // also delete from underlying?
        //this.underlyingIndex.delete(secIdxKey);
        WriteNode writeNode = getWriteNode( secIdxKey, null, WriteType.DELETE );
        updateTransactionWriteSet(writeNode);
    }

}
