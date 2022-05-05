package dk.ku.di.dms.vms.coordinator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsible for assembling the topology of a transaction crossing virtual microservices
 */
public class TransactionBootstrap {

    protected List<EventIdentifier> topology; // the input events
    protected List<Terminal> terminals;
    // for fast seek
    protected Map<String, EventIdentifier> map;

    protected String name; // transaction name

    public TransactionBootstrap(){
        this.topology = new ArrayList<>();
        this.terminals = new ArrayList<>();
        this.map = new HashMap<>();
    }

    public TransactionBootstrapPlus init(String name){
        this.name = name;
        return TransactionBootstrapPlus.build(this);
    }

    public static class TransactionBootstrapPlus {

        private TransactionBootstrap transactionBootstrap;
        private static TransactionBootstrapPlus INSTANCE;

        private TransactionBootstrapPlus(){}

        protected static TransactionBootstrapPlus build(TransactionBootstrap transactionBootstrap){
            INSTANCE = new TransactionBootstrapPlus();
            INSTANCE.transactionBootstrap = transactionBootstrap;
            return INSTANCE;
        }

        public TransactionBootstrapPlus input(String alias, String vms, String event){
            EventIdentifier id = new EventIdentifier( alias, vms, event );
            transactionBootstrap.map.put( alias, id );
            transactionBootstrap.topology.add( id );
            return this;
        }

        public TransactionBootstrapPlus internal(String alias, String vms, String event, String... deps){

            if(deps == null) throw new RuntimeException("Cannot have an internal event without a parent event");

            EventIdentifier toAdd = new EventIdentifier( alias, vms, event );

            for(String dep : deps){
                EventIdentifier id = transactionBootstrap.map.get(dep);
                id.addChildren( toAdd );
            }

            transactionBootstrap.map.put( alias, toAdd );

            return this;
        }

        public TransactionBootstrapPlus terminal(String alias, String vms, String... deps){
            if(deps == null) throw new RuntimeException("Cannot have a terminal event without a parent event");


            Terminal terminal = new Terminal(vms);
            for(String dep : deps){
                EventIdentifier id = transactionBootstrap.map.get(dep);
                terminal.addDependence( id );
            }

            return this;
        }

    }

}