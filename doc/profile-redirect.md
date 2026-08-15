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

Nothing to do, if the server runs Loki. It already knows which API server it was pointed at, so it
puts that in its own Server List Ping response, splicing the key into the document on its way out
rather than reparsing it. Turn it off with `-DLoki.disable_profile_advertise=true`.

It declares the endpoints it actually sends its own profile queries to, whichever way it was
configured, so nothing has to be inferred from a root that may not exist.

`profileApi` may be `http://`, and is honoured as such, for the same reason a configured API server
may be: Loki does not decide an operator's transport. Profile reads are what travel over it, and a
request carrying credentials is refused whatever the transport.

For a server that does not run Loki, or one that wants to name an API server other than its own, add
a `loki` object to the Server List Ping response yourself. Name the endpoints:

```json
{
  "version": { "name": "1.21.1", "protocol": 767 },
  "description": "A Minecraft Server",
  "loki": {
    "session":  "https://example.com/sessionserver",
    "account":  "https://example.com/api",
    "services": "https://example.com/minecraftservices",
    "skinDomains": ["cdn.example.com"]
  }
}
```

Each is optional and independent: what is not named is not moved. Nothing here assumes a Yggdrasil
server lays its paths out the way authlib-injector does, because most of the point of naming them is
that it need not.

If yours does lay them out that way, `profileApi` is a shorthand for exactly the three above, and is
expanded by the client into them:

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

`enforceSecureProfile` says that this server checks signatures, and asks the clients on it to do the
same. A server running Loki declares it when it was started with `-DLoki.enforce_secure_profile=true`
— its own flag, not `enforce-secure-profile` from `server.properties`. That setting arrived in 1.19,
so on the versions where a client most needs telling the file has nothing to say, while the flag
means the same thing on every version. It stands on its own — a server with no API server of its own
to name still has this to say — and a client that already checks is unaffected.

```json
"loki": { "enforceSecureProfile": true }
```

`skinDomains` names the hosts your textures are served from, and Loki adds them to the client's
texture allowlist for as long as the player is connected, so your CDN does not have to be reachable
under the client's own API server domains. If you run Loki, it declares the domains it was
configured with, and there is nothing to do.

Leaving `skinDomains` out is not a stricter setting than filling it in. It says nothing about where
your textures live, and a client that has been told nothing has no allowlist worth enforcing: it
would reject the very textures the redirect exists to fetch. So Loki stands the list down for the
duration instead, and any texture host is accepted while connected. Name your domains if you would
rather the client keep checking.

## Client side

Nothing to configure. Loki performs its own Server List Ping when the game joins a server, and
caches the answer per address for the session.

A connection on its own does not mean the player is arriving: opening the multiplayer screen dials
every server on the list. Loki reads the handshake the game is about to write and acts only on the
one asking to move to the login state, so the servers on your list are neither pinged a second time
nor able to set the profile API for a session you never joined.

How the player got there does not matter. The server list, the direct connect screen, a `--server`
argument and a 1.20.5+ transfer all end up at the same connection announcing itself in that same
field, and all are honoured.

A client that would rather not have its texture allowlist stood down by a server that named no
domains can keep it:

```
-DLoki.strict_texture_domains=true
```

Textures then have to come from a domain the client itself allows, or from one the server named, and
a server that named none gets no relaxation. It costs you the skins on any such server.

Either way the allowlist is the client's own again on leaving, along with the redirect and the keys.

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

## Signing keys

Joining a server makes its API server the authority for that session. It is the one that authorised
the join, and everyone else on it authenticated against it too, so their textures and their chat
certificates carry its signature rather than the one your client is configured with. Checked against
your own keys, every player on a perfectly healthy third party server would fail.

So with `Loki.enforce_secure_profile`, Loki also trusts the keys the declared server publishes, for
as long as the player is on it. It asks its `/publickeys` first and falls back to
`signaturePublickeys` in its authlib-injector metadata, in that order and for both the declared
server and the configured one: the endpoint says which keys are for properties and which are for
certificates, while the metadata has a single field and no way to tell them apart. Falling back to
it means treating what it declares as answering for both.

The cost is worth stating. While connected, that server's API server can vouch for a texture, and
for whose chat key is whose, which is the guarantee `enforce_secure_profile` otherwise gives against
the server you are playing on. The bound is time rather than kind:

- **added, not substituted.** The configured server's keys and Mojang's keep working
- **dropped on leaving.** Loki watches the connection that was the arrival and undoes all of it when
  that connection ends — quitting, being kicked, or the connection simply dropping. Not at the next
  arrival: a player sitting in the menu is not on a server, and should not still be trusting one

None of this applies while nothing is being verified — but whether anything is being verified is no
longer settled when the game starts. A server that declares `enforceSecureProfile` turns checking on
for the client while it is on that server, and leaving turns it back to whatever the client chose:
on if it was launched with `Loki.enforce_secure_profile`, off otherwise.

The asymmetry is the point. A server can make a client stricter about the server's own players and
nothing else, which is a thing it can already do by refusing to let them in. It cannot make a client
laxer, and it cannot reach past the visit. A client that would rather not be asked at all sets
`Loki.ignore_declared_secure_profile=true` and decides for itself everywhere.

Worth knowing what "stricter" costs: on a server whose API server publishes no usable keys, checking
turns every player into Steve where accepting would have shown them. That is the server operator's
choice to make about their own server, and it is undone the moment the player leaves.

Two further limits apply to what a server can do with a redirect it was granted:

- a redirected profile response is stripped down to its `textures` property, so a declared API
  server cannot inject other properties into a profile
- a request carrying credentials is never redirected, even if its path is on the allowlist

None of the redirected endpoints take an access token, so a hostile declaration gains a server
nothing it did not already have: it learns that a client on it asked about a player on it.

## Checking it works

Two scripts, because the two sides fail in different ways and a test that covers both at once tells
you neither. Both stand up real Minecraft servers, so both accept the EULA on your behalf and say so
on every run.

```
scripts\real-server-test.ps1
```
The server side: a real server of each era, with and without Loki, checked for whether its status
response carries the declaration and still parses as what it was.

```
scripts\cross-system-test.ps1 -Stub
scripts\cross-system-test.ps1 -YggdrasilA https://a.example/authlib-injector `
                              -YggdrasilB https://b.example/authlib-injector `
                              -TokenA $a -TokenB $b -ProbeUuid <a player on A>
```
The client side, which is where this feature is either doing something or rendering everyone as
Steve. It joins one server, running on API server A, with three separate clients — one on Mojang,
one on A, one on B — and checks that each of them resolves a player who exists only on A, verifies
that player's signature against A's keys, keeps its own credentials on its own API server, and hands
all of it back on leaving. `-Stub` runs the whole thing against two stand-in API servers, one of
which publishes several signing keys and signs with the last of them.

Tokens are optional and go through the environment rather than the command line. Without one, the
check that a client's own token still reaches its own API server is skipped and the rest runs.

## Turning it off

```
-DLoki.disable_profile_redirect=true
```

Server declarations are then ignored entirely, and the Netty hook is not installed at all.
