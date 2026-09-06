# Revision Brief for `project-foundation-plan.md`

## Purpose

Please revise the existing foundation plan rather than replacing its architecture.

The current plan is technically strong and should remain **foundation-first**, but it is somewhat over-scoped for the actual university goal: building and demonstrating a hybrid P2P file-sharing application and then extending it toward resumable and multi-source chunk downloading.

The revised plan should preserve the existing Java 21, Maven multi-module, trusted/private-LAN architecture and most of the current correctness/lifecycle work. The main objective of this revision is to **reduce stabilization overhead where it does not materially help the P2P project**, while preserving the parts that solve real correctness, data-safety, concurrency, and lifecycle problems.

---

# Overall assessment

The current plan is strong in:

- architectural separation;
- protocol and metadata validation;
- transfer-state correctness;
- concurrency/lifecycle ownership;
- safe file publication;
- reproducible testing;
- explicit distinction between planned, implemented, and verified behavior;
- preservation of the current direct TCP P2P transfer model;
- future dependency ordering for resume, catalogue/search, and multi-source downloading.

Do **not** replace the project with Spring, Netty, WebSockets, REST, a database, or another framework.

Do **not** implement resume, catalogue/search, or multi-source downloading inside the stabilization phase.

The main concern is scope efficiency: the stabilization phase should strengthen the project, but it must not become the entire project.

---

# Required revision 1 — Split F-01 into a minimal planning step and a later documentation migration

## Problem

Current F-01 performs a very large documentation migration before application source changes:

- moving all handbook pages;
- moving CSS/JS/favicon;
- rebasing every relative link;
- updating all navigation;
- changing repository references;
- verifying offline navigation;
- adding the foundation plan;
- reorganizing the entire `docs/` hierarchy.

This is well specified, but it creates a very large diff before any P2P correctness issue is repaired.

The documentation hierarchy migration provides little immediate technical value to the running application.

## Revision

Split F-01 into two separate phases.

### F-01A — Publish the minimum foundation authority

Do this before source changes.

Required work:

- create the authoritative foundation plan under `docs/`;
- establish stable F-item identifiers;
- record architecture boundaries;
- record issue-to-work-item mapping;
- distinguish:
  - Planned
  - In progress
  - Implemented
  - Verified / Unverified
- link the plan from the existing documentation entry points;
- correct only documentation that must change to accurately describe the current baseline or the approved foundation work.

Do **not** require the full documentation tree migration before application work begins.

### F-01B — Reorganize and polish the handbook

Move the large purpose-based documentation migration to after core stabilization and packaged/VM proof, unless a university requirement explicitly makes the migration necessary earlier.

This phase may contain the existing detailed relocation specification:

- `reference/`
- `guides/`
- `development/`
- `plans/`
- asset relocation;
- URL rebasing;
- navigation verification;
- offline browser checks;
- screenshots;
- stale-path cleanup.

The full documentation migration should not block F-02 through F-07.

---

# Required revision 2 — Separate the generic frame payload ceiling from the file-transfer chunk ceiling

## Problem

The current plan allows:

```text
MAX_PAYLOAD_BYTES = 64 MiB
```

and also permits a file-transfer chunk to be as large as that entire maximum.

That is unnecessarily large for this application.

With multiple concurrent transfers, sender/receiver buffers and hashes can create avoidable memory pressure.

## Revision

Keep a large absolute protocol ceiling if desired:

```java
FrameIO.MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
```

but introduce a separate application-level chunk ceiling, for example:

```java
MAX_CHUNK_BYTES = 8 * 1024 * 1024;
```

Recommended behavior:

```text
Default chunk size:      1 MiB
Maximum file chunk:      8 MiB
Absolute frame payload: 64 MiB
```

The exact transfer ceiling may be adjusted if there is a documented reason, but it should be substantially smaller than the absolute frame limit.

`FileMetadata` and `AppConfig` validation should enforce the file-transfer chunk ceiling rather than merely the generic protocol ceiling.

Preserve the current default of 1 MiB.

---

# Required revision 3 — Clarify duplicate-chunk idempotency semantics

## Problem

Current F-05 says an immediately repeated previously accepted chunk may be re-ACKed only if its:

> digest/layout/content

are identical.

However, the proposed constant-memory receiver stores only the previous accepted digest and associated metadata, not the full previous chunk payload.

Therefore it cannot literally compare the old and new byte arrays.

## Revision

Change the requirement to something implementable with constant session memory.

Suggested contract:

> A repeat of exactly `nextChunkIndex - 1` may be re-ACKed `OK` without rewriting or incrementing progress when its transfer ID, chunk index, expected offset, expected length, and successfully validated SHA-256 digest match the last accepted chunk.

If any of those fields differ, fail the session.

Do not require retaining an entire previous chunk only to perform duplicate detection.

The cryptographic digest is the intended application-level identity check for this behavior.

---

# Required revision 4 — Explicitly justify application-level ACKs on top of TCP

Add a documentation requirement explaining why the protocol uses:

```text
TCP
+
CHUNK_ACK
+
SHA-256
+
RETRY
```

TCP already provides:

- ordered byte delivery;
- transport retransmission;
- duplicate suppression;
- stream integrity checks at the transport layer.

Therefore `CHUNK_ACK` must **not** be described as replacing TCP reliability.

Its purpose is application-level semantics:

- confirm that a complete frame was parsed;
- confirm that the chunk belongs to the expected transfer;
- confirm that its index/layout are valid;
- confirm that its application-level SHA-256 passed;
- confirm that the receiver accepted the chunk into transfer state;
- support future chunk-oriented features such as:
  - resume;
  - explicit chunk requests;
  - multi-source downloading;
  - source switching;
  - chunk scheduling.

This distinction should appear in the protocol/concepts documentation because it is an important architectural point and is likely to be questioned in a university presentation.

---

# Required revision 5 — Preserve F-06, but explicitly classify it as a deliberate filesystem contract

The F-06 publication design is strong and should remain.

The plan currently proposes:

```text
unique random .part
    ->
whole-file verification
    ->
Files.createLink(candidate, part)
    ->
remove owned temporary entry
```

This gives:

- no overwrite of an existing destination;
- no exists-then-move race;
- safe concurrent same-name receives;
- no unverified bytes exposed under the final filename.

Keep this design if the intended supported filesystems remain local NTFS/ext4.

However, state clearly that this is a **deliberate operational contract**, not universal filesystem compatibility.

Expected rule:

- intended foundation filesystems: local NTFS and ext4;
- hard-link publication failure is explicit;
- retain the verified `.part`;
- do not silently weaken the guarantee with copy/overwrite fallback.

This is acceptable for the planned Windows host + Ubuntu VM environment.

---

# Required revision 6 — Clarify generic codec limits vs tracker semantic limits

The current plan retains a defensive peer-list codec maximum of 10,000 records while the tracker itself is capped at 64 connected peers.

Keep the distinction if desired, but make the layering explicit.

For example:

```text
PeerListCodec
    absolute defensive parser ceiling
    e.g. 10,000

Tracker protocol/application contract
    maximum legitimate active registrations
    64
```

`TrackerClient` should reject a tracker response whose count violates the tracker application's current semantic maximum.

This demonstrates the useful distinction between:

- parser safety ceiling;
- application semantic ceiling.

---

# Required revision 7 — Enforce one shared shutdown deadline

F-07 correctly states that shutdown should take at most five seconds total for ordinary local I/O.

Ensure the implementation contract does not accidentally allow:

```text
PeerServer.close()       waits 5 seconds
TransferManager.close()  waits 5 seconds
other cleanup            waits again
```

which would violate the stated total bound.

Use one shared monotonic shutdown deadline.

Conceptually:

```java
long deadline =
    System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
```

Each shutdown stage should receive only the remaining duration.

The plan should explicitly state:

> The five-second shutdown budget is shared across runtime-owned worker/acceptor waits, not independently reset for every component.

The existing caveat about stalled filesystem operations should remain.

---

# Required revision 8 — Divide F-08 verification into core acceptance and deep regression tiers

## Problem

The current F-08 matrix is excellent, but treating every row as equally mandatory risks spending too much project time on edge cases that do not materially advance the P2P assignment.

Examples include:

- every Windows reserved device name;
- exact tracker saturation at 64 clients;
- concurrent symlink publication;
- partial unlink failure;
- cancellation at every possible startup stage.

These are useful regression cases but should not delay future P2P functionality once the core foundation is trustworthy.

## Revision

Split proof into two tiers.

## F-08A — Core required foundation proof

This must pass before moving to resume/catalogue/multi-source work.

At minimum include:

### Build and packaging

- `mvn -B -ntp clean verify`;
- executable tracker JAR exists and launches;
- executable peer JAR exists and launches.

### Protocol

- valid frame roundtrip;
- malformed magic/version/type;
- duplicate header rejection;
- payload cap enforcement.

### Configuration

- invalid port;
- invalid chunk size;
- invalid boolean property;
- blank required identity/host.

### File transfer

Test representative sizes:

```text
0
1 byte
exactly C
C + 1
multiple chunks
```

where `C` is the configured test chunk size.

Require:

- exact received bytes;
- final SHA-256 match;
- correct terminal state;
- progress <= 100%;
- empty completed file displays 100%.

### Transfer-state correctness

- rejection;
- wrong transfer ID;
- wrong ACK chunk index;
- corrupt chunk -> RETRY;
- successful retransmission;
- early completion rejected;
- invalid/skipped chunk index rejected.

### Storage safety

- existing final filename is not overwritten;
- two same-name receives produce distinct files;
- interrupted/mismatched receive does not publish a final file.

### Tracker behavior

- registration/list/disconnect;
- duplicate peer ID rejection;
- original registration survives rejected duplicate;
- ID can be reused after the owner disconnects.

### Lifecycle

- blocked tracker request can be interrupted by close;
- active peer transfer is terminated on local shutdown;
- listener port can be rebound after shutdown;
- startup failure rolls back acquired resources.

### P2P architectural proof

- establish a direct peer transfer;
- stop/fail the tracker after peer discovery;
- show that an already established direct peer transfer can still complete;
- discovery refresh correctly reports tracker loss.

### UI

- Swing window remains responsive during startup/transfer;
- send/refresh are enabled only after startup;
- accept/reject works;
- terminal error/completion is visible;
- window close does not leave obvious orphan prompts/workers.

### Deployment

Keep localhost proof and VM proof separate.

Required university demonstration target:

```text
Windows host: tracker
Ubuntu VM 1: peer A
Ubuntu VM 2: peer B
```

Prove:

- registration/discovery;
- direct multi-chunk transfer;
- SHA-256 equality;
- rejection;
- restart/port rebind;
- tracker/direct-transfer separation.

Do not claim VM PASS from localhost tests.

## F-08B — Deep regression proof

Keep these as desirable permanent regressions, but they should not block future P2P phases if all F-08A requirements pass and the remaining cases are documented.

Examples:

- exact 64-client tracker saturation;
- all Windows device basename variants;
- symlink collision behavior;
- unsupported hard-link publication path;
- owned partial unlink failure;
- close during every individual startup/hash/prompt phase;
- deliberately delayed final verification reply;
- uncommon race scheduling cases.

If a deep-regression case exposes a plausible data-loss or correctness defect in changed code, it may be promoted back into the required core set.

---

# Recommended execution order

Replace the current strict order:

```text
F-01
F-02
F-03
F-04
F-05
F-06
F-07
F-08
```

with:

```text
F-01A
Minimal foundation plan + architecture/issue authority

    ↓

F-02
Repeatable Maven build and behavioral test gate

    ↓

F-03
Frame, configuration, peer, and file-metadata validation

    ↓

F-05
Sequential transfer state machine and truthful accounting

    ↓

F-06
Safe staging and verified no-overwrite publication

    ↓

F-04
Tracker registration ownership and bounded lifetime

    ↓

F-07
Transfer admission, socket ownership, timeouts,
shutdown, prompt lifecycle, Swing startup

    ↓

F-08A
Core regression + packaged localhost proof

    ↓

Windows host + two Ubuntu VM deployment proof

    ↓

F-01B
Full documentation hierarchy migration/polish

    ↓

Future feature planning
```

Rationale:

- F-03 must precede transfer-state repairs because F-05 depends on trustworthy metadata/frame boundaries.
- F-05 and F-06 are the core of the actual file-transfer product and should be stabilized early.
- F-04 remains important, but tracker duplicate-ID/resource saturation work is less central to the P2P file path than transfer correctness.
- F-07 integrates lifecycle behavior after the underlying protocol/state/storage contracts are trustworthy.
- Full documentation migration should not block application correctness work.

---

# Future-feature gates to preserve

Keep the current future dependency logic.

## Resume

Only after:

- safe `.part` ownership exists;
- chunk/session validation is complete;
- interruption behavior is tested.

Resume must receive its own docs-owned plan defining:

- persisted transfer metadata;
- `.part.meta` ownership;
- received-chunk representation;
- validation on restart;
- negotiation between peers;
- crash recovery;
- mixed-version behavior.

Do not add ad-hoc resume by merely checking file length.

## Catalogue / search

Only after:

- stable `fileId` semantics;
- tracker registration ownership;
- stale-source behavior is defined.

Requires its own metadata advertisement contract.

The tracker must remain metadata/discovery only.

Do not relay file payloads through the tracker.

## Multi-source chunk downloading

Only after:

- explicit chunk-request protocol exists;
- resume/chunk state contract is accepted;
- catalogue/source discovery exists.

The downloader must own scheduling.

The design must define:

- chunk assignment;
- duplicate request prevention;
- per-source failure;
- source switching;
- final single-file verification;
- one safe publication step.

Do not describe existing `RandomAccessFile.seek(...)` support as an already implemented multi-source scheduler.

---

# Scope boundary to preserve

The application remains a controlled private/trusted-LAN university project.

Do not claim that:

- SHA-256 provides authentication;
- validation provides authorization;
- resource limits provide internet security;
- the app is ready for public/untrusted networks.

Public-network support must remain a separate future plan involving a threat model and authenticated/encrypted transport.

---

# Architectural principles that should remain unchanged

Preserve these principles from the current plan:

1. `common` owns shared protocol/domain contracts only.
2. Tracker owns presence/discovery only.
3. File bytes never traverse the tracker.
4. Every peer is both:
   - tracker client;
   - peer server;
   - peer client.
5. Transfer remains direct TCP.
6. Current transfer remains sequential stop-and-wait during stabilization.
7. Per-chunk SHA-256 and whole-file SHA-256 remain.
8. No resume/multi-source implementation during foundation stabilization.
9. Swing remains presentation-only.
10. Networking/hashing/configuration/shutdown must not block the EDT.
11. Temporary files are not presented as completed files.
12. Existing files are never silently overwritten.
13. Implementation status and verification status remain separate.
14. Localhost PASS and VM PASS remain separate evidence categories.

---

# Expected result of the revision

The revised plan should still be rigorous, but it should better match the university project's actual progression:

```text
Trustworthy single-peer chunk transfer
        ↓
Safe interruption/lifecycle behavior
        ↓
Real VM-to-VM proof
        ↓
Resume
        ↓
Shared-file catalogue/search
        ↓
Explicit CHUNK_REQUEST protocol
        ↓
Multi-peer chunk scheduling
        ↓
Peer failure/source switching
```

The goal is **not** to weaken correctness.

The goal is to ensure that foundation work remains proportional and leaves enough project time to implement the P2P features that make the assignment interesting.

In short:

> Keep the architecture and most of the stabilization design. Reduce documentation-first overhead, tighten a few technical contracts, distinguish core proof from deep regression work, and move the project toward resume/catalogue/multi-source features as soon as the core foundation is genuinely trustworthy.
