package dk.ku.di.dms.vms.tpcc.workload;

import dk.ku.di.dms.vms.utils.Utils;

import static dk.ku.di.dms.vms.tpcc.workload.Constants.*;

/**
 * Creates the input for a NewOrder transaction.
 */
public class NewOrderTransactionFactory {

    public static NewOrderTransactionInput buildWorkPackage(final Integer num_ware, final Integer max_items, Integer dist_per_ware){

        int dist_per_ware_ = DIST_PER_WARE;
        if (dist_per_ware != null){
            dist_per_ware_ = dist_per_ware;
        }

        final int num_ware_ = num_ware == null ? DEFAULT_NUM_WARE : num_ware;
        final int max_items_ = max_items == null ? MAX_ITEMS : max_items;

        // TODO implement table_num functionality later
        // local table_num = sysbench.rand.uniform(1, sysbench.opt.tables)

        int w_id = Utils.randomNumber(1, num_ware_);
        int d_id = Utils.randomNumber(1, dist_per_ware_);
        int c_id = Utils.nuRand(1023, 1, CUST_PER_DIST);

        int ol_cnt = Utils.randomNumber(MIN_NUM_ITEMS, MAX_NUM_ITEMS);
        int all_local = 1;

        int rbk = 0;

        int[] itemid = new int[MAX_NUM_ITEMS];
        int[] supware = new int[MAX_NUM_ITEMS];
        int[] qty = new int[MAX_NUM_ITEMS];

        // TODO adjust to make use of all_local variable...

        for (int i = 0; i < ol_cnt; i++) {

            itemid[i] = Utils.nuRand(8191, 1, max_items_);

            if (Utils.randomNumber(1, 100) != 1) {
                supware[i] = w_id;
            } else {
                supware[i] = otherWare(num_ware_, w_id);
                all_local = 0;
            }

            qty[i] = Utils.randomNumber(MIN_ITEM_QTD, MAX_ITEM_QTD);
        }

        return new NewOrderTransactionInput(w_id, d_id, c_id, ol_cnt, itemid, supware, qty);
    }

    /*
     * produce the id of a valid warehouse other than home_ware
     * (assuming there is one)
     */
    public static int otherWare(int num_ware, int home_ware) {
        int tmp;

        if (num_ware == 1) return home_ware;
        while ((tmp = Utils.randomNumber(1, num_ware)) == home_ware) ;
        return tmp;
    }

    public static String getOrderPerWarehouseAndDistrictId(int w_id, int d_id){
        return w_id + "_" + d_id;
    }

}
