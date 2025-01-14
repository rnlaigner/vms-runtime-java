package dk.ku.di.dms.vms.modb.common.logging;

import dk.ku.di.dms.vms.modb.common.memory.MemoryUtils;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class CompressedLoggingHandler extends DefaultLoggingHandler {

    private static final LZ4Factory LZ4_FACTORY = LZ4Factory.fastestInstance();
    private static final LZ4Compressor LZ4_COMPRESSOR = LZ4_FACTORY.fastCompressor();

    private static final Map<Integer, ConcurrentLinkedQueue<ByteBuffer>> COMPRESSED_BUFFER_POOL = new ConcurrentHashMap<>();

    public CompressedLoggingHandler(FileChannel channel, String fileName) {
        super(channel, fileName);
    }

    @Override
    public void log(ByteBuffer byteBuffer) throws IOException {
        // get at least same size
        int maxCompressedLength = LZ4_COMPRESSOR.maxCompressedLength(byteBuffer.remaining());
        int key = MemoryUtils.nextPowerOfTwo(maxCompressedLength);
        Queue<ByteBuffer> targetBufferPool = COMPRESSED_BUFFER_POOL.computeIfAbsent(key, (x) -> new ConcurrentLinkedQueue<>());
        ByteBuffer targetBuffer = targetBufferPool.poll();
        if(targetBuffer == null){
            targetBuffer = ByteBuffer.allocateDirect(key);
            targetBuffer.position(0);
        }
        LZ4_COMPRESSOR.compress(byteBuffer, targetBuffer);
        byteBuffer.rewind();
        targetBuffer.flip();
        do {
            this.fileChannel.write(targetBuffer);
        } while(targetBuffer.hasRemaining());
        targetBuffer.clear();
        COMPRESSED_BUFFER_POOL.get(key).add(targetBuffer);
    }

}
