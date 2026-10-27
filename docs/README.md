# TradeCore technical documentation

TradeCore provides a Minecraft economy, server shop, physical chest shops, fixed-price auctions, and an optional HTTP API.

- Default configuration: `src/main/resources/config.json`
- Price catalogue: `src/main/resources/prices.json`
- API contract: `docs/openapi.json`
- Entry command: `/markt` (retained for backward compatibility)
- Permissions: `marktwerk.*` (retained for backward compatibility)
- Tests: `mvn test`
- Package: `mvn package`

The detailed original German documentation is included in the source archive under `docs/de/` and has not been fully translated yet. Do not assume old database identifiers can be renamed without a migration.
