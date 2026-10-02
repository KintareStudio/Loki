package org.unmojang.loki;

import org.unmojang.loki.util.logger.NilLogger;
import org.unmojang.loki.transformers.*;

import java.lang.instrument.Instrumentation;

public class Loki {
    public static final NilLogger log = NilLogger.get("Loki");

    public static final boolean auto_update = Boolean.getBoolean("Loki.auto_update");
    public static final boolean chat_restrictions = Boolean.parseBoolean(System.getProperty("Loki.chat_restrictions", "true"));
    public static boolean disable_factory = Boolean.getBoolean("Loki.disable_factory");
    public static final boolean disable_profile_lookup = Boolean.getBoolean("Loki.disable_profile_lookup");
    public static final boolean disable_profile_advertise = Boolean.getBoolean("Loki.disable_profile_advertise");
    public static final boolean disable_profile_redirect = Boolean.getBoolean("Loki.disable_profile_redirect");
    public static final boolean enable_patchy = Boolean.parseBoolean(System.getProperty("Loki.enable_patchy", "true"));
    public static final boolean enable_snooper = Boolean.parseBoolean(System.getProperty("Loki.enable_snooper", "true"));
    public static final boolean enforce_secure_profile = Boolean.parseBoolean(System.getProperty(
            "Loki.enforce_secure_profile", System.getProperty("Loki.verify_signatures", "true")));
    public static final boolean launcher_trigger_update = Boolean.getBoolean("Loki.launcher_trigger_update");
    public static final String launcher_version = System.getProperty("Loki.launcher_version");
    public static final boolean modded_capes = Boolean.getBoolean("Loki.modded_capes");
    public static final boolean modify_user_agent = Boolean.getBoolean("Loki.modify_user_agent");
    public static final boolean username_validation = Boolean.getBoolean("Loki.username_validation");
    public static final boolean verify_signatures = enforce_secure_profile;

    public static void premain(String agentArgs, Instrumentation inst) {
        if (auto_update && LokiUpdater.updateAndSwap(agentArgs, inst)) return;

        log.info("Hello Loki " + LokiUtil.getAgentVersion() + " World!");
        LokiUtil.earlyInit(agentArgs, inst);

        // Kill Loki alternatives so that Loki won't break
        LokiUtil.addRetransformTransformer(new LokiAlternativesTransformer(), inst);
        LokiUtil.retransformClass("moe.yushi.authlibinjector.Premain", inst);
        LokiUtil.retransformClass("moe.yushi.authlibinjector.javaagent.AuthlibInjectorPremain", inst);
        LokiUtil.retransformClass("org.to2mbn.authlibinjector.javaagent.AuthlibInjectorPremain", inst);
        LokiUtil.retransformClass("moe.yushi.authlibinjector.transform.ClassTransformer", inst);
        LokiUtil.retransformClass("org.to2mbn.authlibinjector.transform.ClassTransformer", inst);

        // Authentication & skins/capes
        RequestInterceptor.setURLFactory();
        inst.addTransformer(new YggdrasilURLTransformer()); // Transform Yggdrasil URL strings
        inst.addTransformer(new RealmsURLTransformer());
        inst.addTransformer(new AppletParameterTransformer()); // Fetch mppass for classic multiplayer
        inst.addTransformer(new PlayerSafetyFilterTransformer());
        inst.addTransformer(new DiscoveryServiceTransformer()); // Feed authlib a crafted discovery doc. 26.3+

        // Textures
        inst.addTransformer(new AllowedDomainTransformer()); // Allowed texture domains. 1.7.6-1.16.5, 1.17-1.19.2, 1.19.3+
        inst.addTransformer(new SignatureValidTransformer()); // Verify texture signatures. 1.7.6-1.18.2
        inst.addTransformer(new FetchTexturesByPlayerNameTransformer()); // Fetch textures on offline mode servers
        inst.addTransformer(new NettyConnectTransformer()); // Let a server redirect profile queries. 1.7+
        inst.addTransformer(new NettyBindTransformer()); // Declare this server's API server to Loki clients. 1.7+

        // Public keys
        inst.addTransformer(new MainArgsTransformer()); // secure-profile breaks if userType is "mojang" on 1.19.3-1.21.8
        inst.addTransformer(new ServicesKeyInfoTransformer());  // 1.19+

        // Usernames
        inst.addTransformer(new ClassicUsernameLengthTransformer()); // Disable username length limit on Classic server 1.10.x (0.30)
        inst.addTransformer(new UsernameCharacterCheckTransformer()); // Support cursed usernames on 1.18.2+

        // Anti-features
        inst.addTransformer(new PatchyTransformer());
        inst.addTransformer(new PlayerAttributesTransformer());

        // Applet Launcher
        inst.addTransformer(new AppletLauncherLoginTransformer()); // Handle pre-Yggdrasil login flow
        inst.addTransformer(new AppletLauncherNativesTransformer()); // Use natives from version manifest
        inst.addTransformer(new AppletLauncherLoginFormTransformer()); // Disable fields & add Logout button for MS accounts

        // 1.6 Launcher
        inst.addTransformer(new OneSixLauncherLoginTransformer()); // Handle pre-Yggdrasil login flow
        inst.addTransformer(new OneSixLauncherYggdrasilAuthTransformer()); // Handle MS account support on 1.1+
        inst.addTransformer(new OneSixLauncherLoginFormTransformer()); // Disable login fields & add Logout for MS accounts
        inst.addTransformer(new OneSixLauncherGameRunnerTransformer()); // Insert Loki into JVM args
        inst.addTransformer(new OneSixLauncherLibraryTransformer()); // Fix 1.19+ natives handling
        inst.addTransformer(new OneSixLauncherModernAssetsTransformer()); // Backport modern resource/asset system to older launcher versions
        inst.addTransformer(new OneSixLauncherTelemetryTransformer()); // Disable launcher telemetry by default
        inst.addTransformer(new OneSixLauncherUpdateNagTransformer()); // Hide the "old version of the launcher" banner
        inst.addTransformer(new OneSixLauncherReleaseTypeTransformer()); // Version release type related fixes

        // Misc fixes
        inst.addTransformer(new BungeeCordTransformer()); // Patch BungeeCord's public key check and username filter
        inst.addTransformer(new ConcatenateURLTransformer()); // Prevent port number being ignored in old authlib, if you specified it
        inst.addTransformer(new ReIndevGetSkinTransformer()); // Fix a bug in ReIndev's ThreadGetSkin
        inst.addTransformer(new MCOSEMultiplayerTransformer()); // Propagate Loki into MCOSE server subprocess
        LokiUtil.addRetransformTransformer(new SetURLFactoryTransformer(), inst); // Fix 1.13-1.16 Forge, LegacyFix agent
        LokiUtil.retransformClass("uk.betacraft.legacyfix.LegacyFixLauncher", inst);
        inst.addTransformer(new TitleScreenTransformer()); // Server brand on title screen for 26.1+

        // Intercept OptiFine capes to prevent collisions, for whatever reason it isn't caught by Loki's URL factory
        inst.addTransformer(new OptiFineCapeTransformer());

        // Block some DNS lookups
        LokiUtil.addRetransformTransformer(new InetAddressTransformer(), inst);
        LokiUtil.retransformClass("java.net.InetAddress", inst);

        // The pre-1.7 announcement, which has no Netty to hang off and lives on the socket instead
        LokiUtil.addRetransformTransformer(new SocketStreamTransformer(), inst);
        LokiUtil.retransformClass("java.net.Socket", inst);
        LokiUtil.retransformClass("java.net.ServerSocket", inst);

        // The same announcement on Classic, which is the one era that never asks a socket for its
        // streams: both ends of it are non-blocking NIO
        LokiUtil.addRetransformTransformer(new ChannelTransformer(), inst);
        LokiUtil.retransformClass("sun.nio.ch.SocketChannelImpl", inst);
        LokiUtil.retransformClass("sun.nio.ch.ServerSocketChannelImpl", inst);

        // Apply 1.21.9+ fixes
        LokiUtil.apply1_21_9Fixes();
    }
}
