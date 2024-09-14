package dk.ku.di.dms.vms.marketplace.seller.infra;

import dk.ku.di.dms.vms.marketplace.common.Constants;
import dk.ku.di.dms.vms.marketplace.seller.SellerService;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import io.vertx.core.*;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;

import java.util.concurrent.ExecutionException;

public class SellerHttpServerVertx extends AbstractVerticle {

    static VmsApplication SELLER_VMS;
    static SellerService SELLER_SERVICE;

    public static void init(VmsApplication sellerVms, int numVertices, int httpThreadPoolSize, boolean nativeTransport){
        SELLER_VMS = sellerVms;
        var optService = SELLER_VMS.<SellerService>getService();
        if(optService.isEmpty()){
            throw new RuntimeException("Could not find service for SellerService");
        }
        SELLER_SERVICE = optService.get();
        VertxOptions vertxOptions = new VertxOptions().setPreferNativeTransport(nativeTransport);
        if(httpThreadPoolSize > 0){
            vertxOptions.setEventLoopPoolSize(httpThreadPoolSize);
        }
        Vertx vertx = Vertx.vertx(vertxOptions);
        boolean usingNative = vertx.isNativeTransportEnabled();
        DeploymentOptions deploymentOptions = new DeploymentOptions()
                .setThreadingModel(ThreadingModel.EVENT_LOOP)
                .setInstances(numVertices);
        try {
            vertx.deployVerticle(SellerHttpServerVertx.class,
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
        options.setPort(Constants.SELLER_HTTP_PORT);
        options.setHost("0.0.0.0");
        options.setTcpKeepAlive(true);
        options.setTcpNoDelay(true);
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
            String[] uriSplit = exchange.uri().split("/");
            switch (exchange.method().name()){
                case "GET" -> {
                    // assert uriSplit[1].equals("seller") && uriSplit[2].equals("dashboard");
                    int sellerId = Integer.parseInt(uriSplit[uriSplit.length - 1]);
                    // picking the last tid "finished"
                    long tid = SELLER_VMS.lastTidFinished();
                    try (var txCtx = SELLER_VMS.getTransactionManager().beginTransaction(tid, 0, tid, true)) {
                        var view = SELLER_SERVICE.queryDashboard(sellerId);
                        exchange.response().setChunked(true);
                        exchange.response().setStatusCode(200);
                        exchange.response().write(view.toString());
                        exchange.response().end();
                    } catch (Exception e) {
                        exchange.response().setChunked(true);
                        exchange.response().setStatusCode(500);
                        exchange.response().write(e.getMessage());
                        exchange.response().end();
                    }
                }
                case "PATCH" -> {
                    // path: /seller/reset
                    try {
                        SELLER_VMS.getTransactionManager().reset();
                        exchange.response().setStatusCode(200).end();
                    } catch (Exception e){
                        handleError(exchange, e.getMessage());
                    }
                }
                default -> handleError(exchange, "Invalid URI");
            }
        }

        private static void handleError(HttpServerRequest exchange, String msg) {
            exchange.response().setChunked(true);
            exchange.response().setStatusCode(500);
            exchange.response().write(msg);
            exchange.response().end();
        }
    }

}