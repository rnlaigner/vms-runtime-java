package dk.ku.di.dms.vms.micro_tpcc.repository;

import dk.ku.di.dms.vms.micro_tpcc.events.StockNewOrderIn;
import dk.ku.di.dms.vms.micro_tpcc.events.StockNewOrderOut;
import dk.ku.di.dms.vms.modb.common.interfaces.IDTO;
import dk.ku.di.dms.vms.modb.common.interfaces.IRepository;
import dk.ku.di.dms.vms.modb.common.query.builder.QueryBuilderFactory;
import dk.ku.di.dms.vms.modb.common.query.builder.SelectStatementBuilder;
import dk.ku.di.dms.vms.modb.common.query.statement.IStatement;
import dk.ku.di.dms.vms.sdk.core.annotations.Inbound;
import dk.ku.di.dms.vms.sdk.core.annotations.Outbound;
import dk.ku.di.dms.vms.sdk.core.annotations.Repository;
import dk.ku.di.dms.vms.micro_tpcc.entity.Stock;

import static dk.ku.di.dms.vms.modb.common.query.enums.ExpressionTypeEnum.EQUALS;

@Repository
public interface IStockRepository extends IRepository<Stock.StockId, Stock> {

    // This would be the ideal world. For this first prototype, everything should be handled through service and queryAPI

    // I don't need a for update here like in the original TPC-C query, the code is decoupled!
    // This is in case of data dependence
//    @Inbound(values = "stock-new-order-in")
//    @Outbound(value = "stock-new-order-out")
//    @Query("FOR EACH i in input.ol_cnt DO " +
//            "SELECT s.s_i_id, s.s_w_id, s.s_dist " +
//            "FROM stock s " +
//            "WHERE s_i_id = input.itemIds.get(i) " +
//            "AND s_w_id = input.supware.get(i)" +
//            "GROUP BY s.s_i_id, s.s_w_id")
//    Map<Pair<Integer,Integer>,Float> getItemsDistributionInfo(StockNewOrderIn input); //(List<Integer> itemIds, Integer s_w_id);

    // T1 - order 1
    // T2 - order 2

    // they are all fulfilled data dependencies

}