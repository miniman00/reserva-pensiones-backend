package uy.pensiones.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaTransactionLifecycleTest {

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackActionRunsOnlyWhenTransactionRollsBack() {
        MediaTransactionLifecycle lifecycle = new MediaTransactionLifecycle();
        AtomicInteger calls = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        lifecycle.afterRollback(calls::incrementAndGet);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertEquals(1, calls.get());
    }

    @Test
    void afterCommitActionDoesNotRunBeforeCommit() {
        MediaTransactionLifecycle lifecycle = new MediaTransactionLifecycle();
        AtomicInteger calls = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        lifecycle.afterCommit(calls::incrementAndGet);
        assertEquals(0, calls.get());
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        assertEquals(1, calls.get());
    }
}
