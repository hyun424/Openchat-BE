package io.hyun424.openchat.chat.room.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomAfterCommitExecutorTest {

    private final RoomAfterCommitExecutor executor = new RoomAfterCommitExecutor();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void execute_runsImmediatelyWhenTransactionSynchronizationIsInactive() {
        AtomicInteger runs = new AtomicInteger();

        executor.execute(runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    @Test
    void execute_defersActionUntilAfterCommitWhenTransactionSynchronizationIsActive() {
        AtomicInteger runs = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        executor.execute(runs::incrementAndGet);

        assertEquals(0, runs.get());
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        assertEquals(1, runs.get());
    }
}
