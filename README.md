# TradeCore — Minecraft Economy & Marketplace

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-55AA55)](https://papermc.io/)
![Java](https://img.shields.io/badge/Java-21-orange)
![Paper](https://img.shields.io/badge/Platform-Paper-blue)
![Status](https://img.shields.io/badge/Status-Testing-yellow)

**Keywords:** Minecraft economy plugin · Minecraft shop plugin · Paper 1.21.11 · Java plugin · chest shops · auction house · server economy · SQLite · Vault · Bedrock / Floodgate · HTTP API

**TradeCore** is an economy and marketplace plugin for Minecraft Paper. It combines SQLite-backed accounts, player transfers, a central server shop, physically stocked chest shops, fixed-price auctions, a pickup mailbox, optional Bedrock menus, Vault compatibility and an HTTP administration API.

**Target:** Paper **1.21.11**, Java **21**. Folia and other Minecraft versions are unsupported.

## Features

- UUID-based player accounts, accurate balance handling and a transaction ledger
- Central server shop with finite inventory, supplier rewards and adjustable prices
- Protected physical chest shops with configurable layouts
- Fixed-price auction house with listing fees, search, sorting and expiration
- Persistent pickup mailbox for bought and returned items
- Optional Floodgate Bedrock forms and Vault integration
- Optional HTTP management API with one-time website linking codes

## Build and installation

Requires **JDK 21** and **Maven 3.9+**.

```bash
mvn test
mvn package
```

Copy the shaded JAR from `target/` into the `plugins/` folder of a **test** Paper 1.21.11 server. After the first startup, stop the server to edit the generated configuration and price catalogue; restart instead of using `/reload`.

The primary in-game command is `/markt`. Administrators can enable the compass menu with `/markt menu <player-uuid> on`.

## Compatibility and language

**English is the primary project language, but the translation is not yet complete.** The main README, initial documentation overview and many user-facing menu messages have English equivalents in the prepared source archive. Detailed engineering documents are still in German under `docs/de/`, and some error strings, technical identifiers or configuration values may remain in German.

The following legacy identifiers are intentionally unchanged for backwards compatibility:

- `/markt` command
- `marktwerk.*` permission nodes
- `de.marktwerk` Java package and entry-point class
- Existing database or persisted schema identifiers

Renaming any of those requires a separate migration and compatibility review, not just a text translation.

## Project status

The Java source code is committed to this repository. No precompiled JAR is provided; build the plugin using Maven as described above. Rebranding and translations have not yet been validated on a live Paper server, so run the tests and verify compatibility before production use.

The original archive includes detailed German documentation that must still be translated and verified. Security-sensitive operations, especially the HTTP API and inventory recovery, should be reviewed in that reference before deployment.

## Security

For a secure website-to-Minecraft account verification workflow, see the [Account Linking Security Guide](docs/ACCOUNT_LINKING.md). This is a proposed integration pattern, not a claim that TradeCore implements it automatically.


Restrict the management API with authentication and network controls. Use an appropriate chest/land protection plugin and back up the SQLite database before updates.

## License and contributions

Copyright © 2026 TradeCore contributors. Project maintained by Toobad269.

**Free use and modification are allowed.** You may download, use, run and modify TradeCore for your own Minecraft servers and personal projects, including servers that accept donations or sell unrelated in-game services.

**Reselling TradeCore is not allowed.** You may not sell the plugin, its source code, or a modified/rebranded version as your own paid product, nor charge for access to a competing derivative distribution.

You are welcome to suggest improvements: fork the project and open a **pull request** to share your changes for review. Pull requests are proposals, not automatic acceptance.

These are the maintainer's intended permissions and restrictions. A formal LICENSE file with precise legal terms has not yet been adopted; please contact the maintainer for redistribution or commercial-licensing questions.
