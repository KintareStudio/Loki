package org.unmojang.loki;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

final class ServiceOverrides {
    private static String address(String value, boolean origin) {
        if (value == null || value.length() == 0) return null;
        try {
            URL url = new URL(value);
            if ((!"https".equals(url.getProtocol()) && !"http".equals(url.getProtocol()))
                    || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
                    || url.getHost().length() == 0) return null;
            if (origin && (!"https".equals(url.getProtocol())
                    || (url.getPath().length() != 0 && !"/".equals(url.getPath())))) return null;
            return value.replaceAll("/+$", "");
        } catch (Exception ignored) { return null; }
    }

    static Map<String, String> apply(Map<String, String> overrides) {
        Map<String, String> aliases = new LinkedHashMap<String, String>();
        String[][] services = {
            {"api", "minecraft.api.account.host", "api.mojang.com"},
            {"authserver", "minecraft.api.auth.host", "authserver.mojang.com"},
            {"sessionserver", "minecraft.api.session.host", "sessionserver.mojang.com"},
            {"minecraftservices", "minecraft.api.services.host", "api.minecraftservices.com"},
            {"signaling", "", "signaling-afd.franchise.minecraft-services.net"}
        };
        for (int i = 0; i < services.length; i++) {
            String[] service = services[i];
            String url = address(overrides.get(service[0]), false);
            if (url == null) continue;
            if (service[1].length() != 0) System.setProperty(service[1], url);
            aliases.put(service[2], url);
            if ("api".equals(service[0])) System.setProperty("minecraft.api.profiles.host", url);
            if ("sessionserver".equals(service[0]))
                System.setProperty("mojang.sessionserver", url + "/session/minecraft/hasJoined");
        }
        String realms = address(overrides.get("realms"), false);
        if (realms != null) {
            System.setProperty("minecraft.api.realms.host", realms);
            String[] hosts = {"pc.realms.minecraft.net", "mcoapi.minecraft.net", "mcoapi-stage.minecraft.net",
                "pc-stage.realms.minecraft.net", "java.frontendlegacy.realms.minecraft-services.net",
                "java.frontend.realms.minecraft-services.net",
                "java.frontendlegacy.stage-c2a40e62.realms.minecraft-services.net",
                "java.frontend.stage-c2a40e62.realms.minecraft-services.net"};
            for (int i = 0; i < hosts.length; i++) aliases.put(hosts[i], realms);
        }
        String filtering = address(overrides.get("filtering"), true);
        if (filtering != null) System.setProperty("Loki.filteringV1.authority", filtering + "/");
        return aliases;
    }
}
