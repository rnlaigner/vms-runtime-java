package dk.ku.di.dms.vms.modb.query.planner.operators.count;

import dk.ku.di.dms.vms.modb.common.memory.MemoryRefNode;
import dk.ku.di.dms.vms.modb.definition.key.IKey;
import dk.ku.di.dms.vms.modb.index.interfaces.ReadOnlyBufferIndex;
import dk.ku.di.dms.vms.modb.query.planner.filter.FilterContext;
import dk.ku.di.dms.vms.modb.storage.iterator.IRecordIterator;

import java.util.HashMap;
import java.util.Map;

/**
 * No projecting any other column for now
 * To make reusable, internal state must be made ephemeral
 */
public class IndexCountDistinct extends AbstractCount {

    private static class EphemeralState {
        private int count;
        // hashed by the values in the distinct clause
        private final Map<Integer, Integer> valuesSeen;

        private EphemeralState() {
            this.count = 0;
            this.valuesSeen = new HashMap<>();
        }
    }

    public IndexCountDistinct(ReadOnlyBufferIndex<IKey> index) {
        super(index, Integer.BYTES);
    }

    /**
     * Can be reused across different distinct columns
     */
    public MemoryRefNode run(int distinctColumnIndex, FilterContext filterContext, IKey... keys){

        EphemeralState state = new EphemeralState();

        IRecordIterator<IKey> iterator = this.index.iterator(keys);
        while(iterator.hasElement()){
            if(index.checkCondition(iterator, filterContext)){
                Object val = index.record(iterator)[distinctColumnIndex];
                if( !state.valuesSeen.containsKey(val.hashCode())) {
                    state.count++;
                    state.valuesSeen.put(val.hashCode(), 1);
                }
            }
            iterator.next();
        }

        append(state.count);
        return memoryRefNode;

    }

}
