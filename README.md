# Hybrid P2P Chunked File Sharing

A university-scale Java 21 application for direct file transfer between desktop peers. A central tracker provides registration and discovery; file payloads travel directly from sender to receiver over a separate TCP connection.

Current behavior includes peer discovery, in-app Settings dialog with atomic configuration persistence, accept/reject prompts, sequential chunk transfer, per-chunk SHA-256 checks and acknowledgements, whole-file verification, `.part` files until verification succeeds, and signed GitHub Release distribution with safe in-app updates. It is a trusted-LAN prototype with documented reliability and security limits.

## Architecture

| Module | Responsibility |
|---|---|
| `common` | Versioned binary frame protocol and shared models |
| `tracker-server` | In-memory peer registration and discovery |
| `peer-app` | Swing UI, in-app settings, software updates, tracker client, P2P listener, sender and receiver |

The tracker does not relay file bytes. After discovery, the sender connects directly to the recipient's advertised peer port.

## Quick Start (End-User Release)

End users do not need Git, Maven, or source code.

1. Install **Java 21** (e.g. Eclipse Temurin 21 or Microsoft OpenJDK 21).
2. Download the latest `p2p-client-VERSION.zip` from [GitHub Releases](https://github.com/Megumi2910/p2p-file-sharing/releases).
3. Extract the ZIP to an ordinary local folder.
4. Launch the application:
   - **Windows:** Double-click or run `run-peer-windows.bat`.
   - **Linux:** Run `./run-peer-linux.sh`.
5. On first launch, the **Settings dialog** opens automatically:
   - A unique peer ID is generated automatically.
   - Confirm your display name, tracker address (`127.0.0.1:5000` by default), and shared/download folders.
   - Click **Save and start**.

Updates can be checked and installed safely inside the application via the **Software updates...** toolbar button. Updates are signed with Ed25519 and applied atomically outside the running JAR.

## Developer Build and Run

Requires JDK 21 and Maven 3.9+.

```bash
mvn clean package
```

This produces `tracker-server/target/tracker-server.jar` and `peer-app/target/peer-app.jar`.

Run the tracker from the repository root:

```powershell
java -jar .\tracker-server\target\tracker-server.jar 5000
```

Run a peer using the launch script or directly with an optional configuration path:

```bash
# Windows
.\run-peer-windows.bat peer.properties

# Linux
./run-peer-linux.sh peer.properties
```

To build a standalone distribution ZIP and validate package integrity:

```bash
mvn clean package -Pclient-release -Dclient.version=1.0.0 -Dclient.update.publicKey=YOUR_BASE64_PUBLIC_KEY
```

## Security Boundary Notice

- **Release authenticity:** Official client downloads and in-app updates are digitally signed using Java `Ed25519` and verified against embedded trust keys.
- **P2P transport:** P2P file transfers and tracker communication run over unencrypted TCP on a trusted private local network (LAN/VPN). Release signing verifies binary authenticity, not P2P transport encryption or peer authentication.

## Project Handbook

Open [`docs/index.html`](docs/index.html) directly in a browser. The offline handbook explains the concepts, architecture, protocol, transfer lifecycle, configuration, demonstration flow, source structure, verification evidence, troubleshooting, limitations, roadmap, and documentation maintenance policy.

- [Setup and configuration](docs/guides/setup-and-configuration.html)
- [User guide and demonstration](docs/guides/user-guide-and-demo.html)
- [Architecture reference](docs/reference/architecture.html)
- [Code guide and invariants](docs/development/code-guide.html)
- [Protocol reference](docs/reference/protocol-reference.html)
- [Testing and verification evidence](docs/development/testing-and-evidence.html)
- [Troubleshooting guide](docs/guides/troubleshooting.html)
- [Known limitations and roadmap](docs/plans/limitations-and-roadmap.html)
- [Documentation maintenance contract](docs/development/maintenance.html)
- [Change history and changelog](docs/development/changelog.html)
