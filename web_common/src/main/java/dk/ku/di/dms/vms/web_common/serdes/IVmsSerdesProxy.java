package dk.ku.di.dms.vms.web_common.serdes;

import dk.ku.di.dms.vms.modb.common.event.DataRequestEvent;
import dk.ku.di.dms.vms.modb.common.event.DataResponseEvent;
import dk.ku.di.dms.vms.modb.common.event.SystemEvent;
import dk.ku.di.dms.vms.modb.common.event.TransactionalEvent;
import dk.ku.di.dms.vms.web_common.meta.VmsDataSchema;
import dk.ku.di.dms.vms.web_common.meta.VmsEventSchema;

import java.util.Collection;
import java.util.Map;

/**
 * A proxy for all types of events exchanged between the sdk and the sidecar
 * Used for complex objects like schema definitions
 */
public interface IVmsSerdesProxy {

    byte[] serializeEventSchema(Map<String, VmsEventSchema> vmsEventSchema);
    byte[] serializeEventSchema( Collection<VmsEventSchema> vmsEventSchema);
    Map<String, VmsEventSchema> deserializeEventSchema(byte[] bytes);
    Map<String, VmsEventSchema> deserializeEventSchema(String json);

    byte[] serializeDataSchema(Map<String, VmsDataSchema> vmsEventSchema);
    Map<String, VmsDataSchema> deserializeDataSchema(byte[] bytes);

    byte[] serializeSystemEvent(SystemEvent systemEvent);
    SystemEvent deserializeSystemEvent(byte[] bytes);

    /**
     * A transactional event serves for both input and output
     * @param event
     * @return
     */
    byte[] serializeTransactionalEvent(TransactionalEvent event);
    TransactionalEvent deserializeToTransactionalEvent(byte[] bytes);

    byte[] serializeDataRequestEvent(DataRequestEvent event);
    DataRequestEvent deserializeDataRequestEvent(byte[] bytes);

    byte[] serializeDataResponseEvent(DataResponseEvent event);
    DataResponseEvent deserializeToDataResponseEvent(byte[] bytes);

}
