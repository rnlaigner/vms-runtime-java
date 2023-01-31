package dk.ku.di.dms.vms.sdk.core.example;

import dk.ku.di.dms.vms.modb.api.annotations.Inbound;
import dk.ku.di.dms.vms.modb.api.annotations.Microservice;
import dk.ku.di.dms.vms.modb.api.annotations.Outbound;
import dk.ku.di.dms.vms.modb.api.annotations.Transactional;

@Microservice("example1")
public class MicroserviceExample1 {

    int count1;
    int count2;
    int sharedCount;

    public MicroserviceExample1(){
        count1 = 0;
        count2 = 0;
        sharedCount = 0;
    }

    @Inbound(values = {"in"})
    @Outbound("out1")
    @Transactional
    public OutputEventExample1 methodExample1(InputEventExample1 in) {
        count1++;
        sharedCount++;
        System.out.println("I am microservice 1: outputting out1 ");
        return new OutputEventExample1(count1);
    }

    @Inbound(values = {"in"})
    @Outbound("out2")
    @Transactional
    public OutputEventExample2 methodExample2(InputEventExample1 in) {
        count2++;
        sharedCount++;
        System.out.println("I am microservice 1: outputting out2 ");
        return new OutputEventExample2(count2);
    }

}