package dk.ku.di.dms.vms.marketplace.cart;

import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.cart.repositories.ICartItemRepository;
import dk.ku.di.dms.vms.marketplace.cart.repositories.IProductReplicaRepository;
import dk.ku.di.dms.vms.marketplace.common.events.PriceUpdated;
import dk.ku.di.dms.vms.marketplace.common.events.ProductUpdated;
import dk.ku.di.dms.vms.modb.api.annotations.Inbound;
import dk.ku.di.dms.vms.modb.api.annotations.Microservice;
import dk.ku.di.dms.vms.modb.api.annotations.PartitionBy;
import dk.ku.di.dms.vms.modb.api.annotations.Transactional;

import static dk.ku.di.dms.vms.marketplace.common.Constants.PRICE_UPDATED;
import static dk.ku.di.dms.vms.marketplace.common.Constants.PRODUCT_UPDATED;
import static dk.ku.di.dms.vms.modb.api.enums.TransactionTypeEnum.RW;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

@Microservice("cart")
public final class CartService {

    private static final System.Logger LOGGER = System.getLogger(CartService.class.getName());

    private final ICartItemRepository cartItemRepository;
    private final IProductReplicaRepository productReplicaRepository;

    public CartService(ICartItemRepository cartItemRepository,
                       IProductReplicaRepository productReplicaRepository) {
        this.cartItemRepository = cartItemRepository;
        this.productReplicaRepository = productReplicaRepository;
    }

    /* for testing purpose only
    @Inbound(values = {CUSTOMER_CHECKOUT})
    @Outbound(RESERVE_STOCK)
    @Transactional(type=RW)
    @PartitionBy(clazz = CustomerCheckout.class, method = "getId")
    public ReserveStock checkout(CustomerCheckout checkout) {
        LOGGER.log(INFO, "APP: Cart received a checkout request with TID: "+checkout.instanceId);

        // get cart items from a customer
        List<CartItem> cartItems = this.cartItemRepository.getCartItemsByCustomerId(checkout.CustomerId);

        if(cartItems == null || cartItems.isEmpty()) {
            LOGGER.log(ERROR, "APP: No cart items found for TID: "+checkout.instanceId);
            throw new RuntimeException("APP: No cart items found for TID: "+checkout.CustomerId);
        }

        for (var cartItem : cartItems) {
            var product = this.productReplicaRepository.lookupByKey(new ProductReplica.ProductId(cartItem.seller_id, cartItem.product_id));
            if (product != null && cartItem.version.contentEquals(product.version) && cartItem.unit_price < product.price) {
                cartItem.voucher += (product.price - cartItem.unit_price);
                cartItem.unit_price = product.price;
            }
        }

        this.cartItemRepository.deleteAll(cartItems);

        return new ReserveStock(new Date(), checkout, convertCartItems( cartItems ), checkout.instanceId);
    }

    private static List<dk.ku.di.dms.vms.marketplace.common.entities.CartItem> convertCartItems(List<CartItem> cartItems){
        return cartItems.stream().map(f-> new dk.ku.di.dms.vms.marketplace.common.entities.CartItem( f.seller_id, f.product_id, f.product_name, f.unit_price, f.freight_value, f.quantity, f.voucher, f.version)).toList();
    }
    */

    @Inbound(values = {PRICE_UPDATED})
    @Transactional(type=RW)
    @PartitionBy(clazz = PriceUpdated.class, method = "getId")
    public void updateProductPrice(PriceUpdated priceUpdated) {
        LOGGER.log(INFO,"APP: Cart received an update price event with version: "+priceUpdated.instanceId);

        // could use issue statement for faster update
        ProductReplica product = this.productReplicaRepository.lookupByKey(
                new ProductReplica.ProductId(priceUpdated.sellerId, priceUpdated.productId));

        if(product == null){
            LOGGER.log(WARNING,"Cart has no product replica with seller ID "+priceUpdated.sellerId+" : product ID "+priceUpdated.productId);
            return;
        }

        if(product.version.contentEquals(priceUpdated.instanceId)){
            product.price = priceUpdated.price;
        }

        // update all carts?

        this.productReplicaRepository.update(product);
    }

    /**
     * id being the [seller_id,product_id] is object causality
     * id being seller_id is seller causality
     */
    @Inbound(values = {PRODUCT_UPDATED})
    @Transactional(type=RW)
    @PartitionBy(clazz = ProductUpdated.class, method = "getId")
    public void processProductUpdate(ProductUpdated productUpdated) {
        LOGGER.log(INFO,"APP: Cart received a product update event with version: "+productUpdated.version);

        var product = new ProductReplica(productUpdated.seller_id, productUpdated.product_id, productUpdated.name, productUpdated.sku, productUpdated.category,
                productUpdated.description, productUpdated.price, productUpdated.freight_value, productUpdated.status, productUpdated.version);

        this.productReplicaRepository.upsert(product);
    }

}
