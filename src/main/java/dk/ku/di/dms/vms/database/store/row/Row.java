package dk.ku.di.dms.vms.database.store.row;

/**
 * Defines an abstract row.
 * Later a row will be specialized for each concurrency control protocol.
 */
public class Row {

    protected final Object[] values;

    /**
     * Constructor to facilitate row creation. numParams should be ignored
     * @param values
     */
    public Row(final Object... values) {
        this.values = values;
    }

    public Object get(int id) {
        return values[id];
    }

    public int getInt(int id) {
        return (int) values[id];
    }

    public double getDouble(int id) {
        return (Double) values[id];
    }

    public long getLong(int id) {
        return (long) values[id];
    }

    public String getString(int id) {
        return (String) values[id];
    }

    public Character getCharacter(int id) {
        return (Character) values[id];
    }

}