package dk.ku.di.dms.vms.modb.definition;

import dk.ku.di.dms.vms.modb.common.data_structure.Tuple;
import dk.ku.di.dms.vms.modb.definition.key.IKey;
import dk.ku.di.dms.vms.modb.index.IIndexKey;
import dk.ku.di.dms.vms.modb.index.interfaces.ReadWriteIndex;
import dk.ku.di.dms.vms.modb.transaction.multiversion.index.NonUniqueSecondaryIndex;
import dk.ku.di.dms.vms.modb.transaction.multiversion.index.PrimaryIndex;
import dk.ku.di.dms.vms.modb.transaction.multiversion.index.UniqueSecondaryIndex;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Basic building block
 * This class holds the metadata to other data structures that concern a table and its operations
 * In other words, it does not hold/store rows, since this is the task of an index
 */
public final class Table {

    // at first, I am considering the table name is immutable. the hash code is cached to uniquely identify the table in java maps
    public final int hashCode;

    public final String name;

    private final Schema schema;

    /**
     * All tables must have a pk, thus a primary index.
     * Besides, used for fast path on planner
     */
    private final PrimaryIndex primaryIndex;

    /**
     * Why foreign keys is on table and not on {@link Schema}?
     * (i) To avoid circular dependence schema <-> table.
     *      This way Schema does not know about the details of a Table
     *      (e.g., the primary index structure, the table name, the secondary indexes, etc.).
     * (ii) The planner needs the foreign keys in order to define an optimal plan.
     *      As the table carries the PK, the table holds references to other tables.
     * (iii) Foreign key necessarily require coordination across different indexes that are maintaining records.
     *       In other words, foreign key maintenance is a concurrency control behavior.
     * /
     * The array int[] are the column positions, ordered, that form the foreign key (i.e., refer to the other table).
     * Who I am pointing to? The referenced or parent table.
     * -
     * Why references to foreign key constraints are here?
     * Because an index should not know about other indexes.
     * It is better to have an upper class taking care of this constraint.
     * Can be made parallel.
     */
    private final Map<PrimaryIndex, int[]> foreignKeyMap;

    /**
     * Other indexes from this table, hashed by the column set in order of the schema
     *  logical key - column list in order that appear in the schema
     *  physical key - column list in order of index definition
     */
    public final Map<IIndexKey, NonUniqueSecondaryIndex> secondaryIndexMap;

    // column position, value expected
    public final Map<IIndexKey, Tuple<Integer, Object>> partialIndexMetaMap;

    public final Map<IIndexKey, UniqueSecondaryIndex> partialIndexMap;

    /**
     * Indexes from other tables pointing to the primary index of this table.
     * It resolves the question: Who is pointing to me? Who am I parenting?
     * In order to enable cascading DELETE
      */
    public final List<NonUniqueSecondaryIndex> children;

    public Table(String name, Schema schema, PrimaryIndex primaryIndex,
                 Map<PrimaryIndex, int[]> foreignKeyMap,
                 Map<IIndexKey, NonUniqueSecondaryIndex> secondaryIndexMap,
                 Map<IIndexKey, Tuple<Integer, Object>> partialIndexMetaMap,
                 Map<IIndexKey, UniqueSecondaryIndex> partialIndexMap){
        this.name = name;
        this.schema = schema;
        this.hashCode = name.hashCode();
        this.primaryIndex = primaryIndex;
        this.foreignKeyMap = foreignKeyMap;
        this.secondaryIndexMap = secondaryIndexMap;
        this.partialIndexMetaMap = partialIndexMetaMap;
        this.partialIndexMap = partialIndexMap;
        this.children = Collections.emptyList();
    }

    public Table(String name, Schema schema, PrimaryIndex primaryIndex){
        this.name = name;
        this.schema = schema;
        this.hashCode = name.hashCode();
        this.primaryIndex = primaryIndex;
        this.foreignKeyMap = Collections.emptyMap();
        this.secondaryIndexMap = Collections.emptyMap();
        this.partialIndexMetaMap = Collections.emptyMap();
        this.partialIndexMap = Collections.emptyMap();
        this.children = Collections.emptyList();
    }

    @Override
    public boolean equals(Object anotherTable){
        return anotherTable instanceof Table && this.hashCode == anotherTable.hashCode();
    }

    @Override
    public int hashCode(){
        return this.hashCode;
    }

    public Schema schema(){
        return this.schema;
    }

    public String getName(){
        return this.name;
    }

    public ReadWriteIndex<IKey> underlyingPrimaryKeyIndex(){
        return this.primaryIndex.underlyingIndex();
    }

    public PrimaryIndex primaryKeyIndex(){
        return this.primaryIndex;
    }

    public Map<PrimaryIndex, int[]> foreignKeys(){
        return this.foreignKeyMap;
    }

}
