package dk.ku.di.dms.vms.modb.definition.key;

import dk.ku.di.dms.vms.modb.index.IIndexKey;

/**
 * A value that serves both for identifying a unique row (e.g., as PK) or a unique index entry.
 * In this case, the hash code is the hash of the object itself rather than the composition of values as in {@link CompositeKey}
 */
public final class SimpleKey implements IKey, IIndexKey {

    private final int value;

    private SimpleKey(Object value) {
        this.value = value.hashCode();
    }

    public static SimpleKey of(Object value) {
        return new SimpleKey(value);
    }

    @Override
    public int hashCode() {
        return this.value;
    }

    @Override
    public boolean equals(Object object){
        if(object instanceof SimpleKey o){
            return this.value == o.value;
        }
        return false;
    }

    @Override
    public String toString() {
        return "{" +
                "value =" + value +
                '}';
    }
}
