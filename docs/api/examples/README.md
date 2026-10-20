# JSON examples

These are illustrative wire fixtures, not live actions. Do not send them to production. Their timestamps expire deliberately; create fresh IDs and lifetimes for staging. The all-zero appeal token in `provision-response.json` is an obvious placeholder with **no registered website binding**. The backend must generate a fresh cryptographically random token; never copy this placeholder into a real response.

For GET examples, set `X-Request-Id` to the response fixture's `requestId`. Every request also carries `X-Server-Id: mineplay-smp` and an actual server-specific bearer credential supplied outside the source tree. POST response request IDs echo their paired request exactly.

`accepted-appeal-action.json` assumes revision 2 because attaching the initial appeal URL may have advanced the creation's revision 1. Always use the actual acknowledged revision; do not infer it from this example.
