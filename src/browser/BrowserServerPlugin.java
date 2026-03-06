package browser;

import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.net.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BrowserServerPlugin extends Plugin {
    private static final String PACKET = "bw";
    private static boolean verbose = false;

    private static final Map<String, Long> modPlayers = new ConcurrentHashMap<>();
    private static final Map<String, WindowState> windows = new LinkedHashMap<>();

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("bw-debug", "Toggle Browser Windows debug logging", args -> {
            verbose = !verbose;
            Log.info("[BWS] Debug logging: @", verbose ? "ON" : "OFF");
        });
    }

    @Override
    public void init() {
        Vars.netServer.addPacketHandler(PACKET, (player, contents) -> {
            try {
                handleClientPacket(player, contents);
            } catch (Throwable t) {
                Log.err("[BWS] Error in packet handler", t);
            }
        });

        Events.on(PlayerLeave.class, e -> {
            try {
                String uuid = e.player.uuid();
                if (modPlayers.remove(uuid) != null) {
                    if (verbose) Log.info("[BWS] Mod player left: @", e.player.plainName());
                    removePlayerWindows(uuid);
                }
            } catch (Throwable t) {
                Log.err("[BWS] Error on player leave", t);
            }
        });

        Log.info("[BWS] Browser Windows Server plugin initialized");
    }

    private static void handleClientPacket(Player player, String contents) {
        if (player == null || contents == null || contents.isEmpty()) return;
        String uuid = player.uuid();

        char cmd = contents.charAt(0);
        String data = contents.length() > 2 ? contents.substring(2) : "";

        if (verbose) Log.info("[BWS] @ -> @", player.plainName(), cmd);

        if (cmd == 'H') {
            handleHandshake(player, uuid);
            return;
        }

        if (!modPlayers.containsKey(uuid)) {
            if (verbose) Log.info("[BWS] Auto-registering @", player.plainName());
            handleHandshake(player, uuid);
        }
        modPlayers.put(uuid, System.currentTimeMillis());

        switch (cmd) {
            case 'C': handleCreate(player, uuid, data); break;
            case 'M': handleMove(uuid, data); break;
            case 'R': handleResize(uuid, data); break;
            case 'U': handleUrl(uuid, data); break;
            case 'D': handleDelete(uuid, data); break;
            case 'K': handleKeyRelay(uuid, data); break;
            case 'I': handleRelay(uuid, 'I', data); break;
            case 'W': handleRelay(uuid, 'W', data); break;
            case 'S': handleStateSync(uuid, data); break;
            case 'V': handleMedia(uuid, data); break;
        }
    }

    private static void handleHandshake(Player player, String uuid) {
        modPlayers.put(uuid, System.currentTimeMillis());

        NetConnection con = player.con();
        if (con == null) {
            Log.err("[BWS] player.con() is null for @", player.plainName());
            return;
        }

        Call.clientPacketReliable(con, PACKET, "H");

        if (!windows.isEmpty()) {
            StringBuilder sb = new StringBuilder("F");
            boolean first = true;
            for (WindowState ws : windows.values()) {
                sb.append(first ? '|' : ';');
                first = false;
                sb.append(ws.id).append(',')
                  .append(ws.ownerName).append(',')
                  .append(ws.x).append(',')
                  .append(ws.y).append(',')
                  .append(ws.w).append(',')
                  .append(ws.h).append(',')
                  .append(ws.url);
            }
            Call.clientPacketReliable(con, PACKET, sb.toString());
        }

        if (verbose) Log.info("[BWS] Handshake + sync to @, total mod players: @", player.plainName(), modPlayers.size());
    }

    private static void handleCreate(Player player, String uuid, String data) {
        String[] p = data.split("\\|", 6);
        if (p.length < 6) return;

        String id = p[0];
        if (windows.containsKey(id)) return;

        WindowState ws = new WindowState();
        ws.id = id;
        ws.ownerUuid = uuid;
        ws.ownerName = cleanName(player.name());
        ws.x = Float.parseFloat(p[1]);
        ws.y = Float.parseFloat(p[2]);
        ws.w = Integer.parseInt(p[3]);
        ws.h = Integer.parseInt(p[4]);
        ws.url = p[5];
        windows.put(id, ws);

        sendToModPlayers("C|" + ws.ownerName + "|" + id + "|" + ws.x + "|" + ws.y
                + "|" + ws.w + "|" + ws.h + "|" + ws.url, uuid);
    }

    private static void handleMove(String uuid, String data) {
        String[] p = data.split("\\|", 3);
        if (p.length < 3) return;

        WindowState ws = windows.get(p[0]);
        if (ws == null || !ws.ownerUuid.equals(uuid)) return;

        ws.x = Float.parseFloat(p[1]);
        ws.y = Float.parseFloat(p[2]);
        sendToModPlayers("M|" + data, uuid);
    }

    private static void handleResize(String uuid, String data) {
        String[] p = data.split("\\|", 3);
        if (p.length < 3) return;

        WindowState ws = windows.get(p[0]);
        if (ws == null || !ws.ownerUuid.equals(uuid)) return;

        ws.w = Integer.parseInt(p[1]);
        ws.h = Integer.parseInt(p[2]);
        sendToModPlayers("R|" + data, uuid);
    }

    private static void handleUrl(String uuid, String data) {
        String[] p = data.split("\\|", 2);
        if (p.length < 2) return;

        WindowState ws = windows.get(p[0]);
        if (ws == null || !ws.ownerUuid.equals(uuid)) return;

        ws.url = p[1];
        sendToModPlayers("U|" + data, uuid);
    }

    private static void handleDelete(String uuid, String data) {
        String id = data.trim();
        WindowState ws = windows.get(id);
        if (ws == null || !ws.ownerUuid.equals(uuid)) return;

        windows.remove(id);
        sendToModPlayers("D|" + id, uuid);
    }

    private static void handleKeyRelay(String uuid, String data) {
        String[] p = data.split("\\|", 5);
        if (p.length < 5) return;
        if (!windows.containsKey(p[0])) return;
        sendToModPlayers("K|" + data, uuid);
    }

    private static void handleRelay(String uuid, char cmd, String data) {
        String[] p = data.split("\\|", 2);
        if (p.length < 2) return;
        if (!windows.containsKey(p[0])) return;
        sendToModPlayers(cmd + "|" + data, uuid);
    }

    private static void handleStateSync(String uuid, String data) {
        String[] p = data.split("\\|", 6);
        if (p.length < 6) return;

        WindowState ws = windows.get(p[0]);
        if (ws == null || !ws.ownerUuid.equals(uuid)) return;

        ws.x = Float.parseFloat(p[1]);
        ws.y = Float.parseFloat(p[2]);
        ws.w = Integer.parseInt(p[3]);
        ws.h = Integer.parseInt(p[4]);
        ws.url = p[5];
        sendToModPlayers("S|" + data, uuid);
    }

    private static void handleMedia(String uuid, String data) {
        String[] p = data.split("\\|", 4);
        if (p.length < 4) return;

        WindowState ws = windows.get(p[0]);
        if (ws == null || !ws.ownerUuid.equals(uuid)) return;

        ws.videoId = p[1];
        ws.videoTime = Float.parseFloat(p[2]);
        ws.videoPlaying = "P".equals(p[3]);
        sendToModPlayers("V|" + data, uuid);
    }

    private static void removePlayerWindows(String uuid) {
        Iterator<Map.Entry<String, WindowState>> it = windows.entrySet().iterator();
        List<String> removed = new ArrayList<>();
        while (it.hasNext()) {
            WindowState ws = it.next().getValue();
            if (ws.ownerUuid.equals(uuid)) {
                removed.add(ws.id);
                it.remove();
            }
        }
        for (String id : removed) {
            sendToModPlayers("X|" + id, null);
        }
    }

    private static void sendToModPlayers(String data, String excludeUuid) {
        for (Map.Entry<String, Long> entry : modPlayers.entrySet()) {
            if (entry.getKey().equals(excludeUuid)) continue;
            Player p = findPlayerByUuid(entry.getKey());
            if (p != null && p.con() != null) {
                try {
                    Call.clientPacketReliable(p.con(), PACKET, data);
                } catch (Throwable t) {
                    Log.err("[BWS] Send failed: @", t.getMessage());
                }
            }
        }
    }

    private static Player findPlayerByUuid(String uuid) {
        return Groups.player.find(p -> uuid.equals(p.uuid()));
    }

    private static String cleanName(String name) {
        if (name == null) return "Unknown";
        return name.replaceAll("\\[.*?\\]", "").trim();
    }

    static class WindowState {
        String id;
        String ownerUuid;
        String ownerName;
        float x, y;
        int w, h;
        String url;
        String videoId = "";
        float videoTime;
        boolean videoPlaying;
    }
}
