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

import com.teragrep.net_01.channel.buffer.writable.Writeable;
import com.teragrep.net_01.channel.buffer.writable.WriteableStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.NeedsReadException;
import tlschannel.NeedsWriteException;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

/**
 * EgressImpl provides a way to transmit information queue holds information to be transmitted in transmission order
 * (FIFO) partially transmitted information may reside on the HEAD of the queue and newly added is at the tail. Once all
 * ByteBuffers of the Writeable are transmitted (consumed) the Writeable is closed and removed from the queue.
 * Thread-safety is ensured by having a single consumer thread to drain the queue under the lock while allowing multiple
 * lock-free producers to add more Writeables
 */

final class EgressImpl implements Egress {

    private static final Logger LOGGER = LoggerFactory.getLogger(EgressImpl.class);

    private final EstablishedContext establishedContext;

    private final ConcurrentLinkedQueue<Writeable> queue;

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
    public void accept(Writeable writeable) {

        if (!writeable.isStub()) {
            queue.add(writeable);
        }

        while (queue.peek() != null) {
            if (lock.tryLock()) {
                try { // lock acquired
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("starting drain of the transmission queue with queue.size() <{}>", queue.size());
                    }

                    try {
                        final boolean tlsRequiresOp = drainQueue();
                        if (tlsRequiresOp) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER
                                        .debug(
                                                "drain paused as eventloop selector needs to run due to tlsRequiresOp <{}>",
                                                tlsRequiresOp
                                        );
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

        // sockets use gathering read pattern and need a ByteBuffer[] (array)

        // snapshot the queue for this drain attempt
        Writeable[] snapshot = queue.toArray(new Writeable[0]);

        // estimate number of buffers in the queue for array size
        int numberOfBuffers = 0;
        for (Writeable w : snapshot) {
            numberOfBuffers += w.buffers().length;
        }

        // create the gathering write array
        ByteBuffer[] writeBuffers = new ByteBuffer[numberOfBuffers];

        // populate the array
        int writeBuffersIndex = 0;

        for (Writeable w : snapshot) {
            for (ByteBuffer buffer : w.buffers()) {
                writeBuffers[writeBuffersIndex] = buffer;
                writeBuffersIndex++;
            }
        }

        boolean tlsRequiresOp = false;
        try {
            final long bytesTransmitted = establishedContext.socket().write(writeBuffers);
            if (LOGGER.isDebugEnabled()) {
                long totalBytes = 0;
                for (ByteBuffer b : writeBuffers) {
                    totalBytes += b.limit();
                }
                LOGGER.debug("bytesTransmitted <{}> of totalBytes <{}>", bytesTransmitted, totalBytes);
            }
        }
        catch (NeedsReadException nre) {
            needRead.set(true);
            establishedContext.interestOps().add(OP_READ);
            tlsRequiresOp = true;
        }
        catch (NeedsWriteException nwe) {
            establishedContext.interestOps().add(OP_WRITE);
            tlsRequiresOp = true;
        }

        // drain consumed ones from the queue

        while (true) {
            Writeable headPeek = queue.peek();

            if (headPeek == null) {
                // everything drained
                break;
            }

            if (!headPeek.hasRemaining()) {
                LOGGER.trace("headPeek written, polling out and closing");
                Writeable consumedWriteable = queue.poll();
                consumedWriteable.close();
            }
            else {
                // there are still queued elements left with data
                break;
            }
        }
        return tlsRequiresOp;
    }

    @Override
    public void run() {
        accept(new WriteableStub());
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
