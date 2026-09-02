package org.iphoto.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

// Runs a block of work in a brand-new, independently-committed transaction
// (PROPAGATION_REQUIRES_NEW), regardless of what transaction (if any) is already active on the
// calling thread.
//
// Exists specifically so callers don't each hand-build their own
// `new TransactionTemplate(...)` + `setPropagationBehavior(REQUIRES_NEW)`, and - more importantly
// - so REQUIRES_NEW actually works when the transactional work is called from *within the same
// class* as the caller: `@Transactional(propagation = REQUIRES_NEW)` is proxy-based and silently
// does nothing on such same-class ("self-invocation") calls, a common, easy-to-miss trap. This
// class drives the transaction directly via TransactionTemplate/PlatformTransactionManager, with
// no AOP proxy involved, so it behaves identically no matter where the caller lives - same class,
// a lambda, wherever.
//
// Typical use: "this write must be durably committed before I do something that can't be rolled
// back" - e.g. committing a DB row before moving a file into its final location on disk (see
// service/immich/ImmichImportService#finalizeUploadFromImmich, the motivating case: a file must
// only be moved into its permanent path once the row pointing at it is genuinely committed, not
// merely "no exception thrown yet" in some outer, not-yet-committed transaction).
@Service
public class RequiresNewTransactionService {

    private final TransactionTemplate requiresNewTemplate;

    public RequiresNewTransactionService(PlatformTransactionManager transactionManager) {
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Runs `action` in a new transaction and returns its result. By the time this method returns
    // normally, the transaction has committed - the result reflects durably-saved data.
    public <T> T runInNewTransaction(Supplier<T> action) {
        return requiresNewTemplate.execute(status -> action.get());
    }

    // Same as runInNewTransaction(Supplier<T>), for work with nothing to return.
    public void runInNewTransaction(Runnable action) {
        requiresNewTemplate.executeWithoutResult(status -> action.run());
    }
}
