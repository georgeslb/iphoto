package org.iphoto.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Service
public class RequiresNewTransactionServiceImpl implements RequiresNewTransactionService {

    private final TransactionTemplate requiresNewTemplate;

    public RequiresNewTransactionServiceImpl(PlatformTransactionManager transactionManager) {
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T runInNewTransaction(Supplier<T> action) {
        return requiresNewTemplate.execute(status -> action.get());
    }

    @Override
    public void runInNewTransaction(Runnable action) {
        requiresNewTemplate.executeWithoutResult(status -> action.run());
    }
}
