package dk.ku.di.dms.vms.micro_tpcc.repository.order;

import dk.ku.di.dms.vms.sdk.core.annotations.Repository;
import dk.ku.di.dms.vms.micro_tpcc.entity.NewOrder;
import dk.ku.di.dms.vms.modb.common.interfaces.IRepository;

@Repository
public interface INewOrderRepository extends IRepository<NewOrder.NewOrderId, NewOrder> {


}