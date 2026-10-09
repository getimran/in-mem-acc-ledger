package ae.mal.acc.ledger.dto;

import ae.mal.acc.ledger.model.Accrual;
import ae.mal.acc.ledger.model.AuthRecord;
import ae.mal.acc.ledger.model.LedgerEntry;

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
