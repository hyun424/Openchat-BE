package io.hyun424.openchat.chat.room.partition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "room_partition_state")
public class RoomPartitionState {

    @Id
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "partition_count", nullable = false)
    private int partitionCount;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomPartitionStatus status;

    @Column(name = "draining_partitions", nullable = false, length = 200)
    private String drainingPartitions;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    public RoomPartitionState(Long roomId,
                              int partitionCount,
                              int version,
                              RoomPartitionStatus status,
                              String drainingPartitions,
                              Instant updatedAt,
                              String updatedBy) {
        this.roomId = roomId;
        this.partitionCount = Math.max(1, partitionCount);
        this.version = Math.max(1, version);
        this.status = status == null ? RoomPartitionStatus.ACTIVE : status;
        this.drainingPartitions = drainingPartitions == null ? "" : drainingPartitions;
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        this.updatedBy = normalizeUpdatedBy(updatedBy);
    }

    public static RoomPartitionState initialize(Long roomId,
                                                int partitionCount,
                                                Instant now,
                                                String updatedBy) {
        return new RoomPartitionState(
                roomId,
                partitionCount,
                1,
                RoomPartitionStatus.ACTIVE,
                "",
                now,
                updatedBy
        );
    }

    public void scaleUp(int targetPartitionCount, Instant now, String updatedBy) {
        int resolvedTarget = Math.max(this.partitionCount, targetPartitionCount);
        if (resolvedTarget == this.partitionCount && this.status == RoomPartitionStatus.SCALING_UP) {
            touch(now, updatedBy);
            return;
        }
        this.partitionCount = resolvedTarget;
        this.status = RoomPartitionStatus.SCALING_UP;
        this.version++;
        touch(now, updatedBy);
    }

    public void completeScaleUp(Instant now, String updatedBy) {
        if (this.status != RoomPartitionStatus.SCALING_UP) {
            touch(now, updatedBy);
            return;
        }
        this.status = RoomPartitionStatus.ACTIVE;
        this.drainingPartitions = "";
        touch(now, updatedBy);
    }

    public void startDrain(String drainingPartitions, Instant now, String updatedBy) {
        String resolved = drainingPartitions == null ? "" : drainingPartitions;
        if (this.status == RoomPartitionStatus.DRAINING && this.drainingPartitions.equals(resolved)) {
            touch(now, updatedBy);
            return;
        }
        this.status = RoomPartitionStatus.DRAINING;
        this.drainingPartitions = resolved;
        this.version++;
        touch(now, updatedBy);
    }

    public void completeDrain(int targetPartitionCount, Instant now, String updatedBy) {
        this.partitionCount = Math.max(1, Math.min(this.partitionCount, targetPartitionCount));
        this.status = RoomPartitionStatus.ACTIVE;
        this.drainingPartitions = "";
        this.version++;
        touch(now, updatedBy);
    }

    private void touch(Instant now, String updatedBy) {
        this.updatedAt = now == null ? Instant.now() : now;
        this.updatedBy = normalizeUpdatedBy(updatedBy);
    }

    private String normalizeUpdatedBy(String updatedBy) {
        return updatedBy == null || updatedBy.isBlank() ? "system" : updatedBy;
    }
}
