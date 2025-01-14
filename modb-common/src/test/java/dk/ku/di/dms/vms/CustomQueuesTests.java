package dk.ku.di.dms.vms;

import dk.ku.di.dms.vms.modb.common.data_structure.OneProducerOneConsumerQueue;
import org.junit.Test;

import static java.lang.System.Logger.Level.INFO;

public class CustomQueuesTests {

    protected static final System.Logger logger = System.getLogger("CustomQueuesTests");

    private static final OneProducerOneConsumerQueue<Integer> queue =
            new OneProducerOneConsumerQueue<>(1);

    private static class Producer implements Runnable {

        @Override
        public void run() {
            Integer val = 1;
            while(true) {
                logger.log(INFO, "Adding "+val);
                queue.add(val);
                try {
                    logger.log(INFO, "Going to bed... ");
                    Thread.sleep(10000);
                    logger.log(INFO, "I woke up! Time to insert one more ");
                } catch (InterruptedException ignored) { }
                val++;
            }
        }
    }

    private static class Consumer implements Runnable {

        private volatile Integer val;

        public Consumer(){
            this.val = null;
        }

        @Override
        public void run() {

            while(true) {

                Integer newVal = queue.remove();
                if(newVal != null){
                    this.val = newVal;
                    logger.log(INFO, "Value read: "+val);
                }

            }
        }

        public int val(){
            return this.val;
        }

    }

    @Test
    public void test(){

        Thread producerThread = new Thread(new Producer());
        producerThread.start();

        var consumer = new Consumer();
        Thread consumerThread = new Thread(consumer);
        consumerThread.start();

        try {
            logger.log(INFO, "Main thread going to bed... ");
            Thread.sleep(20000);
            logger.log(INFO, "Main thread woke up!");
        } catch (InterruptedException ignored) { }

        // producer should be able to insert two numbers after 65000 milliseconds
        assert consumer.val() == 2;

    }



}
