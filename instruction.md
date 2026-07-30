# Bitemporal Expense Ledger State Machine

## Overview
You are tasking with building a backend bitemporal event-sourcing state machine that processes financial ledger events (payments, splits, corrections, revocations, and reinstatements) to determine the exact net balances for all system users.

Transactions arrive out-of-order and carry two temporal coordinates:
- `valid_timestamp`: When the transaction took place in business logic.
- `system_revision`: The sequence/revision timestamp when the record was committed into the state store.

Your task is to implement an executable script at `/app/run.sh` (or compile and run your solution) that reads the input dataset at `/app/ledger_events.json` and outputs the computed balances to `/app/balances.json`.

---

## Input JSON Schema (`/app/ledger_events.json`)

The input file contains top-level cutoff boundaries and an array of event objects:

```json
{
  "valid_timestamp_cutoff": 5000,
  "system_revision_cutoff": 500,
  "events": [ ... ]
}
```

### Event Types

All events contain the standard fields `event_id`, `type`, `valid_timestamp`, and `system_revision`.

1. **`payment`**: Represents a financial expense payment.
   - `event_id` (string): Unique identifier.
   - `type`: `"payment"`
   - `parent_event_id` (string or null): ID of parent payment event, if linked.
   - `payer_id` (string): User ID of the payer.
   - `amount` (number): Payment amount in standard units (e.g., `100.0`).
   - `participants` (array of strings): User IDs of participants sharing the expense.
   - `valid_timestamp` (integer/long): Valid timeline coordinate.
   - `system_revision` (integer/long): System revision coordinate.

2. **`correction`**: Absolute restatement of a target payment's amount.
   - `event_id` (string)
   - `type`: `"correction"`
   - `target_event_id` (string): Targeted payment ID.
   - `amount` (number): Absolute restated amount.
   - `valid_timestamp`, `system_revision`

3. **`revocation`**: Revokes a target payment.
   - `event_id` (string)
   - `type`: `"revocation"`
   - `target_event_id` (string): Targeted payment ID.
   - `valid_timestamp`, `system_revision`

4. **`reinstate`**: Reinstates a previously revoked payment.
   - `event_id` (string)
   - `type`: `"reinstate"`
   - `target_event_id` (string): Targeted payment ID.
   - `valid_timestamp`, `system_revision`

---

## Declarative Execution Rules

### 1. Global 2D Bitemporal Filter First
Filter out any event where:
`valid_timestamp > valid_timestamp_cutoff` OR `system_revision > system_revision_cutoff`

This filter is applied **strictly first** to all events in the input file. All subsequent state machine rules apply **solely among surviving events**.

### 2. Tie-Breaking Rule
When determining the latest status (`revocation`/`reinstate`) or latest `correction` targeting a payment:
- The event with the highest `system_revision` among surviving events wins.
- If multiple target events share the same highest `system_revision`, the tie is broken by selecting the event with the **lexicographically ascending `event_id`** (e.g. `"evt_01"` is selected over `"evt_02"`).

### 3. Orphans, Cycles, and Subtree Masking
- **Orphan Handling**: If a surviving payment's `parent_event_id` refers to an event that does not exist or was excluded by the 2D filter, the child payment is an orphan and is unconditionally revoked.
- **Cycle Detection**: If payments form a cycle via `parent_event_id` references, all payments participating in or depending on the cycle are unconditionally revoked.
- **Logical Inheritance (Ancestry Cascade)**: A payment's base status is determined by its latest explicit status event (`revocation` or `reinstate`). If none exists, its base status is active. A payment is **Truly Active** if and only if it is not an orphan/cycle AND its base status is active AND **all of its parent ancestors up to root are Truly Active**. A revoked ancestor severs the branch, revoking the payment and its entire descendant subtree.

### 4. Effective Amount Determination
For each Truly Active payment:
- Its effective amount is determined by its latest surviving `correction` event (highest `system_revision`, tie-broken by `event_id` ascending).
- If no surviving correction event targeting the payment exists, its effective amount is its original `amount`.

---

## Net Balance Semantics & Split Mathematics

### 1. Participant Deduplication
Duplicate `user_id`s in a payment's `participants` array must be deduplicated to a single instance before splitting.

### 2. Payer Credit & Participant Debits
- The `payer_id` is credited the full active payment effective amount.
- Each unique user in `participants` is debited their computed share.
- Net Balance = Total Credits - Total Debits.

### 3. Integer Penny-Rounding Arithmetic
When splitting amount `A` (converted to integer cents) among `N` unique participants:
- Base share (in cents) = `floor(A / N)`.
- Remainder cents `R = A % N`.
- Sort the `N` unique participants in ascending alphabetical order of `user_id`.
- The first `R` participants in alphabetical order each receive `base_share + 1` cent debit.
- The remaining `N - R` participants each receive `base_share` cents debit.

### 4. Zero-Balance & Completeness
**Every user ID** ever seen in the `payer_id` or `participants` array of ANY payment event in the input file (including payments that are revoked, orphaned, in cycles, or excluded by the 2D filter) **MUST** be included in `/app/balances.json`. Users with no net active debits or credits must have a balance of `"0.00"`.

---

## Output Requirements (`/app/balances.json`)

The output `/app/balances.json` must be a JSON object mapping each seen user ID to their formatted net balance string:

```json
{
  "alice": "46.66",
  "bob": "26.67",
  "charlie": "-73.33",
  "david": "0.00"
}
```

### Strict Formatting Rules:
- All balance values must be formatted as strings with exactly 2 decimal places (e.g., `"12.50"`, `"-50.00"`).
- Zero values must strictly be `"0.00"` (never `"-0.00"` or `"0"`).
