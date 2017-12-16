package nxt.peer;

import nxt.util.CountingInputReader;
import nxt.util.CountingInputStream;
import nxt.util.CountingOutputStream;
import nxt.util.JSON;
import nxt.util.Logger;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.servlets.gzip.CompressedResponseWrapper;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class PeerServlet extends WebSocketServlet {

    abstract static class PeerRequestHandler {
        abstract JSONStreamAware processRequest(JSONObject request, Peer peer);
    }

    private static final Map<String,PeerRequestHandler> peerRequestHandlers;

    static {
        Map<String,PeerRequestHandler> map = new HashMap<>();
        map.put("addPeers", AddPeers.instance);
        map.put("getCumulativeDifficulty", GetCumulativeDifficulty.instance);
        map.put("getInfo", GetInfo.instance);
        map.put("getMilestoneBlockIds", GetMilestoneBlockIds.instance);
        map.put("getNextBlockIds", GetNextBlockIds.instance);
        map.put("getNextBlocks", GetNextBlocks.instance);
        map.put("getPeers", GetPeers.instance);
        map.put("getUnconfirmedTransactions", GetUnconfirmedTransactions.instance);
        map.put("processBlock", ProcessBlock.instance);
        map.put("processTransactions", ProcessTransactions.instance);
        map.put("getAccountBalance", GetAccountBalance.instance);
        map.put("getAccountRecentTransactions", GetAccountRecentTransactions.instance);
        peerRequestHandlers = Collections.unmodifiableMap(map);
    }

    private static final JSONStreamAware UNSUPPORTED_REQUEST_TYPE;
    static {
        JSONObject response = new JSONObject();
        response.put("error", "Unsupported request type!");
        UNSUPPORTED_REQUEST_TYPE = JSON.prepare(response);
    }

    private static final JSONStreamAware UNSUPPORTED_PROTOCOL;
    static {
        JSONObject response = new JSONObject();
        response.put("error", "Unsupported protocol!");
        UNSUPPORTED_PROTOCOL = JSON.prepare(response);
    }

    private static final JSONStreamAware BLACKLISTED;
    static {
        JSONObject response = new JSONObject();
        response.put("error", "Blacklisted IP!");
        BLACKLISTED = JSON.prepare(response);
    }

    private static final JSONStreamAware UNKNOWN_PEER;
    static {
        JSONObject response = new JSONObject();
        response.put("error", "Unknown peer!");
        UNKNOWN_PEER = JSON.prepare(response);
    }

    private static final JSONStreamAware SEQUENCE_ERROR;
    static {
        JSONObject response = new JSONObject();
        response.put("error", "SEQUENCE_ERROR");
        SEQUENCE_ERROR = JSON.prepare(response);
    }

    private static final JSONStreamAware MAX_INBOUND_CONNECTIONS;
    static {
        JSONObject response = new JSONObject();
        response.put("error", "MAX_INBOUND_CONNECTIONS");
        MAX_INBOUND_CONNECTIONS = JSON.prepare(response);
    }

    private static final JSONStreamAware DOWNLOADING;
    static {
        JSONObject response = new JSONObject();
        response.put("error", "DOWNLOADING");
        DOWNLOADING = JSON.prepare(response);
    }

    private static final JSONStreamAware LIGHT_CLIENT;
    static {
        JSONObject response = new JSONObject();
        response.put("error", "LIGHT_CLIENT");
        LIGHT_CLIENT = JSON.prepare(response);
    }

    private boolean isGzipEnabled;

    static JSONStreamAware error(Exception e) {
        JSONObject response = new JSONObject();
        response.put("error", e.toString());
        return response;
    }

    @Override
    public void configure(WebSocketServletFactory factory) {
        factory.getPolicy().setIdleTimeout(300000); // Peers.webSocketIdleTimeout
        factory.getPolicy().setMaxBinaryMessageSize(Peers.MAX_MESSAGE_SIZE);
        factory.setCreator(new PeerSocketCreator());
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        isGzipEnabled = Boolean.parseBoolean(config.getInitParameter("isGzipEnabled"));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        PeerImpl peer = null;
        JSONStreamAware response;

        try {
            peer = Peers.addPeer(req.getRemoteAddr(), null);
            if (peer == null) {
                return;
            }
            if (peer.isBlacklisted()) {
                return;
            }

            JSONObject request;
            CountingInputStream cis = new CountingInputStream(req.getInputStream());
            try (Reader reader = new InputStreamReader(cis, "UTF-8")) {
                request = (JSONObject) JSONValue.parse(reader);
            }
            if (request == null) {
                return;
            }

            if (peer.getState() == Peer.State.DISCONNECTED) {
                peer.setState(Peer.State.CONNECTED);
                if (peer.getAnnouncedAddress() != null) {
                    Peers.updateAddress(peer);
                }
            }
            peer.updateDownloadedVolume(cis.getCount());
            if (! peer.analyzeHallmark(peer.getPeerAddress(), (String)request.get("hallmark"))) {
                peer.blacklist();
                return;
            }

            if (request.get("protocol") != null && ((String)request.get("protocol")).equals("B1")) {
                PeerRequestHandler peerRequestHandler = peerRequestHandlers.get(request.get("requestType"));
                if (peerRequestHandler != null) {
                    response = peerRequestHandler.processRequest(request, peer);
                } else {
                    response = UNSUPPORTED_REQUEST_TYPE;
                }
            } else {
                Logger.logDebugMessage("Unsupported protocol " + request.get("protocol"));
                response = UNSUPPORTED_PROTOCOL;
            }

        } catch (RuntimeException e) {
            Logger.logDebugMessage("Error processing POST request", e);
            JSONObject json = new JSONObject();
            json.put("error", e.toString());
            response = json;
        }

        resp.setContentType("text/plain; charset=UTF-8");
        try {
            long byteCount;
            if (isGzipEnabled) {
                try (Writer writer = new OutputStreamWriter(resp.getOutputStream(), "UTF-8")) {
                    response.writeJSONString(writer);
                }
                byteCount = ((Response) ((CompressedResponseWrapper) resp).getResponse()).getContentCount();
            } else {
                CountingOutputStream cos = new CountingOutputStream(resp.getOutputStream());
                try (Writer writer = new OutputStreamWriter(cos, "UTF-8")) {
                    response.writeJSONString(writer);
                }
                byteCount = cos.getCount();
            }
            if (peer != null) {
                peer.updateUploadedVolume(byteCount);
            }
        } catch (Exception e) {
            if (peer != null) {
                peer.blacklist(e);
            }
            throw e;
        }
    }

    void doPost(PeerWebSocket webSocket, long requestId, String request) {
        JSONStreamAware jsonResponse;
        InetSocketAddress socketAddress = webSocket.getRemoteAddress();
        if (socketAddress == null) {
            return;
        }
        String remoteAddress = socketAddress.getHostString();
        PeerImpl peer = Peers.addPeer(remoteAddress, null);
        if (peer == null) {
            jsonResponse = UNKNOWN_PEER;
        } else {
            peer.setWebSocket(webSocket);
            jsonResponse = process(peer, new StringReader(request));
        }

        try {
            StringWriter writer = new StringWriter(1000);
            JSON.writeJSONString(jsonResponse, writer);
            String response = writer.toString();

            webSocket.sendResponse(requestId, response);
            if (peer != null) {
                peer.updateUploadedVolume(response.length());
            }
        } catch (RuntimeException | IOException e) {
            if (peer != null) {
                if ((Peers.communicationLoggingMask & Peers.LOGGING_MASK_EXCEPTIONS) != 0) {
                    if (e instanceof RuntimeException) {
                        Logger.logDebugMessage("Error sending response to peer " + peer.getHost(), e);
                    } else {
                        Logger.logDebugMessage(String.format("Error sending response to peer %s: %s",
                            peer.getHost(), e.getMessage() != null ? e.getMessage() : e.toString()));
                    }
                }
                peer.blacklist(e);
            }
        }
    }

    private JSONStreamAware process(PeerImpl peer, Reader inputReader) {
        if (peer.isBlacklisted()) {
            return BLACKLISTED;
        }
        Peers.addPeer(peer);
        //
        // Process the request
        //
        try (CountingInputReader cr = new CountingInputReader(inputReader, Peers.MAX_REQUEST_SIZE)) {
            JSONObject request = (JSONObject)JSONValue.parseWithException(cr);

            peer.updateDownloadedVolume(cr.getCount());
            if (request.get("protocol") == null || !((String)request.get("protocol")).equals("B1")) {
                Logger.logDebugMessage("Unsupported protocol " + request.get("protocol"));
                return UNSUPPORTED_PROTOCOL;
            }
            PeerRequestHandler peerRequestHandler = peerRequestHandlers.get((String)request.get("requestType"));
            if (peerRequestHandler == null) {
                return UNSUPPORTED_REQUEST_TYPE;
            }
            if (peer.getState() == Peer.State.DISCONNECTED) {
                peer.setState(Peer.State.CONNECTED);
            }
            if (peer.getVersion() == null && !"getInfo".equals(request.get("requestType"))) {
                return SEQUENCE_ERROR;
            }
            
            return peerRequestHandler.processRequest(request, peer);
        } catch (RuntimeException|ParseException|IOException e) {
            Logger.logDebugMessage("Error processing POST request: " + e.toString());
            peer.blacklist(e);
            return error(e);
        }
    }

    private class PeerSocketCreator implements WebSocketCreator  {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp) {
            return Peers.useWebSockets ? new PeerWebSocket(PeerServlet.this) : null;
        }
    }

}
