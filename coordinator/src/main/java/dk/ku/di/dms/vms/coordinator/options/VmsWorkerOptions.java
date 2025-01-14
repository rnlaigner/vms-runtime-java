package dk.ku.di.dms.vms.coordinator.options;

public record VmsWorkerOptions(boolean active,
                               boolean logging,
                               int maxSleep,
                               int networkBufferSize,
                               int networkSendTimeout,
                               int numQueuesVmsWorker,
                               boolean initHandshake) {}