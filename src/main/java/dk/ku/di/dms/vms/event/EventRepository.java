package dk.ku.di.dms.vms.event;

import dk.ku.di.dms.vms.operational.DataOperationTask;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 *   This class has the objective to decouple completely the
 *   event handler (responsible for receiving external events)
 *   and the vms executor (responsible for collecting these events
 *   and reasoning about their schedule).
 *   Particularly, this class contains a repository of events
 *   that are ready for processing, either in case of
 *   events triggering data operations and results of operations.
 *
 *   This class is inspired by the Proxy pattern:
 *   https://en.wikipedia.org/wiki/Proxy_pattern
 *
 *   TODO to transfer data from a h2 to another, we can modify the script command to
 *          generate the sql query we want and then we transfer the file created
 *
 *   FIXME the concept of a channel (in the context of coroutines) may help in the future
 *         https://kotlinlang.org/docs/channels.html
 */
public final class EventRepository {

    final private static EventRepository INSTANCE = new EventRepository();

    /**
     * It represents events ready for scheduler consumption
     *
     */
    final public Queue<IEvent> inputQueue;

    /**
     *  It represents events ready for delivery
     *  The event handler threads consume from this queue
      */
    final public Queue<IEvent> outputQueue;

    /**
     * It represents events ready for exec consumption
     *
     */
    final public Queue<DataOperationTask> readyQueue;

    private EventRepository(){
        this.inputQueue = new ConcurrentLinkedQueue<>();
        this.outputQueue = new ConcurrentLinkedQueue<>();

        this.readyQueue = new ConcurrentLinkedQueue<>();
    }

    public static EventRepository get(){
        return INSTANCE;
    }

}
