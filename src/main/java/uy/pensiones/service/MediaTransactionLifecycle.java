package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class MediaTransactionLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MediaTransactionLifecycle.class);

    public void afterRollback(Runnable action) {
        if (action == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) runSafely("rollback-or-unknown", action);
            }
        });
    }

    public void afterCommit(Runnable action) {
        if (action == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely("immediate", action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely("after-commit", action);
            }
        });
    }

    private void runSafely(String phase, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("Operación de filesystem diferida falló. phase={}, error={}", phase, ex.getMessage(), ex);
        }
    }
}
