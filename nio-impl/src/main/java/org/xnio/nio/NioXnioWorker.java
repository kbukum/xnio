/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.xnio.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.xnio.Cancellable;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.ClosedWorkerException;
import org.xnio.FailedIoFuture;
import org.xnio.FinishedIoFuture;
import org.xnio.FutureResult;
import org.xnio.IoFuture;
import org.xnio.IoUtils;
import org.xnio.Option;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;
import org.xnio.channels.BoundChannel;
import org.xnio.channels.CloseableChannel;
import org.xnio.channels.ConnectedStreamChannel;
import org.xnio.channels.MulticastMessageChannel;

import static org.xnio.IoUtils.safeClose;
import static org.xnio.ChannelListener.SimpleSetter;
import static org.xnio.nio.Log.log;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class NioXnioWorker extends XnioWorker {

    private static final int CLOSE_REQ = (1 << 31);
    private static final int CLOSE_COMP = (1 << 30);

    private volatile int state = 0;

    private final WorkerThread[] readWorkers;
    private final WorkerThread[] writeWorkers;

    private static final AtomicIntegerFieldUpdater<NioXnioWorker> stateUpdater = AtomicIntegerFieldUpdater.newUpdater(NioXnioWorker.class, "state");

    private static final AtomicInteger seq = new AtomicInteger(1);

    private final SimpleSetter<NioXnioWorker> closeSetter = new SimpleSetter<NioXnioWorker>();

    NioXnioWorker(final NioXnio xnio, final OptionMap optionMap) throws IOException {
        super(xnio);
        final int readCount = optionMap.get(Options.WORKER_READ_THREADS, 1);
        if (readCount < 0) {
            throw new IllegalArgumentException("Worker read thread count must be >= 0");
        }
        final int writeCount = optionMap.get(Options.WORKER_WRITE_THREADS, 1);
        if (writeCount < 0) {
            throw new IllegalArgumentException("Worker write thread count must be >= 0");
        }
        final long workerStackSize = optionMap.get(Options.STACK_SIZE, 0L);
        if (workerStackSize < 0L) {
            throw new IllegalArgumentException("Worker stack size must be >= 0");
        }
        String workerName = optionMap.get(Options.WORKER_NAME);
        if (workerName == null) {
            workerName = "XNIO-" + seq.getAndIncrement();
        }
        WorkerThread[] readWorkers, writeWorkers;
        readWorkers = new WorkerThread[readCount];
        writeWorkers = new WorkerThread[writeCount];
        boolean ok = false;
        try {
            for (int i = 0; i < readCount; i++) {
                readWorkers[i] = new WorkerThread(this, Selector.open(), String.format("%s read-%d", workerName, i+1), null, workerStackSize);
            }
            for (int i = 0; i < writeCount; i++) {
                writeWorkers[i] = new WorkerThread(this, Selector.open(), String.format("%s write-%d", workerName, i+1), null, workerStackSize);
            }
            ok = true;
        } finally {
            if (! ok) {
                for (WorkerThread worker : readWorkers) {
                    if (worker != null) safeClose(worker.getSelector());
                }
                for (WorkerThread worker : writeWorkers) {
                    if (worker != null) safeClose(worker.getSelector());
                }
            }
        }
        this.readWorkers = readWorkers;
        this.writeWorkers = writeWorkers;
    }

    void start() {
        for (WorkerThread worker : readWorkers) {
            openResourceUnconditionally();
            worker.start();
        }
        for (WorkerThread worker : writeWorkers) {
            openResourceUnconditionally();
            worker.start();
        }
    }

    private static final WorkerThread[] NO_WORKERS = new WorkerThread[0];

    WorkerThread choose() {
        final WorkerThread[] write = writeWorkers;
        final WorkerThread[] read = readWorkers;
        final int writeLength = write.length;
        final int readLength = read.length;
        if (writeLength == 0) {
            return choose(false);
        }
        if (readLength == 0) {
            return choose(true);
        }
        final Random random = IoUtils.getThreadLocalRandom();
        final int idx = random.nextInt(writeLength + readLength);
        return idx >= readLength ? write[idx - readLength] : read[idx];
    }

    WorkerThread chooseOptional(final boolean write) {
        final WorkerThread[] orig = write ? writeWorkers : readWorkers;
        final int length = orig.length;
        if (length == 0) {
            return null;
        }
        if (length == 1) {
            return orig[0];
        }
        final Random random = IoUtils.getThreadLocalRandom();
        return orig[random.nextInt(length)];
    }

    WorkerThread choose(final boolean write) {
        final WorkerThread result = chooseOptional(write);
        if (result == null) {
            throw new IllegalArgumentException("No threads configured");
        }
        return result;
    }

    WorkerThread[] choose(int count, boolean write) {
        if (count == 0) {
            return NO_WORKERS;
        }
        final WorkerThread[] orig = write ? writeWorkers : readWorkers;
        final int length = orig.length;
        if (length == 0) {
            throw new IllegalArgumentException("No threads configured");
        }
        if (count == length) {
            return orig;
        }
        final WorkerThread[] result = new WorkerThread[count];
        final Random random = IoUtils.getThreadLocalRandom();
        if (count == 1) {
            result[0] = orig[random.nextInt(length)];
            return result;
        }
        if (length < 32) {
            int bits = 0;
            do {
                bits |= (1 << random.nextInt(length));
            } while (Integer.bitCount(bits) < count);
            for (int i = 0; i < count; i ++) {
                final int bit = Integer.numberOfTrailingZeros(bits);
                result[i] = orig[bit];
                bits ^= Integer.lowestOneBit(bits);
            }
            return result;
        }
        if (length < 64) {
            long bits = 0;
            do {
                bits |= (1L << (long) random.nextInt(length));
            } while (Long.bitCount(bits) < count);
            for (int i = 0; i < count; i ++) {
                final int bit = Long.numberOfTrailingZeros(bits);
                result[i] = orig[bit];
                bits ^= Long.lowestOneBit(bits);
            }
            return result;
        }
        // lots of threads.  No faster way to do it.
        final HashSet<WorkerThread> set;
        if (count >= (length >> 1)) {
            // We're returning half or more of the threads.
            set = new HashSet<WorkerThread>(Arrays.asList(orig));
            while (set.size() > count) {
                set.remove(orig[random.nextInt(length)]);
            }
        } else {
            // We're returning less than half of the threads.
            set = new HashSet<WorkerThread>(length);
            while (set.size() < count) {
                set.add(orig[random.nextInt(length)]);
            }
        }
        return set.toArray(result);
    }

    protected AcceptingChannel<? extends ConnectedStreamChannel> createTcpServer(final InetSocketAddress bindAddress, final ChannelListener<? super AcceptingChannel<ConnectedStreamChannel>> acceptListener, final OptionMap optionMap) throws IOException {
        boolean ok = false;
        final ServerSocketChannel channel = ServerSocketChannel.open();
        try {
            channel.configureBlocking(false);
            channel.socket().bind(bindAddress);
            final NioTcpServer server = new NioTcpServer(this, channel, optionMap);
            final ChannelListener.SimpleSetter<NioTcpServer> setter = server.getAcceptSetter();
            setter.set((ChannelListener<? super NioTcpServer>) acceptListener);
            ok = true;
            return server;
        } finally {
            if (! ok) {
                IoUtils.safeClose(channel);
            }
        }
    }

    protected IoFuture<ConnectedStreamChannel> connectTcpStream(final InetSocketAddress bindAddress, final InetSocketAddress destinationAddress, final ChannelListener<? super ConnectedStreamChannel> openListener, final ChannelListener<? super BoundChannel> bindListener, final OptionMap optionMap) {
        try {
            final SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.socket().bind(bindAddress);
            final NioTcpChannel tcpChannel = new NioTcpChannel(this, channel);
            final NioHandle<NioTcpChannel> connectHandle = optionMap.get(Options.WORKER_ESTABLISH_WRITING, false) ? tcpChannel.getWriteHandle() : tcpChannel.getReadHandle();
            ChannelListeners.invokeChannelListener(tcpChannel.getBoundChannel(), bindListener);
            if (channel.connect(destinationAddress)) {
                connectHandle.getWorkerThread().execute(ChannelListeners.getChannelListenerTask(tcpChannel, openListener));
                return new FinishedIoFuture<ConnectedStreamChannel>(tcpChannel);
            }
            final SimpleSetter<NioTcpChannel> setter = connectHandle.getHandlerSetter();
            final FutureResult<ConnectedStreamChannel> futureResult = new FutureResult<ConnectedStreamChannel>();
            setter.set(new ChannelListener<NioTcpChannel>() {
                public void handleEvent(final NioTcpChannel channel) {
                    final SocketChannel socketChannel = (SocketChannel) channel.getReadChannel();
                    try {
                        if (socketChannel.finishConnect()) {
                            connectHandle.suspend();
                            connectHandle.getHandlerSetter().set(null);
                            futureResult.setResult(tcpChannel);
                            //noinspection unchecked
                            ChannelListeners.invokeChannelListener(tcpChannel, openListener);
                        }
                    } catch (IOException e) {
                        IoUtils.safeClose(channel);
                        futureResult.setException(e);
                    }
                }

                public String toString() {
                    return "Connection finisher for " + channel;
                }
            });
            futureResult.addCancelHandler(new Cancellable() {
                public Cancellable cancel() {
                    if (futureResult.setCancelled()) {
                        IoUtils.safeClose(channel);
                    }
                    return this;
                }

                public String toString() {
                    return "Cancel handler for " + channel;
                }
            });
            connectHandle.resume(SelectionKey.OP_CONNECT);
            return futureResult.getIoFuture();
        } catch (IOException e) {
            return new FailedIoFuture<ConnectedStreamChannel>(e);
        }
    }

    protected IoFuture<ConnectedStreamChannel> acceptTcpStream(final InetSocketAddress destination, final ChannelListener<? super ConnectedStreamChannel> openListener, final ChannelListener<? super BoundChannel> bindListener, final OptionMap optionMap) {
        final WorkerThread connectThread = choose(optionMap.get(Options.WORKER_ESTABLISH_WRITING, false));
        try {
            final ServerSocketChannel channel = ServerSocketChannel.open();
            channel.configureBlocking(false);
            channel.socket().bind(destination);
            final NioSetter<NioTcpChannel> closeSetter = new NioSetter<NioTcpChannel>();
            //noinspection unchecked
            ChannelListeners.invokeChannelListener(new BoundChannel() {
                public XnioWorker getWorker() {
                    return NioXnioWorker.this;
                }

                public SocketAddress getLocalAddress() {
                    return channel.socket().getLocalSocketAddress();
                }

                public <A extends SocketAddress> A getLocalAddress(final Class<A> type) {
                    final SocketAddress address = getLocalAddress();
                    return type.isInstance(address) ? type.cast(address) : null;
                }

                public ChannelListener.Setter<? extends BoundChannel> getCloseSetter() {
                    return closeSetter;
                }

                public boolean isOpen() {
                    return channel.isOpen();
                }

                public boolean supportsOption(final Option<?> option) {
                    return false;
                }

                public <T> T getOption(final Option<T> option) throws IOException {
                    return null;
                }

                public <T> T setOption(final Option<T> option, final T value) throws IllegalArgumentException, IOException {
                    return null;
                }

                public void close() throws IOException {
                    channel.close();
                }

                public String toString() {
                    return String.format("TCP acceptor bound channel (NIO) <%h>", this);
                }
            }, bindListener);
            final SocketChannel accepted = channel.accept();
            if (accepted != null) {
                IoUtils.safeClose(channel);
                final NioTcpChannel tcpChannel = new NioTcpChannel(this, accepted);
                //noinspection unchecked
                ChannelListeners.invokeChannelListener(tcpChannel, openListener);
                return new FinishedIoFuture<ConnectedStreamChannel>(tcpChannel);
            }
            final SimpleSetter<ServerSocketChannel> setter = new SimpleSetter<ServerSocketChannel>();
            final FutureResult<ConnectedStreamChannel> futureResult = new FutureResult<ConnectedStreamChannel>();
            final NioHandle<ServerSocketChannel> handle = connectThread.addChannel(channel, channel, 0, setter);
            setter.set(new ChannelListener<ServerSocketChannel>() {
                public void handleEvent(final ServerSocketChannel channel) {
                    final SocketChannel accepted;
                    try {
                        accepted = channel.accept();
                        if (accepted == null) {
                            return;
                        }
                    } catch (IOException e) {
                        IoUtils.safeClose(channel);
                        handle.cancelKey();
                        futureResult.setException(e);
                        return;
                    }
                    boolean ok = false;
                    try {
                        handle.cancelKey();
                        IoUtils.safeClose(channel);
                        try {
                            accepted.configureBlocking(false);
                            final NioTcpChannel tcpChannel;
                            tcpChannel = new NioTcpChannel(NioXnioWorker.this, accepted);
                            futureResult.setResult(tcpChannel);
                            ok = true;
                            //noinspection unchecked
                            ChannelListeners.invokeChannelListener(tcpChannel, openListener);
                        } catch (IOException e) {
                            futureResult.setException(e);
                            return;
                        }
                    } finally {
                        if (! ok) {
                            IoUtils.safeClose(accepted);
                        }
                    }
                }

                public String toString() {
                    return "Accepting finisher for " + channel;
                }
            });
            handle.resume(SelectionKey.OP_ACCEPT);
            return futureResult.getIoFuture();
        } catch (IOException e) {
            return new FailedIoFuture<ConnectedStreamChannel>(e);
        }
    }

    /** {@inheritDoc} */
    public MulticastMessageChannel createUdpServer(final InetSocketAddress bindAddress, final ChannelListener<? super MulticastMessageChannel> bindListener, final OptionMap optionMap) throws IOException {
        if (!NioXnio.NIO2 && optionMap.get(Options.MULTICAST, false)) {
            final MulticastSocket socket = new MulticastSocket(bindAddress);
            final BioMulticastUdpChannel channel = new BioMulticastUdpChannel(this, optionMap.get(Options.SEND_BUFFER, 8192), optionMap.get(Options.RECEIVE_BUFFER, 8192), socket, chooseOptional(false), chooseOptional(true));
            channel.open();
            //noinspection unchecked
            ChannelListeners.invokeChannelListener(channel, bindListener);
            return channel;
        } else {
            final DatagramChannel channel = DatagramChannel.open();
            channel.configureBlocking(false);
            channel.socket().bind(bindAddress);
            final NioUdpChannel udpChannel = new NioUdpChannel(this, channel);
            //noinspection unchecked
            ChannelListeners.invokeChannelListener(udpChannel, bindListener);
            return udpChannel;
        }
    }

    public boolean isOpen() {
        return (state & CLOSE_REQ) == 0;
    }

    /**
     * Open a resource unconditionally (i.e. accepting a connection on an open server).
     */
    void openResourceUnconditionally() {
        int oldState = stateUpdater.getAndIncrement(this);
        if (log.isTraceEnabled()) {
            log.tracef("CAS %s %08x -> %08x", this, oldState, oldState + 1);
        }
    }

    /**
     * Open a resource.  Must be matched with a corresponding {@code closeResource()}.
     *
     * @throws ClosedWorkerException if the worker is closed
     */
    void openResource() throws ClosedWorkerException {
        int oldState;
        do {
            oldState = state;
            if ((oldState & CLOSE_REQ) != 0) {
                throw new ClosedWorkerException("Worker is shutting down");
            }
        } while (! stateUpdater.compareAndSet(this, oldState, oldState + 1));
        if (log.isTraceEnabled()) {
            log.tracef("CAS %s %08x -> %08x", this, oldState, oldState + 1);
        }
    }

    void closeResource() {
        int oldState = stateUpdater.decrementAndGet(this);
        if (log.isTraceEnabled()) {
            log.tracef("CAS %s %08x -> %08x", this, oldState + 1, oldState);
        }
        while (oldState == CLOSE_REQ) {
            if (stateUpdater.compareAndSet(this, CLOSE_REQ, CLOSE_REQ | CLOSE_COMP)) {
                log.tracef("CAS %s %08x -> %08x (close complete)", this, CLOSE_REQ, CLOSE_REQ | CLOSE_COMP);
                synchronized (this) {
                    notifyAll();
                }
            }
            oldState = state;
        }
    }

    public void close() throws IOException {
        int oldState = state;
        if ((oldState & CLOSE_COMP) != 0) {
            log.tracef("Idempotent close of %s", this);
            return;
        }
        boolean intr = false;
        try {
            synchronized (this) {
                oldState = state;
                while ((oldState & CLOSE_COMP) == 0) {
                    if ((oldState & CLOSE_REQ) == 0) {
                        // need to do the close ourselves...
                        if (! stateUpdater.compareAndSet(this, oldState, oldState | CLOSE_REQ)) {
                            // changed in the meantime
                            oldState = state;
                            continue;
                        }
                        log.tracef("Initiating close of %s", this);
                        for (WorkerThread worker : readWorkers) {
                            worker.shutdown();
                        }
                        for (WorkerThread worker : writeWorkers) {
                            worker.shutdown();
                        }
                        final ChannelListener<? super NioXnioWorker> listener = closeSetter.get();
                        while ((state & ~(CLOSE_COMP|CLOSE_REQ)) > 0) try {
                            log.tracef("Waiting for resources to close in %s", this);
                            wait();
                        } catch (InterruptedException e) {
                            intr = true;
                        }
                        log.tracef("Completed close of %s", this);
                        ChannelListeners.invokeChannelListener(this, listener);
                        return;
                    }
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        intr = true;
                    }
                    oldState = state;
                }
                log.tracef("Idempotent close of %s", this);
                return;
            }
        } finally {
            if (intr) Thread.currentThread().interrupt();
        }
    }

    public void execute(final Runnable command) {
        choose().execute(command);
    }

    public ChannelListener.Setter<? extends CloseableChannel> getCloseSetter() {
        return closeSetter;
    }

    public boolean supportsOption(final Option<?> option) {
        return false;
    }

    public <T> T getOption(final Option<T> option) throws IOException {
        return null;
    }

    public <T> T setOption(final Option<T> option, final T value) throws IllegalArgumentException, IOException {
        return null;
    }

    public Key executeAfter(final Runnable command, final long time, final TimeUnit unit) {
        return choose().executeAfter(command, unit.toMillis(time));
    }

    public NioXnio getXnio() {
        return (NioXnio) super.getXnio();
    }
}