# Hybrid P2P Chunked File Sharing

A university-scale Java 21 application for direct file transfer between desktop peers. A central tracker provides registration and discovery; file payloads travel directly from sender to receiver over a separate TCP connection.

Current behavior includes peer discovery, accept/reject prompts, sequential chunk transfer, per-chunk SHA-256 checks and acknowledgements, whole-file verification, and `.part` files until verification succeeds. It is a trusted-LAN prototype with documented reliability and security limits.

## Architecture

| Module | Responsibility |
|---|---|
| `common` | Versioned binary frame protocol and shared models |
| `tracker-server` | In-memory peer registration and discovery |
| `peer-app` | Swing UI, tracker client, P2P listener, sender and receiver |

The tracker does not relay file bytes. After discovery, the sender connects directly to the recipient's advertised peer port.

## Build and run

Requires JDK 21 and Maven 3.9+.

```bash
mvn clean package
```

This produces `tracker-server/target/tracker-server.jar` and `peer-app/target/peer-app.jar`.

Run the tracker from the repository root:

```powershell
java -jar .\tracker-server\target\tracker-server.jar 5000
```

Copy `peer-app/src/main/resources/peer.properties.example` beside `peer-app.jar`, name it `peer.properties`, adjust the peer identity, port, and tracker address, then run on a graphical desktop:

```bash
java -jar peer-app.jar peer.properties
```

## Project handbook

Open [`docs/index.html`](docs/index.html) directly in a browser. The offline handbook explains the concepts, architecture, protocol, transfer lifecycle, configuration, demonstration flow, source structure, verification evidence, troubleshooting, limitations, roadmap, and documentation maintenance policy.

- [Setup and configuration](docs/setup-and-configuration.html)
- [Protocol reference](docs/protocol-reference.html)
- [Known limitations and roadmap](docs/limitations-and-roadmap.html)
- [Documentation maintenance contract](docs/maintenance.html)

Documentation is part of every change: all affected pages and the handbook changelog must be updated in the same task.
