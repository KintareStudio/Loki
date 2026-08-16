/**
 * Starts a proxy old enough to refuse the JVM it is given, without changing what it does.
 * <p>
 * BungeeCord builds from the era this test needs check {@code java.version} against the literal
 * "1.7" before doing anything else. It is a string comparison written when Java 8 did not exist,
 * not a capability check, and the property cannot be set from the command line: the VM fills it in
 * itself and ignores any {@code -D} for it. Setting it from inside the process, before the proxy's
 * own main runs, is the only way to get past a check that is wrong rather than protective.
 * <p>
 * Nothing else is altered. The proxy is the real jar, started by its real entry point, in its own
 * directory with its own configuration.
 *
 * @param args the main class to run, then whatever it should be given
 */
public final class OldProxyHost {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: OldProxyHost <main-class> [args...]");
            System.exit(2);
        }
        System.setProperty("java.version", "1.7.0_80");

        String[] rest = new String[Math.max(0, args.length - 1)];
        System.arraycopy(args, 1, rest, 0, rest.length);
        Class.forName(args[0]).getMethod("main", String[].class).invoke(null, (Object) rest);
    }
}
