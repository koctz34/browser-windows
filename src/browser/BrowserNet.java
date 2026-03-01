package browser;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;

import java.awt.event.KeyEvent;
import java.util.Random;

public class BrowserNet {
    private static final String PACKET = "bw";
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Random rng = new Random();

    static boolean multiplayerActive = false;
    static boolean handshakeReceived = false;
    static boolean verbose = false;
    private static boolean wasClient = false;
    private static int cleanupCounter = 0;
    private static int mediaSyncCounter = 0;
    private static int keepaliveCounter = 0;
    private static int stateSyncCounter = 0;
    private static int handshakeRetries = 0;
    private static final int MAX_HANDSHAKE_RETRIES = 5;

    static void init() {
        if (Vars.netClient == null) {
            Core.app.post(() -> {
                if (Vars.netClient != null) {
                    Vars.netClient.addPacketHandler(PACKET, BrowserNet::handleServerPacket);
                }
            });
        } else {
            Vars.netClient.addPacketHandler(PACKET, BrowserNet::handleServerPacket);
        }
    }

    static String shortId() {
        char[] buf = new char[6];
        for (int i = 0; i < 6; i++) buf[i] = ID_CHARS.charAt(rng.nextInt(ID_CHARS.length()));
        return new String(buf);
    }

    static String cleanName(String name) {
        if (name == null) return "Unknown";
        return name.replaceAll("\\[.*?\\]", "").trim();
    }

    static void update() {
        if (Vars.state == null) return;
        try {
            detectMultiplayerState();
        } catch (Exception ignored) {}

        if (!Vars.net.client() || !multiplayerActive) return;

        keepaliveCounter++;
        if (keepaliveCounter >= 3600) {
            keepaliveCounter = 0;
            try { Call.serverPacketReliable(PACKET, "H"); } catch (Throwable ignored) {}
        }

        cleanupCounter++;
        if (cleanupCounter >= 120) {
            cleanupCounter = 0;
            try { cleanupDisconnectedOwners(); } catch (Exception ignored) {}
        }

        mediaSyncCounter++;
        if (mediaSyncCounter >= 300) {
            mediaSyncCounter = 0;
            try { sendAllMediaStates(); } catch (Exception ignored) {}
        }

        stateSyncCounter++;
        if (stateSyncCounter >= 600) {
            stateSyncCounter = 0;
            try { sendFullStateSync(); } catch (Exception ignored) {}
        }
    }

    private static void detectMultiplayerState() {
        boolean isClient = Vars.net.client();

        if (isClient && !wasClient) {
            multiplayerActive = false;
            handshakeReceived = false;
            handshakeRetries = 0;
            cleanupCounter = 0;
            mediaSyncCounter = 0;
            keepaliveCounter = 0;
            stateSyncCounter = 0;
            scheduleHandshake(1.5f);
        } else if (!isClient && wasClient) {
            multiplayerActive = false;
            handshakeReceived = false;
            clearAllBrowsers();
        }

        wasClient = isClient;
    }

    private static void scheduleHandshake(float delay) {
        Timer.schedule(() -> {
            if (!Vars.net.client()) return;
            if (handshakeReceived) return;

            handshakeRetries++;
            try {
                Call.serverPacketReliable(PACKET, "H");
                if (verbose) Log.info("[BW] Handshake sent (@/@)", handshakeRetries, MAX_HANDSHAKE_RETRIES);
            } catch (Throwable ignored) {}

            if (!handshakeReceived && handshakeRetries < MAX_HANDSHAKE_RETRIES) {
                scheduleHandshake(3f);
            }
        }, delay);
    }

    private static void handleServerPacket(String contents) {
        if (contents == null || contents.isEmpty()) return;

        try {
            char cmd = contents.charAt(0);
            String data = contents.length() > 2 ? contents.substring(2) : "";

            if (verbose) Log.info("[BW] Server -> @", cmd);

            switch (cmd) {
                case 'H': handleHandshake(); break;
                case 'F': handleFullSync(data); break;
                case 'C': handleCreate(data); break;
                case 'M': handleMove(data); break;
                case 'R': handleResize(data); break;
                case 'U': handleUrl(data); break;
                case 'D': handleDelete(data); break;
                case 'K': handleKey(data); break;
                case 'I': handleMouseInput(data); break;
                case 'W': handleScrollInput(data); break;
                case 'V': handleMediaState(data); break;
                case 'S': handleStateSync(data); break;
                case 'X': handleRemove(data); break;
            }
        } catch (Exception e) {
            Log.err("[BW] Packet error", e);
        }
    }

    private static void handleHandshake() {
        handshakeReceived = true;
        multiplayerActive = true;
        if (verbose) Log.info("[BW] Sync active");
    }

    private static void handleFullSync(String data) {
        if (data.isEmpty()) return;

        String[] entries = data.split(";");
        for (String entry : entries) {
            String[] p = entry.split(",", 7);
            if (p.length < 7) continue;

            String id = p[0];
            if (findById(id) != null) continue;

            String owner = p[1];
            float bx = Float.parseFloat(p[2]);
            float by = Float.parseFloat(p[3]);
            int bw = Integer.parseInt(p[4]);
            int bh = Integer.parseInt(p[5]);
            String url = p[6];

            Core.app.post(() -> {
                if (!BrowserMod.browserReady) return;
                if (findById(id) != null) return;
                WorldBrowser wb = new WorldBrowser(id, owner, true, bx, by, bw, bh, url);
                BrowserMod.browsers.add(wb);
            });
        }
    }

    private static void handleCreate(String data) {
        String[] p = data.split("\\|", 7);
        if (p.length < 7) return;

        String owner = p[0];
        String id = p[1];
        if (findById(id) != null) return;

        float bx = Float.parseFloat(p[2]);
        float by = Float.parseFloat(p[3]);
        int bw = Integer.parseInt(p[4]);
        int bh = Integer.parseInt(p[5]);
        String url = p[6];

        Core.app.post(() -> {
            if (!BrowserMod.browserReady) return;
            if (findById(id) != null) return;
            WorldBrowser wb = new WorldBrowser(id, owner, true, bx, by, bw, bh, url);
            BrowserMod.browsers.add(wb);
        });
    }

    private static void handleMove(String data) {
        String[] p = data.split("\\|", 3);
        if (p.length < 3) return;

        WorldBrowser wb = findById(p[0]);
        if (wb == null || !wb.remote) return;

        wb.x = Float.parseFloat(p[1]);
        wb.y = Float.parseFloat(p[2]);
    }

    private static void handleResize(String data) {
        String[] p = data.split("\\|", 3);
        if (p.length < 3) return;

        WorldBrowser wb = findById(p[0]);
        if (wb == null || !wb.remote) return;

        int w = Integer.parseInt(p[1]);
        int h = Integer.parseInt(p[2]);
        Core.app.post(() -> wb.resizeTo(w, h));
    }

    private static void handleUrl(String data) {
        String[] p = data.split("\\|", 2);
        if (p.length < 2) return;

        WorldBrowser wb = findById(p[0]);
        if (wb == null || !wb.remote) return;

        String url = p[1];
        Core.app.post(() -> wb.loadURL(url));
    }

    private static void handleDelete(String data) {
        WorldBrowser wb = findById(data.trim());
        if (wb == null || !wb.remote) return;

        Core.app.post(() -> {
            wb.dispose();
            BrowserMod.browsers.remove(wb);
        });
    }

    private static void handleMouseInput(String data) {
        String[] p = data.split("\\|", 5);
        if (p.length < 5) return;

        WorldBrowser wb = findById(p[0]);
        if (wb == null || wb.browser == null) return;

        int eventType = Integer.parseInt(p[1]);
        int mx = Integer.parseInt(p[2]);
        int my = Integer.parseInt(p[3]);
        int button = Integer.parseInt(p[4]);

        Core.app.post(() -> wb.applyRemoteMouse(eventType, mx, my, button));
    }

    private static void handleScrollInput(String data) {
        String[] p = data.split("\\|", 4);
        if (p.length < 4) return;

        WorldBrowser wb = findById(p[0]);
        if (wb == null || wb.browser == null) return;

        int sx = Integer.parseInt(p[1]);
        int sy = Integer.parseInt(p[2]);
        int scrollAmount = Integer.parseInt(p[3]);

        Core.app.post(() -> wb.applyRemoteScroll(sx, sy, scrollAmount));
    }

    private static void handleKey(String data) {
        String[] p = data.split("\\|", 5);
        if (p.length < 5) return;

        WorldBrowser wb = findById(p[0]);
        if (wb == null || wb.browser == null) return;

        char type = p[1].charAt(0);
        int vk = Integer.parseInt(p[2]);
        char ch = (char) Integer.parseInt(p[3]);
        int mods = Integer.parseInt(p[4]);

        int eventType = type == 'P' ? KeyEvent.KEY_PRESSED
                      : type == 'R' ? KeyEvent.KEY_RELEASED
                      : KeyEvent.KEY_TYPED;

        Core.app.post(() -> wb.applyRemoteKey(eventType, vk, ch, mods));
    }

    private static void handleMediaState(String data) {
        String[] p = data.split("\\|", 4);
        if (p.length < 4) return;

        WorldBrowser wb = findById(p[0]);
        if (wb == null || !wb.remote) return;

        String videoId = p[1];
        float time = Float.parseFloat(p[2]);
        boolean playing = "P".equals(p[3]);

        Core.app.post(() -> wb.applyMediaState(videoId, time, playing));
    }

    private static void handleStateSync(String data) {
        String[] p = data.split("\\|", 6);
        if (p.length < 6) return;

        String id = p[0];
        float sx = Float.parseFloat(p[1]);
        float sy = Float.parseFloat(p[2]);
        int sw = Integer.parseInt(p[3]);
        int sh = Integer.parseInt(p[4]);
        String url = p[5];

        WorldBrowser wb = findById(id);
        if (wb == null) {
            Core.app.post(() -> {
                if (!BrowserMod.browserReady) return;
                if (findById(id) != null) return;
                String owner = "Synced";
                WorldBrowser nwb = new WorldBrowser(id, owner, true, sx, sy, sw, sh, url);
                BrowserMod.browsers.add(nwb);
            });
            return;
        }
        if (!wb.remote) return;

        wb.x = sx;
        wb.y = sy;
        if (wb.browserW != sw || wb.browserH != sh) {
            int fw = sw, fh = sh;
            Core.app.post(() -> wb.resizeTo(fw, fh));
        }
        if (!url.equals(wb.currentURL)) {
            Core.app.post(() -> wb.loadURL(url));
        }
    }

    private static void handleRemove(String data) {
        String id = data.trim();
        WorldBrowser wb = findById(id);
        if (wb == null || !wb.remote) return;

        Core.app.post(() -> {
            wb.dispose();
            BrowserMod.browsers.remove(wb);
        });
    }

    private static void send(String payload) {
        if (!multiplayerActive) return;
        try {
            Call.serverPacketReliable(PACKET, payload);
        } catch (Throwable ignored) {}
    }

    static void sendCreate(WorldBrowser wb) {
        if (!multiplayerActive) return;
        send("C|" + wb.id + "|" + wb.x + "|" + wb.y
                + "|" + wb.browserW + "|" + wb.browserH + "|" + wb.currentURL);
    }

    static void sendMove(WorldBrowser wb) {
        send("M|" + wb.id + "|" + wb.x + "|" + wb.y);
    }

    static void sendResize(WorldBrowser wb) {
        send("R|" + wb.id + "|" + wb.browserW + "|" + wb.browserH);
    }

    static void sendUrl(WorldBrowser wb) {
        send("U|" + wb.id + "|" + wb.currentURL);
    }

    static void sendDelete(WorldBrowser wb) {
        send("D|" + wb.id);
    }

    static void sendKey(WorldBrowser wb, char type, int vk, char ch, int mods) {
        send("K|" + wb.id + "|" + type + "|" + vk + "|" + (int) ch + "|" + mods);
    }

    static void sendMouseInput(WorldBrowser wb, int eventType, int x, int y, int button) {
        send("I|" + wb.id + "|" + eventType + "|" + x + "|" + y + "|" + button);
    }

    static void sendScroll(WorldBrowser wb, int x, int y, int scrollAmount) {
        send("W|" + wb.id + "|" + x + "|" + y + "|" + scrollAmount);
    }

    static void sendMediaState(WorldBrowser wb, String videoId, float time, boolean playing) {
        send("V|" + wb.id + "|" + videoId + "|" + time + "|" + (playing ? "P" : "S"));
    }

    private static void sendAllMediaStates() {
        for (int i = 0; i < BrowserMod.browsers.size; i++) {
            WorldBrowser wb = BrowserMod.browsers.get(i);
            if (!wb.remote) {
                wb.extractAndSendMediaState();
            }
        }
    }

    private static void sendFullStateSync() {
        for (int i = 0; i < BrowserMod.browsers.size; i++) {
            WorldBrowser wb = BrowserMod.browsers.get(i);
            if (!wb.remote) {
                sendStateSync(wb);
            }
        }
    }

    static void sendStateSync(WorldBrowser wb) {
        send("S|" + wb.id + "|" + wb.x + "|" + wb.y
            + "|" + wb.browserW + "|" + wb.browserH + "|" + wb.currentURL);
    }

    static void requestResync() {
        Timer.schedule(() -> {
            if (!Vars.net.client()) return;
            try {
                Call.serverPacketReliable(PACKET, "H");
                Log.info("[BW] Re-sync requested (browser backend now ready)");
            } catch (Throwable ignored) {}
        }, 1f);
    }

    static boolean isOnMultiplayerServer() {
        return Vars.net.client();
    }

    static WorldBrowser findById(String id) {
        if (id == null) return null;
        for (int i = 0; i < BrowserMod.browsers.size; i++) {
            WorldBrowser wb = BrowserMod.browsers.get(i);
            if (id.equals(wb.id)) return wb;
        }
        return null;
    }

    private static void cleanupDisconnectedOwners() {
        Seq<String> activeNames = new Seq<>();
        Groups.player.each(p -> activeNames.add(cleanName(p.name())));

        Seq<WorldBrowser> toRemove = new Seq<>();
        for (int i = 0; i < BrowserMod.browsers.size; i++) {
            WorldBrowser wb = BrowserMod.browsers.get(i);
            if (wb.remote && !activeNames.contains(wb.ownerName)) {
                toRemove.add(wb);
            }
        }
        for (int i = 0; i < toRemove.size; i++) {
            WorldBrowser wb = toRemove.get(i);
            wb.dispose();
            BrowserMod.browsers.remove(wb);
        }
    }

    private static void clearAllBrowsers() {
        for (int i = 0; i < BrowserMod.browsers.size; i++) {
            BrowserMod.browsers.get(i).dispose();
        }
        BrowserMod.browsers.clear();
    }
}
