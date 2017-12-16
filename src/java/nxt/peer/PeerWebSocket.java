/*
 * Copyright © 2013-2016 The Nxt Core Developers.
 * Copyright © 2016-2017 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */

package nxt.peer;

import nxt.Nxt;
import nxt.util.Logger;
import nxt.util.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * PeerWebSocket represents an HTTP/HTTPS upgraded connection
 */
@WebSocket
public class PeerWebSocket {

    private static final int FLAG_COMPRESSED = 1;
    private static final int VERSION = 1;

    private int version = VERSION;

    private static WebSocketClient peerClient;
    static {
        try {
            peerClient = new WebSocketClient();
            peerClient.getPolicy().setIdleTimeout(Peers.webSocketIdleTimeout);
            peerClient.getPolicy().setMaxBinaryMessageSize(Peers.MAX_MESSAGE_SIZE);
            peerClient.setConnectTimeout(Peers.connectTimeout); 
            peerClient.start();
        } catch (Exception exc) {
            Logger.logErrorMessage("Unable to start WebSocket client", exc);
            peerClient = null;
        }
    }

    private static final ExecutorService threadPool = new QueuedThreadPool(Nxt.getCPUCoresProperty(), Nxt.getCPUCoresProperty() * 4);

    private volatile Session session;
    private final PeerServlet peerServlet;
    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentHashMap<Long, PostRequest> requestMap = new ConcurrentHashMap<>();
    private long nextRequestId = 0;
    private long connectTime = 0;

    public PeerWebSocket() {
        peerServlet = null;
    }

    public PeerWebSocket(PeerServlet peerServlet) {
        this.peerServlet = peerServlet;
    }

    public boolean startClient(URI uri) throws IOException {
        if (peerClient == null) {
            return false;
        }
        String address = String.format("%s:%d", uri.getHost(), uri.getPort());
        boolean useWebSocket = false;
        //
        // Create a WebSocket connection.  We need to serialize the connection requests
        // since the NRS server will issue multiple concurrent requests to the same peer.
        // After a successful connection, the subsequent connection requests will return
        // immediately.  After an unsuccessful connection, a new connect attempt will not
        // be done until 60 seconds have passed.
        //
        lock.lock();
        try {
            if (session != null) {
                useWebSocket = true;
            } else if (System.currentTimeMillis() > connectTime + 60 * 1000) {
                connectTime = System.currentTimeMillis();
                ClientUpgradeRequest req = new ClientUpgradeRequest();
                Future<Session> conn = peerClient.connect(this, uri, req);
                conn.get(Peers.connectTimeout + 100, TimeUnit.MILLISECONDS);
                useWebSocket = true;
            }
        } catch (ExecutionException exc) {
            if (exc.getCause() instanceof UpgradeException) {
            } else if (exc.getCause() instanceof IOException) {
                throw (IOException)exc.getCause();
            } else {
                Logger.logDebugMessage(String.format("WebSocket connection to %s failed", address), exc);
            }
        } catch (TimeoutException exc) {
            throw new SocketTimeoutException(String.format("WebSocket connection to %s timed out", address));
        } catch (IllegalStateException exc) {
            if (! peerClient.isStarted()) {
                Logger.logDebugMessage("WebSocket client not started or shutting down");
                throw exc;
            }
            Logger.logDebugMessage(String.format("WebSocket connection to %s failed", address), exc);
        } catch (Exception exc) {
            Logger.logDebugMessage(String.format("WebSocket connection to %s failed", address), exc);
        } finally {
            if (!useWebSocket) {
                close();
            }
            lock.unlock();
        }
        return useWebSocket;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        this.session = session;
        if ((Peers.communicationLoggingMask & Peers.LOGGING_MASK_200_RESPONSES) != 0) {
            Logger.logMessage(String.format("%s WebSocket connection with %s completed",
                    peerServlet != null ? "Inbound" : "Outbound",
                    session.getRemoteAddress().getHostString()));
        }
    }

    public boolean isOpen() {
        Session s;
        return ((s=session) != null && s.isOpen());
    }

    public InetSocketAddress getRemoteAddress() {
        Session s;
        return ((s=session) != null && s.isOpen() ? s.getRemoteAddress() : null);
    }

    public String doPost(String request) throws IOException {
        long requestId;
        lock.lock();
        try {
            if (session == null || !session.isOpen()) {
                throw new IOException("WebSocket session is not open");
            }
            requestId = nextRequestId++;
            byte[] requestBytes = request.getBytes("UTF-8");
            int requestLength = requestBytes.length;
            int flags = 0;
            if (Peers.isGzipEnabled && requestLength >= Peers.MIN_COMPRESS_SIZE) {
                flags |= FLAG_COMPRESSED;
                ByteArrayOutputStream outStream = new ByteArrayOutputStream(requestLength);
                try (GZIPOutputStream gzipStream = new GZIPOutputStream(outStream)) {
                    gzipStream.write(requestBytes);
                }
                requestBytes = outStream.toByteArray();
            }
            ByteBuffer buf = ByteBuffer.allocate(requestBytes.length + 20);
            buf.putInt(version)
               .putLong(requestId)
               .putInt(flags)
               .putInt(requestLength)
               .put(requestBytes)
               .flip();
            if (buf.limit() > Peers.MAX_MESSAGE_SIZE) {
                throw new ProtocolException("POST request length exceeds max message size");
            }
            session.getRemote().sendBytes(buf);
        } catch (WebSocketException exc) {
            throw new SocketException(exc.getMessage());
        } finally {
            lock.unlock();
        }

        String response;
        try {
            PostRequest postRequest = new PostRequest();
            requestMap.put(requestId, postRequest);
            response = postRequest.get(Peers.readTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exc) {
            throw new SocketTimeoutException("WebSocket POST interrupted");
        }
        return response;
    }

    public void sendResponse(long requestId, String response) throws IOException {
        lock.lock();
        try {
            if (session != null && session.isOpen()) {
                byte[] responseBytes = response.getBytes("UTF-8");
                int responseLength = responseBytes.length;
                int flags = 0;
                if (Peers.isGzipEnabled && responseLength >= Peers.MIN_COMPRESS_SIZE) {
                    flags |= FLAG_COMPRESSED;
                    ByteArrayOutputStream outStream = new ByteArrayOutputStream(responseLength);
                    try (GZIPOutputStream gzipStream = new GZIPOutputStream(outStream)) {
                        gzipStream.write(responseBytes);
                    }
                    responseBytes = outStream.toByteArray();
                }
                ByteBuffer buf = ByteBuffer.allocate(responseBytes.length + 20);
                buf.putInt(version)
                   .putLong(requestId)
                   .putInt(flags)
                   .putInt(responseLength)
                   .put(responseBytes)
                   .flip();
                if (buf.limit() > Peers.MAX_MESSAGE_SIZE) {
                    throw new ProtocolException("POST response length exceeds max message size");
                }
                session.getRemote().sendBytes(buf);
            }
        } catch (WebSocketException exc) {
            throw new SocketException(exc.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @OnWebSocketMessage
    public void onMessage(byte[] inbuf, int off, int len) {
        lock.lock();
        try {
            ByteBuffer buf = ByteBuffer.wrap(inbuf, off, len);
            version = Math.min(buf.getInt(), VERSION);
            Long requestId = buf.getLong();
            int flags = buf.getInt();
            int length = buf.getInt();
            byte[] msgBytes = new byte[buf.remaining()];
            buf.get(msgBytes);
            if ((flags&FLAG_COMPRESSED) != 0) {
                ByteArrayInputStream inStream = new ByteArrayInputStream(msgBytes);
                try (GZIPInputStream gzipStream = new GZIPInputStream(inStream, 1024)) {
                    msgBytes = new byte[length];
                    int offset = 0;
                    while (offset < msgBytes.length) {
                        int count = gzipStream.read(msgBytes, offset, msgBytes.length - offset);
                        if (count < 0) {
                            throw new EOFException("End-of-data reading compressed data");
                        }
                        offset += count;
                    }
                }
            }
            String message = new String(msgBytes, "UTF-8");

            if (peerServlet != null) {
                threadPool.execute(() -> peerServlet.doPost(this, requestId, message));
            } else {
                PostRequest postRequest = requestMap.remove(requestId);
                if (postRequest != null) {
                    postRequest.complete(message);
                }
            }
        } catch (Exception exc) {
            Logger.logDebugMessage("Exception while processing WebSocket message", exc);
        } finally {
            lock.unlock();
        }
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        lock.lock();
        try {
            if (session != null) {
                if ((Peers.communicationLoggingMask & Peers.LOGGING_MASK_200_RESPONSES) != 0) {
                    Logger.logMessage(String.format("%s WebSocket connection with %s closed",
                            peerServlet != null ? "Inbound" : "Outbound",
                            session.getRemoteAddress().getHostString()));
                }
                session = null;
            }
            SocketException exc = new SocketException("WebSocket connection closed");
            Set<Map.Entry<Long, PostRequest>> requests = requestMap.entrySet();
            requests.forEach((entry) -> entry.getValue().complete(exc));
            requestMap.clear();
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception exc) {
            Logger.logDebugMessage("Exception while closing WebSocket", exc);
        } finally {
            lock.unlock();
        }
    }

    private class PostRequest {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String response;
        private volatile IOException exception;

        public PostRequest() {
        }

        public String get(long timeout, TimeUnit unit) throws InterruptedException, IOException {
            if (!latch.await(timeout, unit)) {
                throw new SocketTimeoutException("WebSocket read timeout exceeded");
            }
            if (exception != null) {
                throw exception;
            }
            return response;
        }

        public void complete(String response) {
            this.response = response;
            latch.countDown();
        }

        public void complete(IOException exception) {
            this.exception = exception;
            latch.countDown();
        }
    }
}
