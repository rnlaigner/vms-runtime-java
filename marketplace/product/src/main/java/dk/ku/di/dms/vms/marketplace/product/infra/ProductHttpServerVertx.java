package dk.ku.di.dms.vms.marketplace.product.infra;

import dk.ku.di.dms.vms.marketplace.common.Constants;
import dk.ku.di.dms.vms.marketplace.product.Product;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import dk.ku.di.dms.vms.sdk.embed.facade.AbstractProxyRepository;
import io.vertx.core.*;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

public final class ProductHttpServerVertx extends AbstractVerticle {

    static VmsApplication PRODUCT_VMS;
    static AbstractProxyRepository<Product.ProductId, Product> PRODUCT_REPO;

    @SuppressWarnings("unchecked")
    public static void init(VmsApplication productVms, int numVertices, boolean nativeTransport){
        PRODUCT_VMS = productVms;
        PRODUCT_REPO = (AbstractProxyRepository<Product.ProductId, Product>) PRODUCT_VMS.getRepositoryProxy("products");
        Vertx vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(nativeTransport));
        boolean usingNative = vertx.isNativeTransportEnabled();
        System.out.println("Vertx is running with native: " + usingNative);
        DeploymentOptions deploymentOptions = new DeploymentOptions()
                .setThreadingModel(ThreadingModel.EVENT_LOOP)
                .setInstances(numVertices);

        try {
            vertx.deployVerticle(ProductHttpServerVertx.class,
                            deploymentOptions
                    )
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace(System.out);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void start(Promise<Void> startPromise) {
        HttpServerOptions options = new HttpServerOptions();
        options.setPort(Constants.PRODUCT_HTTP_PORT);
        options.setHost("0.0.0.0");
        options.setTcpKeepAlive(true);
        HttpServer server = this.vertx.createHttpServer(options);
        server.requestHandler(new VertxHandler());
        server.listen(res -> {
            if (res.succeeded()) {
                startPromise.complete();
            } else {
                startPromise.fail(res.cause());
            }
        });
    }

    public static class VertxHandler implements Handler<HttpServerRequest> {
        @Override
        public void handle(HttpServerRequest exchange) {
            final String[] uriSplit = exchange.uri().split("/");
            if (uriSplit.length == 0 || !uriSplit[1].equals("product")) {
                handleError(exchange, "Invalid URI");
                return;
            }
            exchange.bodyHandler(buff -> {
                switch (exchange.method().name()) {
                    case "GET" -> {
                        int sellerId = Integer.parseInt(uriSplit[uriSplit.length - 2]);
                        int productId = Integer.parseInt(uriSplit[uriSplit.length - 1]);
                        try(var _ = PRODUCT_VMS.getTransactionManager().beginTransaction(0, 0, 0,true)) {
                            Product product = PRODUCT_REPO.lookupByKey(new Product.ProductId(sellerId, productId));
                            if (product == null) {
                                handleError(exchange, "Product does not exists");
                                return;
                            }
                            exchange.response().setChunked(true);
                            exchange.response().setStatusCode(200);
                            exchange.response().write(product.toString());
                            exchange.response().end();
                        } catch (Exception e) {
                            handleError(exchange, e.getMessage());
                        }
                    }
                    case "POST" -> {
                        try {
                            String payload = buff.toString(StandardCharsets.UTF_8);
                            ProductDbUtils.addProduct(payload, PRODUCT_REPO, PRODUCT_VMS.getTable("products"));
                            exchange.response().setStatusCode(200).end();
                        } catch (Exception e) {
                            handleError(exchange, e.getMessage());
                        }
                    }
                    default -> handleError(exchange, "Invalid URI");
                }
            });
        }
    }

    private static void handleError(HttpServerRequest exchange, String msg) {
        exchange.response().setChunked(true);
        exchange.response().setStatusCode(500);
        exchange.response().write(msg);
        exchange.response().end();
    }

}
