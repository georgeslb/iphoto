package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - RequiresNewTransactionService has no HTTP endpoint of its own (it's
// a low-level transaction-running helper consumed internally by other services, see its own
// class javadoc), so both public methods are exercised by autowiring the interface directly.
class RequiresNewTransactionServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RequiresNewTransactionService requiresNewTransactionService;

    @Test
    void runInNewTransactionWithSupplierReturnsTheSuppliedValue() {
        String result = requiresNewTransactionService.runInNewTransaction(() -> "committed-value");

        assertThat(result).isEqualTo("committed-value");
    }

    @Test
    void runInNewTransactionWithRunnableActuallyRunsTheAction() {
        boolean[] ran = {false};

        requiresNewTransactionService.runInNewTransaction((Runnable) () -> ran[0] = true);

        assertThat(ran[0]).isTrue();
    }
}
