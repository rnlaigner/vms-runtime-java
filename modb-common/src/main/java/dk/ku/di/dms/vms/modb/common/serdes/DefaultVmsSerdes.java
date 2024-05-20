package dk.ku.di.dms.vms.modb.common.serdes;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import dk.ku.di.dms.vms.modb.common.schema.VmsDataModel;
import dk.ku.di.dms.vms.modb.common.schema.VmsEventSchema;
import dk.ku.di.dms.vms.modb.common.schema.network.node.IdentifiableNode;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link IVmsSerdesProxy}
 * It abstracts the use of {@link Gson}
 */
final class DefaultVmsSerdes implements IVmsSerdesProxy {

    private final Gson gson;

    public DefaultVmsSerdes(Gson gson) {
        this.gson = gson;
    }

    /**
     VMS METADATA EVENTS
     **/

    @Override
    public String serializeEventSchema(Map<String, VmsEventSchema> vmsEventSchema) {
        return this.gson.toJson( vmsEventSchema );
    }

    @Override
    public Map<String, VmsEventSchema> deserializeEventSchema(String vmsEventSchemaStr) {
        return this.gson.fromJson(vmsEventSchemaStr, new TypeToken<Map<String, VmsEventSchema>>(){}.getType());
    }

    @Override
    public String serializeDataSchema(Map<String, VmsDataModel> vmsDataSchema) {
        return this.gson.toJson( vmsDataSchema );
    }

    @Override
    public Map<String, VmsDataModel> deserializeDataSchema(String dataSchemaStr) {
        return this.gson.fromJson(dataSchemaStr, new TypeToken<Map<String, VmsDataModel>>(){}.getType());
    }

    @Override
    public <K,V> String serializeMap( Map<K,V> map ){
        return this.gson.toJson( map, new TypeToken<Map<K, V>>(){}.getType() );
    }

    @Override
    public <K,V> Map<K,V> deserializeMap(String mapStr){
        Type type = new TypeToken<Map<K, V>>(){}.getType();
        return this.gson.fromJson(mapStr, type);
    }

    @Override
    public <V> String serializeSet(Set<V> map) {
        return this.gson.toJson( map, new TypeToken<Set<V>>(){}.getType() );
    }

    @Override
    public <V> Set<V> deserializeSet(String setStr) {
        return this.gson.fromJson(setStr, new TypeToken<Set<V>>(){}.getType());
    }

    @Override
    public String serializeConsumerSet(Map<String, List<IdentifiableNode>> map) {
        return this.gson.toJson( map );
    }

    private static final Type typeConsMap = new TypeToken<Map<String, List<IdentifiableNode>>>(){}.getType();

    @Override
    public Map<String, List<IdentifiableNode>> deserializeConsumerSet(String mapStr) {
        return this.gson.fromJson(mapStr, typeConsMap);
    }

    private static final Type typeDepMap = new TypeToken<Map<String, Long>>(){}.getType();

    @Override
    public Map<String, Long> deserializeDependenceMap(String dependenceMapStr) {
        try {
            return this.gson.fromJson(dependenceMapStr, typeDepMap);
        } catch (JsonSyntaxException e){
            throw new RuntimeException("Failed to deserialize dependence map: " + dependenceMapStr, e);
        }
    }

    @Override
    public <V> String serializeList(List<V> list) {
        return this.gson.toJson( list, new TypeToken<List<V>>(){}.getType() );
    }

    @Override
    public <V> List<V> deserializeList(String listStr) {
        return this.gson.fromJson(listStr, new TypeToken<List<V>>(){}.getType());
    }

    @Override
    public String serialize(Object value, Class<?> clazz) {
        return this.gson.toJson( value, clazz );
    }

    @Override
    public <T> T deserialize(String valueStr, Class<T> clazz) {
        return this.gson.fromJson( valueStr, clazz );
    }

    @Override
    public String fromJson(String string){
        return this.gson.fromJson(string, String.class);
    }

}
