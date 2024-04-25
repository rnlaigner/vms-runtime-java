package dk.ku.di.dms.vms.marketplace.common.events;

import dk.ku.di.dms.vms.marketplace.common.inputs.UpdatePrice;
import dk.ku.di.dms.vms.modb.api.annotations.Event;

@Event
public final class PriceUpdated {

    public int sellerId;

    public int productId;

    public float price;

    public String instanceId;

    public PriceUpdated(){}

    public PriceUpdated(int sellerId,
                       int productId,
                       float price,
                       String instanceId) {
        this.sellerId = sellerId;
        this.productId = productId;
        this.price = price;
        this.instanceId = instanceId;
    }

    public UpdatePrice.ProductId getId(){
        return new UpdatePrice.ProductId(this.sellerId, this.productId);
    }

    public record ProductId( int sellerId, int productId){}

}