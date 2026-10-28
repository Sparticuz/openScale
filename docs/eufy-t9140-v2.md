# Eufy T9140 V2 collector

Experimental live weight and impedance collection for the captured F6 variant.
Advertised names: `eufy T9140` and `eufy T9140 V2`. Other T9140 service variants
and C1/P1/P2 protocols are not covered. No hardware trial has been performed.

## Transport and frames

- Service: `4143f6b0-5300-4900-4700-414943415245`
- Notify: `4143f6b2-5300-4900-4700-414943415245` (checked on connection)
- Subscription only: no application writes, startup commands, profile upload,
  history reads, acknowledgements or history clearing. Notifications preceded
  initial application writes in the target capture; passive startup still needs
  hardware verification. The public T9140 client below also subscribes passively.

Eight-byte frames start `AC02`; byte 7 is sum(bytes 2..6) modulo 256.
Weight is bytes 2–3 big endian / 10 kg, with bytes 4–5 zero and byte 6 `CA`
(final) or `CE` (settling). A 0.1–300 kg sanity bound is not a device rating.
Impedance requires bytes 2–3 `FD01`, bytes 4–5 big endian, and byte 6 `CB`.
Zero and `FFFF` are unavailable; `FD00` is not an impedance result.

Sanitized target frames retained in tests (no identifiers or raw captures):

- `AC0203FD0000CACA`: 102.1 kg final.
- `AC02FD0102B3CB7E`: impedance-labelled integer 691.
- `AC02FD01029FCB6A`: integer 671 in a separate 102.1 kg session.

One ohm/count is an accepted inferred mapping, not electrical calibration.
Frequency, electrode geometry and suitability for particular formulas remain
unverified. The handler supplies only WEIGHT (kg) and IMPEDANCE (ohm); it supplies
no derived body metrics or user slot. Existing app assignment, inheritance and
user-selected formulas are unchanged. The catalog does not mark device body
composition as implemented.

## Session and delivery limits

Split/coalesced notifications and checksum resynchronization are supported; at
most seven incomplete bytes remain buffered. Final weight can precede impedance.
Publication waits up to three seconds, polled every 250 ms, then emits weight
alone. Late impedance does not update an already published reading. Repeated
finals neither extend the deadline nor duplicate the result. Progress, a different
final weight, reset/reconnect or a 15-second recognized-frame gap starts a new
session. Incomplete bytes expire after a 15-second input gap. These timeouts are
heuristics; identical final-only sessions without a boundary cannot be separated
from retransmissions.

Disconnect/scope cancellation attempts a pending-final callback once. This is
NOT a persistence guarantee: baseline `BleConnector.releaseActiveCommunicator`
cancels event observation before closing the adapter. Reconnect/device switch or
owner teardown can therefore lose a pending final (or an undrained event). The
handler cannot acknowledge persistence through the existing API. No shared
lifecycle or save-pipeline change is included; a lossless-teardown requirement
needs a separately approved shared change. Handler callback tests do not establish
Room persistence or radio interoperability. Stay connected through completion;
this reduces exposure but is not a durability guarantee.

Protocol reference: https://github.com/bdr99/eufylife-ble-client
Contribution guide: https://github.com/oliexdev/openScale/wiki/How-to-support-a-new-scale
