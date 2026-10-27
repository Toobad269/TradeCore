# Optional integration idea: Link a Minecraft account to a website

> **Design suggestion, not an implemented TradeCore feature.**
> This workflow is a general suggestion for Minecraft servers integrating TradeCore with a website.

## Goal

Allow a player to prove that they control a Minecraft account before linking it to an authenticated website user. A Minecraft username alone does **not** prove account ownership.

## Recommended flow

1. **Sign in on the website.** The website user must have an authenticated session (preferably with email verification and appropriate session security).
2. **Start linking.** The website asks for a Minecraft username. Resolve the corresponding Java UUID using a trusted source, or use Floodgate's documented identity handling for Bedrock players. Do not assume Java and Bedrock identities are interchangeable.
3. **Generate a pending request.** The backend creates a single-use, randomly generated request ID and challenge. Store it server-side with the target player identity, website account ID, creation timestamp and a short expiration (for example, five minutes).
4. **Verify in Minecraft.** When that exact player joins (or is already online), show a request inside the game: “Link this Minecraft account to your website account?” The player must explicitly approve, preferably with `/link confirm <code>` or a clickable menu. The website should show matching request details.
5. **Bind identities atomically.** The backend checks the authenticated in-game UUID, request lifetime, one-time use and any existing links. It then links the verified Minecraft identity to the website account in a database transaction.
6. **Confirm on both sides.** Show the result on the website and in Minecraft. Offer the ability to view connected accounts and revoke a link.

## Security essentials

- **Never authenticate from a username alone.** A username typed into a form is only a lookup hint.
- **Do not send bearer tokens or long-term secrets in Minecraft chat.** Short-lived confirmation challenges must not grant access by themselves.
- Use **HTTPS**, secure website sessions, short challenge expiration, one-time consumption and rate limiting.
- Require a new explicit confirmation when switching an existing link to a different web account; log changes for abuse investigation.
- Use the server's authenticated player identity. With Geyser/Floodgate, follow official identity and UUID conventions instead of treating name prefixes as proof.
- Protect endpoints from CSRF where applicable. Check authorization for **every** web API action, not just during linking.
- Separate account linking from account login: linking alone should not silently provide a reusable login session.
- Permit secure unlinking, and consider recovery options if the Minecraft account is lost.

## Example player experience

**Website:** Sign in → “Connect Minecraft account” → Enter player name → “Confirmation requested in game.”

**Minecraft:** “A website account wants to link to your Minecraft account. Confirm?” → Approve or reject.

**Website:** “Minecraft account linked successfully.”

## TradeCore integration notes

TradeCore already describes optional HTTP integration and website-linking concepts. This document is a suggested **UX and security pattern**, not a guarantee that the current code follows this exact process.

If implementing this pattern, document the actual endpoints and authentication model in the OpenAPI specification, and test account switching, reconnection, expiration, duplicate requests and Java/Bedrock identities separately.

Contributions and improvements are welcome through GitHub pull requests.
