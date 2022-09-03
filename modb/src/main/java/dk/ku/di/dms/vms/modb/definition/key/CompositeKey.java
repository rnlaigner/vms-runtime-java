package dk.ku.di.dms.vms.modb.definition.key;

import dk.ku.di.dms.vms.modb.index.IIndexKey;
import dk.ku.di.dms.vms.modb.definition.Row;

import java.util.Arrays;

/**
 * A sequence of values that serves both for identifying a
 * unique row (e.g., as PK) or a unique index entry.
 * The hash code is the hash of the array composed by all
 * values involved in this composition.
 */
public class CompositeKey extends Row implements IKey, IIndexKey {

    private final int hashKey;

    public static CompositeKey of(Object... values){
        return new CompositeKey(values);
    }

    public CompositeKey(Object... values) {
        super(values);
        this.hashKey = Arrays.hashCode(values);
    }

    @Override
    public int hashCode() {
        return hashKey;
    }

    @Override
    public boolean equals(Object key){
        return this.hashCode() == key.hashCode();
    }

}