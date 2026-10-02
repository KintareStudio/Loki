package org.unmojang.loki.hooks;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.unmojang.loki.RequestInterceptor;

public final class RealmsHooks {
    private static final Set<String> HOSTS = new HashSet<String>(Arrays.asList(
            "pc.realms.minecraft.net", "pc-stage.realms.minecraft.net", "mcoapi.minecraft.net",
            "mcoapi-stage.minecraft.net", "java.frontendlegacy.realms.minecraft-services.net",
            "java.frontend.realms.minecraft-services.net",
            "java.frontendlegacy.stage-c2a40e62.realms.minecraft-services.net",
            "java.frontend.stage-c2a40e62.realms.minecraft-services.net"));

    public static String redirect(String value) {
        URI source = URI.create(value);
        if (!HOSTS.contains(source.getHost())) return value;
        String endpoint = RequestInterceptor.YGGDRASIL_MAP.get(source.getHost());
        if (endpoint == null) return value;
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        return endpoint + source.getRawPath()
                + (source.getRawQuery() == null ? "" : "?" + source.getRawQuery());
    }
}
