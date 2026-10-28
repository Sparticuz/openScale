# Eufy T9140 V2 — experimental live support

## Scope

The handler supports the captured `eufy T9140 V2` / T9140 F6-service protocol:

- Service: `4143f6b0-5300-4900-4700-414943415245`
- Notifications: `4143f6b2-5300-4900-4700-414943415245`
- Observed write characteristic: `4143f6b1-5300-4900-4700-414943415245`

Only final live weigh-ins are published. Settling/progress notifications establish
session boundaries; they are not saved as independent measurements. No history,
battery, time sync, unit configuration, profile upload, or authentication support
is claimed. Other T9140 F7/FFB variants and C1/P1/P2 scales are not supported by this
handler. Recognition is by model name; the F6 characteristic is checked on connection.

This implementation was compiled and tested offline, not tried on hardware.
Subscription-only startup is deliberate: the capture contains notifications before
its initial application writes, and the published eufylife-ble-client T9140 path
subscribes without authentication/startup writes. Real connection/subscription
and notification timing must still be verified on the scale.

### Startup safety

The target scale's initial application writes were inspected before implementation:

- `AC 02 F2 <four timestamp bytes> CC <checksum>`: variable time synchronization.
- `AC 02 FE 06 01 00 CC D1`: unit/configuration-family write; not required by
  the offline parser, and its necessity on this hardware is unverified.
- `AC 02 FF 00 00 00 CF CE`: undocumented control command. Its exact effect is
  not established; it is **not** assumed to be a safe measurement-start command.

None are sent. The only outbound operation is the normal GATT subscription/CCCD.
There are no application characteristic writes, acknowledgements, history reads,
or history-clearing commands. A hardware trial should first test this passive path
rather than replaying an undocumented command sequence.

## Live frames and session association

Eight-byte frames start `AC 02`. The checksum is the sum of bytes 2 through 6,
modulo 256, stored at byte 7. Frames can concatenate or split between notifications.
The decoder scans after a bad checksum and retains at most seven unfinished bytes.
Unrecognized/configuration/history payloads are ignored, not decoded as history.

- Weight: bytes 2–3, big endian, divided by 10 kg. Bytes 4–5 must be zero.
  Byte 6 `CA` is final; `CE` is progress. Accepted weight range is 0.1–300 kg
  (a parser sanity bound, not a verified T9140 maximum specification).
- Impedance: `FD 01` in bytes 2–3, bytes 4–5 big endian, flag `CB` at byte 6.
  Zero and `FFFF` are unavailable, not usable impedance. `FD 00` is not a result.

Sanitized observed examples in tests:

- `AC0203FD0000CACA`: final 102.1 kg.
- `AC02FD0102B3CB7E`: raw impedance-labelled value 691.
- `AC02FD01029FCB6A`: raw value 671 in a separate 102.1 kg session.

Final weight precedes impedance in both captured sessions. Publication waits up to
3 seconds (timer polling interval 250 ms), then falls back to weight alone. Late
impedance after publication does not create or mutate a saved weigh-in. Repeated
finals do not extend the deadline or duplicate a result. Disconnect or lifecycle
scope cancellation flushes a pending final once and resets state.

Progress, a different final weight, reconnect/reset, or 15 seconds without a
recognized frame establishes a new session. Incomplete frames also expire after
a 15-second input gap. Independent same-weight sessions work across these
boundaries; identical final-only traffic without a boundary is indistinguishable
from retransmission. The timeout and idle boundary are implementation heuristics,
not reverse-engineered firmware guarantees.

## Composition and units

The selected path is Eufy/Belter-compatible estimation, **not StandardImpedance**.
The accepted working assumption is one transmitted impedance count per ohm for
openScale's impedance field. That is inferred, not electrically calibrated; the
frequency and resistance versus impedance-magnitude interpretation are unverified.
The calculator independently consumes `raw / 1000`, matching the app caller's
algorithm scaling. This division does not establish physical units.

Inputs are weight in kg, height in cm, age in years, sex, and the raw integer.
Missing/invalid numeric profile inputs or absent app identity omit estimates while
preserving weight and usable raw impedance. There is no athlete-mode argument in
the recovered T9140 calculator. Positive numeric acceptance is not validation for
all ages, body types, extreme sizes, pregnancy, or medical use.

Fat, water and muscle map to percentage points; bone is converted from the vendor's
rounded percentage to **kg** for openScale BONE. LBM is kg derived from the rounded
fat percentage. BMR maps to kcal/day; visceral fat is a dimensionless vendor index.
Percentages and estimates are rounded to one decimal; mass fields are derived after
percentage rounding. The Kotlin implementation uses readable double algebra after
float input scaling, not a bitwise native floating-point emulator. Native rounding
ties can differ. openScale owns display-unit conversion; the vendor's pound display
truncation is not imposed on the rest of the application.

For male, age 41, height 175 cm, weight 102.1 kg, both raw 691 and 671 reproduce
32.3% fat, 49.4% water, 37.1% muscle, 7.8% vendor bone, 8.0 kg bone,
69.1 kg LBM, 3075.4 kcal/day BMR and visceral index 16.6.

Profile-dependent estimates carry transient app-profile provenance. If selection
or smart assignment changes the eventual owner, or assisted weighing changes the
input weight, the save pipeline drops those estimates rather than saving another
profile's composition. Weight/raw impedance survive; ordinary inheritance and
explicitly selected app formulas still follow the user's settings. Provenance is
not a database field and does not change assignment behavior for other handlers.

## Evidence and provenance

Protocol evidence is limited to two target-scale sessions and selected public
T9140 client source. No private capture, device/account identifiers, archive,
bundled native library, or decompiled implementation is included.

The composition equations were reconstructed as readable arithmetic from narrow,
authorized local static inspection of EufyLife 3.3.12, package
`com.oceanwing.smarthome`, versionCode 743: the Belter `getFat` entry point and its
managed storage/mapping caller. No Android application or vendor native library
was executed. Female and alternative branches have algebraic test vectors, not
female hardware validation. Male displayed values were compared with supplied
screenshots; the 175 cm profile is an evidence-backed assumption, not a database
read. Neither screenshot uniquely distinguishes the two impedance samples.

The initial lean-like expression is also disclosed in CN108364690A, claim 10:
https://patents.google.com/patent/CN108364690A/zh . Public disclosure is not proof
of authorship, peer-reviewed validation, permission to incorporate proprietary
calculations, or patent/licensing clearance. Maintainer/legal review remains
necessary before distribution; no legal clearance is asserted here.

References:

- https://github.com/oliexdev/openScale/wiki/How-to-support-a-new-scale
- https://github.com/bdr99/eufylife-ble-client
- https://patents.google.com/patent/CN108364690A/zh

## Offline verification

JUnit exercises the actual production Kotlin parser, session accumulator,
calculator and handler callbacks, using sanitized captured frames and explicitly
synthetic malformed/split/noise/timeout fixtures. Calculator tests cover both sexes,
both visceral branches, age-40 bone branching, output clamps, invalid inputs and
rounding order. Handler tests check startup performs no application writes, unit
mapping, missing characteristic, absent profile, fallback, disconnect/reset and
scope cancellation. The real registry/catalog tests cover recognition/routing.

These checks do not establish real BLE interoperability, clinical accuracy,
complete native numerical equivalence, or end-to-end Android UI/database behavior
on a physical device. No device communication is part of offline verification.
