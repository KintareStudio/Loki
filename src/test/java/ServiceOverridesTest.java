package org.unmojang.loki;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
public class ServiceOverridesTest {
 private static void checkAgentRouting() throws Exception {
  HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  provider.createContext("/custom/", exchange -> {
   exchange.getResponseHeaders().set("X-Authlib-Injector-Api-Location", "api-root/");
   exchange.sendResponseHeaders(200, -1);
   exchange.close();
  });
  provider.createContext("/custom/api-root", exchange -> {
   byte[] document = "{\"meta\":{\"serverName\":\"Independent provider\"}}".getBytes("UTF-8");
   exchange.sendResponseHeaders(200, document.length);
   exchange.getResponseBody().write(document);
   exchange.close();
  });
  provider.start();
  try {
   String agent = "http://127.0.0.1:" + provider.getAddress().getPort() + "/custom/";
   System.setProperty("Loki.url", "https://ignored.invalid/");
   java.lang.reflect.Method init = LokiUtil.class.getDeclaredMethod("initAuthlibInjectorAPI", String.class);
   init.setAccessible(true);
   init.invoke(null, LokiUtil.authlibInjectorUrl(agent));
   if (!(agent + "api-root/minecraftservices").equals(System.getProperty("minecraft.api.services.host")))
    throw new AssertionError("agent argument and relative metadata location");
   if (!"Independent provider".equals(LokiUtil.SERVER_NAME)) throw new AssertionError("provider metadata");
   for (String host : ServiceOverrides.SIGNALING_HOSTS) {
    if (!(agent + "api-root/signaling").equals(RequestInterceptor.YGGDRASIL_MAP.get(host)))
     throw new AssertionError("Signaling fallback for " + host);
   }
  } finally {
   System.clearProperty("Loki.url");
   provider.stop(0);
  }
 }

 public static void main(String[] args) throws Exception {
  checkAgentRouting();
  Map<String,String> urls = new HashMap<String,String>();
  urls.put("realms", "https://realms.test/"); urls.put("filtering", "https://filtering.test");
  urls.put("api", "https://discovery.test/authlib-injector/api");
  urls.put("sessionserver", "https://sessions.test/");
  urls.put("signaling", "https://signaling.test/");
  Map<String,String> aliases = ServiceOverrides.apply(urls);
  if (!"https://signaling.test".equals(aliases.get("signaling-afd.franchise.minecraft-services.net"))) throw new AssertionError("production Signaling");
  if (!"https://signaling.test".equals(aliases.get("signaling-afd.stage-6fd5f759.franchise.minecraft-services.net"))) throw new AssertionError("staging Signaling");
  if (!"https://realms.test".equals(aliases.get("pc.realms.minecraft.net"))) throw new AssertionError("legacy Realm");
  if (!"https://realms.test".equals(aliases.get("java.frontend.realms.minecraft-services.net"))) throw new AssertionError("modern Realm");
  if (!"https://filtering.test/".equals(System.getProperty("Loki.filteringV1.authority"))) throw new AssertionError("Filtering authority");
  if (!"https://sessions.test/session/minecraft/hasJoined".equals(System.getProperty("mojang.sessionserver"))) throw new AssertionError("session override");
  urls.put("realms", "file:///private"); urls.put("filtering", "http://filtering.test");
  if (ServiceOverrides.apply(urls).containsKey("pc.realms.minecraft.net")) throw new AssertionError("invalid URL");
  if (!"https://filtering.test/".equals(System.getProperty("Loki.filteringV1.authority"))) throw new AssertionError("insecure authority");
  String document = "{\"meta\":{\"serverName\":\"Example\"},\"urlsRedefining\":{\"realms\":\"https://metadata-realms.test\",\"filtering\":\"https://metadata-filtering.test\"}}";
  System.setProperty("authlibinjector.yggdrasil.prefetched", java.util.Base64.getEncoder().encodeToString(document.getBytes("UTF-8")));
  java.lang.reflect.Method read = LokiUtil.class.getDeclaredMethod("initServerMetadata", String.class);
  read.setAccessible(true);
  read.invoke(null, "https://unreachable.invalid/authlib-injector");
  if (!"https://metadata-realms.test".equals(RequestInterceptor.YGGDRASIL_MAP.get("pc.realms.minecraft.net"))) throw new AssertionError("advertised Realm override");
  if (!"https://metadata-filtering.test/".equals(System.getProperty("Loki.filteringV1.authority"))) throw new AssertionError("advertised Filtering authority");
  System.setProperty("minecraft.api.services.host", "https://discovery.test/authlib-injector/minecraftservices/");
  org.unmojang.loki.util.Json.JSONObject discovery = new org.unmojang.loki.util.Json.JSONObject(
      org.unmojang.loki.hooks.Hooks.getDiscoveryJson()).getJSONObject("discovery");
  org.unmojang.loki.util.Json.JSONObject player = discovery.getJSONObject("player").getJSONObject("endpoints");
  String base = "https://discovery.test/authlib-injector/minecraftservices";
  if (!(base + "/friends").equals(player.getJSONObject("getFriends").getString("uri"))) throw new AssertionError("friends discovery");
  if (!(base + "/friends").equals(player.getJSONObject("updateFriends").getString("uri"))) throw new AssertionError("friends update discovery");
  if (!(base + "/presence").equals(player.getJSONObject("updatePresence").getString("uri"))) throw new AssertionError("presence discovery");
  if (discovery.getJSONObject("authentication").getJSONObject("endpoints").has("loginXbox")) throw new AssertionError("Xbox login must not be announced");
  if (!"https://metadata-realms.test".equals(RequestInterceptor.YGGDRASIL_MAP.get("java.frontend.realms.minecraft-services.net"))) throw new AssertionError("Discovery changed Realms routing");
  if (!"https://metadata-filtering.test/".equals(System.getProperty("Loki.filteringV1.authority"))) throw new AssertionError("Discovery changed Filtering authority");
  System.out.println("Loki metadata and service override checks passed");
 }
}
