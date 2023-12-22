package dk.ku.di.dms.vms.modb.common.schema.network.transaction;

import dk.ku.di.dms.vms.modb.common.schema.network.Constants;
import dk.ku.di.dms.vms.modb.common.schema.network.node.VmsNode;
import dk.ku.di.dms.vms.modb.common.utils.ByteUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * An abort request is sent from a VMS to the leader.
 * The leader is then responsible for issuing abort request to all VMS participating in the given transaction.
 * The transaction must be stored in memory and discarded when the batch is committed.
 * This is necessary to get the participating VMSs.
 */
public class TransactionAbort {

    public static void write(ByteBuffer buffer, Payload payload) {
        buffer.put(Constants.TX_ABORT);
        buffer.putLong(payload.batch);
        buffer.putLong(payload.tid);
        buffer.putInt( payload.vms().length() );
        buffer.put( payload.vms().getBytes(StandardCharsets.UTF_8) );
    }

    public static void write(ByteBuffer buffer, VmsNode vmsIdentifier, long batch, long tid){
        buffer.put(Constants.TX_ABORT);
        buffer.putLong(batch);
        buffer.putLong(tid);
        buffer.putInt( vmsIdentifier.vmsIdentifier.length() );
        buffer.put( vmsIdentifier.vmsIdentifier.getBytes(StandardCharsets.UTF_8) );
    }

    public static Payload read(ByteBuffer buffer){
        long batch = buffer.getLong();
        long tid = buffer.getLong();
        int size = buffer.getInt();
        String vms = ByteUtils.extractStringFromByteBuffer(buffer, size);
        return new Payload(batch, tid, vms);
    }

    // a leader cannot issue new events (and batches of course) without receiving batch ACKs from all vms involved
    // so no need for further information in the payload
    public record Payload(
            long batch, long tid, String vms
    ) {}

}