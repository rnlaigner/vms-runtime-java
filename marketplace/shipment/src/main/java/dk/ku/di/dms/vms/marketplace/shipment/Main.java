package dk.ku.di.dms.vms.marketplace.shipment;

import dk.ku.di.dms.vms.marketplace.common.Constants;
import dk.ku.di.dms.vms.marketplace.common.Utils;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplicationOptions;

import java.util.Properties;

public final class Main {

    public static void main(String[] args) throws Exception {

        Properties properties = Utils.loadProperties();
        int networkBufferSize = Integer.parseInt( properties.getProperty("network_buffer_size") );
        int networkThreadPoolSize = Integer.parseInt( properties.getProperty("network_thread_pool_size") );
        String[] packages = String.valueOf( properties.getProperty("packages") ).split(",");

        VmsApplicationOptions options = new VmsApplicationOptions("localhost", Constants.SHIPMENT_VMS_PORT, packages, networkBufferSize, networkThreadPoolSize);

        VmsApplication vms = VmsApplication.build(options);
        vms.start();
    }

}
