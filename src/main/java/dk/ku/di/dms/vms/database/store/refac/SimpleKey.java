package dk.ku.di.dms.vms.database.store.refac;

public class SimpleKey implements IKey {

    private final Object value;

    public SimpleKey(Object value) {
        this.value = value;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

}
