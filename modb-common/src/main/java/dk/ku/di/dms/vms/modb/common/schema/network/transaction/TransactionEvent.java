package dk.ku.di.dms.vms.modb.common.schema.network.transaction;

import dk.ku.di.dms.vms.modb.common.schema.network.Constants;
import dk.ku.di.dms.vms.modb.common.utils.ByteUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 *  The actual payload of what is sent to the VMSs
 */
public final class TransactionEvent {

    // this payload
    // message type | tid | batch | size | event name | size | payload | size | precedence map
    private static final int FIXED_LENGTH = 1 + (2 * Long.BYTES) + (3 *  Integer.BYTES);

    public static void write(ByteBuffer buffer, PayloadRaw payload){
        buffer.put( Constants.EVENT );
        buffer.putLong( payload.tid );
        buffer.putLong( payload.batch );
        buffer.putInt( payload.event.length );
        buffer.put( payload.event );
        buffer.putInt( payload.payload.length );
        buffer.put( payload.payload );
        buffer.putInt( payload.precedenceMap.length );
        buffer.put( payload.precedenceMap );
    }

    public static Payload read(ByteBuffer buffer){
        long tid = buffer.getLong();
        long batch = buffer.getLong();
        int eventSize = buffer.getInt();
        String event = ByteUtils.extractStringFromByteBuffer( buffer, eventSize );
        int payloadSize = buffer.getInt();
        String payload = ByteUtils.extractStringFromByteBuffer( buffer, payloadSize );
//        System.out.println("VMS: Payload read for TID "+tid+" \n" + payload);
        int precedenceSize = buffer.getInt();
        String precedenceMap = ByteUtils.extractStringFromByteBuffer( buffer, precedenceSize );
        return new Payload( tid, batch, event, payload, precedenceMap, (Long.BYTES * 3) + eventSize + payloadSize + precedenceSize );
    }

    /**
     * This is the base class for representing the data transferred across the framework and the sidecar
     * It serves both for input and output
     * Why total size? to know the size beforehand, before inserting into the byte buffer
     * otherwise would need further controls...
     */
    public record PayloadRaw(
            long tid, long batch, byte[] event, byte[] payload, byte[] precedenceMap, int totalSize
    ){}

    //
    public record Payload(
            long tid, long batch, String event, String payload, String precedenceMap, int totalSize
    ){}

    public static PayloadRaw of(long tid, long batch, String event, String payload, String precedenceMap){
        // considering UTF-8
        // https://www.quora.com/How-many-bytes-can-a-string-hold
        byte[] eventBytes = event.getBytes(StandardCharsets.UTF_8);
        // System.out.println("Leader : Payload written for TID "+tid+" \n"+payload);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] precedenceMapBytes = precedenceMap.getBytes(StandardCharsets.UTF_8);
        return new PayloadRaw(tid, batch, eventBytes, payloadBytes, precedenceMapBytes,
                FIXED_LENGTH + eventBytes.length + payloadBytes.length + precedenceMapBytes.length);
    }

}