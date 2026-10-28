package de.hytalehub.votifier;

import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.service.VoteDedupeService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Votifier TCP server that receives votes from voting services.
 */
public class VotifierServer {

    private static final int SOCKET_TIMEOUT_MS = 10000;
    private static final int MAX_CONNECTIONS = 10;

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final VoteHandler voteHandler;
    private final VoteDedupeService dedupeService;
    private final AtomicBoolean running;
    private final AtomicLong connectionCounter;

    private ServerSocket serverSocket;
    private ExecutorService connectionPool;
    private Thread acceptThread;

    public VotifierServer(HytaleHubPlugin plugin, HytaleHubConfig config, VoteHandler voteHandler, VoteDedupeService dedupeService) {
        this.plugin = plugin;
        this.config = config;
        this.voteHandler = voteHandler;
        this.dedupeService = dedupeService;
        this.running = new AtomicBoolean(false);
        this.connectionCounter = new AtomicLong(0);
    }

    public void start() throws IOException {
        if (running.getAndSet(true)) {
            return;
        }

        connectionPool = Executors.newFixedThreadPool(MAX_CONNECTIONS, r -> {
            Thread t = new Thread(r, "HytaleHub-Votifier-Worker");
            t.setDaemon(true);
            return t;
        });

        String bindHost = config.getVotifier().getBindHost();
        int bindPort = config.getVotifier().getPort();

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindHost, bindPort));

        plugin.log(Level.INFO, "Votifier server listening on " + bindHost + ":" + bindPort);

        if (config.isDebug()) {
            plugin.log(Level.WARNING, "Votifier DEBUG MODE is ON - raw payloads will be logged!");
        }

        acceptThread = new Thread(this::acceptLoop, "HytaleHub-Votifier-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                plugin.log(Level.WARNING, "Error closing Votifier socket: " + e.getMessage());
            }
        }

        if (acceptThread != null) {
            try {
                acceptThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (connectionPool != null) {
            connectionPool.shutdown();
            try {
                if (!connectionPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    connectionPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                connectionPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        plugin.log(Level.INFO, "Votifier server stopped (handled " + connectionCounter.get() + " connections)");
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

                long connId = connectionCounter.incrementAndGet();
                String remoteAddr = clientSocket.getRemoteSocketAddress().toString();

                plugin.log(Level.INFO, "[Votifier] Connection #" + connId + " from " + remoteAddr);

                connectionPool.submit(() -> handleConnection(clientSocket, connId, remoteAddr));

            } catch (SocketException e) {
                if (running.get()) {
                    plugin.log(Level.WARNING, "[Votifier] Accept error: " + e.getMessage());
                }
            } catch (IOException e) {
                if (running.get()) {
                    plugin.log(Level.WARNING, "[Votifier] Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket, long connId, String remoteAddr) {
        try {
            VotifierProtocolHandler handler = new VotifierProtocolHandler(
                    plugin,
                    config,
                    voteHandler,
                    dedupeService,
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    connId
            );

            handler.handle();

            plugin.log(Level.INFO, "[Votifier] Connection #" + connId + " completed successfully");

        } catch (Exception e) {
            plugin.log(Level.WARNING, "[Votifier] Connection #" + connId + " error: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getConnectionCount() {
        return connectionCounter.get();
    }
}
