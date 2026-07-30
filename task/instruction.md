# Bitemporal Expense Ledger State Machine

You are tasked with building a backend bitemporal event-sourcing state machine that processes financial ledger events (payments, splits, corrections, revocations, and reinstatements) to determine the exact net balances for all system users.

Transactions arrive out-of-order and carry two temporal coordinates:
- `valid_timestamp`: When the transaction took place in business logic.
- `system_revision`: The sequence/revision timestamp when the record was committed into the state store.

Your solution must read the input dataset at `/app/ledger_events.json` and output the computed balances to `/app/balances.json`.

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
1. **`payment`**: `{"event_id": "p1", "type": "payment", "parent_event_id": null, "payer_id": "u1", "amount": 100.0, "participants": ["u1", "u2"], "valid_timestamp": 100, "system_revision": 10}`
2. **`correction`**: `{"event_id": "c1", "type": "correction", "target_event_id": "p1", "amount": 150.0, "valid_timestamp": 110, "system_revision": 12}`
3. **`revocation`**: `{"event_id": "r1", "type": "revocation", "target_event_id": "p1", "valid_timestamp": 120, "system_revision": 15}`
4. **`reinstate`**: `{"event_id": "i1", "type": "reinstate", "target_event_id": "p1", "valid_timestamp": 130, "system_revision": 18}`

---

## Declarative Execution Rules

### 1. Global 2D Bitemporal Filter First
Filter out any event where `valid_timestamp > valid_timestamp_cutoff` OR `system_revision > system_revision_cutoff`. This filter is applied strictly first to all events. All subsequent state machine rules evaluate solely among surviving events.

### 2. Tie-Breaking Rule
When determining the latest status (`revocation`/`reinstate`) or latest `correction` targeting a payment:
- The event with the highest `system_revision` among surviving events wins.
- If multiple target events share the highest `system_revision`, select the event with the **lexicographically ascending `event_id`** (e.g. `"evt_01"` is selected over `"evt_02"`).

### 3. Orphans, Cycles, and Subtree Masking
- **Orphan Handling**: If a surviving payment's `parent_event_id` refers to an event that does not exist or was excluded by the 2D filter, the child payment is an orphan and is unconditionally revoked.
- **Cycle Detection**: If payments form a cycle via `parent_event_id` references, all payments in that cycle are unconditionally revoked.
- **Logical Inheritance**: A payment's base status is determined by its latest explicit status event (`revocation` or `reinstate`). Default base status is active. A payment is **Truly Active** if and only if it is not an orphan/cycle AND its base status is active AND **all of its parent ancestors up to root are Truly Active**. A revoked ancestor severs the branch, revoking the payment and its entire descendant subtree.

### 4. Effective Amount & Integer Penny-Rounding Split Math
- For each Truly Active payment, its amount is determined by its latest valid `correction` event (ABSOLUTE restatement) or original `amount`.
- Deduplicate `participants` to unique user IDs ($N$).
- Payer is credited the full effective amount.
- Compute integer-cent base share = `floor(Amount / N)`.
- Remainder cents $R = \text{Amount} \pmod N$ are distributed 1 cent ($0.01$) at a time to unique participants sorted in ascending alphabetical order of `user_id`.

### 5. Zero-Balance Completeness & Formatting
- **Every user ID** ever seen in the `payer_id` or `participants` array of ANY payment event in `/app/ledger_events.json` (including revoked, orphaned, cycle, or excluded payments) **MUST** be included in `/app/balances.json`.
- Output `/app/balances.json` must map `user_id` to exact 2-decimal formatted string values (e.g. `"12.50"`, `"-50.00"`, `"0.00"`).
