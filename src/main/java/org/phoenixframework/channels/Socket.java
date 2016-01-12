package org.phoenixframework.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BaseJsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketCall;
import com.squareup.okhttp.ws.WebSocketListener;
import okio.Buffer;
import okio.BufferedSource;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;


public class Socket {
    private static final Logger LOG = Logger.getLogger(Socket.class.getName());
    private static final String PHOENIX = "phoenix";
    private static final String HEARTBEAT = "heartbeat";

    public static final int RECONNECT_INTERVAL_MS = 5000;
    public static final int HEARTBEAT_INTERVAL_MS = 10000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient();
    private WebSocket webSocket = null;

    private String endpointUri = null;
    private final List<Channel> channels = new ArrayList<>();

    private final Timer timer;
    private final Timer heartbeatTimer;
    private TimerTask reconnectTimerTask = null;
    private TimerTask heartbeatTimerTask;

    private Set<ISocketOpenCallback> socketOpenCallbacks = Collections.newSetFromMap(new WeakHashMap<ISocketOpenCallback, Boolean>());
    private Set<ISocketCloseCallback> socketCloseCallbacks = Collections.newSetFromMap(new WeakHashMap<ISocketCloseCallback, Boolean>());
    private Set<IErrorCallback> errorCallbacks = Collections.newSetFromMap(new WeakHashMap<IErrorCallback, Boolean>());
    private Set<IMessageCallback> messageCallbacks = Collections.newSetFromMap(new WeakHashMap<IMessageCallback, Boolean>());

    private int refNo = 1;

    /**
     * Annotated WS Endpoint. Private member to prevent confusion with "onConn*" registration methods.
     */
    private PhoenixWSListener wsListener = new PhoenixWSListener();
    private ConcurrentLinkedDeque<Buffer> sendBuffer = new ConcurrentLinkedDeque<>();

    public class PhoenixWSListener implements WebSocketListener {
        private PhoenixWSListener() {
        }

        @Override
        public void onOpen(final WebSocket webSocket, final Request request, final Response response) throws IOException {
            LOG.log(Level.FINE, "WebSocket onOpen: {0}", webSocket);
            Socket.this.webSocket = webSocket;
            cancelReconnectTimer();
            scheduleHeartbeatTimer();


            // TODO - Heartbeat
            for (final ISocketOpenCallback callback : socketOpenCallbacks) {
                callback.onOpen();
            }

            Socket.this.flushSendBuffer();
        }

        @Override
        public void onMessage(final BufferedSource payload, final WebSocket.PayloadType type) throws IOException {
            LOG.log(Level.FINE, "Envelope received: {0}", payload);

            try {
                if (type == WebSocket.PayloadType.TEXT) {
                    final Envelope envelope =
                            objectMapper.readValue(payload.inputStream(), Envelope.class);
                    for (final Channel channel : channels) {
                        if (channel.isMember(envelope.getTopic())) {
                            channel.trigger(envelope.getEvent(), envelope);
                        }
                    }

                    for (final IMessageCallback callback : messageCallbacks) {
                        callback.onMessage(envelope);
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Failed to read message payload", e);
            } finally {
                payload.close();
            }
        }

        @Override
        public void onPong(final Buffer payload) {
            LOG.log(Level.INFO, "PONG received: {0}", payload);
        }

        @Override
        public void onClose(final int code, final String reason) {
            LOG.log(Level.FINE, "WebSocket onClose {0}/{1}", new Object[]{code, reason});
            Socket.this.webSocket = null;
            scheduleReconnectTimer();
            cancelHeartbeatTimer();

            for (final ISocketCloseCallback callback : socketCloseCallbacks) {
                callback.onClose();
            }
        }

        @Override
        public void onFailure(final IOException e) {
            LOG.log(Level.WARNING, "WebSocket connection error", e);
            try {
                for (final IErrorCallback callback : errorCallbacks) {
                    triggerChannelError();
                    callback.onError(e.toString());
                }
            }
            finally {
                // Assume closed on failure
                if(Socket.this.webSocket != null) {
                    try {
                        Socket.this.webSocket.close(1001 /*CLOSE_GOING_AWAY*/, "EOF received");
                    } catch (IOException ioe) {
                        LOG.log(Level.WARNING, "Failed to explicitly close following failure");
                    } finally {
                        Socket.this.webSocket = null;
                    }
                }
                scheduleReconnectTimer();
            }
        }
    }

    /**
     * Sets up and schedules a timer task to make repeated reconnect attempts at configured intervals
     */
    private void scheduleReconnectTimer() {
        cancelReconnectTimer();

        // TODO - Clear heartbeat timer

        Socket.this.reconnectTimerTask = new TimerTask() {
            @Override
            public void run() {
                LOG.log(Level.FINE, "reconnectTimerTask run");
                try {
                    Socket.this.connect();
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Failed to reconnect to " + Socket.this.wsListener, e);
                }
            }
        };
        timer.schedule(Socket.this.reconnectTimerTask, RECONNECT_INTERVAL_MS    );
    }

    private void cancelReconnectTimer() {
        if (Socket.this.reconnectTimerTask != null) {
            Socket.this.reconnectTimerTask.cancel();
        }
    }

    private void scheduleHeartbeatTimer() {
        cancelHeartbeatTimer();

        Socket.this.heartbeatTimerTask = new TimerTask() {
            @Override
            public void run() {
                LOG.log(Level.FINE, "heartbeatTimerTask run");
                Envelope envelope = new Envelope(PHOENIX, HEARTBEAT, null, "");

                try {
                    push(envelope);
                    webSocket.sendPing(null);
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        };
        heartbeatTimer.scheduleAtFixedRate(heartbeatTimerTask, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);

    }

    private void cancelHeartbeatTimer(){
        if (Socket.this.heartbeatTimerTask != null){
            Socket.this.heartbeatTimerTask.cancel();
        }
    }

    public Socket(final String endpointUri) throws IOException {
        LOG.log(Level.FINE, "PhoenixSocket({0})", endpointUri);
        this.endpointUri = endpointUri;
        this.timer = new Timer("Reconnect Timer for " + endpointUri);
        this.heartbeatTimer = new Timer("Heartbeat timer for " + endpointUri);
    }

    public void disconnect() throws IOException {
        LOG.log(Level.FINE, "disconnect");
        if (webSocket != null) {
            webSocket.close(1001 /*CLOSE_GOING_AWAY*/, "Disconnected by client");
        }
    }

    public void connect() throws IOException {
        LOG.log(Level.FINE, "connect");
        disconnect();
        // No support for ws:// or ws:// in okhttp. See https://github.com/square/okhttp/issues/1652
        final String httpUrl = this.endpointUri.replaceFirst("^ws:", "http:").replaceFirst("^wss:", "https:");
        final Request request = new Request.Builder().url(httpUrl).build();
        final WebSocketCall wsCall = WebSocketCall.create(httpClient, request);
        wsCall.enqueue(wsListener);
    }

    /**
     * @return true if the socket connection is connected
     */
    public boolean isConnected() {
        return webSocket != null;
    }


    /**
     * Retrieve a channel instance for the specified topic
     *
     * @param topic   The channel topic
     * @param payload The message payload
     * @return A Channel instance to be used for sending and receiving events for the topic
     */
    public Channel chan(final String topic, final JsonNode payload) {
        LOG.log(Level.FINE, "chan: {0}, {1}", new Object[]{topic, payload});
        final Channel channel = new Channel(topic, payload, Socket.this);
        synchronized (channels) {
            channels.add(channel);
        }
        return channel;
    }

    /**
     * Removes the specified channel if it is known to the socket
     *
     * @param channel The channel to be removed
     */
    public void remove(final Channel channel) {
        synchronized (channels) {
            for (final Iterator chanIter = channels.iterator(); chanIter.hasNext(); ) {
                if (chanIter.next() == channel) {
                    chanIter.remove();
                    break;
                }
            }
        }
    }

    /**
     * Sends a message envelope on this socket
     *
     * @param envelope The message envelope
     * @return This socket instance
     * @throws IOException Thrown if the message cannot be sent
     */
    public Socket push(final Envelope envelope) throws IOException {
        LOG.log(Level.FINE, "Pushing envelope: {0}", envelope);
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("topic", envelope.getTopic());
        node.put("event", envelope.getEvent());
        node.put("ref", envelope.getRef());
        node.set("payload", envelope.getPayload() == null ? objectMapper.createObjectNode() : envelope.getPayload());
        final String json = objectMapper.writeValueAsString(node);
        LOG.log(Level.FINE, "Sending JSON: {0}", json);
        final Buffer payload = new Buffer();
        payload.writeUtf8(json);

        if (this.isConnected()) {
            try {
                webSocket.sendMessage(WebSocket.PayloadType.TEXT, payload);
            }
            catch(IllegalStateException e) {
                LOG.log(Level.SEVERE, "Attempted to send push when socket is not open", e);
            }
        } else {
            this.sendBuffer.add(payload);
        }

        return this;
    }

    /**
     * Register a callback for SocketEvent.OPEN events
     *
     * @param callback The callback to receive OPEN events
     * @return This Socket instance
     */
    public Socket onOpen(final ISocketOpenCallback callback) {
        cancelReconnectTimer();
        this.socketOpenCallbacks.add(callback);
        return this;
    }

    /**
     * Register a callback for SocketEvent.ERROR events
     *
     * @param callback The callback to receive CLOSE events
     * @return This Socket instance
     */
    public Socket onClose(final ISocketCloseCallback callback) {
        this.socketCloseCallbacks.add(callback);
        return this;
    }

    /**
     * Register a callback for SocketEvent.ERROR events
     *
     * @param callback The callback to receive ERROR events
     * @return This Socket instance
     */
    public Socket onError(final IErrorCallback callback) {
        this.errorCallbacks.add(callback);
        return this;
    }

    /**
     * Register a callback for SocketEvent.MESSAGE events
     *
     * @param callback The callback to receive MESSAGE events
     * @return This Socket instance
     */
    public Socket onMessage(final IMessageCallback callback) {
        this.messageCallbacks.add(callback);
        return this;
    }

    @Override
    public String toString() {
        return "PhoenixSocket{" +
                "endpointUri='" + endpointUri + '\'' +
                ", channels=" + channels +
                ", refNo=" + refNo +
                ", webSocket=" + webSocket +
                '}';
    }

    synchronized String makeRef() {
        int val = refNo++;
        if (refNo == Integer.MAX_VALUE) {
            refNo = 0;
        }
        return Integer.toString(val);
    }

    private void triggerChannelError() {
        for (final Channel channel : channels) {
            channel.trigger(ChannelEvent.ERROR.getPhxEvent(), null);
        }
    }

    private void flushSendBuffer() {
        while (this.isConnected() && !this.sendBuffer.isEmpty()) {
            final Buffer buffer = this.sendBuffer.removeFirst();
            try {
                this.webSocket.sendMessage(WebSocket.PayloadType.TEXT, buffer);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Failed to send payload {0}", buffer);
            }
        }
    }

    static String replyEventName(final String ref) {
        return "chan_reply_" + ref;
    }
}