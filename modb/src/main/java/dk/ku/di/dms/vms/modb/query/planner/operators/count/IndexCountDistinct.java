package dk.ku.di.dms.vms.modb.query.planner.operators.count;

import dk.ku.di.dms.vms.modb.common.type.DataType;
import dk.ku.di.dms.vms.modb.storage.memory.DataTypeUtils;
import dk.ku.di.dms.vms.modb.definition.key.IKey;
import dk.ku.di.dms.vms.modb.index.AbstractIndex;
import dk.ku.di.dms.vms.modb.index.IndexTypeEnum;
import dk.ku.di.dms.vms.modb.index.non_unique.NonUniqueHashIndex;
import dk.ku.di.dms.vms.modb.index.unique.UniqueHashIndex;
import dk.ku.di.dms.vms.modb.query.planner.operators.AbstractOperator;
import dk.ku.di.dms.vms.modb.query.planner.filter.FilterContext;
import dk.ku.di.dms.vms.modb.storage.iterator.RecordBucketIterator;
import dk.ku.di.dms.vms.modb.storage.memory.MemoryRefNode;

import java.util.HashMap;
import java.util.Map;

/**
 * No projecting any other column for now
 */
public class IndexCountDistinct extends AbstractOperator {

    private final AbstractIndex<IKey> index;

    private final FilterContext filterContext;

    private final IKey[] keys;

    private int count;

    // hashed by the values in the distinct clause
    private final Map<int,int> valuesSeen;

    private final int distinctColumnIndex;

    public IndexCountDistinct(int id, AbstractIndex<IKey> index,
                      FilterContext filterContext,
                      int distinctColumnIndex, // today only support for one
                      IKey... keys) {
        super(Integer.BYTES);
        this.index = index;
        this.filterContext = filterContext;
        this.keys = keys;
        this.count = 0;
        this.valuesSeen = new HashMap<int,int>();
        this.distinctColumnIndex = distinctColumnIndex;
    }

    public MemoryRefNode run(){

        DataType dt = this.index.getTable().getSchema().getColumnDataType(distinctColumnIndex);

        if(index.getType() == IndexTypeEnum.UNIQUE){

            UniqueHashIndex cIndex = index.asUniqueHashIndex();
            long address;
            for(IKey key : keys){
                address = cIndex.retrieve(key);
                Object val = DataTypeUtils.getValue( dt, address );
                if(checkCondition(address, filterContext, index) && !valuesSeen.containsKey(val.hashCode())){
                    this.count++;
                    this.valuesSeen.put(val.hashCode(),1);
                }
            }

            append(count);
            return memoryRefNode;

        }

        // non unique
        NonUniqueHashIndex cIndex = index.asNonUniqueHashIndex();
        long address;
        for(IKey key : keys){
            RecordBucketIterator iterator = cIndex.iterator(key);
            while(iterator.hasNext()){

                address = iterator.next();
                Object val = DataTypeUtils.getValue( dt, address );
                if(checkCondition(address, filterContext, index) && !valuesSeen.containsKey(val.hashCode())){
                    this.count++;
                    this.valuesSeen.put(val.hashCode(),1);
                }

            }
        }

        append(count);
        return memoryRefNode;

    }

}