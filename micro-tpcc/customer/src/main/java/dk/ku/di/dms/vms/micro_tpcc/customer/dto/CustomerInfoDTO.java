package dk.ku.di.dms.vms.micro_tpcc.customer.dto;

import dk.ku.di.dms.vms.modb.api.interfaces.IDTO;

public class CustomerInfoDTO implements IDTO {

    public float c_discount;
    public String c_last;
    public String c_credit;

    public float c_discount() {
        return c_discount;
    }

    public String c_last() {
        return c_last;
    }

    public String c_credit() {
        return c_credit;
    }
}