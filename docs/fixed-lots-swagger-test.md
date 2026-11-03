# Fixed-size lots: a test in Swagger

A walk through the fixed-size lot rules with Swagger (`/swagger-ui.html`), on a **test** database
where V36 is applied. Each step gives the exact request and what to check afterwards. The numbers
assume a lot size of 100 kg and a grade with **no stock yet** (see step 0).

Use your own ids where the steps say `<...>`. Every `clientUuid` must be new: the ones below are
examples, change the last digits if a step says "already recorded" (`200` instead of `201`).

## 0. Before you start

1. `POST /api/auth/login` with the manager's email and password, then **Authorize** with the token.
2. `GET /api/settings`: `lotSizeKg` is `100`. If not, set it with `PUT /api/settings` (your current
   settings plus `"lotSizeKg": 100, "lotSizeReason": "Swagger test of fixed-size lots"`); only lots
   opened afterwards get that size.
3. Pick a grade with no stock: `GET /api/lots/stock` must not list it, and
   `GET /api/lots/open?gradeId=<grade>` must answer `204` (no lot being filled). Below it is
   `<grade>`. If every grade has stock, the steps still work, but the sale in step 3 first takes
   the older lots and the lot being filled; skip those with `skipLotIds` (see step 3) or read the
   numbers as "on top of what was there".
4. Two active members `<m1>` and `<m2>` (`GET /api/members`) and a buyer `<buyer>` (`GET /api/buyers`).
5. Turn SMS off so the manager can confirm deliveries in Swagger without the members' codes:
   `PUT /api/settings` with your current settings and `"smsEnabled": false`, for example

```json
{
  "smsEnabled": false,
  "cooperativeEmail": null,
  "location": "Busogo",
  "minOrderKg": 100,
  "committeeEmails": [],
  "cooperativeName": null,
  "cooperativePhone": null
}
```

Turn SMS back on at the end if it was on.

## 1. Record three deliveries (they wait, in no lot)

`POST /api/deliveries`, three times:

```json
{ "clientUuid": "6f1d5a40-0000-4000-8000-000000000001", "memberId": <m1>, "gradeId": <grade>, "quantityKg": 60 }
```
```json
{ "clientUuid": "6f1d5a40-0000-4000-8000-000000000002", "memberId": <m2>, "gradeId": <grade>, "quantityKg": 70 }
```
```json
{ "clientUuid": "6f1d5a40-0000-4000-8000-000000000003", "memberId": <m1>, "gradeId": <grade>, "quantityKg": 50 }
```

Expect for each: `201`, `confirmationStatus` `PENDING`, `lotId` and `lotCode` `null`, `lots` `[]`.
Call their ids `<d1>`, `<d2>`, `<d3>`. `GET /api/lots/open?gradeId=<grade>` still answers `204`.

## 2. Confirm them: they fill 100 kg lots in the order they are confirmed

`POST /api/deliveries/<d1>/review`, then `<d2>`, then `<d3>`, each with:

```json
{ "note": "Checked with the member" }
```

Expect:

| After | Delivery's `lots` | `GET /api/lots/open?gradeId=<grade>` |
|---|---|---|
| `<d1>` | `[{lotCode: L1, kg: 60}]` | lot L1, `totalKg` 60, `capacityKg` 100 |
| `<d2>` | `[{L1, kg: 40}, {L2, kg: 30}]`: it completes L1 and starts L2 | lot L2, `totalKg` 30 |
| `<d3>` | `[{L2, kg: 50}]` | lot L2, `totalKg` 80 |

`L1` and `L2` are the next lot codes of the grade (for example `A-2026-03` and `A-2026-04`).
`GET /api/lots/<L1 id>`: status `FULL`, `deliveredKg` 100, `shortfallKg` 0, members `<m1>` 60 kg and
`<m2>` 40 kg. `GET /api/lots/<L2 id>`: status `FILLING`, `deliveredKg` 80, `spaceLeftKg` 20.

## 3. Sell 130 kg: lot L1 whole, then 30 kg of L2 shared in proportion

First `GET /api/grades/<grade>/sale-preview?quantityKg=130&pricePerKg=300`, then
`POST /api/sales`:

```json
{
  "clientUuid": "6f1d5a40-0000-4000-8000-000000000010",
  "gradeId": <grade>,
  "buyerId": <buyer>,
  "quantityKg": 130,
  "pricePerKg": 300
}
```

If the grade had older stock (step 0), add `"skipLotIds": [<their lot ids>]` and
`"skipReason": "Testing the new lots only"`; only lots the sale would reach can be skipped.

Expect `201`, `totalAmountRwf` 39000, `totalDeductionRwf` 650 (130 kg x 5 RWF, rounded once),
`totalNetRwf` 38350, and these `batches` (the preview shows the same, with `lotPortionId`):

| Lot | Delivery | kg | gross | deduction | net |
|---|---|---|---|---|---|
| L1 | `<d1>` | 60.00 | 18000 | 300 | 17700 |
| L1 | `<d2>` | 40.00 | 12000 | 200 | 11800 |
| L2 | `<d2>` | 11.25 | 3375 | 56 | 3319 |
| L2 | `<d3>` | 18.75 | 5625 | 94 | 5531 |

L2 had 30 kg of `<d2>` and 50 kg of `<d3>`: 30 kg are taken 30 : 50, so 11.25 and 18.75.
Afterwards L1 is `SOLD`; L2 stays `FILLING` (it can sell and keep filling) with 50 kg left.

## 4. Fill L2 with a fourth delivery, then record a loss in it

`POST /api/deliveries`, then `POST /api/deliveries/<d4>/review` with `{"note": "Checked"}`:

```json
{ "clientUuid": "6f1d5a40-0000-4000-8000-000000000004", "memberId": <m2>, "gradeId": <grade>, "quantityKg": 20 }
```

L2 now holds 100 kg and is `FULL`. Then `POST /api/lots/<L2 id>/losses`:

```json
{ "quantityKg": 10, "reason": "ROT", "notes": "Wet corner of the store" }
```

Expect `201`. The loss is shared in proportion to what each has left in L2 (18.75, 31.25 and
20 kg, 70 kg in all); the kg already sold carry none. `GET /api/deliveries/<d2>`, `<d3>`, `<d4>`:
their `lots` entry for L2 shows `lostKg` 2.68, 4.46 and 2.86 (10.00 together). `GET /api/lots/<L2 id>`:
`lostKg` 10, `remainingKg` 60.

## 5. Reverse a delivery

1. `POST /api/deliveries/<d3>/reverse` with `{"reason": "Recorded for the wrong member"}`.
   Expect `409` "Delivery RCT-... already has 18.75 kg sold, so it can no longer be reversed or
   corrected." Nothing changes.
2. `POST /api/deliveries/<d4>/reverse` with `{"reason": "Recorded twice"}`. Expect `200`,
   `reversed: true`.

After 2: `GET /api/lots/<L2 id>`: status still `FULL`, `deliveredKg` 80, `shortfallKg` 20 (a full lot
is never filled again), `lostKg` still 10: `<d4>`'s 2.86 kg went to `<d2>` and `<d3>` in proportion,
so their L2 `lostKg` are now 3.75 and 6.25. The next delivery of the grade starts lot L3.

## 6. Check everything

- `GET /api/ledger/verify`: `valid: true`.
- `GET /api/lots/stock`, `GET /api/dashboard`: `lotsBeingFilled` is empty for the grade until the next
  confirmed delivery.
- Run `docs/fixed-lots-checks.sql` in the Neon SQL Editor: every true/false column is `true`.
- Turn SMS back on if needed (step 0).
