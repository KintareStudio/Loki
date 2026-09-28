# Troubleshooting

## Fallback API server players see "Chat validation error"

Whenever anyone from a different API server talks in chat, fallback API server players may see "Chat validation error" and be unable to send messages afterward until they relog. This is due to the vanilla 1.19+ game client performing signature checks on messages prior to accepting them. The solution is to use Loki or authlib-injector on the client, which will most likely already be in use unless the fallback API server is Mojang. For supporting Mojang players, you should ensure that you are using either Loki or authlib-injector on the server, **NOT `minecraft.api.*.host` parameters**, then install [No Chat Reports](https://modrinth.com/mod/no-chat-reports) on the server[^1].


## My game immediately crashed!

If you're getting a crash like this:

```
javax.net.ssl.SSLException: Received fatal alert: protocol_version
```

or this:

```
[LF] ERROR: mouse
        java.lang.ClassCircularityError
        javax/crypto/BadPaddingException
        sun.security.rsa.RSASignature.engineVerify(RSASignature.java:216)
        java.security.Signature$Delegate.engineVerify(Signature.java:1393)
        java.security.Signature.verify(Signature.java:770)
```

then you need to upgrade your Java installation. If the crash is something else, please file an issue.

## Loki fails to connect to my API server

If Loki logs something like:

```
**** CONNECTION WAS FORBIDDEN TO THE API SERVER!
```

then something in front of your API server is rejecting connections. This most commonly happens when the API server is behind Cloudflare.

You can check whether the user agent is the problem by running the following commands:

```
$ curl -i -H "User-Agent: Java/1.8.0_51" https://drasl.example.com
$ curl -i -H "User-Agent: Loki/1.2.3" https://drasl.example.com
```

If the first command gets a 403 response code and the second succeeds with a 200 response code, the API server (or something in front of it) is blocking Java user agents specifically.

As a client-side workaround, you can try switching Loki's user agent with:

```
-DLoki.modify_user_agent=true
```

If both commands fail with a 403 response code, this workaround will not work. Please confirm you can visit the API server's page in your web browser, and notify your API server operator. This is not a problem with Loki, so you should not open an issue here about it.

[^1]: If your server is Bukkit-based (Spigot, Paper, etc.) then you can install a No Chat Reports plugin.
