# Server-declared profile API

A server can tell Loki clients to resolve **profile queries** against its own API server instead of
the one the client is configured with. Nothing else moves: the client still authenticates, joins and
signs chat against its own API server.

This exists because a client and a server do not have to agree on an API server. When they do not,
the client has no way to resolve the players it is looking at, and everyone renders as Steve.

## When it actually does something

On an online mode server, none of this applies: the server performs `hasJoined` itself and hands the
client a `GameProfile` with the `textures` property already attached, so the client never asks anyone
about anyone. This feature matters where the client does the asking:

- **offline mode servers**, where no profile properties are sent and Loki looks textures up by
  username (see `Loki.disable_profile_lookup`)
- **legacy clients** that fetch skins from `skins.minecraft.net/MinecraftSkins/<name>.png`
- **mods** that query the Mojang API directly

## Server side

Add a `loki` object to your Server List Ping response:

```json
{
  "version": { "name": "1.21.1", "protocol": 767 },
  "players": { "max": 100, "online": 5 },
  "description": "A Minecraft Server",
  "loki": {
    "profileApi": "https://drasl.example.com/authlib-injector"
  }
}
```

`profileApi` is an authlib-injector API root. Loki derives the endpoints from it the same way it
does for the API server you pass on the command line, and honours
`X-Authlib-Injector-Api-Location`, so a bare site root works too. A value without a scheme is
assumed to be `https://`.

Clients that do not run Loki ignore the field, because every Minecraft client since 1.7 parses the
status response with a parser that drops unknown keys. Adding this cannot break vanilla players.

If your API server declares `skinDomains`, Loki adds them to the texture allowlist for the duration,
so your CDN does not have to be reachable under the client's own API server domains.

## Client side

Nothing to configure. Loki reads the declaration by performing its own Server List Ping the moment
the game dials a server, before a single handshake byte is written, and caches the answer per
address for the session.

Loki does the ping itself rather than reading the game's. Vanilla clients are obfuscated and the
classes that model a status response are renamed every version, whereas the status handshake has
been unchanged since 1.7. That is what keeps this version agnostic without a mapping database.

Requires 1.7 or later, since the hook is Netty's `Bootstrap`. Older clients ignore the feature and
keep using their configured API server for everything.

## What is redirected

Only unauthenticated profile reads:

| Endpoint | Purpose |
|---|---|
| `sessionserver.mojang.com/session/minecraft/profile/<uuid>` | textures for a UUID |
| `api.mojang.com/users/profiles/minecraft/<name>` | name to UUID |
| `api.mojang.com/profiles/minecraft` | name to UUID, batch |
| `api.minecraftservices.com/minecraft/profile/lookup/...` | profile lookups |

Everything else keeps going to your configured API server, and that is enforced by an allowlist
rather than by convention:

- authentication of any kind, including `authserver.mojang.com` in full
- `session/minecraft/join` and `session/minecraft/hasJoined`
- `minecraft/profile` without the `lookup/` prefix, which is the authenticated own-profile endpoint
- player certificates, public keys, attributes, the blocklist, reports and telemetry

Two further limits apply to what a server can do with a redirect it was granted:

- a redirected profile response is stripped down to its `textures` property, so a declared API
  server cannot inject other properties into a profile
- a request carrying credentials is never redirected, even if its path is on the allowlist

None of the redirected endpoints take an access token, so a hostile declaration gains a server
nothing it did not already have: it learns that a client on it asked about a player on it.

## Turning it off

```
-DLoki.disable_profile_redirect=true
```

Server declarations are then ignored entirely, and the Netty hook is not installed at all.
