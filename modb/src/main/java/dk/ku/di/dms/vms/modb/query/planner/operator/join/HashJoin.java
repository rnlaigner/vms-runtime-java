package dk.ku.di.dms.vms.modb.query.planner.operator.join;

import dk.ku.di.dms.vms.modb.query.planner.operator.filter.IFilter;
import dk.ku.di.dms.vms.modb.query.planner.operator.result.RowOperatorResult;
import dk.ku.di.dms.vms.modb.common.utils.IdentifiableNode;
import dk.ku.di.dms.vms.modb.store.common.IKey;
import dk.ku.di.dms.vms.modb.store.index.AbstractIndex;
import dk.ku.di.dms.vms.modb.store.index.UnsupportedIndexOperationException;
import dk.ku.di.dms.vms.modb.store.row.Row;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A hash join where its dependencies are fulfilled from the start
 * In other words, previous scanning and transformation steps are not necessary
 */

public class HashJoin extends AbstractJoin {

    public HashJoin(int identifier, AbstractIndex<IKey> innerIndex, AbstractIndex<IKey> outerIndex) {
        super(identifier, innerIndex, outerIndex);
    }

    @Override
    public JoinOperatorTypeEnum getType() {
        return JoinOperatorTypeEnum.HASH;
    }

    @Override
    public RowOperatorResult get() {

        Set<Map.Entry<IKey,Row>> entries = null;
        try {
            entries = innerIndex.entrySet();
        } catch (UnsupportedIndexOperationException e) {
            e.printStackTrace();
            return null;
        }

        for(final Map.Entry<IKey,Row> rowEntry : entries){

            IKey currRowKey = rowEntry.getKey();
            Row currRowVal = rowEntry.getValue();

            // check filter first
            boolean leftIsGreen = true;

            if(filterInner.filters != null){
                leftIsGreen = check(
                        currRowVal,
                        filterInner.filters,
                        filterInner.filterColumns,
                        filterInner.filterParams);
            }

            Row probedRow = null;
            // and then probe
            if(leftIsGreen && outerIndex.retrieve(currRowKey, probedRow)){
                boolean rightIsGreen = true;
                // then check whether the probed row satisfy
                if(filterOuter.filters != null) {
                    rightIsGreen = check(
                            currRowVal,
                            filterInner.filters,
                            filterInner.filterColumns,
                            filterInner.filterParams);
                }

                if(rightIsGreen){
                    // TODO push result upstream... it would call the accept API of a consumer
                }

            }

        }

        // TODO finish

        return null;

    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private boolean check(final Row row,
                          final IFilter<?>[] filters,
                          final int[] filterColumns,
                          final Collection<IdentifiableNode<Object>> filterParams){

        boolean conditionHolds = true;
        int filterIdx = 0;

        IFilter currFilter;

        Iterator<IdentifiableNode<Object>> paramsIterator = filterParams.iterator();
        IdentifiableNode<Object> currParam = null;
        if (paramsIterator.hasNext()){
            currParam = paramsIterator.next();
        }

        while( conditionHolds && filterIdx < filters.length ){
            currFilter = filters[filterIdx];

            // unchecked cast, but we know it is safe since the analyzer makes sure that
            if(currParam != null && currParam.id == filterIdx) {
                conditionHolds = currFilter.eval(row.get(filterColumns[filterIdx]), currParam.object );
                if (paramsIterator.hasNext()){
                    currParam = paramsIterator.next();
                } else {
                    currParam = null;
                }
            }
            else {
                conditionHolds = currFilter.eval(row.get(filterColumns[filterIdx]));
            }

            // no need to continue anymore
            if(!conditionHolds) break;

            filterIdx++;

        }

        return conditionHolds;

    }


}