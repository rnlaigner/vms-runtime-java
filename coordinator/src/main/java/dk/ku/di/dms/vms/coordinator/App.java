package dk.ku.di.dms.vms.coordinator;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args )
    {

//        NioEventLoopGroup group = new NioEventLoopGroup();
//        ServerBootstrap bootstrap = new ServerBootstrap();
//        bootstrap.group(group)
//                .channel(NioServerSocketChannel.class)
//                .childHandler(new SimpleChannelInboundHandler<ByteBuf>() {
//                    @Override
//                    protected void channelRead0(ChannelHandlerContext ctx,
//                                                ByteBuf byteBuf) throws Exception {
//                        System.out.println("Received data");
//                    }
//                } );

        // the input a new-order transaction
//        a payload containin these 4 events {
//
//
//        "a", "customer", "customer-new-order-in" )
//                .input("b", "item","item-new-order-in" )
//                .input( "c", "stock","stock-new-order-in" )
//                .input( "d", "warehouse", "waredist-new-order-in" )
//    }
        // assemble this into a new new-order tx, giving a unique id

        // new order transaction
        TransactionBootstrap txBootstrap = new TransactionBootstrap();
        txBootstrap.init("new-order")
                .input( "a", "customer", "customer-new-order-in" )
                .input("b", "item","item-new-order-in" )
                .input( "c", "stock","stock-new-order-in" )
                .input( "d", "warehouse", "waredist-new-order-in" )
                .internal( "e", "customer","customer-new-order-out",  "a" )
                .internal( "f", "item","item-new-order-out", "b" )
                .internal( "g", "stock", "stock-new-order-out", "c" )
                .internal( "h", "warehouse","waredist-new-order-out", "d" )
                .terminal("i", "order", "e", "f", "g", "h" );


        // the coordinator receives many events ... it maintains these received events in main memory and then it forwards to the respective dbms proxies

        // at some point we do a checkpoint

        // a checkpoint is a batch of received events (transactions), the dbms proxies advances in time at every checkpoint

        // between checkpoints there is not guarantee, if failure, the dbms proxies they have to restore the old snpashot (checkpoint)

        // when fail, after coming back, the coordinator asks the dbms proxies about the current global state

        // deterministic execution, no 2-phase commit
        // in the coordinator, we batch the events, try to commit in batches, one at a time

        // we rollback the dbms proxies to the last checkpointed state (state + events applied so far at that time)

        // write down the protocol carefully
        // coordinators are stateless
        // if the coordinator is stateless, how can it ensure correctness?

        // the batch commit would require 2-PC?

        // TODO write the protocol.. just rely on the proxy, why do we need the coordinator? to sequence the events... and forward the events to other vms
        //  how can it be stateless if we need to remember the sequence of events?
        //  the sequence of events is stored together with the batch commit in each dbms proxy

        // we fix the problem of durability first
        // batch logging is fine, still need to logged it!

        // can we combine the proxies and the coordinator?
        // is that some microservice may require a lot of data processing. and increasing the number of tasks (sequencing) in the proxy, may be an overkill for the application
        // but you can have multiple proxies doing the work, one dbms proxy
        // separate functionality among the proxies


        // TODO 1 - setup server
        // socket connection. protocol do ad a new service, has to send the contextual info

        // TODO 2 - receive metadata from all microservices. the dbms daemon will connect

        // TODO 3 - build global view of vms dependencies/interactions

        // TODO 4 - receive transactions and verify whether they can be processed given the global application view (3)



    }
}