package dk.ku.di.dms.vms.web_common.meta;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads are performed via single-thread anyway by design (completion handler),
 * but writes (and update to the channel after crashes) must be serialized to avoid concurrency errors
 *
 * Some attributes are non-final
 */
public class ConnectionMetadata {

    // generic, serves for both servers and VMSs, although the key may change (e.g., use of vms name or <host+port>)
    public int key;
    public final NodeType nodeType;

    public enum NodeType {
        SERVER,
        VMS
    }

    public final ByteBuffer readBuffer;
    public final ByteBuffer writeBuffer;
    public AsynchronousSocketChannel channel;

    // unique read thread by design (completion handler)
    // with batching of messages in windows, this will be no longer necessary
    public final ReentrantLock writeLock;

    public ConnectionMetadata(int key, NodeType nodeType, ByteBuffer readBuffer, ByteBuffer writeBuffer, AsynchronousSocketChannel channel, ReentrantLock writeLock) {
        this.key = key;
        this.nodeType = nodeType;
        this.readBuffer = readBuffer;
        this.writeBuffer = writeBuffer;
        this.channel = channel;
        this.writeLock = writeLock;
    }

}