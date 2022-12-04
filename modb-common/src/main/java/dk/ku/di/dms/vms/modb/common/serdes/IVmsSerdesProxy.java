package dk.ku.di.dms.vms.modb.common.serdes;

import dk.ku.di.dms.vms.modb.common.event.DataRequestEvent;
import dk.ku.di.dms.vms.modb.common.event.DataResponseEvent;
import dk.ku.di.dms.vms.modb.common.schema.VmsDataSchema;
import dk.ku.di.dms.vms.modb.common.schema.VmsEventSchema;
import dk.ku.di.dms.vms.modb.common.schema.network.NetworkNode;

import java.util.List;
import java.util.Map;

/**
 * A proxy for all types of events exchanged between the sdk and the servers
 * Used for complex objects like schema definitions
 */
public interface IVmsSerdesProxy {

    String serializeEventSchema(Map<String, VmsEventSchema> vmsEventSchema);
    Map<String, VmsEventSchema> deserializeEventSchema(String json);

    String serializeDataSchema(Map<String, VmsDataSchema> vmsDataSchema);
    Map<String, VmsDataSchema> deserializeDataSchema(String vmsDataSchema);

    byte[] serializeDataRequestEvent(DataRequestEvent event);
    DataRequestEvent deserializeDataRequestEvent(byte[] bytes);

    byte[] serializeDataResponseEvent(DataResponseEvent event);
    DataResponseEvent deserializeToDataResponseEvent(byte[] bytes);

    <K,V> String serializeMap( Map<K,V> map );
    <K,V> Map<K,V> deserializeMap(String mapStr);

    String serializeConsumerSet( Map<String, NetworkNode> map );
    Map<String, NetworkNode> deserializeConsumerSet(String mapStr);

    <V> String serializeList( List<V> map );
    <V> List<V> deserializeList(String listStr);

    String serialize(Object value, Class<?> clazz);
    <T> T deserialize( String valueStr, Class<T> clazz );

}