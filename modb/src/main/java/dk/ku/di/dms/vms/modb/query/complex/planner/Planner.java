package dk.ku.di.dms.vms.modb.query.complex.planner;

import dk.ku.di.dms.vms.modb.common.etc.IdentifiableNode;
import dk.ku.di.dms.vms.modb.index.IndexTypeEnum;
import dk.ku.di.dms.vms.modb.query.analyzer.QueryTree;
import dk.ku.di.dms.vms.modb.query.analyzer.predicate.GroupByPredicate;
import dk.ku.di.dms.vms.modb.query.analyzer.predicate.JoinPredicate;
import dk.ku.di.dms.vms.modb.query.analyzer.predicate.WherePredicate;
import dk.ku.di.dms.vms.modb.common.query.enums.ExpressionTypeEnum;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.aggregate.Average;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.aggregate.IAggregate;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.filter.FilterInfo;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.join.AbstractJoin;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.join.IndexNestedLoopJoin;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.join.JoinOperatorTypeEnum;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.join.NestedLoopJoin;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.scan.AbstractScanOld;
import dk.ku.di.dms.vms.modb.query.complex.planner.tree.PlanNode;
import dk.ku.di.dms.vms.modb.query.complex.planner.tree.QueryTreeTypeEnum;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.filter.FilterBuilder;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.filter.IFilter;
import dk.ku.di.dms.vms.modb.common.interfaces.result.RowOperatorResult;
import dk.ku.di.dms.vms.modb.query.complex.planner.operator.scan.IndexScan;
import dk.ku.di.dms.vms.modb.query.planner.operator.join.*;
import dk.ku.di.dms.vms.modb.definition.key.IKey;
import dk.ku.di.dms.vms.modb.index.AbstractIndex;
import dk.ku.di.dms.vms.modb.definition.Table;
import dk.ku.di.dms.vms.modb.definition.key.CompositeKey;
import dk.ku.di.dms.vms.modb.definition.key.SimpleKey;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class responsible for deciding for a plan to run a query
 * https://www.interdb.jp/pg/pgsql03.html
 * "The planner receives a query tree from the rewriter and generates a (query)
 *   plan tree that can be processed by the executor most effectively."
 */
public final class Planner {

    private static Planner INSTANCE = new Planner();

    public static Planner instance(){
        return INSTANCE;
    }

    private Planner(){}

//    public PlanNode planBulkInsert(Table table, List<? extends IEntity<?>> entities){
//
//        // get the indexes to check for foreign key first
//        Map<Table, int[]> foreignKeysGroupedByTable = table.getForeignKeysGroupedByTable();
//
//        Map<Table, AbstractIndex<IKey>> indexPerFk = new HashMap<>();
//
//        // for each table, get appropriate index
//        for( Map.Entry<Table, int[]> fkEntry : foreignKeysGroupedByTable.entrySet() ){
//            Optional<AbstractIndex<IKey>> index = findOptimalIndex(fkEntry.getKey(), fkEntry.getValue() );
//            indexPerFk.put(fkEntry.getKey(), index.get());
//        }
//
//        ConstraintEnforcer constraintEnforcer = new ConstraintEnforcer(entities, indexPerFk, table);
//
//        PlanNode constraintPlanNode = new PlanNode( constraintEnforcer );
//
//        BulkInsert bulkInsert = new BulkInsert();
//
//        PlanNode bulkInsertPlanNode = new PlanNode(bulkInsert);
//
//        constraintPlanNode.father = bulkInsertPlanNode;
//        bulkInsertPlanNode.left = constraintPlanNode;
//
//        return constraintPlanNode;
//    }

    // we can also have the option: AUTO, meaning the planner will look for the possibility of building a bushy tree
    public PlanNode plan(QueryTree queryTree) {
        return this.plan( queryTree, QueryTreeTypeEnum.LEFT_DEEP );
    }

    // tables involved in join
    private Map<String,Integer> getTablesInvolvedInJoin(final QueryTree queryTree){
        final Map<String,Integer> tablesInvolvedInJoin = new HashMap<>();
        for(final JoinPredicate join : queryTree.joinPredicates){
            tablesInvolvedInJoin.put(join.getLeftTable().getName(), 1);
            tablesInvolvedInJoin.put(join.getRightTable().getName(), 1);
        }
        return tablesInvolvedInJoin;
    }

    // tables involved in aggregate
    private Map<String,Integer> getTablesInvolvedInAggregate(final QueryTree queryTree, Map<String,Integer> baseMap){
        if(baseMap == null) baseMap = new HashMap<>();
        for(final GroupByPredicate predicate : queryTree.groupByPredicates){
            baseMap.put(predicate.columnReference.table.getName(), 1);
        }
        return baseMap;
    }

    private Map<Table,List<WherePredicate>> getWherePredicatesGroupedByTableNotInAnyJoinOrAggregate(
            QueryTree queryTree, Map<String,Integer> tablesInvolvedInJoinOrAggregate ){

        // TODO optimization. avoid stream by reusing the table list in query tree. simply maintain a bool (in_join?)
        //  other approach is delivering these maps to the planner from the analyzer...

        return queryTree
                .wherePredicates.stream()
                .filter( clause -> !tablesInvolvedInJoinOrAggregate
                        .containsKey(clause.getTable().getName()) )
                .collect(
                        Collectors.groupingBy( WherePredicate::getTable,
                                Collectors.toList())
                );
    }

    private Map<Table, List<WherePredicate>> getFiltersForTablesInvolvedInJoin(
            QueryTree queryTree, Map<String,Integer> tablesInvolvedInJoin) {
        return queryTree
                        .wherePredicates.stream()
                        .filter(clause -> tablesInvolvedInJoin
                                .containsKey(clause.getTable().getName()))
                        .collect(
                                Collectors.groupingByConcurrent(WherePredicate::getTable,
                                        Collectors.toList())
                        );
    }

    private Map<Table,List<AbstractJoin>> buildJoinOperators(QueryTree queryTree){

        final Map<Table,List<AbstractJoin>> joinsPerTable = new HashMap<>();

        // simply strategy for assigning identifiers to joins
        int counter = 0;

        // TODO I am not considering a JOIN predicate may contain more than a column for now....
        // if the pk index or other indexes only apply to one of the columns, then others become filters
        for(final JoinPredicate joinPredicate : queryTree.joinPredicates){

            Table tableLeft = joinPredicate.getLeftTable();
            int[] colPosArr = { joinPredicate.columnLeftReference.columnPosition };
            AbstractIndex<IKey> leftTableIndex = findOptimalIndex( tableLeft, colPosArr );

            Table tableRight = joinPredicate.getRightTable();
            colPosArr = new int[]{ joinPredicate.columnRightReference.columnPosition };
            AbstractIndex<IKey> rightTableIndex = findOptimalIndex( tableRight, colPosArr );

            AbstractJoin join;
            Table tableInner;
            Table tableOuter;

            // TODO on app startup, I can "query" all the query builders.
            //  or store the query plan after the first execution so startup time is faster
            //      take care to leave the holes whenever the substitution is necessary

            // now we have to decide the physical operators
            if(leftTableIndex != null && rightTableIndex != null){
                // does this case necessarily lead to symmetric HashJoin? https://cs.uwaterloo.ca/~david/cs448/

                if(leftTableIndex.size() < rightTableIndex.size()){
                    join = new HashJoinOld( counter, leftTableIndex, rightTableIndex );
                    tableInner = tableLeft;
                    tableOuter = tableRight;
                } else {
                    join = new HashJoinOld( counter, rightTableIndex, leftTableIndex );
                    tableInner = tableRight;
                    tableOuter = tableLeft;
                }
            } else if( leftTableIndex != null ){ // indexed nested loop join on inner table. the outer probes its rows
                tableInner = tableRight;
                tableOuter = tableLeft;
                join = new IndexNestedLoopJoin( counter, tableRight.getPrimaryKeyIndex(), leftTableIndex );
            } else if( rightTableIndex != null ) {
                tableInner = tableLeft;
                tableOuter = tableRight;
                join = new IndexNestedLoopJoin( counter, tableLeft.getPrimaryKeyIndex(), rightTableIndex );
            } else { // nested loop join
                // ideally this is pushed upstream to benefit from pruning performed by downstream nodes
                if(tableLeft.getPrimaryKeyIndex().size() < tableRight.getPrimaryKeyIndex().size()){
                    tableInner = tableLeft;
                    tableOuter = tableRight;
                    join = new NestedLoopJoin(counter, tableLeft.getPrimaryKeyIndex(), tableRight.getPrimaryKeyIndex());
                } else {
                    tableInner = tableRight;
                    tableOuter = tableLeft;
                    join = new NestedLoopJoin(counter, tableRight.getPrimaryKeyIndex(), tableLeft.getPrimaryKeyIndex());
                }

            }

            counter++;

            this.addJoinToRespectiveTableInOrderOfJoinOperation(tableInner, join, joinsPerTable);
            this.addJoinToRespectiveTableInOrderOfJoinOperation(tableOuter, join, joinsPerTable);

        }

        return joinsPerTable;

    }

    /**
     * the shortest the table, lower the degree of the join operation in the query tree
     * thus, store a map of plan nodes keyed by table
     * the plan node with the largest probability of pruning (considering index type + filters + size of table)
     * more records earlier in the query are pushed downstream in the physical query plan
     * ordered list of participating tables. the first one is the smallest table and so on.
     * better selectivity rate leads to a deeper node
     * Consider that the base table is always the left one in the join
     * So I just need to check to order the plan nodes according to the size of the right table
     * TODO binary search -- but for that we need a priority concept
     * TODO perhaps the {@link Comparator} interface can abstract the insertion
     * Priority order of sorting conditions: type of join, selectivity (we are lacking this right now), size of (right) table
     * @param table Associated table
     * @param join Associated join
     * @param joinsPerTable Joins per table mapped so far
     */
    private void addJoinToRespectiveTableInOrderOfJoinOperation(Table table, AbstractJoin join, Map<Table,List<AbstractJoin>> joinsPerTable ){

        List<AbstractJoin> joins = joinsPerTable.getOrDefault( table, new ArrayList<>() );

        if(joins.size() == 0){
            joins.add(join);
            joinsPerTable.put( table, joins );
            return;
        }

        Iterator<AbstractJoin> it = joins.iterator();
        int posToInsert = 0;
        AbstractJoin next = it.next();
        boolean hasNext = true;
        while (hasNext &&
                (
                    (
                        ( next.getType() == JoinOperatorTypeEnum.HASH && (join.getType() == JoinOperatorTypeEnum.INDEX_NESTED_LOOP || join.getType() == JoinOperatorTypeEnum.NESTED_LOOP ) )
                        || // TODO this may not be true for all joins. e.g., some index nested loop may take longer than a nested loop join, as e.g. in tables with lesser records
                        ( next.getType() == JoinOperatorTypeEnum.INDEX_NESTED_LOOP && (join.getType() == JoinOperatorTypeEnum.NESTED_LOOP ) )
                    )
                    ||
                    ( join.getOuterIndex().size() > next.getOuterIndex().size() )
                )
        ){
            if(it.hasNext()){
                next = it.next();
                posToInsert++;
            } else {
                hasNext = false;
            }

        }
        joins.add(posToInsert, join);
    }

    /**
     * The position of the join in the list reflects
     * @param joinsPerTable Joins per table mapped so far
     * @param filtersForJoinGroupedByTable Filters ought to be applied to join operations grouped by table
     */
    private void applyFiltersToJoins(Map<Table,List<AbstractJoin>> joinsPerTable, Map<Table, List<WherePredicate>> filtersForJoinGroupedByTable) {

        Table currTable;
        // TODO Merging the filters with the join
        //          The heuristic now is just to apply the filter as early as possible. later we can revisit
        //          remove from map after applying the additional filters
        //          i.e., the indexes chosen so far may not be optimal given that, by considering the filters,
        //          we may find better indexes that would make the join much faster
        //          a first approach is simply deciding now an index and later
        //          when applying the filters, we check whether there are better indexes

        for( Map.Entry<Table,List<WherePredicate>> tableFilter : filtersForJoinGroupedByTable.entrySet() ){

            currTable = tableFilter.getKey();

            List<AbstractJoin> joins = joinsPerTable.get( currTable );

            // it does not matter whether this table only appears as outer index, the order of insertion in the collection
            // is a good guess the filter will be applied as soon as possible

            // we always have at least one
            AbstractJoin joinToBeFiltered = joins.get( joins.size() - 1 );

            // is it an outer? if so, should delete this entry from the collection
            if(joinToBeFiltered.getOuterIndex().getTable().hashCode() == currTable.hashCode()){
                joinToBeFiltered.setFilterOuter( buildFilterInfo( tableFilter.getValue() ) );

                if(joins.size() == 1){
                    // just remove the entry from the map
                    filtersForJoinGroupedByTable.remove( currTable );
                } else {
                    joins.remove(joins.size() - 1);
                }

            } else {
                joinToBeFiltered.setFilterInner( buildFilterInfo( tableFilter.getValue() ) );
            }

        }

    }

    /**
     *
     * @param wherePredicatesGroupedByTableNotInAnyJoinOrAggregate Filters ought for use in scan
     * @return A list of independent scan operators
     */
    private List<AbstractScanOld> buildScanOperators(Map<Table,List<WherePredicate>> wherePredicatesGroupedByTableNotInAnyJoinOrAggregate) {

        List<AbstractScanOld> planNodes = new ArrayList<>();

        // a scan for each table
        // the scan strategy only applies for those tables not involved in any join
        // TODO all predicates with the same column should be a single filter
        // TODO build filter checks where the number of parameters are known a priori and can
        //      take better advantage of the runtime, i.e., avoid loops over the filters
        //      E.g., eval2(predicate1, values2, predicate2, values2) ...
        //      each one of the tables here lead to a cartesian product operation
        //      if there are any join, so it should be pushed upstream to avoid high cost
        for( final Map.Entry<Table, List<WherePredicate>> entry : wherePredicatesGroupedByTableNotInAnyJoinOrAggregate.entrySet() ){

            final List<WherePredicate> wherePredicates = entry.getValue();
            final Table currTable = entry.getKey();

            int[] filterColumns = wherePredicates.stream().mapToInt( WherePredicate::getColumnPosition ).toArray();

            // I need the index information to decide which operator, e.g., index scan
            final AbstractIndex<IKey> optimalIndex = findOptimalIndex( currTable, filterColumns );
            if(optimalIndex != null){
                if( optimalIndex.getType() == IndexTypeEnum.NON_UNIQUE) {
                    // what columns are not subject for hash probing?
                    int[] indexColumns = optimalIndex.getColumns();

                    Object[] indexParams = new Object[indexColumns.length];

                    // build a new list of where predicates without the columns involved in the index,
                    // since the index will only lead to the interested values
                    List<WherePredicate> wherePredicatesNoIndex = new ArrayList<>();
                    // FIXME naive n^2 ... indexColumns can be a map
                    boolean found = false;
                    int idxParamPos = 0;
                    for(WherePredicate wherePredicate : wherePredicates){
                        for(int indexColumn : indexColumns){
                            if( indexColumn == wherePredicate.columnReference.columnPosition ){
                                indexParams[idxParamPos] = wherePredicate.value;
                                idxParamPos++;
                                found = true;
                                break;
                            }
                        }
                        if(!found) wherePredicatesNoIndex.add( wherePredicate );
                        found = false;
                    }

                    // build hash probe
                    // the key to probe in the hash index
                    IKey probeKey;
                    if (indexColumns.length > 1) {
                        probeKey = new CompositeKey(indexParams);
                    } else {
                        probeKey = new SimpleKey(indexParams[0]);
                    }

                    IndexScan indexScan;
                    if(wherePredicatesNoIndex.size() == 0){
                        indexScan = new IndexScan( optimalIndex, probeKey );
                    } else {
                        final FilterInfo filterInfo = buildFilterInfo(wherePredicatesNoIndex);
                        indexScan = new IndexScan( optimalIndex, probeKey, filterInfo );
                    }

                    planNodes.add(indexScan);
                } else {
                    final FilterInfo filterInfo = buildFilterInfo( wherePredicates );
                    // planNodes.add( new SequentialScan(optimalIndex, filterInfo) );
                }
            } else {
                final FilterInfo filterInfo = buildFilterInfo( wherePredicates );
                // planNodes.add( new SequentialScan(currTable.getPrimaryKeyIndex(), filterInfo) );
            }

        }

        // TODO think about parallel seq scan,
        // SequentialScan sequentialScan = new SequentialScan( filterList, table);

        return planNodes;

    }

    /**
     * A cartesian product can receive the result of the upmost join in the query plan (considering a left deep tree)
     * @param scans
     * @param joins
     * @return
     */
    private PlanNode planCartesianProduct(List<AbstractScanOld> scans, List<PlanNode> joins ){
        // TODO finish
        return null;
    }

    private PlanNode planCartesianProduct( List<PlanNode> joins ){
        // TODO finish
        return null;
    }

    private PlanNode planScans( List<AbstractScanOld> scans ){

        if(scans.size() == 1){
            return new PlanNode( scans.get(0) );
        }

        // TODO finish cartesian product

        return null;
    }

    private PlanNode planAggregates(List<IAggregate> aggregates, boolean embedScan ){
        // TODO finish TODO consider filters in case embedded scan
        if(embedScan){

            IAggregate aggregate = aggregates.get(0);

            RowOperatorResult input = null; // new RowOperatorResult( aggregate.getTable().getPrimaryKeyIndex().rows() );

            aggregate.accept( input );

        }

        return new PlanNode( aggregates.get(0) );
    }

    /**
     *
     * @return List of independent tree of join operations
     */
    private List<PlanNode> planJoins( Map<Table,List<AbstractJoin>> joinsPerTable, QueryTreeTypeEnum treeType ){

        // left deep, most cases will fall in this category
        if(treeType == QueryTreeTypeEnum.LEFT_DEEP){

            // here I need to iterate over the joins defined earlier to build the plan tree for the joins
            PlanNode previous = null;

            final List<PlanNode> headers = new ArrayList<>(joinsPerTable.size());

            for(final Map.Entry<Table,List<AbstractJoin>> joinEntry : joinsPerTable.entrySet()){
                List<AbstractJoin> joins = joinEntry.getValue();
                for( final AbstractJoin join : joins ) {
                    PlanNode node = new PlanNode(join);
                    node.left = previous;
                    if(previous != null){
                        previous.father = node;
                    }
                    previous = node;
                }

                headers.add( previous );
                previous = null;

            }

            return headers;

        } else{
            // TODO finish BUSHY TREE later
        }

        return null;
    }

    public PlanNode plan(QueryTree queryTree, QueryTreeTypeEnum treeType) {

        /*
         * Processing joins
         */
        Map<String,Integer> tablesInvolvedInJoin = getTablesInvolvedInJoin(queryTree);

        final Map<Table, List<WherePredicate>> filtersForJoinGroupedByTable = getFiltersForTablesInvolvedInJoin( queryTree, tablesInvolvedInJoin );

        final Map<Table,List<AbstractJoin>> joinsPerTable = buildJoinOperators( queryTree );

        if(!joinsPerTable.isEmpty() && !filtersForJoinGroupedByTable.isEmpty()) {
            applyFiltersToJoins(joinsPerTable, filtersForJoinGroupedByTable);
        }

        getTablesInvolvedInAggregate(queryTree, tablesInvolvedInJoin);

        /*
         * Processing scans
         */
        final Map<Table,List<WherePredicate>> wherePredicatesGroupedByTableNotInAnyJoinOrAggregate =
                getWherePredicatesGroupedByTableNotInAnyJoinOrAggregate( queryTree, tablesInvolvedInJoin );

        List<AbstractScanOld> scans = null;
        if( !wherePredicatesGroupedByTableNotInAnyJoinOrAggregate.isEmpty() ){
            scans = buildScanOperators(wherePredicatesGroupedByTableNotInAnyJoinOrAggregate);
        }

        List<PlanNode> joins = null;
        if( !joinsPerTable.isEmpty() ){
            joins = planJoins( joinsPerTable, treeType );
        }

        // TODO process other group by predicates
        List<IAggregate> aggregates = null;
        if(!queryTree.groupByPredicates.isEmpty()) {
            aggregates = new ArrayList<>();
            for (GroupByPredicate groupByPredicate : queryTree.groupByPredicates) {
                // for now, only avg
                Average average = new Average(groupByPredicate.columnReference, groupByPredicate.groupByColumnsReference);
                aggregates.add(average);
            }
        }

        /*
         * Processing projection
         */
        PlanNode projection = buildProjectionOperator( queryTree );

        /*
         * Reasoning about everything so far
         */

        // do we have both joins and scans?
        if( !joinsPerTable.isEmpty() && !wherePredicatesGroupedByTableNotInAnyJoinOrAggregate.isEmpty() ){
            PlanNode cartesianProductNode = planCartesianProduct( scans, joins );
            projection.left = cartesianProductNode;
            cartesianProductNode.father = projection;
        } else if( !joinsPerTable.isEmpty() ){

            // == 1 means only one independent join tree, so no cartesian product
            if(joins.size() == 1){
                projection.left = joins.get(0);
                joins.get(0).father = projection;
            } else {
                PlanNode cartesianProductNode = planCartesianProduct( joins );
                projection.left = cartesianProductNode;
                cartesianProductNode.father = projection;
            }

        } else {
            // it can be a cartesian product or a simple scan
            if(scans != null) {
                PlanNode scanPlan = planScans(scans);
                projection.left = scanPlan;
                scanPlan.father = projection;
            } else if (aggregates != null) {

                //projection.left = aggregatesPlan;
                //aggregatesPlan.father = projection;

                projection = planAggregates(aggregates,true);

            }
        }

        return projection;
    }

    private PlanNode buildProjectionOperator(QueryTree queryTree){

        PlanNode projectionPlanNode = null;

        // case where we need to convert to a class type
//        if(queryTree.returnType != null){
//            TypedProjector projector = new TypedProjector( queryTree.returnType, queryTree.projections );
//            projectionPlanNode = new PlanNode();
//            projectionPlanNode.consumer = projector;
//            projectionPlanNode.supplier = projector;
//        }

            // simply a return containing rows
            // RawProjector projector = new RawProjector( queryTree.projections, queryTree.groupByPredicates );

        return projectionPlanNode;
    }

    private FilterInfo buildFilterInfo( List<WherePredicate> wherePredicates ) {

        final int size = wherePredicates.size();
        IFilter<?>[] filters = new IFilter<?>[size];
        int[] filterColumns = new int[size];
        Collection<IdentifiableNode<Object>> filterParams = new LinkedList<>();

        int i = 0;
        for(final WherePredicate w : wherePredicates){

            boolean nullableExpression = w.expression == ExpressionTypeEnum.IS_NOT_NULL
                    || w.expression == ExpressionTypeEnum.IS_NULL;

            filters[ i ] = FilterBuilder.build( w );
            filterColumns[ i ] = w.columnReference.columnPosition;
            if( !nullableExpression ){
                filterParams.add( new IdentifiableNode<>( i, w.value ) );
            }

            i++;
        }

        return new FilterInfo(filters, filterColumns, filterParams);

    }

    /**
     * The index may not be composed by all the columns in the set of columns
     * passed as parameter
     */
    private float calculateSelectivity(AbstractIndex<IKey> index, int[] columns){

        // optimistic belief that the number of columns would lead to higher pruning of records

        // 0.1 ~~> 10% of the table is checked
        // 1.0 ~~> 100% of the table is checked

        // the actual values of the columns are disregard in this calc. OK for now...

        // 1 - how much this index cover the columns?
        return ((float) (index.getColumns().length / columns.length)) - 1;

        // unique hash 0
        // non unique hash 0.25
        // range 0.5

        // a non-unique hash index can be implemented similarly to the unique hash index
        // but the hash in a file and the actual records mapped to a second file
        // the records in the second file are linked lists

        // the linked lists are ordered by the PK, so binary search could be implemented
        // using a linked list, although scattering records from different hashes,
        // (forcing paging...) allow for easier implementation

    }

    /**
     *  Here we are relying on the fact that, either the developer has declared the columns
     *  or system code safeguards the columns are provided in order of definition,
     *  in the where clause matching the actual index column order definition
     */
    private AbstractIndex<IKey> findOptimalIndex(Table table, int[] filterColumns){

        // fast path: do the columns form the PK?
        int hashCodeAllColumns = Arrays.hashCode(filterColumns);
        if(table.getPrimaryKeyIndex().hashCode() == hashCodeAllColumns){
            return table.getPrimaryKeyIndex();
        }

        // all combinations... this can be built in the analyzer
        List<int[]> combinations = getAllPossibleColumnCombinations(filterColumns);

        // build map of hash code
        Map<Integer, int[]> hashCodeMap = new HashMap<>(combinations.size());

        for(int[] arr : combinations){
            if( arr.length == 1 ) {
                hashCodeMap.put(arr[0], arr);
            } else {
                hashCodeMap.put(Arrays.hashCode(arr), arr);
            }
        }
        
        // for each index, check if the column set matches
        float bestSelectivity = Float.MAX_VALUE;
        AbstractIndex<IKey> optimalIndex = null;

        // the selectivity may be unknown, we should use simple heuristic here to decide what is the best index
        // the heuristic is simply the number of columns an index covers
        // of course this can be misleading, since the selectivity can be poor, leading to scan the entire table anyway...
        // we could insert a selectivity collector in the sequential scan so later this data can be used here
        for( AbstractIndex<IKey> index : table.getIndexes() ){

            /*
             * in case selectivity is not present,
             * then we use size the type of the column, e.g., int > long > float > double > String
             *  https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
             */
            // for this algorithm I am only considering exact matches, so range indexes >,<,>=,=< are not included for now
            int[] columns = hashCodeMap.getOrDefault( index.hashCode(), null );
            if( columns != null ){

                float heuristicSelectivity = calculateSelectivity(index, columns);

                // try next index then
                if(heuristicSelectivity > bestSelectivity) continue;

                // if tiebreak, hash index type is chosen as the tiebreaker criterion
//                if(heuristicSelectivity == bestSelectivity
////                        && optimalIndex.getType() == IndexDataStructureEnum.TREE
////                        && index.getType() == IndexDataStructureEnum.HASH
//                        ){
//                    // do not need to check whether optimalIndex != null since totalSize
//                    // can never be equals to FLOAT.MAX_VALUE
//                    optimalIndex = index;
//                    continue;
//                }

                // heuristicSelectivity < bestSelectivity
                optimalIndex = index;
                bestSelectivity = heuristicSelectivity;

            }

        }

        return optimalIndex;

    }

}