package dk.ku.di.dms.vms.modb.common.event;

public record TransactionalEvent(int tid, IEvent event) {
}
