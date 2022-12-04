package dk.ku.di.dms.vms.modb.common.schema;

import dk.ku.di.dms.vms.modb.common.type.DataType;

/**
 * The class record describes the schema of Transactional Events.
 *
 *
 *
 */
public class VmsEventSchema {

    public String eventName; // the respective queue name

    // the name of the columns
    public String[] columnNames;

    // the data types of the columns
    public DataType[] columnDataTypes;

    public VmsEventSchema(String eventName, String[] columnNames, DataType[] columnDataTypes) {
        this.eventName = eventName;
        this.columnNames = columnNames;
        this.columnDataTypes = columnDataTypes;
    }

}