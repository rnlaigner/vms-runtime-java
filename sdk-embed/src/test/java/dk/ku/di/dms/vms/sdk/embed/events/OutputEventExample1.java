package dk.ku.di.dms.vms.sdk.embed.events;

import dk.ku.di.dms.vms.modb.api.annotations.Event;

@Event
public class OutputEventExample1 {

    public final int id;
    public OutputEventExample1(int id) {
        this.id=id;
    }
}