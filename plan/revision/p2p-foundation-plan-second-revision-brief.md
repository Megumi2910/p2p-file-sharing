# Second Revision Brief for `project-foundation-plan(1).md`

## Purpose

Please revise the current foundation plan one more time.

The revised plan is now technically strong, proportionate, and largely implementation-ready. It correctly preserves the Java 21 / Maven / three-module / Swing / trusted-LAN architecture and has incorporated the previous revision brief well.

This pass is **not** a request to redesign the plan.

Instead, please make a small set of focused corrections and clarifications before implementation begins.

The main goals of this revision are:

- remove one timeout race;
- place transfer limits at the correct architectural layer;
- clarify the meaning of the tracker capacity constant;
- slightly strengthen peer identity validation;
- simplify the intended VM demonstration path;
- clarify whether VM proof blocks future coding or only blocks deployment/foundation completion.

After these corrections, the plan should be considered sufficiently detailed for implementation. Do not expand stabilization scope further unless a concrete implementation defect requires it.

---

# Overall assessment

The current revision is strong in the following areas:

- F-01A/F-01B successfully separate minimum planning authority from the large documentation migration;
- transfer correctness now precedes peripheral tracker hardening;
- file-transfer chunks have a dedicated 8 MiB ceiling while the generic frame ceiling remains 64 MiB;
- duplicate-chunk idempotency is implementable with constant session memory;
- application ACK semantics are correctly distinguished from TCP reliability;
- verified hard-link publication is explicitly a local NTFS/ext4 operational contract;
- generic peer-list parsing and tracker semantic capacity are separated;
- runtime shutdown uses one shared monotonic deadline;
- F-08A and F-08B now distinguish representative required proof from deep regression work;
- future resume/catalogue/multi-source dependencies remain correctly gated.

Keep all of these improvements.

Do not reintroduce the original migration-first ordering.

Do not add frameworks, databases, Spring, Netty, WebSockets, REST, or public-network security work.

---

# Required revision 1 — Separate the human prompt timeout from the sender's offer-response timeout

## Problem

The current plan defines:

```properties
transfer.offer.timeout.ms=120000
```

and uses the same value for both:

1. how long the receiver allows the human user to answer the incoming-file prompt; and
2. how long the sender waits to receive `FILE_ACCEPT` or `FILE_REJECT`.

This creates a timing race.

Example:

```text
t = 0.000
Alice sends FILE_OFFER

t = 0.200
Bob receives FILE_OFFER and starts the 120-second UI prompt

t = 120.000
Alice's 120-second response wait expires

t = 120.200
Bob's 120-second prompt budget expires
```

Alice may therefore fail the transfer while Bob is still legitimately within his configured decision window.

The sender response wait must always be longer than the receiver's human-decision window.

## Revision

Replace the single conceptual timeout with two distinct configuration values.

Recommended defaults:

```properties
transfer.prompt.timeout.ms=120000
transfer.offer.response.timeout.ms=135000
```

Suggested `AppConfig` components:

```java
int transferPromptTimeoutMillis
int transferOfferResponseTimeoutMillis
```

The exact defaults may differ, but the required contract is:

```text
offer-response timeout > prompt timeout
```

Preferably enforce that relation during configuration validation.

For example:

```text
transfer.prompt.timeout.ms = 120000
transfer.offer.response.timeout.ms = 135000
```

means:

```text
Receiver human decision budget: 120 seconds

Sender FILE_ACCEPT / FILE_REJECT wait:
135 seconds
```

This leaves room for:

- network latency;
- scheduling delay;
- EDT dispatch;
- prompt construction;
- the receiver sending the final accept/reject frame.

## Updated semantics

### Receiver

Use:

```text
transfer.prompt.timeout.ms
```

for:

```java
IncomingFilePrompt.accept(...)
```

and its Swing timer / future timeout.

Prompt expiration produces:

```text
REJECTED
```

and cannot later allocate a partial file or accept a stale click.

### Sender

Use:

```text
transfer.offer.response.timeout.ms
```

while waiting for:

```text
FILE_ACCEPT
FILE_REJECT
```

This timeout is a network/protocol wait, not the user's decision budget.

### Other transfer timeouts remain separate

Keep:

```properties
transfer.read.timeout.ms
transfer.verify.timeout.ms
```

with their existing phase meanings.

Do not merge all transfer phases into one global transfer timeout.

---

# Required revision 2 — Move the chunk-size protocol ceiling out of `FileMetadata`

## Current design

The plan currently proposes:

```java
FileMetadata.MAX_CHUNK_BYTES
```

with:

```text
MAX_CHUNK_BYTES = 8 MiB
```

This is technically workable.

However, 8 MiB is not an intrinsic property of the `FileMetadata` value object.

It is a **transfer-protocol constraint**.

## Revision

Prefer introducing a small constants-only transfer protocol class.

Suggested location:

```text
common/
└── src/main/java/vn/edu/p2p/common/protocol/
    └── TransferProtocol.java
```

Example:

```java
public final class TransferProtocol {

    public static final int DEFAULT_CHUNK_BYTES =
            1024 * 1024;

    public static final int MAX_CHUNK_BYTES =
            8 * 1024 * 1024;

    private TransferProtocol() {
    }
}
```

Then consumers use:

```text
TransferProtocol.MAX_CHUNK_BYTES
```

instead of:

```text
FileMetadata.MAX_CHUNK_BYTES
```

Relevant consumers include:

- `FileMetadata`;
- `AppConfig`;
- `FileSender`;
- `FileReceiver`;
- protocol documentation;
- tests.

The final ownership should be:

```text
FrameIO
    generic framing bounds

TrackerProtocol
    tracker protocol semantic bounds

TransferProtocol
    file-transfer protocol semantic bounds
```

This creates a cleaner structure for later additions such as:

```text
CHUNK_REQUEST
resume negotiation
chunk scheduling
multi-source request semantics
```

Do not turn `TransferProtocol` into a large framework or service. It should initially remain a constants/protocol-contract owner only.

## Required limits remain unchanged

```text
Default file chunk:      1 MiB
Maximum file chunk:      8 MiB
Maximum frame payload:  64 MiB
```

The 64 MiB generic frame cap must still not imply permission to use a 64 MiB transfer chunk.

---

# Required revision 3 — Explicitly define 64 peers as a protocol-v1 tracker semantic limit

## Current design

The revised plan correctly separates:

```text
PeerListCodec defensive parser ceiling = 10,000

Tracker application ceiling = 64
```

and proposes:

```java
TrackerProtocol.MAX_ACTIVE_PEERS = 64;
```

The tracker uses this for admission and `TrackerClient` rejects peer lists above that semantic limit.

This is acceptable.

However, once the client enforces the same value, `64` is no longer merely executor tuning.

It becomes part of tracker/client compatibility.

## Revision

Explicitly document:

> `TrackerProtocol.MAX_ACTIVE_PEERS = 64` is the protocol-v1/classroom deployment semantic capacity, not just the current server thread-pool size.

Therefore:

```text
Tracker v1 server
    never admits more than 64 active registrations

Tracker v1 client
    rejects a response claiming more than 64 active records
```

The generic codec can still safely understand more records:

```text
PeerListCodec defensive ceiling = 10,000
```

but a 65-record list is invalid specifically as a **v1 tracker response**.

## Compatibility consequence

Document that changing:

```text
64 -> 128
```

later requires an intentional tracker-protocol compatibility decision.

For example, future work may:

- bump a protocol version;
- update both peers and tracker together;
- or redefine the semantic contract through a separately approved plan.

Do not accidentally present `MAX_ACTIVE_PEERS` as a freely adjustable server-only tuning property if existing clients are expected to reject values above it.

For the current university project, 64 remains completely appropriate.

---

# Required revision 4 — Reject control characters in peer IDs and peer display names

## Current validation

The plan currently validates `PeerInfo` values with rules such as:

```text
peer ID:   nonblank, <= 255 code units
peer name: nonblank, <= 255 code units
host:      nonblank, <= 255 code units
port:      1..65535
```

This is mostly sufficient.

## Revision

Also reject ISO/control characters in identity fields:

```text
peer.id
peer.name
```

At minimum reject characters such as:

```text
NUL
LF
CR
TAB
other ISO control characters
```

Suggested semantic rule:

> Peer IDs and display names must contain no ISO control characters.

There is no need for aggressive Unicode normalization or an ASCII-only restriction.

Valid Unicode display names should remain supported.

For example, these should remain valid:

```text
Alice
Minh
ボブ
Peer-01
Nguyễn Văn A
```

while strings containing embedded terminal/control characters should fail configuration or protocol validation.

## Why

This avoids malformed names interfering with:

- tracker logs;
- Swing labels;
- error output;
- debugging;
- future text-based metadata displays.

This is input hygiene, not authentication or security hardening.

Keep R-008 unresolved.

---

# Required revision 5 — Make the large real transfer the primary VM tracker-loss demonstration

## Current plan

The VM proof currently includes a deterministic test-harness fallback using a `TransferListener` barrier to pause a transfer after its first chunk so that the tracker can be stopped before completion.

This is technically strong but is relatively elaborate for the primary classroom demonstration.

## Revision

Keep the deterministic barrier harness, but explicitly classify it as a **fallback verification technique**.

The primary VM demonstration should use a sufficiently large real file so the transfer remains active long enough to stop the tracker manually.

Suggested demonstration:

```text
VM 1 / Alice
    sends large file

VM 2 / Bob
    receiving

progress:
10%
20%
30%

Windows tracker is stopped

transfer continues:
40%
...
100%

final SHA-256 matches

peer refresh afterward:
tracker disconnected / unavailable
```

The exact file size depends on actual VM/network throughput.

A practical test file might be:

```text
hundreds of MiB
or approximately 1 GiB
```

but the plan should not mandate a specific size.

Instead say:

> Use a large enough generated/test file to provide a reliable manual observation window on the actual VM network.

Do not use personal/private production data merely to obtain a large file.

## Fallback

If the actual VM transfer completes too quickly or manual ordering is unreliable, then use the existing deterministic harness:

```text
real peer runtime
real transfer code
real JARs
TransferListener barrier
stop tracker
release barrier
complete file
```

The fallback must still use the real transfer implementation.

Do not replace the peer with a scripted/fake ACK server.

## Demonstration preference

Documentation should distinguish:

```text
Primary classroom demonstration:
large real transfer + manual tracker shutdown

Deterministic engineering proof:
listener barrier harness when necessary
```

This makes the final demo much easier for an instructor to understand visually.

---

# Required revision 6 — Clarify whether VM proof blocks future coding or only foundation/deployment PASS

## Current rule

The plan currently makes both:

```text
F-08A local PASS
+
F-08A VM PASS
```

mandatory before future resume/catalogue/multi-source implementation begins.

That is very conservative.

It is acceptable if the two Ubuntu VMs are always readily available, but it can unnecessarily block development because of unrelated environment problems.

Examples:

```text
VMware networking temporarily unavailable
Ubuntu GUI issue
host-only adapter issue
classroom machine unavailable
VM access unavailable at the moment
```

These do not necessarily invalidate locally proven Java protocol/state/storage logic.

## Recommended revision

Separate:

```text
engineering-development gate
```

from:

```text
foundation deployment/demo gate
```

### Recommended development gate

Permit planning/implementation of the next P2P feature after:

```text
F-01A through F-07 implemented
+
F-08A local PASS
```

provided there is no known unresolved defect in the foundation behavior that the next feature depends on.

This means local proof can unlock work such as:

```text
resume design
.part.meta model
resume negotiation
catalogue planning
```

without waiting indefinitely for a temporarily unavailable VM environment.

### Required deployment/demo gate

Still require:

```text
F-08A VM PASS
```

before claiming:

```text
foundation deployment PASS
classroom topology PASS
Windows + Ubuntu interoperability PASS
final demonstration readiness
```

and certainly before the final university submission/demo.

## Suggested status model

```text
F-08A local:
PASS

F-08A VM:
UNVERIFIED / BLOCKED

Result:
Local foundation implementation is validated.
Cross-machine deployment is not yet validated.
Next feature engineering may proceed if its dependencies are local-contract-only.
Overall demonstration/deployment PASS is still blocked.
```

## Alternative

If the project owner explicitly wants VM proof to remain a hard gate before **any** future coding, that is acceptable.

But the plan should state this as an intentional project-management decision rather than implying that local correctness logically depends on VM availability.

The preferred policy is:

```text
Local PASS
    unlocks next feature engineering

VM PASS
    unlocks deployment/demo completion
```

This better separates software correctness from environment availability.

---

# Keep F-08A/F-08B separation unchanged

The current core/deep split is good and should remain.

## F-08A remains representative required proof

Keep mandatory representative cases such as:

- frame roundtrip and malformed frames;
- invalid configuration;
- 0/1/C/C+1/multi-chunk transfers;
- whole-file SHA-256 equality;
- explicit rejection;
- wrong transfer ID;
- wrong ACK index;
- corrupt chunk -> `RETRY`;
- duplicate immediate chunk idempotency;
- early completion rejection;
- same-name destination safety;
- duplicate tracker IDs;
- blocked tracker close;
- active transfer shutdown;
- listener port rebind;
- capacity-one admission behavior;
- tracker-loss/direct-transfer separation;
- Swing responsiveness.

## F-08B remains nonblocking deep regression

Keep items such as:

- exact 64-client saturation;
- every Windows reserved filename variant;
- symlink race variants;
- unsupported hard-link injection;
- leftover partial unlink failure;
- cancellation at every tiny lifecycle point;
- adversarial EDT timing.

Do not promote all F-08B work back into the release gate.

Preserve the existing rule:

> If an F-08B experiment discovers a real correctness or data-loss violation of the implemented contract, that concrete defect is promoted into required repair/proof.

---

# Updated configuration proposal

After this revision, the configuration table should preferably resemble:

| Property | Default | Meaning |
|---|---:|---|
| `tracker.read.timeout.ms` | 15000 | Read-inactivity timeout while waiting for tracker control responses |
| `transfer.read.timeout.ms` | 15000 | Read-inactivity timeout for active peer protocol traffic |
| `transfer.prompt.timeout.ms` | 120000 | Maximum receiver human-decision window |
| `transfer.offer.response.timeout.ms` | 135000 | Maximum sender wait for `FILE_ACCEPT` / `FILE_REJECT`; must exceed prompt timeout |
| `transfer.verify.timeout.ms` | 300000 | Sender wait for final whole-file verification result |
| `transfer.max.concurrent` | 4 | Maximum admitted peer transfer tasks |
| `chunk.size.bytes` | 1048576 | Configured transfer chunk size, `1..TransferProtocol.MAX_CHUNK_BYTES` |

Validation should ensure:

```text
transfer.prompt.timeout.ms >= 1

transfer.offer.response.timeout.ms >
transfer.prompt.timeout.ms
```

and all timeout values remain within valid Java socket/timer ranges.

---

# Updated common-module protocol ownership

Prefer the following conceptual organization:

```text
common/
└── protocol/
    ├── FrameIO.java
    │
    │   MAX_PAYLOAD_BYTES = 64 MiB
    │
    ├── TrackerProtocol.java
    │
    │   MAX_ACTIVE_PEERS = 64
    │   MAX_PEER_LIST_PAYLOAD_BYTES = 1 MiB
    │
    └── TransferProtocol.java
        │
        DEFAULT_CHUNK_BYTES = 1 MiB
        MAX_CHUNK_BYTES = 8 MiB
```

Then:

```text
FrameIO
    owns generic framing constraints

TrackerProtocol
    owns tracker-v1 semantic limits

TransferProtocol
    owns file-transfer-v1 semantic limits

FileMetadata
    owns validated metadata values

PeerInfo
    owns validated peer values
```

This is a clean foundation for future protocol evolution without adding a framework.

---

# Updated VM demonstration flow

The intended final classroom demonstration should be easy to explain.

## Topology

```text
                    Windows Host
                  Tracker Server
                       :5000
                         |
               metadata/discovery only
                 /               \
                /                 \
         Ubuntu VM 1          Ubuntu VM 2
            Alice                Bob
            :6001               :6002
                \               /
                 \             /
                  DIRECT TCP P2P
```

## Demonstration sequence

### 1. Start tracker

Show tracker listening on Windows.

### 2. Start Alice and Bob

Show both Ubuntu peers register.

### 3. Show discovery

Alice sees Bob.

Bob sees Alice.

### 4. Send a normal multi-chunk file

Show:

```text
FILE_OFFER
ACCEPT
chunks
progress
whole-file verification
COMPLETED
```

Compare SHA-256 on both VMs.

### 5. Reject a file

Bob rejects another incoming offer.

Show:

```text
REJECTED
no final file
no partial file created for explicit rejection
```

### 6. Restart a peer

Close Bob and restart using the same ID/port.

Show that registration and listening resources were released correctly.

### 7. Prove P2P independence from tracker

Start a sufficiently long direct transfer.

While the file is visibly still transferring:

```text
stop Windows tracker
```

Then show:

```text
Alice <====================> Bob

file continues transferring
```

and eventually:

```text
COMPLETED
SHA-256 equal
```

Then click Refresh and show tracker/discovery failure.

This proves:

```text
Tracker:
peer discovery/control plane

Peer connection:
actual file data plane
```

If manual timing is unreliable, use the deterministic real-runtime barrier harness as a fallback engineering receipt.

---

# Updated progression recommendation

The preferred progression after these corrections is:

```text
F-01A
minimum plan authority

    ↓

F-02
build/test gate

    ↓

F-03
protocol/config/metadata validation

    ↓

F-05
sequential transfer correctness

    ↓

F-06
safe receive/publication

    ↓

F-04
tracker registration/resource ownership

    ↓

F-07
timeouts/admission/lifecycle/Swing

    ↓

F-08A local
core local + packaged + Swing proof

    ↓

next-feature engineering may begin
if no dependency-relevant foundation defect remains

    ↓

F-08A VM
required real topology/deployment proof

    ↓

F-01B
full handbook migration

    ↓

final foundation/demo PASS

    ↓

resume
    ↓
catalogue/search
    ↓
explicit chunk requests
    ↓
multi-source scheduling
    ↓
source failure/failover
```

If the owner explicitly requires strict VM-first progression, retain:

```text
F-08A local
    ↓
F-08A VM
    ↓
future feature implementation
```

but document that this is a chosen project gate.

---

# Future feature gates remain unchanged

## Resume

Still requires:

- safe staging;
- validated chunk/session semantics;
- interruption behavior;
- persisted `.part.meta` contract;
- restart validation;
- negotiation;
- crash recovery;
- mixed-version behavior.

Do not implement resume using only current file length.

## Catalogue/search

Still requires:

- stable content `fileId`;
- peer identity semantics;
- tracker registration ownership;
- stale-source policy;
- separate metadata advertisement contract.

Tracker remains metadata/discovery only.

## Multi-source

Still requires:

- explicit chunk request protocol;
- accepted resume/chunk-state model;
- catalogue/source discovery;
- downloader-owned scheduler;
- duplicate request prevention;
- source state;
- source switching;
- final SHA-256;
- one safe final publication.

Existing random-access writes alone are not a multi-source implementation.

---

# Scope boundary remains unchanged

This remains a:

```text
trusted/private LAN university application
```

Do not claim:

```text
SHA-256 = authentication
validation = authorization
resource bounds = internet security
plaintext TCP = public-network readiness
```

R-008 remains unresolved.

Public/untrusted-network support requires a separate threat model and authenticated/encrypted transport plan.

---

# Expected final state of this revision

After these adjustments, the foundation plan should be considered implementation-ready.

The plan should not continue expanding with speculative production-hardening tasks.

The expected project progression should remain:

```text
trustworthy sequential chunked P2P transfer
        ↓
safe shutdown/interruption/storage
        ↓
real Windows + Ubuntu demonstration
        ↓
resume
        ↓
catalogue/search
        ↓
CHUNK_REQUEST
        ↓
multi-peer chunk downloading
        ↓
peer/source failure recovery
```

The objective is not to reduce correctness.

The objective is to keep correctness work aligned with the university P2P goal and avoid letting foundation planning consume the project.

In short:

> Keep the revised architecture. Fix the prompt/response timeout race, move chunk limits to a transfer-protocol owner, explicitly define the 64-peer v1 compatibility contract, reject control characters in peer identity, make a large real transfer the primary VM tracker-loss demo, and separate local engineering progression from VM deployment proof where appropriate. After that, stop broad planning and begin implementation.
