# Configuration

## JVM Arguments

Loki supports JVM arguments to enable or disable some behaviour.

- Use Authlib-Injector URL instead of `minecraft.api.*.host` parameters
  ```
  -DLoki.url=https://drasl.unmojang.org
  ```

- Enable debug mode (increased verbosity)
  ```
  -DLoki.debug=true
  ```

- Enable trace mode (maximum verbosity)
  ```
  -DLoki.trace=true
  ```

- Automatically update Loki in place on startup (skipped when offline)
  ```
  -DLoki.auto_update=true
  ```

- Re-enable chat restrictions
  ```
  -DLoki.chat_restrictions=true
  ```

- Disable the URL factory
  ```
  -DLoki.disable_factory=true
  ```

- Disable username-based profile lookups [^1]
  ```
  -DLoki.disable_profile_lookup=true
  ```

- Ignore a server's declared profile API [^3]
  ```
  -DLoki.disable_profile_redirect=true
  ```

- Keep enforcing the texture allowlist on a server that declared no `skinDomains`, instead of
  accepting any texture host for the duration [^3]
  ```
  -DLoki.strict_texture_domains=true
  ```

- Stop a server declaring its own API server to Loki clients [^3]
  ```
  -DLoki.disable_profile_advertise=true
  ```

- Re-enable patchy (server blocking)
  ```
  -DLoki.enable_patchy=true
  ```

- Re-enable snooper
  ```
  -DLoki.enable_snooper=true
  ```

- Verify signatures instead of accepting them: profile properties from 1.7.6, player certificates from 1.19, and chat on 1.19+ servers where `enforce-secure-profile=true` is set in `server.properties` [^2] [^4]
  ```
  -DLoki.enforce_secure_profile=true
  ```
  On a server this flag does one more thing: it tells the Loki clients that join that signatures are
  checked here, so they check too for as long as they are on it. [^3]

- Ignore a server asking for signatures to be verified on it, and check only where you said so [^3]
  ```
  -DLoki.ignore_declared_secure_profile=true
  ```

- Force the applet launcher to re-download the game, for pre-Beta 1.3 applet launchers that lack a "Force Update" option
  ```
  -DLoki.launcher_trigger_update=true
  ```

- Choose which Minecraft version the applet launcher runs, required since applet launchers have no version picker
  ```
  -DLoki.launcher_version=1.5.2
  ```

- Re-enable modded capes with username-based lookups (OptiFine, Cloaks+, etc.)
  ```
  -DLoki.modded_capes=true
  ```

- Re-enable the username validation added in 1.18.2 that kicks usernames containing invalid characters
  ```
  -DLoki.username_validation=true
  ```

## Prefetched API Metadata

Loki reads the API metadata a launcher already fetched, in either of the properties
authlib-injector takes:

```
-Dauthlibinjector.yggdrasil.prefetched=<base64 of the API metadata>
-Dorg.to2mbn.authlibinjector.config.prefetched=<the same, under the older name>
```

It is used in place of fetching that document, so a session can start without waiting on the API
server, or at all when it is unreachable. It does not replace `/publickeys`: signing keys are still
asked of that endpoint first, and the prefetched document is what answers when it has nothing.

## Changing the Default API Servers

By default, Loki will use Mojang's API servers if none are provided. If you would like, you can change Loki's default API servers by editing `loki.properties` before compiling:

```
authlibInjectorAPIServer=drasl.unmojang.org
authHost=https://authserver.mojang.com
accountHost=https://api.mojang.com
sessionHost=https://sessionserver.mojang.com
servicesHost=https://api.minecraftservices.com
```

You can also override these properties within the build command:
```
ant -DauthlibInjectorAPIServer=https://drasl.unmojang.org/authlib-injector
```

`authlibInjectorAPIServer` is preferred when it is set, keep it empty if your API server does not support the authlib-injector API.

If you don't want to recompile Loki, you can instead edit the Loki jar's `META-INF/MANIFEST.MF`:

```
AuthlibInjectorAPIServer: 
AuthHost: https://drasl.unmojang.org/auth
AccountHost: https://drasl.unmojang.org/account
SessionHost: https://drasl.unmojang.org/session
ServicesHost: https://drasl.unmojang.org/services
```

[^1]: Username-based profile lookups allow for displaying textures on offline mode servers.
[^3]: A 1.7+ server can name an API server for Loki to resolve profile queries against, so players are visible even when the client and the server do not share an API server. Only profile reads are affected; see [profile-redirect.md](profile-redirect.md).
[^4]: Without this, the signature checks return true without looking, which is what lets an API server that does not sign at all work. With it, they are checked against every key the API server publishes — `signaturePublickeys` in authlib-injector metadata, or `profilePropertyKeys` and `playerCertificateKeys` from `/publickeys` — plus Mojang's own key, which authlib bundles up to 1.19.4 and which is what makes a profile proxied from a fallback API server verify. Trusting a set rather than one key is also what keeps a key rotation from turning every player into Steve. The two kinds of key are kept apart: a property key cannot vouch for a certificate or the other way round. Which method is asked depends on the version — `Property.isSignatureValid` up to 1.18.2, `ServicesKeyInfo.validateProperty` and `ServicesKeyInfo.signature` from 1.19. Note that an **unsigned** property is still rejected before Loki sees it, by `hasSignature`.
[^2]: This option is **NOT** necessary to ensure the integrity of chat reports made to the API server from clients, and will kick [fallback API server](https://github.com/unmojang/drasl/blob/master/doc/configuration.md) players.
