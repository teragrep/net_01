/*
 * Java Zero Copy Networking Library NET-01
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
import java.util.ArrayList;
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

    private final ConcurrentLinkedQueue<Writeable> queue;
    private final ArrayList<Writeable> writeInProgressList; // lock protected

    private final Lock lock;

    // tls
    private final AtomicBoolean needRead;

    private final List<Writeable> toWriteList;

    EgressImpl(EstablishedContext establishedContext) {
        this.establishedContext = establishedContext;
        this.queue = new ConcurrentLinkedQueue<>();
        this.writeInProgressList = new ArrayList<>();
        this.lock = new ReentrantLock();
        this.needRead = new AtomicBoolean();

        this.toWriteList = new ArrayList<>();
    }

    // this must be thread-safe!
    @Override
    public void accept(Writeable writeable) {

        if (!writeable.isStub()) {
            queue.add(writeable);
        }

        while (queue.peek() != null) {
            if (lock.tryLock()) {
                try {
                    while (true) {
                        Writeable w = queue.poll();
                        if (w != null) {
                            if (LOGGER.isTraceEnabled()) {
                                LOGGER
                                        .trace(
                                                "adding writable to toWriteList.size <{}>, writeInProgressList.size <{}>",
                                                toWriteList.size(), writeInProgressList.size()
                                        );
                            }
                            toWriteList.add(w);
                        }
                        else {
                            break;
                        }
                    }

                    if (toWriteList.isEmpty()) {
                        break;
                    }

                    try {
                        transmit(toWriteList);
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

    private void transmit(final List<Writeable> toWriteList) throws IOException {

        try {

            int numberOfBuffers = 0;
            Iterator<Writeable> toWriteIterator = toWriteList.iterator();
            while (toWriteIterator.hasNext()) {
                Writeable w = toWriteIterator.next();
                numberOfBuffers += w.buffers().length;
            }

            ByteBuffer[] writeBuffers = new ByteBuffer[numberOfBuffers];
            int writeBuffersIndex = 0;

            Iterator<Writeable> toWriteIterator2 = toWriteList.iterator();
            while (toWriteIterator2.hasNext()) {
                Writeable w = toWriteIterator2.next();

                for (ByteBuffer buffer : w.buffers()) {
                    writeBuffers[writeBuffersIndex] = buffer;
                    writeBuffersIndex++;
                }

                toWriteIterator2.remove();
                writeInProgressList.add(w);
            }

            establishedContext.socket().write(writeBuffers);

            // remove written ones
            Iterator<Writeable> writeableIterator = writeInProgressList.iterator();
            while (writeableIterator.hasNext()) {
                Writeable w = writeableIterator.next();
                if (!w.hasRemaining()) {
                    LOGGER.debug("complete write, closing written writeable");
                    w.close();
                    writeableIterator.remove();
                }
                else {
                    LOGGER.debug("writable has still buffers, breaking");
                    break;
                }
            }
        }
        catch (NeedsReadException nre) {
            needRead.set(true);
            establishedContext.interestOps().add(OP_READ);
        }
        catch (NeedsWriteException nwe) {
            establishedContext.interestOps().add(OP_WRITE);
        }
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
