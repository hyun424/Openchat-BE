package io.hyun424.openchat.chat.room.workload.dto;

public enum RealtimeWorkloadRecommendationType {
    NO_ACTION,
    WATCH,
    SCALE_UP_CANDIDATE,
    CAP_LIMITED,
    INVESTIGATE_STALE_NODE,
    INVESTIGATE_SEND_FAILURE
}
