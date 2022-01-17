package dk.ku.di.dms.vms.tpcc.service;

import dk.ku.di.dms.vms.annotations.Inbound;
import dk.ku.di.dms.vms.annotations.Microservice;
import dk.ku.di.dms.vms.annotations.Transactional;
import dk.ku.di.dms.vms.tpcc.entity.NewOrder;
import dk.ku.di.dms.vms.tpcc.entity.Order;
import dk.ku.di.dms.vms.tpcc.entity.OrderLine;
import dk.ku.di.dms.vms.tpcc.events.*;
import dk.ku.di.dms.vms.tpcc.repository.INewOrderRepository;
import dk.ku.di.dms.vms.tpcc.repository.IOrderLineRepository;
import dk.ku.di.dms.vms.tpcc.repository.IOrderRepository;
import org.javatuples.Pair;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Microservice("order")
public class OrderService {

    private final IOrderRepository orderRepository;
    private final INewOrderRepository newOrderRepository;
    private final IOrderLineRepository orderLineRepository;

    public OrderService(IOrderRepository orderRepository,
                        INewOrderRepository newOrderRepository,
                        IOrderLineRepository orderLineRepository){
        this.orderRepository = orderRepository;
        this.newOrderRepository = newOrderRepository;
        this.orderLineRepository = orderLineRepository;
    }

    // need all values from waredist out
    @Inbound(values = {"customer-new-order-out","stock-new-order-out",
            "items-new-order-out","waredist-new-order-out"})
    @Transactional
    public void processNewOrderItems(CustomerNewOrderOut customerNewOrderOut,
                                     StockNewOrderOut stockNewOrderOut,
                                     StockNewOrderIn stockNewOrderIn,
                                     ItemNewOrderOut itemsNewOrderOut,
                                     WareDistNewOrderOut wareDistNewOrderOut){
        int n = stockNewOrderIn.ol_cnt;
        ExecutorService exec = Executors.newFixedThreadPool(n);
        CompletableFuture<?>[] futures = new CompletableFuture[n+1];

        futures[n] = CompletableFuture.runAsync(() -> {

            Order orderToInsert = new Order(
                    wareDistNewOrderOut.d_next_o_id,
                    wareDistNewOrderOut.d_id,
                    wareDistNewOrderOut.d_w_id,
                    customerNewOrderOut.c_id,
                    n  // <====== ol_count is necessary from the number of item list
            );

            orderRepository.insert(orderToInsert);

            NewOrder newOrderToInsert = new NewOrder(
                    wareDistNewOrderOut.d_next_o_id,
                    wareDistNewOrderOut.d_id,
                    wareDistNewOrderOut.d_w_id
            );

            newOrderRepository.insert(newOrderToInsert);

        });

        for(int i = 0; i < n; i++){

            int finalI = i;
            int itemId = stockNewOrderIn.itemsIds.get(finalI);
            int wareId = stockNewOrderIn.supware.get(finalI);
            futures[i] = CompletableFuture.runAsync(() -> {

                Float ol_amount = stockNewOrderIn.quantity.get(finalI) *
                        itemsNewOrderOut.itemsPrice.get(itemId) *
                        (1 + wareDistNewOrderOut.w_tax + wareDistNewOrderOut.d_tax) *
                        (1 - customerNewOrderOut.c_discount);

                OrderLine ol = new OrderLine();
                ol.ol_o_id = wareDistNewOrderOut.d_next_o_id;
                ol.ol_d_id = wareDistNewOrderOut.d_id;
                ol.ol_w_id = wareDistNewOrderOut.d_w_id;
                ol.ol_amount = ol_amount;
                Pair<Integer,Integer> itemStockId = new Pair<>(itemId,wareId);
                ol.ol_dist_info = stockNewOrderOut.itemsDistInfo.get(itemStockId);
                ol.ol_number = finalI+1;
                ol.ol_quantity = n;
                ol.ol_i_id = itemId;
                ol.ol_supply_w_id = wareId;

                orderLineRepository.insert(ol);

            });

        }

        CompletableFuture.allOf(futures).join();
        exec.shutdown();

    }


}
