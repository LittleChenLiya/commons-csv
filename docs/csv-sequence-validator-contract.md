# CSV Sequence Validator Contract

`org.apache.commons.csv.sequence.CsvSequenceValidator` validates a read-only CSV record stream against a
caller-declared finite state machine, tracked independently per business key.

## Vocabulary

- **Business key**: value of the configured key column (e.g. `order`). Each key owns an independent state.
- **Target state**: value of the configured state column (e.g. `event`) on a record; the state the record asks the
  key to move to.
- **Initial states**: states a previously unseen key may start in.
- **Transitions**: declared `from -> to` pairs; anything not declared is illegal.
- **Final states**: reaching one completes the key's life cycle and frees its capacity slot.
- **Active key**: a key with an accepted, non-final state. Only active keys count against capacity.

## Gates (evaluated in this order, per record)

1. **Field conversion** ！ every registered `FieldConverter` must convert its field. A failure rejects the record
   with reason `FIELD_CONVERSION`.
2. **Capacity** ！ a record introducing a new key while `activeKeys == maxActiveKeys` is rejected with reason
   `CAPACITY_EXCEEDED`. Still-active states are **never evicted**; the failure changes no existing state.
3. **Transition legality** ！ the target state must be in the allowed set from the key's current state (or in the
   initial states for a new key); otherwise the record is rejected with reason `ILLEGAL_TRANSITION`.

Only when all gates pass is the key's state updated (or the key retired, if the target state is final).

## Rejections

Every rejected record produces a `Rejection` carrying:

- the 1-based **record number** (`CSVRecord#getRecordNumber()`),
- the **business key**,
- the **old state** (`null` for a key with no accepted state yet),
- the attempted target state,
- the **expected state set** ！ the states that would have been accepted (empty for capacity rejections),
- the reason and an optional detail message.

A rejected record never advances its key's state, so a rejected row cannot make a later illegal row legal.

## Capacity

`maxActiveKeys` bounds the number of simultaneously active keys. Overflow records for new keys are rejected;
existing active states are untouched. A slot is freed only when its key reaches a declared final state, after which
the key may be re-admitted as new.

## Read-only guarantee

The validator only reads the CSV input. Converted field values are discarded and no business data is written.

## Example

Order life cycle `new -> paid -> sent` (final), key column `order`, state column `event`:

- `a,new` `a,paid` `a,sent` ！ all accepted; `a` completes and frees its slot.
- `a,new` then `a,sent` ！ the `sent` record is rejected (`oldState=new`, `expected={paid}`); `a` stays `new`.
- Interleaved orders `a` and `b` progress independently.
