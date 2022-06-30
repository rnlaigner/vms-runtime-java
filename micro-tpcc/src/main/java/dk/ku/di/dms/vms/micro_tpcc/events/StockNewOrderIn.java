package dk.ku.di.dms.vms.micro_tpcc.events;

import dk.ku.di.dms.vms.modb.common.event.IVmsApplicationEvent;

public record StockNewOrderIn(
     int[] itemsIds,
     int[] quantity,
     int[] supware,
     int ol_cnt) implements IVmsApplicationEvent {}