package dk.ku.di.dms.vms.micro_tpcc.events;

import dk.ku.di.dms.vms.modb.common.event.IEvent;

public record CustomerNewOrderOut (
     float c_discount,
     String c_last,
     String c_credit,
    // simply forwarding
     int c_id) implements IEvent {}