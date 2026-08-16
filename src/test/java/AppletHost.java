import java.applet.Applet;
import java.applet.AppletContext;
import java.applet.AppletStub;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs the real client, as the applet, so that a test can connect to a server without a person
 * clicking through the menus.
 * <p>
 * The point is that nothing here reimplements the protocol. Earlier tests drove a client written
 * for the purpose, and it sent a protocol version and a login packet that no real client of that
 * version would have sent — which proved the server rejects nonsense, and nothing about whether
 * Loki works. The packets this sends are the game's own, because this <em>is</em> the game.
 * <p>
 * The applet is how old versions were told where to connect: the page passed {@code server} and
 * {@code port} and the client joined on its own. That is still in every client jar of the era, and
 * it is why this needs no window automation.
 *
 * @param args username, sessionid, server, port — the four parameters the applet asks for
 */
public class AppletHost {
    public static void main(String[] args) throws Exception {
        final Map<String, String> parameters = new HashMap<String, String>();
        parameters.put("username", args.length > 0 ? args[0] : "Player");
        parameters.put("sessionid", args.length > 1 ? args[1] : "0");
        if (args.length > 2) parameters.put("server", args[2]);
        if (args.length > 3) parameters.put("port", args[3]);
        if (args.length > 4) parameters.put("mppass", args[4]);
        parameters.put("stand-alone", "true");
        parameters.put("demo", "false");
        parameters.put("fullscreen", "false");

        String appletClass = System.getProperty("loki.applet", "net.minecraft.client.MinecraftApplet");
        final Applet applet = (Applet) Class.forName(appletClass).newInstance();

        final Frame frame = new Frame("Loki test client");
        frame.setLayout(new BorderLayout());
        frame.add(applet, BorderLayout.CENTER);
        frame.setSize(856, 500);

        applet.setStub(new AppletStub() {
            public boolean isActive() {
                return true;
            }

            public URL getDocumentBase() {
                try {
                    return new URL("http://www.minecraft.net/game/");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            public URL getCodeBase() {
                return getDocumentBase();
            }

            public String getParameter(String name) {
                return parameters.get(name);
            }

            public AppletContext getAppletContext() {
                return null;
            }

            public void appletResize(int width, int height) {
                frame.setSize(width, height);
            }
        });

        frame.setVisible(true);
        applet.init();
        applet.start();

        // Left running: the caller kills it when it has seen what it came for. A client that exits
        // on its own has usually crashed, and that is worth noticing rather than tidying away.
        Thread.sleep(Long.parseLong(System.getProperty("loki.seconds", "40")) * 1000L);
        applet.stop();
        applet.destroy();
        frame.dispose();
        System.exit(0);
    }
}
