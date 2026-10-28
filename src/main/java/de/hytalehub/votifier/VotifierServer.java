package de.hytalehub.votifier;

import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.service.VoteDedupeService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Votifier TCP server that receives votes from voting services.
 */
public class VotifierServer {

    /** Per read; a vote arrives right after the greeting. */
    private static final int SOCKET_TIMEOUT_MS = 3000;
    /** Hard limit for a whole connection, so slow clients cannot hold a worker. */
    private static final int CONNECTION_DEADLINE_MS = 5000;
    private static final int MAX_WORKERS = 16;
    private static final int MAX_QUEUED = 64;
    /** Concurrent connections per remote IP; one attacker cannot use up all workers. */
    private static final int MAX_PER_IP = 4;

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final VoteHandler voteHandler;
    private final VoteDedupeService dedupeService;
    private final AtomicBoolean running;
    private final AtomicLong connectionCounter;

    private ServerSocket serverSocket;
    private ThreadPoolExecutor connectionPool;
    private ScheduledExecutorService deadlineTimer;
    private final Map<InetAddress, AtomicInteger> perIp = new ConcurrentHashMap<>();
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

        connectionPool = new ThreadPoolExecutor(MAX_WORKERS, MAX_WORKERS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED), r -> {
            Thread t = new Thread(r, "HytaleHub-Votifier-Worker");
            t.setDaemon(true);
            return t;
        });
        connectionPool.allowCoreThreadTimeOut(true);
        deadlineTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HytaleHub-Votifier-Deadline");
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

        if (deadlineTimer != null) {
            deadlineTimer.shutdownNow();
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
            Socket clientSocket;
            try {
                clientSocket = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) {
                    plugin.log(Level.WARNING, "[Votifier] Accept error: " + e.getMessage());
                }
                continue;
            }

            long connId = connectionCounter.incrementAndGet();
            String remoteAddr = String.valueOf(clientSocket.getRemoteSocketAddress());
            InetAddress ip = clientSocket.getInetAddress();

            AtomicInteger open = perIp.computeIfAbsent(ip, k -> new AtomicInteger());
            if (open.incrementAndGet() > MAX_PER_IP) {
                release(ip, open);
                closeQuietly(clientSocket);
                if (config.isDebug()) {
                    plugin.log(Level.INFO, "[Votifier] Connection #" + connId + " from " + remoteAddr + " dropped (too many from this IP)");
                }
                continue;
            }

            try {
                clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                // Hard deadline: closing the socket ends any read still waiting
                ScheduledFuture<?> deadline = deadlineTimer.schedule(
                        () -> closeQuietly(clientSocket), CONNECTION_DEADLINE_MS, TimeUnit.MILLISECONDS);
                connectionPool.execute(() -> {
                    try {
                        handleConnection(clientSocket, connId, remoteAddr);
                    } finally {
                        deadline.cancel(false);
                        release(ip, open);
                    }
                });
            } catch (RejectedExecutionException e) {
                release(ip, open);
                closeQuietly(clientSocket);
                plugin.log(Level.WARNING, "[Votifier] Connection #" + connId + " dropped (server busy)");
            } catch (IOException e) {
                release(ip, open);
                closeQuietly(clientSocket);
            }
        }
    }

    private void release(InetAddress ip, AtomicInteger open) {
        if (open.decrementAndGet() <= 0) {
            perIp.remove(ip, open);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Ignore
        }
    }

    private void handleConnection(Socket socket, long connId, String remoteAddr) {
        try {
            if (config.isDebug()) {
                plugin.log(Level.INFO, "[Votifier] Connection #" + connId + " from " + remoteAddr);
            }

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

        } catch (Exception e) {
            if (config.isDebug()) {
                plugin.log(Level.INFO, "[Votifier] Connection #" + connId + " ended: " + e.getMessage());
            }
        } finally {
            closeQuietly(socket);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getConnectionCount() {
        return connectionCounter.get();
    }
}
