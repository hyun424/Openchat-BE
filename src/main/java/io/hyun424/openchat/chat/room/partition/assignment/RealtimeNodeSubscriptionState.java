package io.hyun424.openchat.chat.room.partition.assignment;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class RealtimeNodeSubscriptionState {

    private final Set<Integer> subscribedPartitions = ConcurrentHashMap.newKeySet();

    public Set<Integer> subscribedPartitions() {
        return Set.copyOf(subscribedPartitions);
    }

    public void add(Integer partitionId) {
        if (partitionId != null) {
            subscribedPartitions.add(partitionId);
        }
    }

    public void remove(Integer partitionId) {
        if (partitionId != null) {
            subscribedPartitions.remove(partitionId);
        }
    }

    public void replace(Set<Integer> partitions) {
        subscribedPartitions.clear();
        subscribedPartitions.addAll(partitions);
    }
}
