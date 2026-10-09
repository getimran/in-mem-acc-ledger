package ae.mal.acc.ledger.service;

import ae.mal.acc.ledger.dto.AccountSnapshot;
import ae.mal.acc.ledger.dto.DayReport;
import ae.mal.acc.ledger.dto.FeeAssessment;
import ae.mal.acc.ledger.dto.LedgerError;
import ae.mal.acc.ledger.dto.Outcome;
import ae.mal.acc.ledger.model.Account;
import ae.mal.acc.ledger.model.Accrual;
import ae.mal.acc.ledger.model.AuthRecord;
import ae.mal.acc.ledger.model.Currency;
import ae.mal.acc.ledger.model.Event;
import ae.mal.acc.ledger.model.LedgerEntry;
import ae.mal.acc.ledger.model.LedgerEntry.EntryType;
import ae.mal.acc.ledger.util.AmountSplitter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static ae.mal.acc.ledger.config.LedgerConfig.DAILY_INTEREST_RATE;
import static ae.mal.acc.ledger.config.LedgerConfig.FIRST_DAY;
import static ae.mal.acc.ledger.config.LedgerConfig.LAST_DAY;
import static ae.mal.acc.ledger.config.LedgerConfig.OVERDRAFT_FEE;

/**
 * In-memory, append-only account ledger. Events are applied one booking day at a time; closing a
 * day runs overdraft fees and interest accrual, and on the last day capitalizes interest.
 *
 * <p>Nothing is ever removed or changed: ledger entries, authorization records, accrual lines and
 * errors are only appended. Backdated events are handled by re-evaluating every value day up to
 * today at each close and appending what is missing.
 */
public final class LedgerService {


    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final List<AuthRecord> authLog = new ArrayList<>();
    private final List<Accrual> accruals = new ArrayList<>();
    private final List<LedgerError> errors = new ArrayList<>();
    private final Set<String> reportedFeeGaps = new HashSet<>();

    private int openDay = FIRST_DAY;
    private final List<Outcome> todaysOutcomes = new ArrayList<>();
    private int errorsAtDayOpen = 0;

    public void openAccount(String id, Currency currency, BigDecimal openingBalance) {
        if (accounts.containsKey(id)) {
            throw new IllegalArgumentException("Account already open: " + id);
        }
        BigDecimal opening = currency.exact(openingBalance);
        if (opening == null) {
            throw new IllegalArgumentException("Opening balance " + openingBalance + " exceeds " + currency + " precision");
        }
        accounts.put(id, new Account(id, currency));
        if (opening.signum() != 0) {
            // Opening balances count from before Day 1.
            book(id, opening.signum() > 0 ? EntryType.CREDIT : EntryType.DEBIT, opening, 0, 0, "OPENING", "opening balance");
        }
    }

    public int openDay() {
        return openDay;
    }

    // ---------------------------------------------------------------- events

    public Outcome apply(Event event) {
        if (openDay > LAST_DAY) {
            throw new IllegalStateException("Window closed after Day " + LAST_DAY);
        }
        if (event.bookedDay() != openDay) {
            throw new IllegalStateException(event.id() + " is booked on Day " + event.bookedDay()
                    + " but the ledger is open for Day " + openDay);
        }
        Account account = accounts.get(event.accountId());
        if (account == null) {
            return reject(event, "unknown account " + event.accountId());
        }
        if (event.valueDay() < FIRST_DAY || event.valueDay() > event.bookedDay()) {
            return reject(event, "value date Day " + event.valueDay() + " is outside Day " + FIRST_DAY
                    + "..Day " + event.bookedDay() + " (future-dated or pre-window)");
        }
        return switch (event) {
            case Event.Credit e -> applyCredit(e, account);
            case Event.Debit e -> applyDebit(e, account);
            case Event.Authorization e -> applyAuthorization(e, account);
            case Event.Settlement e -> applySettlement(e, account);
            case Event.Reversal e -> applyReversal(e, account);
            case Event.InstalmentCredit e -> applyInstalmentCredit(e, account);
        };
    }

    private Outcome applyCredit(Event.Credit e, Account account) {
        BigDecimal amount = validAmount(e.amount(), account);
        if (amount == null) {
            return reject(e, invalidAmount(e.amount(), account));
        }
        book(account.id(), EntryType.CREDIT, amount, e.valueDay(), e.bookedDay(), e.id(), null);
        return accept(e, "posted " + account.currency().format(amount) + backdatedNote(e));
    }

    private Outcome applyDebit(Event.Debit e, Account account) {
        BigDecimal amount = validAmount(e.amount(), account);
        if (amount == null) {
            return reject(e, invalidAmount(e.amount(), account));
        }
        // Plain debits are not balance-checked: the brief applies the available-balance rule to
        // authorizations only, and overdraft fees exist precisely because debits can overdraw.
        book(account.id(), EntryType.DEBIT, amount.negate(), e.valueDay(), e.bookedDay(), e.id(), null);
        return accept(e, "posted " + account.currency().format(amount.negate()) + backdatedNote(e));
    }

    private Outcome applyAuthorization(Event.Authorization e, Account account) {
        BigDecimal amount = validAmount(e.amount(), account);
        if (amount == null) {
            return reject(e, invalidAmount(e.amount(), account));
        }
        if (latestAuth(e.authId()) != null) {
            return reject(e, "authorization " + e.authId() + " already exists");
        }
        Currency c = account.currency();
        BigDecimal ledgerBalance = ledgerBalance(account.id(), e.bookedDay());
        BigDecimal holds = activeHolds(account.id());
        BigDecimal availableAfter = ledgerBalance.subtract(holds).subtract(amount);
        boolean approved = availableAfter.signum() >= 0;
        String note = "ledger " + c.format(ledgerBalance) + " - holds " + c.format(holds) + " - this hold "
                + c.format(amount) + " = " + c.format(availableAfter) + (approved ? " >= 0" : " < 0");
        AuthRecord.Status status = approved ? AuthRecord.Status.APPROVED : AuthRecord.Status.DECLINED;
        authLog.add(new AuthRecord(e.authId(), account.id(), status, amount, null, e.bookedDay(), e.id(), note));
        return accept(e, e.authId() + " " + status + " (" + note + ")");
    }

    private Outcome applySettlement(Event.Settlement e, Account account) {
        BigDecimal amount = validAmount(e.amount(), account);
        if (amount == null) {
            return reject(e, invalidAmount(e.amount(), account));
        }
        AuthRecord auth = latestAuth(e.authId());
        if (auth == null) {
            return reject(e, "settlement references " + e.authId()
                    + ", which is not in the ledger; no funds moved");
        }
        if (!auth.accountId().equals(account.id())) {
            return reject(e, e.authId() + " belongs to " + auth.accountId() + ", not " + account.id());
        }
        if (auth.status() != AuthRecord.Status.APPROVED) {
            return reject(e, e.authId() + " is " + auth.status() + "; only an APPROVED authorization can settle");
        }
        Currency c = account.currency();
        if (amount.compareTo(auth.authorizedAmount()) > 0) {
            return reject(e, "settlement " + c.format(amount) + " exceeds authorized " + c.format(auth.authorizedAmount()));
        }
        book(account.id(), EntryType.SETTLEMENT, amount.negate(), e.valueDay(), e.bookedDay(), e.id(), e.authId());
        BigDecimal released = auth.authorizedAmount().subtract(amount);
        String note = "settled " + c.format(amount) + " of " + c.format(auth.authorizedAmount())
                + "; full hold released (" + c.format(released) + " unused)";
        authLog.add(new AuthRecord(e.authId(), account.id(), AuthRecord.Status.SETTLED, auth.authorizedAmount(), amount,
                e.bookedDay(), e.id(), note));
        return accept(e, e.authId() + " " + note);
    }

    private Outcome applyReversal(Event.Reversal e, Account account) {
        List<LedgerEntry> targets = entries.stream()
                .filter(x -> x.sourceEventId().equals(e.reversedEventId()))
                .toList();
        if (targets.isEmpty()) {
            return reject(e, "nothing booked by " + e.reversedEventId() + " to reverse");
        }
        if (targets.stream().anyMatch(x -> !x.accountId().equals(account.id()))) {
            return reject(e, e.reversedEventId() + " was not booked on " + account.id());
        }
        if (targets.stream().anyMatch(x -> x.type() != EntryType.CREDIT && x.type() != EntryType.DEBIT)) {
            // Reversing a settlement would also have to re-instate or close the hold; not defined.
            return reject(e, "only CREDIT/DEBIT events can be reversed; " + e.reversedEventId() + " is not one");
        }
        boolean alreadyReversed = entries.stream()
                .anyMatch(x -> x.type() == EntryType.REVERSAL && e.reversedEventId().equals(x.reference()));
        if (alreadyReversed) {
            return reject(e, e.reversedEventId() + " is already reversed");
        }
        BigDecimal total = account.currency().zero();
        for (LedgerEntry target : targets) {
            book(account.id(), EntryType.REVERSAL, target.amount().negate(), e.valueDay(), e.bookedDay(), e.id(),
                    e.reversedEventId());
            total = total.add(target.amount().negate());
        }
        return accept(e, "reversed " + e.reversedEventId() + " with a new entry of "
                + account.currency().format(total) + backdatedNote(e) + "; original entry left untouched");
    }

    private Outcome applyInstalmentCredit(Event.InstalmentCredit e, Account account) {
        BigDecimal total = validAmount(e.total(), account);
        if (total == null) {
            return reject(e, invalidAmount(e.total(), account));
        }
        if (e.instalments() < 1) {
            return reject(e, "instalment count must be at least 1");
        }
        List<BigDecimal> parts = AmountSplitter.split(total, e.instalments(), account.currency());
        if (parts.get(0).signum() == 0) {
            return reject(e, "total " + account.currency().format(total) + " is too small to split into "
                    + e.instalments() + " non-zero instalments");
        }
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            book(account.id(), EntryType.CREDIT, parts.get(i), e.valueDay(), e.bookedDay(), e.id(),
                    "instalment " + (i + 1) + "/" + parts.size());
            shown.add(parts.get(i).toPlainString());
        }
        return accept(e, "posted " + account.currency().format(total) + " as " + String.join(" + ", shown)
                + backdatedNote(e));
    }


    // ---------------------------------------------------------------- end of day

    public DayReport closeDay() {
        int day = openDay;
        if (day > LAST_DAY) {
            throw new IllegalStateException("Window closed after Day " + LAST_DAY);
        }
        List<FeeAssessment> fees = new ArrayList<>();
        List<Accrual> postings = new ArrayList<>();
        List<LedgerEntry> capitalizations = new ArrayList<>();
        for (Account account : accounts.values()) {
            assessOverdraftFees(account, day, fees);
            accrueInterest(account, day, postings);
            if (day == LAST_DAY) {
                LedgerEntry cap = capitalizeInterest(account, day);
                if (cap != null) {
                    capitalizations.add(cap);
                }
            }
        }
        DayReport report = new DayReport(day, List.copyOf(todaysOutcomes), snapshots(day), fees, postings,
                capitalizations, authStates(), List.copyOf(errors.subList(errorsAtDayOpen, errors.size())));
        todaysOutcomes.clear();
        errorsAtDayOpen = errors.size();
        openDay++;
        return report;
    }

    /**
     * Walks every value day up to today, earliest first, so a fee booked for an earlier day is
     * included in later days' closing balances. A day that already has a fee is never charged again,
     * and fees already booked are never removed.
     */
    private void assessOverdraftFees(Account account, int today, List<FeeAssessment> out) {
        for (int day = FIRST_DAY; day <= today; day++) {
            BigDecimal closing = ledgerBalance(account.id(), day);
            if (closing.signum() >= 0 || hasOverdraftFee(account.id(), day)) {
                continue;
            }
            BigDecimal fee = OVERDRAFT_FEE.get(account.currency());
            if (fee == null) {
                if (reportedFeeGaps.add(account.id() + "@" + day)) {
                    errors.add(new LedgerError(today, null, account.id() + " closed Day " + day + " negative ("
                            + account.currency().format(closing) + ") but no overdraft fee is defined for "
                            + account.currency() + "; nothing charged"));
                }
                continue;
            }
            book(account.id(), EntryType.OVERDRAFT_FEE, fee.negate(), day, today, "EOD-D" + today,
                    "overdraft Day " + day);
            out.add(new FeeAssessment(account.id(), day, fee, closing));
        }
    }

    /**
     * Brings the accrual journal in line with the current view of every value day: for each day the
     * target is round(rate x closing balance) when positive, else zero, and any difference from what
     * is already journalled is appended as a new line.
     */
    private void accrueInterest(Account account, int today, List<Accrual> out) {
        for (int day = FIRST_DAY; day <= today; day++) {
            BigDecimal base = interestBase(account.id(), day);
            BigDecimal delta = dailyAccrual(account, base).subtract(accruedFor(account.id(), day));
            if (delta.signum() != 0) {
                Accrual line = new Accrual(account.id(), day, delta, base, today);
                accruals.add(line);
                out.add(line);
            }
        }
    }

    private LedgerEntry capitalizeInterest(Account account, int today) {
        BigDecimal total = account.currency().zero();
        BigDecimal expected = account.currency().zero();
        for (int day = FIRST_DAY; day <= today; day++) {
            total = total.add(accruedFor(account.id(), day));
            expected = expected.add(dailyAccrual(account, interestBase(account.id(), day)));
        }
        if (total.compareTo(expected) != 0) {
            // Never discard or absorb a remainder: the capitalized total must equal the daily sum.
            throw new IllegalStateException("Accrual journal for " + account.id() + " sums to " + total
                    + " but the rounded daily accruals sum to " + expected);
        }
        if (total.signum() == 0) {
            return null;
        }
        return book(account.id(), EntryType.INTEREST, total, today, today, "EOD-D" + today,
                "interest capitalized for Day " + FIRST_DAY + "..Day " + today);
    }

    private BigDecimal interestBase(String accountId, int day) {
        return ledgerBalance(accountId, day, x -> x.type() != EntryType.INTEREST);
    }

    private static BigDecimal dailyAccrual(Account account, BigDecimal base) {
        if (base.signum() <= 0) {
            return account.currency().zero();
        }
        return account.currency().round(base.multiply(DAILY_INTEREST_RATE));
    }

    // ---------------------------------------------------------------- queries

    /** Closing ledger balance of a value day: every entry with value day on or before it. */
    public BigDecimal ledgerBalance(String accountId, int valueDay) {
        return ledgerBalance(accountId, valueDay, x -> true);
    }

    public BigDecimal ledgerBalance(String accountId, int valueDay, Predicate<LedgerEntry> filter) {
        BigDecimal sum = accounts.get(accountId).currency().zero();
        for (LedgerEntry entry : entries) {
            if (entry.accountId().equals(accountId) && entry.valueDay() <= valueDay && filter.test(entry)) {
                sum = sum.add(entry.amount());
            }
        }
        return sum;
    }

    public BigDecimal activeHolds(String accountId) {
        BigDecimal sum = accounts.get(accountId).currency().zero();
        for (AuthRecord auth : authStates()) {
            if (auth.accountId().equals(accountId) && auth.holdsFunds()) {
                sum = sum.add(auth.authorizedAmount());
            }
        }
        return sum;
    }

    public BigDecimal availableBalance(String accountId, int valueDay) {
        return ledgerBalance(accountId, valueDay).subtract(activeHolds(accountId));
    }

    /** Read-only view of the ledger, in booking order. */
    public List<LedgerEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<Accrual> accruals() {
        return Collections.unmodifiableList(accruals);
    }

    public List<LedgerError> errors() {
        return Collections.unmodifiableList(errors);
    }

    public List<AuthRecord> authLog() {
        return Collections.unmodifiableList(authLog);
    }

    /** Latest record of each authorization, in order of first appearance. */
    public List<AuthRecord> authStates() {
        Map<String, AuthRecord> latest = new LinkedHashMap<>();
        for (AuthRecord record : authLog) {
            latest.put(record.authId(), record);
        }
        return List.copyOf(latest.values());
    }

    public Account account(String accountId) {
        return accounts.get(accountId);
    }

    // ---------------------------------------------------------------- internals

    private LedgerEntry book(String accountId, EntryType type, BigDecimal amount, int valueDay, int bookedDay,
                             String sourceEventId, String reference) {
        LedgerEntry entry = new LedgerEntry(entries.size() + 1, accountId, type, amount, valueDay, bookedDay,
                sourceEventId, reference);
        entries.add(entry);
        return entry;
    }

    private boolean hasOverdraftFee(String accountId, int valueDay) {
        return entries.stream().anyMatch(x -> x.accountId().equals(accountId)
                && x.type() == EntryType.OVERDRAFT_FEE && x.valueDay() == valueDay);
    }

    private BigDecimal accruedFor(String accountId, int day) {
        BigDecimal sum = accounts.get(accountId).currency().zero();
        for (Accrual line : accruals) {
            if (line.accountId().equals(accountId) && line.forDay() == day) {
                sum = sum.add(line.amount());
            }
        }
        return sum;
    }

    private AuthRecord latestAuth(String authId) {
        AuthRecord found = null;
        for (AuthRecord record : authLog) {
            if (record.authId().equals(authId)) {
                found = record;
            }
        }
        return found;
    }

    private List<AccountSnapshot> snapshots(int day) {
        List<AccountSnapshot> out = new ArrayList<>();
        for (Account account : accounts.values()) {
            List<BigDecimal> byValueDay = new ArrayList<>();
            for (int d = FIRST_DAY; d <= day; d++) {
                byValueDay.add(ledgerBalance(account.id(), d));
            }
            out.add(new AccountSnapshot(account.id(), account.currency(), ledgerBalance(account.id(), day),
                    availableBalance(account.id(), day), List.copyOf(byValueDay)));
        }
        return out;
    }

    private static BigDecimal validAmount(BigDecimal amount, Account account) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        return account.currency().exact(amount);
    }

    private static String invalidAmount(BigDecimal amount, Account account) {
        return "amount " + amount + " must be positive and representable in " + account.currency()
                + " (" + account.currency().scale() + " decimal places)";
    }

    private static String backdatedNote(Event e) {
        return e.valueDay() < e.bookedDay() ? " (backdated to value Day " + e.valueDay() + ")" : "";
    }

    private Outcome accept(Event e, String message) {
        Outcome outcome = new Outcome(e.id(), true, message);
        todaysOutcomes.add(outcome);
        return outcome;
    }

    private Outcome reject(Event e, String message) {
        Outcome outcome = new Outcome(e.id(), false, message);
        todaysOutcomes.add(outcome);
        errors.add(new LedgerError(e.bookedDay(), e.id(), message));
        return outcome;
    }
}
