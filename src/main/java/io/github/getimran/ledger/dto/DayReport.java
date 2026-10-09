package io.github.getimran.ledger.dto;

import io.github.getimran.ledger.model.Accrual;
import io.github.getimran.ledger.model.AuthRecord;
import io.github.getimran.ledger.model.LedgerEntry;

import java.util.List;

/** Everything that happened on one booking day, snapshotted when the day closed. */
public record DayReport(
        int day,
        List<Outcome> outcomes,
        List<AccountSnapshot> accounts,
        List<FeeAssessment> fees,
        List<Accrual> accrualPostings,
        List<LedgerEntry> capitalizations,
        List<AuthRecord> authStates,
        List<LedgerError> errors) {
}
