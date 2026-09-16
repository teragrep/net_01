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

import com.teragrep.buf_01.buffer.lease.TrackedLease;
import com.teragrep.buf_01.buffer.lease.TrackedMemorySegmentLease;
import com.teragrep.buf_01.buffer.lease.collection.TrackedLeaseCollection;
import com.teragrep.buf_01.buffer.lease.collection.TrackedMemorySegmentLeaseCollection;
import com.teragrep.buf_01.buffer.lease.collection.TrackedMemorySegmentLeaseCollectionStub;
import com.teragrep.net_01.channel.socket.WrittenResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.NeedsReadException;
import tlschannel.NeedsWriteException;

import java.io.IOException;

import java.lang.foreign.MemorySegment;
import java.nio.channels.CancelledKeyException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

final class EgressImpl implements Egress {

    private static final Logger LOGGER = LoggerFactory.getLogger(EgressImpl.class);
    private final EstablishedContext establishedContext;
    private final ConcurrentLinkedQueue<TrackedLeaseCollection<MemorySegment>> queue;
    private final Lock lock;

    // tls
    private final AtomicBoolean needRead;

    EgressImpl(EstablishedContext establishedContext) {
        this.establishedContext = establishedContext;
        this.queue = new ConcurrentLinkedQueue<>();
        this.lock = new ReentrantLock();
        this.needRead = new AtomicBoolean();
    }

    // this must be thread-safe!
    @Override
    public void accept(TrackedLeaseCollection<MemorySegment> coll) {

        if (!coll.isStub()) {
            queue.add(coll);
        }

        while (queue.peek() != null) {
            if (lock.tryLock()) {
                try {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Starting drain of the transmission queue with queue.size() <{}>", queue.size());
                    }

                    try {
                        final boolean tlsRequiresOp = drainQueue();

                        if (tlsRequiresOp) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("Drain paused as eventLoop selector needs to run due to tlsRequiresOp <{}>", tlsRequiresOp);
                            }
                            break;
                        }
                    }
                    catch (CancelledKeyException cke) {
                        LOGGER
                                .warn(
                                        "CancelledKeyException <{}>. Closing connection for PeerAddress <{}> PeerPort <{}>",
                                        cke.getMessage(),
                                        establishedContext.socket().getTransportInfo().getPeerAddress(),
                                        establishedContext.socket().getTransportInfo().getPeerPort()
                                );
                        establishedContext.close();
                        return;
                    }
                    catch (IOException ioException) {
                        LOGGER
                                .error(
                                        "IOException <{}> while writing to socket. PeerAddress <{}> PeerPort <{}>",
                                        ioException, establishedContext.socket().getTransportInfo().getPeerAddress(),
                                        establishedContext.socket().getTransportInfo().getPeerPort()
                                );
                        establishedContext.close();
                        return;
                    }

                }
                finally {
                    lock.unlock();
                }
            }
            else {
                break;
            }
        }
    }

    private boolean drainQueue() throws IOException {
        final TrackedLeaseCollection<MemorySegment>[] snapshot = new TrackedMemorySegmentLeaseCollection[queue.size()];
        int snapshotIndex = 0;
        for (TrackedLeaseCollection<MemorySegment> leaseCollection : queue) {
            snapshot[snapshotIndex] = leaseCollection;
            snapshotIndex++;
        }

        int numberOfBuffers = 0;
        for (final TrackedLeaseCollection<MemorySegment> w : snapshot) {
            numberOfBuffers += w.leases().length;
        }

        final TrackedLease<MemorySegment>[] writeBuffers = new TrackedMemorySegmentLease[numberOfBuffers];

        int i = 0;
        for (final TrackedLeaseCollection<MemorySegment> w : snapshot) {
            for (final TrackedLease<MemorySegment> lease : w.leases()) {
                writeBuffers[i] = lease;
                i++;
            }
        }

        boolean tlsRequiresOp = false;
        try {
            LOGGER.debug("Writing to socket");
            final WrittenResult result = establishedContext.socket().write(writeBuffers);
            LOGGER.info("Transmit <{}> byte(s) to socket", result.bytes());
        }
        catch (final NeedsReadException nre) {
            needRead.set(true);
            establishedContext.interestOps().add(OP_READ);
            tlsRequiresOp = true;
        }
        catch (final NeedsWriteException nwe) {
            establishedContext.interestOps().add(OP_WRITE);
            tlsRequiresOp = true;
        }

        // Drain consumed leases from queue
        while (true) {
            final TrackedLeaseCollection<MemorySegment> headPeek = queue.peek();

            if (headPeek == null) {
                // everything drained
                break;
            }

            if (!headPeek.hasNext()) {
                LOGGER.trace("headPeek written, polling out and closing");
                final TrackedLeaseCollection<MemorySegment> consumedLease = queue.poll();
                if (consumedLease != null) {
                    consumedLease.close();
                }
            } else {
                // there are still queued elements left with data
                break;
            }
        }

        return tlsRequiresOp;
    }

    @Override
    public void run() {
        accept(new TrackedMemorySegmentLeaseCollectionStub());
    }

    @Override
    public AtomicBoolean needRead() {
        return needRead;
    }

    @Override
    public int outstanding() {
        return queue.size();
    }
}
