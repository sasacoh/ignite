/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.taskexecutor.external.communication;

import org.gridgain.grid.*;
import org.gridgain.grid.kernal.processors.hadoop.message.*;
import org.gridgain.grid.kernal.processors.hadoop.taskexecutor.external.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.marshaller.*;
import org.gridgain.grid.thread.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.ipc.*;
import org.gridgain.grid.util.ipc.shmem.*;
import org.gridgain.grid.util.nio.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.worker.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

import static org.gridgain.grid.GridSystemProperties.*;

/**
 * TODO fix javadocs.
 */
public class GridHadoopExternalCommunication {
    /** IPC error message. */
    public static final String OUT_OF_RESOURCES_TCP_MSG = "Failed to allocate shared memory segment " +
        "(switching to TCP, may be slower). For troubleshooting see " +
        GridIpcSharedMemoryServerEndpoint.TROUBLESHOOTING_URL;

    /** Default port which node sets listener to (value is <tt>47100</tt>). */
    public static final int DFLT_PORT = 27100;

    /** Default connection timeout (value is <tt>1000</tt>ms). */
    public static final long DFLT_CONN_TIMEOUT = 1000;

    /** Default Maximum connection timeout (value is <tt>600,000</tt>ms). */
    public static final long DFLT_MAX_CONN_TIMEOUT = 10 * 60 * 1000;

    /** Default reconnect attempts count (value is <tt>10</tt>). */
    public static final int DFLT_RECONNECT_CNT = 10;

    /** Default message queue limit per connection (for incoming and outgoing . */
    public static final int DFLT_MSG_QUEUE_LIMIT = GridNioServer.DFLT_SEND_QUEUE_LIMIT;

    /**
     * Default count of selectors for TCP server equals to
     * {@code "Math.min(4, Runtime.getRuntime().availableProcessors())"}.
     */
    public static final int DFLT_SELECTORS_CNT = 1;

    /** Node ID meta for session. */
    private static final int PROCESS_META = GridNioSessionMetaKey.nextUniqueKey();

    /** Handshake timeout meta for session. */
    private static final int HANDSHAKE_FINISH_META = GridNioSessionMetaKey.nextUniqueKey();

    /** Message tracker meta for session. */
    private static final int TRACKER_META = GridNioSessionMetaKey.nextUniqueKey();

    /**
     * Default local port range (value is <tt>100</tt>).
     * See {@link #setLocalPortRange(int)} for details.
     */
    public static final int DFLT_PORT_RANGE = 100;

    /** Default value for {@code TCP_NODELAY} socket option (value is <tt>true</tt>). */
    public static final boolean DFLT_TCP_NODELAY = true;

    /** Server listener. */
    private final GridNioServerListener<GridHadoopMessage> srvLsnr =
        new GridNioServerListenerAdapter<GridHadoopMessage>() {
            @Override public void onConnected(GridNioSession ses) {
                GridHadoopProcessDescriptor desc = ses.meta(PROCESS_META);

                assert desc != null : "Received connected notification without finished handshake: " + ses;
            }

            /** {@inheritDoc} */
            @Override public void onDisconnected(GridNioSession ses, @Nullable Exception e) {
                if (log.isDebugEnabled())
                    log.debug("Closed connection for session: " + ses);

                GridHadoopProcessDescriptor desc = ses.meta(PROCESS_META);

                if (desc != null) {
                    GridHadoopCommunicationClient rmv = clients.remove(desc.processId());

                    if (rmv != null)
                        rmv.forceClose();
                }

                GridHadoopMessageListener lsnr0 = lsnr;

                if (lsnr0 != null)
                    // Notify listener about connection close.
                    lsnr0.onConnectionLost(desc);
            }

            /** {@inheritDoc} */
            @Override public void onMessage(GridNioSession ses, GridHadoopMessage msg) {
                notifyListener(ses.<GridHadoopProcessDescriptor>meta(PROCESS_META), msg);

                if (msgQueueLimit > 0) {
                    GridNioMessageTracker tracker = ses.meta(TRACKER_META);

                    assert tracker != null : "Missing tracker for limited message queue: " + ses;

                    tracker.run();
                }
            }
        };

    /** Logger. */
    private GridLogger log;

    /** Local process descriptor. */
    private GridHadoopProcessDescriptor locProcDesc;

    /** Marshaller. */
    private GridMarshaller marsh;

    /** Message notification executor service. */
    private ExecutorService execSvc;

    /** Grid name. */
    private String gridName;

    /** Local IP address. */
    private String locAddr;

    /** Complex variable that represents this node IP address. */
    private volatile InetAddress locHost;

    /** Local port which node uses. */
    private int locPort = DFLT_PORT;

    /** Local port range. */
    private int locPortRange = DFLT_PORT_RANGE;

    /** Local port which node uses to accept shared memory connections. */
    private int shmemPort = -1;

    /** Allocate direct buffer or heap buffer. */
    private boolean directBuf = true;

    /** Allocate direct buffer or heap buffer. */
    private boolean directSndBuf;

    /** Connect timeout. */
    private long connTimeout = DFLT_CONN_TIMEOUT;

    /** Maximum connect timeout. */
    private long maxConnTimeout = DFLT_MAX_CONN_TIMEOUT;

    /** Reconnect attempts count. */
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    private int reconCnt = DFLT_RECONNECT_CNT;

    /** Socket send buffer. */
    private int sockSndBuf;

    /** Socket receive buffer. */
    private int sockRcvBuf;

    /** Message queue limit. */
    private int msgQueueLimit = DFLT_MSG_QUEUE_LIMIT;

    /** Min buffered message count. */
    private int minBufferedMsgCnt = Integer.getInteger(GG_MIN_BUFFERED_COMMUNICATION_MSG_CNT, 512);

    /** Buffer size ratio. */
    private double bufSizeRatio = X.parseDouble(X.getSystemOrEnv(GG_COMMUNICATION_BUF_RESIZE_RATIO), 0.8);

    /** NIO server. */
    private GridNioServer<GridHadoopMessage> nioSrvr;

    /** Shared memory server. */
    private GridIpcSharedMemoryServerEndpoint shmemSrv;

    /** {@code TCP_NODELAY} option value for created sockets. */
    private boolean tcpNoDelay = DFLT_TCP_NODELAY;

    /** Shared memory accept worker. */
    private ShmemAcceptWorker shmemAcceptWorker;

    /** Shared memory workers. */
    private final Collection<ShmemWorker> shmemWorkers = new ConcurrentLinkedDeque8<>();

    /** Clients. */
    private final ConcurrentMap<UUID, GridHadoopCommunicationClient> clients = GridConcurrentFactory.newMap();

    /** Message listener. */
    private volatile GridHadoopMessageListener lsnr;

    /** Bound port. */
    private int boundTcpPort = -1;

    /** Bound port for shared memory server. */
    private int boundTcpShmemPort = -1;

    /** Count of selectors to use in TCP server. */
    private int selectorsCnt = DFLT_SELECTORS_CNT;

    /** Local node ID message. */
    private ProcessHandshakeMessage locIdMsg;

    /** Locks. */
    private final GridKeyLock locks = new GridKeyLock();

    /**
     * @param parentNodeId Parent node ID.
     * @param procId Process ID.
     * @param marsh Marshaller to use.
     * @param log Logger.
     * @param execSvc Executor service for message notification.
     * @param gridName Grid name.
     */
    public GridHadoopExternalCommunication(
        UUID parentNodeId,
        UUID procId,
        GridMarshaller marsh,
        GridLogger log,
        ExecutorService execSvc,
        String gridName
    ) {
        locProcDesc = new GridHadoopProcessDescriptor(parentNodeId, procId);

        this.marsh = marsh;
        this.log = log.getLogger(GridHadoopExternalCommunication.class);
        this.execSvc = execSvc;
        this.gridName = gridName;
    }

    /**
     * Sets local host address for socket binding. Note that one node could have
     * additional addresses beside the loopback one. This configuration
     * parameter is optional.
     *
     * @param locAddr IP address. Default value is any available local
     *      IP address.
     */
    public void setLocalAddress(String locAddr) {
        this.locAddr = locAddr;
    }

    /** {@inheritDoc} */
    public String getLocalAddress() {
        return locAddr;
    }

    /**
     * Sets local port for socket binding.
     * <p>
     * If not provided, default value is {@link #DFLT_PORT}.
     *
     * @param locPort Port number.
     */
    public void setLocalPort(int locPort) {
        this.locPort = locPort;
    }

    /** {@inheritDoc} */
    public int getLocalPort() {
        return locPort;
    }

    /**
     * Sets local port range for local host ports (value must greater than or equal to <tt>0</tt>).
     * If provided local port (see {@link #setLocalPort(int)}} is occupied,
     * implementation will try to increment the port number for as long as it is less than
     * initial value plus this range.
     * <p>
     * If port range value is <tt>0</tt>, then implementation will try bind only to the port provided by
     * {@link #setLocalPort(int)} method and fail if binding to this port did not succeed.
     * <p>
     * Local port range is very useful during development when more than one grid nodes need to run
     * on the same physical machine.
     * <p>
     * If not provided, default value is {@link #DFLT_PORT_RANGE}.
     *
     * @param locPortRange New local port range.
     */
    public void setLocalPortRange(int locPortRange) {
        this.locPortRange = locPortRange;
    }

    /** {@inheritDoc} */
    public int getLocalPortRange() {
        return locPortRange;
    }

    /**
     * Sets local port to accept shared memory connections.
     * <p>
     * If set to {@code -1} shared memory communication will be disabled.
     * <p>
     * If not provided, shared memory is disabled.
     *
     * @param shmemPort Port number.
     */
    public void setSharedMemoryPort(int shmemPort) {
        this.shmemPort = shmemPort;
    }

    /** {@inheritDoc} */
    public int getSharedMemoryPort() {
        return shmemPort;
    }

    /**
     * Sets connect timeout used when establishing connection
     * with remote nodes.
     * <p>
     * {@code 0} is interpreted as infinite timeout.
     * <p>
     * If not provided, default value is {@link #DFLT_CONN_TIMEOUT}.
     *
     * @param connTimeout Connect timeout.
     */
    public void setConnectTimeout(long connTimeout) {
        this.connTimeout = connTimeout;
    }

    /** {@inheritDoc} */
    public long getConnectTimeout() {
        return connTimeout;
    }

    /**
     * Sets maximum connect timeout. If handshake is not established within connect timeout,
     * then SPI tries to repeat handshake procedure with increased connect timeout.
     * Connect timeout can grow till maximum timeout value,
     * if maximum timeout value is reached then the handshake is considered as failed.
     * <p>
     * {@code 0} is interpreted as infinite timeout.
     * <p>
     * If not provided, default value is {@link #DFLT_MAX_CONN_TIMEOUT}.
     *
     * @param maxConnTimeout Maximum connect timeout.
     */
    public void setMaxConnectTimeout(long maxConnTimeout) {
        this.maxConnTimeout = maxConnTimeout;
    }

    /** {@inheritDoc} */
    public long getMaxConnectTimeout() {
        return maxConnTimeout;
    }

    /**
     * Sets maximum number of reconnect attempts used when establishing connection
     * with remote nodes.
     * <p>
     * If not provided, default value is {@link #DFLT_RECONNECT_CNT}.
     *
     * @param reconCnt Maximum number of reconnection attempts.
     */
    public void setReconnectCount(int reconCnt) {
        this.reconCnt = reconCnt;
    }

    /** {@inheritDoc} */
    public int getReconnectCount() {
        return reconCnt;
    }

    /**
     * Sets flag to allocate direct or heap buffer in SPI.
     * If value is {@code true}, then SPI will use {@link ByteBuffer#allocateDirect(int)} call.
     * Otherwise, SPI will use {@link ByteBuffer#allocate(int)} call.
     * <p>
     * If not provided, default value is {@code true}.
     *
     * @param directBuf Flag indicates to allocate direct or heap buffer in SPI.
     */
    public void setDirectBuffer(boolean directBuf) {
        this.directBuf = directBuf;
    }

    /** {@inheritDoc} */
    public boolean isDirectBuffer() {
        return directBuf;
    }

    /** {@inheritDoc} */
    public boolean isDirectSendBuffer() {
        return directSndBuf;
    }

    /**
     * Sets whether to use direct buffer for sending.
     * <p>
     * If not provided default is {@code false}.
     *
     * @param directSndBuf {@code True} to use direct buffers for send.
     */
    public void setDirectSendBuffer(boolean directSndBuf) {
        this.directSndBuf = directSndBuf;
    }

    /**
     * Sets the count of selectors te be used in TCP server.
     * <p/>
     * If not provided, default value is {@link #DFLT_SELECTORS_CNT}.
     *
     * @param selectorsCnt Selectors count.
     */
    public void setSelectorsCount(int selectorsCnt) {
        this.selectorsCnt = selectorsCnt;
    }

    /** {@inheritDoc} */
    public int getSelectorsCount() {
        return selectorsCnt;
    }

    /**
     * Sets value for {@code TCP_NODELAY} socket option. Each
     * socket will be opened using provided value.
     * <p>
     * Setting this option to {@code true} disables Nagle's algorithm
     * for socket decreasing latency and delivery time for small messages.
     * <p>
     * For systems that work under heavy network load it is advisable to
     * set this value to {@code false}.
     * <p>
     * If not provided, default value is {@link #DFLT_TCP_NODELAY}.
     *
     * @param tcpNoDelay {@code True} to disable TCP delay.
     */
    public void setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
    }

    /** {@inheritDoc} */
    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    /**
     * Sets receive buffer size for sockets created or accepted by this SPI.
     * <p>
     * If not provided, default is {@code 0} which leaves buffer unchanged after
     * socket creation (OS defaults).
     *
     * @param sockRcvBuf Socket receive buffer size.
     */
    public void setSocketReceiveBuffer(int sockRcvBuf) {
        this.sockRcvBuf = sockRcvBuf;
    }

    /** {@inheritDoc} */
    public int getSocketReceiveBuffer() {
        return sockRcvBuf;
    }

    /**
     * Sets send buffer size for sockets created or accepted by this SPI.
     * <p>
     * If not provided, default is {@code 0} which leaves the buffer unchanged
     * after socket creation (OS defaults).
     *
     * @param sockSndBuf Socket send buffer size.
     */
    public void setSocketSendBuffer(int sockSndBuf) {
        this.sockSndBuf = sockSndBuf;
    }

    /** {@inheritDoc} */
    public int getSocketSendBuffer() {
        return sockSndBuf;
    }

    /**
     * Sets message queue limit for incoming and outgoing messages.
     * <p>
     * When set to positive number send queue is limited to the configured value.
     * {@code 0} disables the size limitations.
     * <p>
     * If not provided, default is {@link #DFLT_MSG_QUEUE_LIMIT}.
     *
     * @param msgQueueLimit Send queue size limit.
     */
    public void setMessageQueueLimit(int msgQueueLimit) {
        this.msgQueueLimit = msgQueueLimit;
    }

    /** {@inheritDoc} */
    public int getMessageQueueLimit() {
        return msgQueueLimit;
    }

    /**
     * Sets the minimum number of messages for this SPI, that are buffered
     * prior to sending.
     * <p>
     * Defaults to either {@code 512} or {@link GridSystemProperties#GG_MIN_BUFFERED_COMMUNICATION_MSG_CNT}
     * system property (if specified).
     *
     * @param minBufferedMsgCnt Minimum buffered message count.
     */
    public void setMinimumBufferedMessageCount(int minBufferedMsgCnt) {
        this.minBufferedMsgCnt = minBufferedMsgCnt;
    }

    /** {@inheritDoc} */
    public int getMinimumBufferedMessageCount() {
        return minBufferedMsgCnt;
    }

    /**
     * Sets the buffer size ratio for this SPI. As messages are sent,
     * the buffer size is adjusted using this ratio.
     * <p>
     * Defaults to either {@code 0.8} or {@link GridSystemProperties#GG_COMMUNICATION_BUF_RESIZE_RATIO}
     * system property (if specified).
     *
     * @param bufSizeRatio Buffer size ratio.
     */
    public void setBufferSizeRatio(double bufSizeRatio) {
        this.bufSizeRatio = bufSizeRatio;
    }

    /** {@inheritDoc} */
    public double getBufferSizeRatio() {
        return bufSizeRatio;
    }

    /**
     * Sets Hadoop communication message listener.
     *
     * @param lsnr Message listener.
     */
    public void setListener(GridHadoopMessageListener lsnr) {
        this.lsnr = lsnr;
    }

    /** {@inheritDoc} */
    public int getOutboundMessagesQueueSize() {
        return nioSrvr.outboundMessagesQueueSize();
    }

    public void start() throws GridException {
        try {
            locHost = locAddr == null ? U.getLocalHost() : U.resolveLocalHost(locAddr);
        }
        catch (IOException e) {
            throw new GridException("Failed to initialize local address: " + locAddr, e);
        }

        try {
            shmemSrv = resetShmemServer();
        }
        catch (GridException e) {
            U.warn(log, "Failed to start shared memory communication server.", e);
        }

        try {
            // This method potentially resets local port to the value
            // local node was bound to.
            nioSrvr = resetNioServer();
        }
        catch (GridException e) {
            throw new GridException("Failed to initialize TCP server: " + locHost, e);
        }

        locProcDesc.address(locHost.getHostAddress());
        locProcDesc.sharedMemoryPort(boundTcpShmemPort);
        locProcDesc.tcpPort(boundTcpPort);

        locIdMsg = new ProcessHandshakeMessage(locProcDesc);

        if (shmemSrv != null) {
            shmemAcceptWorker = new ShmemAcceptWorker(shmemSrv);

            new GridThread(shmemAcceptWorker).start();
        }

        nioSrvr.start();
    }

    /**
     * Gets local process descriptor.
     *
     * @return Local process descriptor.
     */
    public GridHadoopProcessDescriptor localProcessDescriptor() {
        return locProcDesc;
    }

    /**
     * Gets filters used by communication.
     *
     * @return Filters array.
     */
    private GridNioFilter[] filters() {
        return new GridNioFilter[] {
            new GridNioAsyncNotifyFilter(gridName, execSvc, log),
            new HandshakeAndBackpressureFilter(),
            new GridHadoopMarshallerFilter(marsh),
            new GridNioCodecFilter(new GridBufferedParser(directBuf, ByteOrder.nativeOrder()), log, false)
        };
    }

    /**
     * Recreates tpcSrvr socket instance.
     *
     * @return Server instance.
     * @throws GridException Thrown if it's not possible to create server.
     */
    private GridNioServer<GridHadoopMessage> resetNioServer() throws GridException {
        if (boundTcpPort >= 0)
            throw new GridException("Tcp NIO server was already created on port " + boundTcpPort);

        GridException lastEx = null;

        // If configured TCP port is busy, find first available in range.
        for (int port = locPort; port < locPort + locPortRange; port++) {
            try {
                GridNioServer<GridHadoopMessage> srvr =
                    GridNioServer.<GridHadoopMessage>builder()
                        .address(locHost)
                        .port(port)
                        .listener(srvLsnr)
                        .logger(log.getLogger(GridNioServer.class))
                        .selectorCount(selectorsCnt)
                        .gridName(gridName)
                        .tcpNoDelay(tcpNoDelay)
                        .directBuffer(directBuf)
                        .byteOrder(ByteOrder.nativeOrder())
                        .socketSendBufferSize(sockSndBuf)
                        .socketReceiveBufferSize(sockRcvBuf)
                        .sendQueueLimit(msgQueueLimit)
                        .directMode(false)
                        .filters(filters())
                        .build();

                boundTcpPort = port;

                // Ack Port the TCP server was bound to.
                if (log.isInfoEnabled())
                    log.info("Successfully bound to TCP port [port=" + boundTcpPort +
                        ", locHost=" + locHost + ']');

                return srvr;
            }
            catch (GridException e) {
                lastEx = e;

                if (log.isDebugEnabled())
                    log.debug("Failed to bind to local port (will try next port within range) [port=" + port +
                        ", locHost=" + locHost + ']');
            }
        }

        // If free port wasn't found.
        throw new GridException("Failed to bind to any port within range [startPort=" + locPort +
            ", portRange=" + locPortRange + ", locHost=" + locHost + ']', lastEx);
    }

    /**
     * Creates new shared memory communication server.
     * @return Server.
     * @throws GridException If failed.
     */
    @Nullable private GridIpcSharedMemoryServerEndpoint resetShmemServer() throws GridException {
        if (boundTcpShmemPort >= 0)
            throw new GridException("Shared memory server was already created on port " + boundTcpShmemPort);

        if (shmemPort == -1 || U.isWindows())
            return null;

        GridException lastEx = null;

        // If configured TCP port is busy, find first available in range.
        for (int port = shmemPort; port < shmemPort + locPortRange; port++) {
            try {
                GridIpcSharedMemoryServerEndpoint srv = new GridIpcSharedMemoryServerEndpoint(
                    log.getLogger(GridIpcSharedMemoryServerEndpoint.class),
                    locProcDesc.processId(), gridName);

                srv.setPort(port);

                srv.omitOutOfResourcesWarning(true);

                srv.start();

                boundTcpShmemPort = port;

                // Ack Port the TCP server was bound to.
                if (log.isInfoEnabled())
                    log.info("Successfully bound shared memory communication to TCP port [port=" + boundTcpShmemPort +
                        ", locHost=" + locHost + ']');

                return srv;
            }
            catch (GridException e) {
                lastEx = e;

                if (log.isDebugEnabled())
                    log.debug("Failed to bind to local port (will try next port within range) [port=" + port +
                        ", locHost=" + locHost + ']');
            }
        }

        // If free port wasn't found.
        throw new GridException("Failed to bind shared memory communication to any port within range [startPort=" +
            locPort + ", portRange=" + locPortRange + ", locHost=" + locHost + ']', lastEx);
    }

    public void stop() throws GridException {
        // Stop TCP server.
        if (nioSrvr != null)
            nioSrvr.stop();

        U.cancel(shmemAcceptWorker);
        U.join(shmemAcceptWorker, log);

        U.cancel(shmemWorkers);
        U.join(shmemWorkers, log);

        shmemWorkers.clear();

        // Force closing on stop (safety).
        for (GridHadoopCommunicationClient client : clients.values())
            client.forceClose();

        // Clear resources.
        nioSrvr = null;

        boundTcpPort = -1;
    }

    public void sendMessage(GridHadoopProcessDescriptor desc, GridHadoopMessage msg) throws
        GridException {
        assert desc != null;
        assert msg != null;

        if (log.isTraceEnabled())
            log.trace("Sending message to Hadoop process [desc=" + desc + ", msg=" + msg + ']');

        GridHadoopCommunicationClient client = null;

        boolean closeOnRelease = true;

        try {
            client = reserveClient(desc);

            client.sendMessage(desc, msg);

            closeOnRelease = false;
        }
        finally {
            if (client != null) {
                if (closeOnRelease) {
                    client.forceClose();

                    clients.remove(desc.processId(), client);
                }
                else
                    client.release();
            }
        }
    }

    /**
     * Returns existing or just created client to node.
     *
     * @param desc Node to which client should be open.
     * @return The existing or just created client.
     * @throws GridException Thrown if any exception occurs.
     */
    private GridHadoopCommunicationClient reserveClient(GridHadoopProcessDescriptor desc) throws GridException {
        assert desc != null;

        UUID procId = desc.processId();

        while (true) {
            GridHadoopCommunicationClient client = clients.get(procId);

            if (client == null) {
                if (log.isDebugEnabled())
                    log.debug("Did not find client for remote process [locProcDesc=" + locProcDesc + ", desc=" +
                        desc + ']');

                // Do not allow concurrent connects.
                Object sync = locks.lock(procId);

                try {
                    client = clients.get(procId);

                    if (client == null) {
                        GridHadoopCommunicationClient old = clients.put(procId, client = createNioClient(desc));

                        assert old == null;
                    }
                }
                finally {
                    locks.unlock(procId, sync);
                }

                assert client != null;
            }

            if (client.reserve())
                return client;
            else
                // Client has just been closed by idle worker. Help it and try again.
                clients.remove(procId, client);
        }
    }

    /**
     * @param desc Process descriptor.
     * @return Client.
     * @throws GridException If failed.
     */
    @Nullable protected GridHadoopCommunicationClient createNioClient(GridHadoopProcessDescriptor desc)
        throws  GridException {
        assert desc != null;

        int shmemPort = desc.sharedMemoryPort();

        // If remote node has shared memory server enabled and has the same set of MACs
        // then we are likely to run on the same host and shared memory communication could be tried.
        if (shmemPort != -1 && locProcDesc.parentNodeId().equals(desc.parentNodeId())) {
            try {
                return createShmemClient(desc, shmemPort);
            }
            catch (GridException e) {
                if (e.hasCause(GridIpcOutOfSystemResourcesException.class))
                    // Has cause or is itself the GridIpcOutOfSystemResourcesException.
                    LT.warn(log, null, OUT_OF_RESOURCES_TCP_MSG);
                else if (log.isDebugEnabled())
                    log.debug("Failed to establish shared memory connection with local hadoop process: " +
                        desc);
            }
        }

        return createTcpClient(desc);
    }

    /**
     * @param desc Process descriptor.
     * @param port Port.
     * @return Client.
     * @throws GridException If failed.
     */
    @Nullable protected GridHadoopCommunicationClient createShmemClient(GridHadoopProcessDescriptor desc, int port)
        throws GridException {
        int attempt = 1;

        int connectAttempts = 1;

        long connTimeout0 = connTimeout;

        while (true) {
            GridIpcEndpoint clientEndpoint;

            try {
                clientEndpoint = new GridIpcSharedMemoryClientEndpoint(port, (int)connTimeout, log);
            }
            catch (GridException e) {
                // Reconnect for the second time, if connection is not established.
                if (connectAttempts < 2 && X.hasCause(e, ConnectException.class)) {
                    connectAttempts++;

                    continue;
                }

                throw e;
            }

            GridHadoopCommunicationClient client = null;

            try {
                ShmemWorker worker = new ShmemWorker(clientEndpoint, false);

                shmemWorkers.add(worker);

                GridNioSession ses = worker.session();

                HandshakeFinish fin = new HandshakeFinish();

                // We are in lock, it is safe to get session and attach
                ses.addMeta(HANDSHAKE_FINISH_META, fin);

                client = new GridHadoopTcpNioCommunicationClient(ses);

                new GridThread(worker).start();

                fin.await(connTimeout0);
            }
            catch (GridHadoopHandshakeTimeoutException e) {
                if (log.isDebugEnabled())
                    log.debug("Handshake timed out (will retry with increased timeout) [timeout=" + connTimeout0 +
                        ", err=" + e.getMessage() + ", client=" + client + ']');

                if (client != null)
                    client.forceClose();

                if (attempt == reconCnt || connTimeout0 > maxConnTimeout) {
                    if (log.isDebugEnabled())
                        log.debug("Handshake timedout (will stop attempts to perform the handshake) " +
                            "[timeout=" + connTimeout0 + ", maxConnTimeout=" + maxConnTimeout +
                            ", attempt=" + attempt + ", reconCnt=" + reconCnt +
                            ", err=" + e.getMessage() + ", client=" + client + ']');

                    throw e;
                }
                else {
                    attempt++;

                    connTimeout0 *= 2;

                    continue;
                }
            }
            catch (RuntimeException | Error e) {
                if (log.isDebugEnabled())
                    log.debug(
                        "Caught exception (will close client) [err=" + e.getMessage() + ", client=" + client + ']');

                if (client != null)
                    client.forceClose();

                throw e;
            }

            return client;
        }
    }

    /**
     * Establish TCP connection to remote hadoop process and returns client.
     *
     * @param desc Process descriptor.
     * @return Client.
     * @throws GridException If failed.
     */
    protected GridHadoopCommunicationClient createTcpClient(GridHadoopProcessDescriptor desc) throws GridException {
        String addr = desc.address();

        int port = desc.tcpPort();

        if (log.isDebugEnabled())
            log.debug("Trying to connect to remote process [locProcDesc=" + locProcDesc + ", desc=" + desc + ']');

        boolean conn = false;
        GridHadoopTcpNioCommunicationClient client = null;
        GridException errs = null;

        int connectAttempts = 1;

        long connTimeout0 = connTimeout;

        int attempt = 1;

        while (!conn) { // Reconnection on handshake timeout.
            try {
                SocketChannel ch = SocketChannel.open();

                ch.configureBlocking(true);

                ch.socket().setTcpNoDelay(tcpNoDelay);
                ch.socket().setKeepAlive(true);

                if (sockRcvBuf > 0)
                    ch.socket().setReceiveBufferSize(sockRcvBuf);

                if (sockSndBuf > 0)
                    ch.socket().setSendBufferSize(sockSndBuf);

                ch.socket().connect(new InetSocketAddress(addr, port), (int)connTimeout);

                HandshakeFinish fin = new HandshakeFinish();

                GridNioSession ses = nioSrvr.createSession(ch, F.asMap(HANDSHAKE_FINISH_META, fin)).get();

                client = new GridHadoopTcpNioCommunicationClient(ses);

                if (log.isDebugEnabled())
                    log.debug("Waiting for handshake finish for client: " + client);

                fin.await(connTimeout0);

                conn = true;
            }
            catch (GridHadoopHandshakeTimeoutException e) {
                if (client != null) {
                    client.forceClose();

                    client = null;
                }

                if (log.isDebugEnabled())
                    log.debug(
                        "Handshake timedout (will retry with increased timeout) [timeout=" + connTimeout0 +
                            ", desc=" + desc + ", port=" + port + ", err=" + e + ']');

                if (attempt == reconCnt || connTimeout0 > maxConnTimeout) {
                    if (log.isDebugEnabled())
                        log.debug("Handshake timed out (will stop attempts to perform the handshake) " +
                            "[timeout=" + connTimeout0 + ", maxConnTimeout=" + maxConnTimeout +
                            ", attempt=" + attempt + ", reconCnt=" + reconCnt +
                            ", err=" + e.getMessage() + ", addr=" + addr + ']');

                    if (errs == null)
                        errs = new GridException("Failed to connect to remote Hadoop process " +
                            "(is process still running?) [desc=" + desc + ", addrs=" + addr + ']');

                    errs.addSuppressed(e);

                    break;
                }
                else {
                    attempt++;

                    connTimeout0 *= 2;

                    // Continue loop.
                }
            }
            catch (Exception e) {
                if (client != null) {
                    client.forceClose();

                    client = null;
                }

                if (log.isDebugEnabled())
                    log.debug("Client creation failed [addr=" + addr + ", port=" + port +
                        ", err=" + e + ']');

                if (X.hasCause(e, SocketTimeoutException.class))
                    LT.warn(log, null, "Connect timed out (consider increasing 'connTimeout' " +
                        "configuration property) [addr=" + addr + ", port=" + port + ']');

                if (errs == null)
                    errs = new GridException("Failed to connect to remote Hadoop process (is process still running?) " +
                        "[desc=" + desc + ", addrs=" + addr + ']');

                errs.addSuppressed(e);

                // Reconnect for the second time, if connection is not established.
                if (connectAttempts < 2 &&
                    (e instanceof ConnectException || X.hasCause(e, ConnectException.class))) {
                    connectAttempts++;

                    continue;
                }

                break;
            }
        }

        if (client == null) {
            assert errs != null;

            if (X.hasCause(errs, ConnectException.class))
                LT.warn(log, null, "Failed to connect to a remote Hadoop process (is process still running?). " +
                    "Make sure operating system firewall is disabled on local and remote host) " +
                    "[addrs=" + addr + ", port=" + port + ']');

            throw errs;
        }

        if (log.isDebugEnabled())
            log.debug("Created client: " + client);

        return client;
    }

    /**
     * @param desc Sender process descriptor.
     * @param msg Communication message.
     */
    protected void notifyListener(GridHadoopProcessDescriptor desc, GridHadoopMessage msg) {
        GridHadoopMessageListener lsnr = this.lsnr;

        if (lsnr != null)
            // Notify listener of a new message.
            lsnr.onMessageReceived(desc, msg);
        else if (log.isDebugEnabled())
            log.debug("Received communication message without any registered listeners (will ignore) " +
                "[senderProcDesc=" + desc + ", msg=" + msg + ']');
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridHadoopExternalCommunication.class, this);
    }

    /**
     * This worker takes responsibility to shut the server down when stopping,
     * No other thread shall stop passed server.
     */
    private class ShmemAcceptWorker extends GridWorker {
        /** */
        private final GridIpcSharedMemoryServerEndpoint srv;

        /**
         * @param srv Server.
         */
        ShmemAcceptWorker(GridIpcSharedMemoryServerEndpoint srv) {
            super(gridName, "shmem-communication-acceptor", log);

            this.srv = srv;
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException {
            try {
                while (!Thread.interrupted()) {
                    ShmemWorker e = new ShmemWorker(srv.accept(), true);

                    shmemWorkers.add(e);

                    new GridThread(e).start();
                }
            }
            catch (GridException e) {
                if (!isCancelled())
                    U.error(log, "Shmem server failed.", e);
            }
            finally {
                srv.close();
            }
        }

        /** {@inheritDoc} */
        @Override public void cancel() {
            super.cancel();

            srv.close();
        }
    }

    /**
     *
     */
    private class ShmemWorker extends GridWorker {
        /** */
        private final GridIpcEndpoint endpoint;

        /** Adapter. */
        private GridHadoopIpcToNioAdapter<GridHadoopMessage> adapter;

        /**
         * @param endpoint Endpoint.
         */
        private ShmemWorker(GridIpcEndpoint endpoint, boolean accepted) {
            super(gridName, "shmem-worker", log);

            this.endpoint = endpoint;

            adapter = new GridHadoopIpcToNioAdapter<>(
                GridHadoopExternalCommunication.this.log,
                endpoint,
                accepted,
                srvLsnr,
                filters());
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException {
            try {
                adapter.serve();
            }
            finally {
                shmemWorkers.remove(this);

                endpoint.close();
            }
        }

        /** {@inheritDoc} */
        @Override public void cancel() {
            super.cancel();

            endpoint.close();
        }

        /** @{@inheritDoc} */
        @Override protected void cleanup() {
            super.cleanup();

            endpoint.close();
        }

        /** @{@inheritDoc} */
        @Override public String toString() {
            return S.toString(ShmemWorker.class, this);
        }

        /**
         * @return NIO session for this worker.
         */
        public GridNioSession session() {
            return adapter.session();
        }
    }

    /**
     *
     */
    private static class HandshakeFinish {
        /** Await latch. */
        private CountDownLatch latch = new CountDownLatch(1);

        /**
         * Finishes handshake.
         */
        public void finish() {
            latch.countDown();
        }

        /**
         * @param time Time to wait.
         * @throws GridHadoopHandshakeTimeoutException If failed to wait.
         */
        public void await(long time) throws GridHadoopHandshakeTimeoutException {
            try {
                if (!latch.await(time, TimeUnit.MILLISECONDS))
                    throw new GridHadoopHandshakeTimeoutException("Failed to wait for handshake to finish [timeout=" +
                        time + ']');
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                throw new GridHadoopHandshakeTimeoutException("Failed to wait for handshake to finish (thread was " +
                    "interrupted) [timeout=" + time + ']', e);
            }
        }
    }

    /**
     *
     */
    private class HandshakeAndBackpressureFilter extends GridNioFilterAdapter {
        /**
         * Assigns filter name to a filter.
         */
        protected HandshakeAndBackpressureFilter() {
            super("HadoopHandshakeFilter");
        }

        /** {@inheritDoc} */
        @Override public void onSessionOpened(final GridNioSession ses) throws GridException {
            if (ses.accepted()) {
                if (log.isDebugEnabled())
                    log.debug("Accepted connection, initiating handshake: " + ses);

                // Server initiates handshake.
                ses.send(locIdMsg).listenAsync(new CI1<GridNioFuture<?>>() {
                    @Override public void apply(GridNioFuture<?> fut) {
                        try {
                            // Make sure there were no errors.
                            fut.get();
                        }
                        catch (GridException | IOException e) {
                            log.warning("Failed to send handshake message, will close session: " + ses, e);

                            ses.close();
                        }
                    }
                });
            }
        }

        /** {@inheritDoc} */
        @Override public void onSessionClosed(GridNioSession ses) throws GridException {
            proceedSessionClosed(ses);
        }

        /** {@inheritDoc} */
        @Override public void onExceptionCaught(GridNioSession ses, GridException ex) throws GridException {
            proceedExceptionCaught(ses, ex);
        }

        /** {@inheritDoc} */
        @Override public GridNioFuture<?> onSessionWrite(GridNioSession ses, Object msg) throws GridException {
            if (ses.meta(PROCESS_META) == null && !(msg instanceof ProcessHandshakeMessage))
                log.warning("Writing message before handshake has finished [ses=" + ses + ", msg=" + msg + ']');

            return proceedSessionWrite(ses, msg);
        }

        /** {@inheritDoc} */
        @Override public void onMessageReceived(GridNioSession ses, Object msg) throws GridException {
            GridHadoopProcessDescriptor desc = ses.meta(PROCESS_META);

            UUID rmtProcId = desc == null ? null : desc.processId();

            if (rmtProcId == null) {
                if (!(msg instanceof ProcessHandshakeMessage)) {
                    log.warning("Invalid handshake message received, will close connection [ses=" + ses +
                        ", msg=" + msg + ']');

                    ses.close();

                    return;
                }

                ProcessHandshakeMessage nId = (ProcessHandshakeMessage)msg;

                if (log.isDebugEnabled())
                    log.debug("Received handshake message [ses=" + ses + ", msg=" + msg + ']');

                ses.addMeta(PROCESS_META, nId.processDescriptor());

                if (!ses.accepted())
                    // Send handshake reply.
                    ses.send(locIdMsg);
                else {
                    //
                    rmtProcId = nId.processDescriptor().processId();

                    if (log.isDebugEnabled())
                        log.debug("Finished handshake with remote client: " + ses);

                    Object sync = locks.tryLock(rmtProcId);

                    if (sync != null) {
                        try {
                            if (clients.get(rmtProcId) == null) {
                                if (log.isDebugEnabled())
                                    log.debug("Will reuse session for descriptor: " + rmtProcId);

                                // Handshake finished flag is true.
                                clients.put(rmtProcId, new GridHadoopTcpNioCommunicationClient(ses));
                            }
                            else {
                                if (log.isDebugEnabled())
                                    log.debug("Will not reuse client as another already exists [locProcDesc=" +
                                        locProcDesc + ", desc=" + desc + ']');
                            }
                        }
                        finally {
                            locks.unlock(rmtProcId, sync);
                        }
                    }
                    else {
                        if (log.isDebugEnabled())
                            log.debug("Concurrent connection is being established, will not reuse client session [" +
                                "locProcDesc=" + locProcDesc + ", desc=" + desc + ']');
                    }
                }

                if (log.isDebugEnabled())
                    log.debug("Handshake is finished for session [ses=" + ses + ", locProcDesc=" + locProcDesc + ']');

                HandshakeFinish to = ses.meta(HANDSHAKE_FINISH_META);

                if (to != null)
                    to.finish();

                // Notify session opened (both parties).
                proceedSessionOpened(ses);
            }
            else {
                if (msgQueueLimit > 0) {
                    GridNioMessageTracker tracker = ses.meta(TRACKER_META);

                    if (tracker == null) {
                        GridNioMessageTracker old = ses.addMeta(TRACKER_META, tracker =
                            new GridNioMessageTracker(ses, msgQueueLimit));

                        assert old == null;
                    }

                    tracker.onMessageReceived();
                }

                proceedMessageReceived(ses, msg);
            }
        }

        /** {@inheritDoc} */
        @Override public GridNioFuture<Boolean> onSessionClose(GridNioSession ses) throws GridException {
            return proceedSessionClose(ses);
        }

        /** {@inheritDoc} */
        @Override public void onSessionIdleTimeout(GridNioSession ses) throws GridException {
            proceedSessionIdleTimeout(ses);
        }

        /** {@inheritDoc} */
        @Override public void onSessionWriteTimeout(GridNioSession ses) throws GridException {
            proceedSessionWriteTimeout(ses);
        }
    }

    /**
     * Process ID message.
     */
    @SuppressWarnings("PublicInnerClass")
    public static class ProcessHandshakeMessage implements GridHadoopMessage, Externalizable {
        /** Node ID. */
        private GridHadoopProcessDescriptor procDesc;

        /** */
        public ProcessHandshakeMessage() {
            // No-op.
        }

        /**
         * @param procDesc Process descriptor.
         */
        private ProcessHandshakeMessage(GridHadoopProcessDescriptor procDesc) {
            this.procDesc = procDesc;
        }

        /**
         * @return Process ID.
         */
        public GridHadoopProcessDescriptor processDescriptor() {
            return procDesc;
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(procDesc);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            procDesc = (GridHadoopProcessDescriptor)in.readObject();
        }
    }
}
