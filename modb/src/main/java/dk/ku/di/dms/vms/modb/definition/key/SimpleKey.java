package dk.ku.di.dms.vms.modb.definition.key;

import dk.ku.di.dms.vms.modb.index.IIndexKey;

/**
 * A value that serves both for identifying a unique row (e.g., as PK) or a unique index entry.
 * In this case, the hash code is the hash of the object itself rather than the composition of values as in {@link CompositeKey}
 */
public class SimpleKey implements IKey, IIndexKey {

    private final Object value;

    public SimpleKey(Object value) {
        this.value = value;
    }

    public static SimpleKey of(Object value) {
        return new SimpleKey(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public boolean equals(Object object){
        return this.value == ((SimpleKey)object).value;
    }

}
