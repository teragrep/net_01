/*
 * Java Zero Copy Networking Library net_01
 * Copyright (C) 2024 Suomen Kanuuna Oy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 *
 * Additional permission under GNU Affero General Public License version 3
 * section 7
 *
 * If you modify this Program, or any covered work, by linking or combining it
 * with other code, such other code is not for that reason alone subject to any
 * of the requirements of the GNU Affero GPL version 3 as long as this Program
 * is the same Program as licensed from Suomen Kanuuna Oy without any additional
 * modifications.
 *
 * Supplemented terms under GNU Affero General Public License version 3
 * section 7
 *
 * Origin of the software must be attributed to Suomen Kanuuna Oy. Any modified
 * versions must be marked as "Modified version of" The Program.
 *
 * Names of the licensors and authors may not be used for publicity purposes.
 *
 * No rights are granted for use of trade names, trademarks, or service marks
 * which are in The Program if any.
 *
 * Licensee must indemnify licensors and authors for any liability that these
 * contractual assumptions impose on licensors and authors.
 *
 * To the extent this program is licensed as part of the Commercial versions of
 * Teragrep, the applicable Commercial License may apply to this file if you as
 * a licensee so wish it.
 */
package com.teragrep.net_01.channel.context;

import com.teragrep.net_01.channel.buffer.BufferLease;
import com.teragrep.net_01.channel.buffer.BufferLeasePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.NeedsReadException;
import tlschannel.NeedsWriteException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

final class IngressImpl implements Ingress {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngressImpl.class);
    private final EstablishedContextImpl establishedContext;
    private final BufferLeasePool bufferLeasePool;

    private final LinkedList<BufferLease> activeBuffers;
    private final Lock lock;
    // tls
    public final AtomicBoolean needWrite;

    private final List<Clock> interestedClocks;

    IngressImpl(EstablishedContextImpl establishedContext, BufferLeasePool bufferLeasePool) {
        this.establishedContext = establishedContext;
        this.bufferLeasePool = bufferLeasePool;

        this.activeBuffers = new LinkedList<>();
        this.lock = new ReentrantLock();
        this.needWrite = new AtomicBoolean();

        this.interestedClocks = new LinkedList<>();
    }

    @Override
    public void run() {
        LOGGER.debug("run entry!");
        lock.lock();
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("run lock! with activeBuffers.size() <{}>", activeBuffers.size());
            }
            while (true) {
                LOGGER.debug("run loop start");

                // fill buffers for read
                long readBytes = readData();

                if (!isDataAvailable(readBytes)) {
                    break;
                }

                boolean continueReading = true;
                while (!activeBuffers.isEmpty()) {
                    BufferLease bufferLease = activeBuffers.removeFirst();
                    bufferLease.addRef();
                    LOGGER
                            .debug(
                                    "submitting buffer <{}> from activeBuffers <{}> to relpFrame", bufferLease,
                                    activeBuffers
                            );

                    if (!interestedClocks.isEmpty()) {
                        for (Clock clock : interestedClocks) {
                            clock.advance(bufferLease);
                        }
                    }

                    if (interestedClocks.isEmpty()) {
                        continueReading = false;
                    }

                    LOGGER.debug("clock returned continueReading <{}>", continueReading);
                    if (bufferLease.buffer().hasRemaining()) {
                        // return back as it has some remaining
                        LOGGER.debug("pushBack bufferLease id <{}>", bufferLease.id());
                        activeBuffers.push(bufferLease);
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER
                                    .debug(
                                            "buffer.buffer <{}>, buffer.buffer().hasRemaining() <{}> returned it to activeBuffers <{}>",
                                            bufferLease.buffer(), bufferLease.buffer().hasRemaining(), activeBuffers
                                    );
                        }
                    }
                    bufferLease.removeRef();
                    if (!continueReading) {
                        break;
                    }
                }
                if (!continueReading) {
                    break;
                }
            }
        }
        catch (NeedsReadException nre) {
            LOGGER.debug("need read", nre);
            try {
                establishedContext.interestOps().add(OP_READ);
            }
            catch (CancelledKeyException cke) {
                LOGGER.debug("Connection already closed for need read.", cke);
                establishedContext.close();
            }
            catch (Throwable t) {
                LOGGER.error("unexpected error while changing socket interest operations to OP_READ", t);
            }
        }
        catch (NeedsWriteException nwe) {
            LOGGER.debug("need write", nwe);
            needWrite.set(true);
            try {
                establishedContext.interestOps().add(OP_WRITE);
            }
            catch (CancelledKeyException cke) {
                LOGGER.debug("Connection already closed for need write.", cke);
                establishedContext.close();
            }
            catch (Throwable t) {
                LOGGER.error("unexpected error while changing socket interest operations to OP_WRITE", t);
            }
        }
        catch (EndOfStreamException eose) {
            // close connection
            try {
                LOGGER
                        .warn(
                                "End of stream for PeerAddress <{}> PeerPort <{}>. Closing Connection.",
                                establishedContext.socket().getTransportInfo().getPeerAddress(),
                                establishedContext.socket().getTransportInfo().getPeerPort()
                        );
            }
            catch (Exception ignored) {

            }
            finally {
                establishedContext.close();
            }
        }
        catch (Throwable t) {
            LOGGER.error("run() threw", t);
            establishedContext.close();
        }
        finally {
            lock.unlock();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("thread <{}> going to pool", Thread.currentThread());
            }
        }
    }

    private boolean isDataAvailable(long readBytes) throws IOException {
        boolean rv;
        if (readBytes == 0) {
            // socket needs to read more
            establishedContext.interestOps().add(OP_READ);
            LOGGER.debug("more bytes requested from socket");
            rv = false;
        }
        else if (readBytes < 0) {
            throw new EndOfStreamException("negative readBytes <" + readBytes + ">");
        }
        else {
            rv = true;
        }
        return rv;
    }

    private long readData() throws IOException {
        long readBytes = 0;

        List<BufferLease> bufferLeases = bufferLeasePool.take(4);

        List<ByteBuffer> byteBufferList = new LinkedList<>();
        for (BufferLease bufferLease : bufferLeases) {
            if (bufferLease.isStub()) {
                continue;
            }
            byteBufferList.add(bufferLease.buffer());
        }
        ByteBuffer[] byteBufferArray = byteBufferList.toArray(new ByteBuffer[0]);

        readBytes = establishedContext.socket().read(byteBufferArray);

        activateBuffers(bufferLeases);

        LOGGER.debug("establishedContext.read got <{}> bytes from socket", readBytes);

        return readBytes;
    }

    private void activateBuffers(List<BufferLease> bufferLeases) {
        for (BufferLease bufferLease : bufferLeases) {
            if (bufferLease.buffer().position() != 0) {
                bufferLease.buffer().flip();
                activeBuffers.add(bufferLease);
            }
            else {
                // unused buffer, releasing back to pool
                bufferLease.removeRef();
            }
        }
    }

    public AtomicBoolean needWrite() {
        return needWrite;
    }

    @Override
    public void register(Clock clock) {
        lock.lock();
        try {
            if (!interestedClocks.isEmpty()) {
                throw new IllegalStateException(
                        "Unable to register ingress clock, only one interested clock is allowed"
                );
            }
            interestedClocks.add(clock);
            establishedContext.interestOps().add(OP_READ);
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void unregister(Clock clock) {
        lock.lock();
        try {
            if (!interestedClocks.contains(clock)) {
                throw new IllegalStateException("Unable to unregister ingress clock, it is not registered");
            }
            interestedClocks.remove(clock);
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws Exception {
        lock.lock();
        try {
            for (Clock clock : interestedClocks) {
                clock.close();
            }
        }
        finally {
            lock.unlock();
        }
    }
}
