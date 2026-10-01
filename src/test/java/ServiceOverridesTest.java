package org.unmojang.loki;
import java.util.*;
public class ServiceOverridesTest {
 public static void main(String[] args) throws Exception {
  Map<String,String> urls = new HashMap<String,String>();
  urls.put("realms", "https://realms.test/"); urls.put("filtering", "https://filtering.test");
  urls.put("api", "https://discovery.test/authlib-injector/api");
  urls.put("sessionserver", "https://sessions.test/");
  Map<String,String> aliases = ServiceOverrides.apply(urls);
  if (!"https://realms.test".equals(aliases.get("pc.realms.minecraft.net"))) throw new AssertionError("legacy Realm");
  if (!"https://realms.test".equals(aliases.get("java.frontend.realms.minecraft-services.net"))) throw new AssertionError("modern Realm");
  if (!"https://filtering.test/".equals(System.getProperty("Loki.filteringV1.authority"))) throw new AssertionError("Filtering authority");
  if (!"https://sessions.test/session/minecraft/hasJoined".equals(System.getProperty("mojang.sessionserver"))) throw new AssertionError("session override");
  urls.put("realms", "file:///private"); urls.put("filtering", "http://filtering.test");
  if (ServiceOverrides.apply(urls).containsKey("pc.realms.minecraft.net")) throw new AssertionError("invalid URL");
  if (!"https://filtering.test/".equals(System.getProperty("Loki.filteringV1.authority"))) throw new AssertionError("insecure authority");
  String document = "{\"meta\":{\"serverName\":\"Kintare\"},\"urlsRedefining\":{\"realms\":\"https://metadata-realms.test\",\"filtering\":\"https://metadata-filtering.test\"}}";
  System.setProperty("authlibinjector.yggdrasil.prefetched", java.util.Base64.getEncoder().encodeToString(document.getBytes("UTF-8")));
  java.lang.reflect.Method read = LokiUtil.class.getDeclaredMethod("initServerMetadata", String.class);
  read.setAccessible(true);
  read.invoke(null, "https://unreachable.invalid/authlib-injector");
  if (!"https://metadata-realms.test".equals(RequestInterceptor.YGGDRASIL_MAP.get("pc.realms.minecraft.net"))) throw new AssertionError("advertised Realm override");
  if (!"https://metadata-filtering.test/".equals(System.getProperty("Loki.filteringV1.authority"))) throw new AssertionError("advertised Filtering authority");
  System.out.println("Loki metadata and service override checks passed");
 }
}
