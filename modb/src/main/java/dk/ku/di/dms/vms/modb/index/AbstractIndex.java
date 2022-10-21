package dk.ku.di.dms.vms.modb.index;

import dk.ku.di.dms.vms.modb.common.memory.MemoryUtils;
import dk.ku.di.dms.vms.modb.definition.Schema;
import dk.ku.di.dms.vms.modb.definition.key.IKey;
import dk.ku.di.dms.vms.modb.definition.key.SimpleKey;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Base implementation of an index
 * @param <K> extends {@link IKey}
 * Inspiration from:
 * <a href="https://github.com/apache/flink/blob/master/flink-core/src/main/java/org/apache/flink/core/memory/MemorySegment.java">...</a>
 * <a href="https://stackoverflow.com/questions/24026918/java-nio-bytebuffer-allocatedirect-size-limit-over-the-int">...</a>
 */
public abstract class AbstractIndex<K> implements ReadWriteIndex<K> {

    protected static final jdk.internal.misc.Unsafe UNSAFE = MemoryUtils.UNSAFE;

    protected final int[] columns;

    // to speed up queries, so the filters can be build on flight
    protected final HashSet<Integer> columnsHash;

    private final IIndexKey key;

    private final int hashCode;

    // respective table of this index
    protected final Schema schema;

    public AbstractIndex(Schema schema, int... columnsIndex) {
        this.schema = schema;
        this.columns = columnsIndex;
        if(columnsIndex.length == 1) {
            this.hashCode = columnsIndex[0];
        } else {
            this.hashCode = Arrays.hashCode(columnsIndex);
        }
        this.key = SimpleKey.of(this.hashCode);
        this.columnsHash = new HashSet<>(columns.length);
        for(int i : columnsIndex) this.columnsHash.add(i);
    }

    // use bitwise comparison to find whether a given index exists for such columns
    // https://stackoverflow.com/questions/8504288/java-bitwise-comparison-of-a-byte/8504393
    @Override
    public int hashCode(){
        return this.hashCode;
    }

    @Override
    public int[] columns(){
        return this.columns;
    }

    @Override
    public boolean containsColumn(int columnPos) {
        return columnsHash.contains(columnPos);
    }

    @Override
    public IIndexKey key(){
        return this.key;
    }

    public abstract int size();

    @Override
    public Schema schema(){
        return this.schema;
    }

}
