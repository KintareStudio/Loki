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

The ping hook is Netty's `Bootstrap`, so it covers 1.7 and later. Below that there is no ping to
read and the declaration travels on the game connection itself — see the next section.

## Before 1.7

There is no status response to put anything in until 1.7, so the same declaration is carried on the
connection instead, in the same format and ending up in the same place. It works in both directions
against unmodified software, and has been tested against the real client and the real server of each
era rather than reasoned about.

Two things happen, and both fail towards doing nothing:

1. **The client marks itself**, in a field its own packets already carry and nothing reads. It never
   makes a packet longer than it was, except from 1.3, where the field is a counted string and the
   count is updated with it. A server without Loki reads the field and ignores it, exactly as it
   ignored what was there before.
2. **The server answers with a block**, and only to a client that marked itself. The client takes it
   back out before the game reads a byte of it, so the game sees a protocol that has not changed. A
   client without Loki never marks itself, is never sent a block, and cannot tell the difference.

   How that block is framed depends on whether anything might be reading the connection. Below 1.3
   it is raw bytes — `0xFE 'L' 'O' 'K'`, a length, and the declaration as UTF-8 — because a
   connection of that age is a wire between two programs and nothing sits in the middle of it. From
   1.3 there are proxies, and a proxy decodes every packet in both directions to decide where to
   send it, so the same declaration travels in a **plugin message** instead: `0xFA`, the channel
   `Loki`, a length, and the same UTF-8. A proxy decodes that like any other packet and either
   forwards it or drops it; either way the connection survives, which raw bytes could not promise.

Where the mark goes depends on the version, and there are more shapes down there than eras:

| Versions | Protocol | Where the mark goes |
|---|---|---|
| Classic | 7 | the trailing byte of Player Identification, which is unused |
| a1.0.11 – a1.1.2_01 | 10 – 14, then 1 – 2 | the password field, which is a constant nothing reads |
| a1.2.0 – b1.4_01 | 3 – 10 | the map seed in the login packet, behind the password string |
| b1.5 – 1.1 | 11 – 23 | the map seed, straight after the username |
| 1.2.1 – 1.2.5 | 28 – 29 | the two zero ints behind the level type |
| 1.3 – 1.6.4 | 39+ | the end of the host in the handshake |

A client sends the seed as zero because it has no seed to send, which is what makes those eight bytes
free; Loki writes there only if it finds zeros, so a packet shaped differently costs a declaration
rather than the login.

The earliest Alpha has no seed — its login packet ends after the password — so the password is the
space instead. The client of that range always sends the literal `Password`, eight characters, and
the servers of that range read it with an uncapped `readUTF` and never look at it again. Eight
characters is what makes it the same mechanism: the marker replaces them, the string keeps its count
and the packet keeps its length. Loki writes there only if it finds `Password`.

Note the two tens in that table. The numbering restarted at a1.0.17, so a1.0.11 and b1.4_01 are both
protocol 10, and one of them has a seed and the other does not. What separates them is that the
older one sends no handshake at all — its login packet is the first thing on the wire — and that is
what decides which shape is read.

The top of that row is the only part of the range that cannot be tested against a server, and not
for want of one being archived: survival multiplayer arrived with a1.0.15 on 3 August 2010 and the
first server, Alpha 0.1.0, the day after it. The wiki says as much for each of the versions before
that — "corresponding server unavailable" — and both the Betacraft and Omniarchive collections
begin at 0.1.0. Those clients carry the networking code, which is why they can be made to send a
login packet at all, but no server was ever published that would answer one. They are covered by
the shape cases, built from the packets their own binaries really send, and that is the most that
exists to check them against.

From 1.3 the login packet has no username at all, and the mark moves to the host: the address the
client says it dialled, which the server already knows and drops. That is the field Forge has
appended `\0FML\0` to since the same release.

### Behind a proxy

It is also the only part of this a proxy can see, since a proxy does read the host — so it was put
in front of one and measured, with `scripts/proxy-test.sh`, using the BungeeCord builds that
supported these versions. The marker goes through untouched: the host arrives at the backend with
`\0Loki\0` still on the end of it, and the proxy has no more to say about a marked handshake than
about an unmarked one.

The block was the part that was not safe, and that is why it is a plugin message now. A raw block
came back from BungeeCord as `Unknown packet id 79` — the `O` of `0xFE 'L' 'O' 'K'` — because a
proxy is parsing that stream and raw bytes are not a packet in it.

Whether the declaration survives the trip is a separate question from whether the connection does,
and it depends on the proxy: one that rewrites the handshake for its own IP forwarding may leave the
backend never knowing a Loki client was there. That costs a declaration, not a login.

**Put Loki on the proxy instead.** It is one `-javaagent:` on the thing players actually connect to,
and it is where the declaration belongs: the proxy's address is the one the player typed, so it is
the one the client keys the override on, and a network behind a proxy usually has one API server
that the proxy already knows about. The servers behind it then need to know nothing about any of
this. It works on both sides of 1.7:

- **1.7 and up**: the proxy's own status response carries the declaration, the same as a server's.
  Measured on BungeeCord 26.1 and Velocity 4.0.0.
- **1.3 to 1.6.4**: the proxy reads the marker off the client's handshake and answers the plugin
  message itself. Measured on the BungeeCord builds of that era, with a backend running no Loki at
  all.

That second one is hooked below Netty rather than inside it — on the NIO channel the proxy's Netty
is itself reading from. It has to be: proxies old enough to serve those versions are old enough to
carry a Netty whose handler API was replaced in 2013, and Loki cannot install a handler in it at
all. Underneath is the one place every one of them looks the same.

**The proxy is the authority.** A server behind it that also runs Loki does not get to declare over
it: its declaration is taken out of the stream and dropped rather than passed on or acted upon. That
is not a preference, it is what the addresses say — the player typed the proxy's address, that is
what any override is keyed on, and a network behind a proxy has one API server that the proxy
already knows about. Loki works out which it is without being told: only a proxy is serving players
and connecting onward at the same moment. A player who opens a world to the LAN is serving someone
too, and is back to hearing servers normally the moment they are not.

A client that would rather send nothing at all turns the whole feature off with
`-DLoki.disable_profile_redirect=true`, which stops it asking as well as stops it listening.

Classic is the one era that never asks a socket for its streams — both ends of it are non-blocking
NIO — so it is hooked on the channel instead. Its Player Identification is 131 bytes and the last of
them is unused, which is where CPE has announced extended clients for years; Loki does not use CPE's
own `0x42` there, since that claims to speak a protocol it does not implement. The block comes back
in front of everything the server sends rather than after its identification, because on Classic the
client speaks first and the server has therefore read the marker before it writes a byte.

The block goes in front from 1.3 as well, and for the same reason. Everything between those two ends
of the range has it after the server's first packet, which is as early as the marker can be known
there.

Leaving restores, the same as on 1.7 and up. Nothing down here tells the game a visit is over, so the
end of the connection stands in for it — the stream ending, the stream closing, or the socket being
closed, which from 1.3 is the usual one.

There is no switch of its own for any of this. It is the same feature as the ping, so it answers to
the same two: `-DLoki.disable_profile_advertise=true` stops a server declaring on every version, and
`-DLoki.disable_profile_redirect=true` stops a client asking or listening on every version.

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
laxer, and it cannot reach past the visit. A client that would rather not be asked sets `Loki.enforce_secure_profile` itself — either way, and
a server cannot argue with an answer already given.

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

```
scripts/legacy-matrix.sh a1.2.6 b1.7.3 1.2.5 1.6.4
```
Below 1.7, where the only thing worth testing is the real thing. Each version is run twice against
its own server — once with a vanilla client, once with a Loki one — and the login is a real
authenticated one, with the API server on the other end checking the token rather than agreeing with
whatever it is sent. Four things have to hold every time: the player gets in with Loki, the player
gets in without it, only the Loki client is told anything, and the override is gone once the
connection is. With no arguments it runs the versions that have an archived server.

```
scripts/login-probe.sh
```
What each version's client actually puts on the wire, which is where the table above comes from. It
answers a handshake and dumps the login packet without interpreting it; run it if a version ever
turns out to be shaped differently than the table says.

## Turning it off

```
-DLoki.disable_profile_redirect=true
```

Server declarations are then ignored entirely, the Netty hook is not installed, and below 1.7 the
client neither marks itself nor reads a block. The server's half answers to
`-DLoki.disable_profile_advertise=true`, on every version alike.
