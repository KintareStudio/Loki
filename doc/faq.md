# Frequently Asked Questions

## What versions are supported?

Loki offers support for skins, capes, and authentication to every Minecraft version that has skins, capes, or authentication. From Classic 0.0.15a up to the latest version of Minecraft, Loki makes a strong effort to support **everything**.

## Wait, what about classic servers?

Minecraft Classic servers use the [Classic Protocol](https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Classic_Protocol), which is entirely incompatible with the Yggdrasil protocol.

If you are using Drasl version 4.0.0 or later, then the Classic Protocol is supported. Unfortunately, fallback API servers are not supported.

If you are using another API server (Blessing Skin, Ely.by), you could ask them to [implement it how Drasl does](https://github.com/unmojang/drasl/blob/da510ceb9cff4da9a5513b04e52ab9f6ac69c15b/session.go#L304-L419). For an immediate solution, consider playing [ClassiCube](https://www.classicube.net/) or [Classic+](https://legacy-plus.dejvoss.cz/) instead.

## Are total conversion mods or obscure mod loaders supported?

They should be, but if not, please file an issue.

## Does chat reporting/secure-profile work?

Chat reporting remains available when the API server supports it and accepts the report. This fork verifies profile signatures and player certificates by default against the trusted published keys. Set `-DLoki.enforce_secure_profile=false` or `-DLoki.verify_signatures=false` to explicitly disable verification; the former takes precedence when both are set. An API server still decides which reports to accept.

![Attempted cross-API server chat report](/img/chatreport.png)

## I'd like to use this on Windows 95, does it support Java 5?

Loki supports Java 5 and above. However, Java 5 lacks some functionality and may have issues in some modded environments. If you are using Java 6 or later, there should be no issues.
