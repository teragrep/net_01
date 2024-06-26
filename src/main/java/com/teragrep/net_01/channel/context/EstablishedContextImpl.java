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

import com.teragrep.net_01.channel.socket.Socket;
import com.teragrep.net_01.channel.buffer.BufferLeasePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

/**
 * Implementation of the {@link EstablishedContext}
 */
final class EstablishedContextImpl implements EstablishedContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(EstablishedContextImpl.class);

    private final ExecutorService executorService;
    private final Socket socket;
    private final InterestOps interestOps;

    private final BufferLeasePool bufferLeasePool;
    private final Ingress ingress;
    private final Egress egress;

    EstablishedContextImpl(ExecutorService executorService, Socket socket, InterestOps interestOps) {
        this.interestOps = interestOps;
        this.executorService = executorService;
        this.socket = socket;

        this.bufferLeasePool = new BufferLeasePool();
        this.ingress = new IngressImpl(this, this.bufferLeasePool);
        this.egress = new EgressImpl(this);

    }

    @Override
    public void close() {
        try {
            interestOps.removeAll();
        }
        catch (CancelledKeyException cke) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("CancelledKeyException <{}> in close", cke.getMessage());
            }
        }

        try {
            ingress.close();
        }
        catch (Exception exception) {
            LOGGER.warn("FrameDelegate close threw exception <{}>", exception.getMessage());
        }

        try {
            socket.close();
        }
        catch (IOException ioe) {
            LOGGER.warn("IOException <{}> in close", ioe.getMessage());
        }

        bufferLeasePool.close();
    }

    @Override
    public AbstractSelectableChannel socketChannel() {
        return socket.socketChannel();
    }

    @Override
    public int initialSelectionKey() {
        return OP_READ;
    }

    @Override
    public void handleEvent(SelectionKey selectionKey) {
        // TODO refactor this into Strategy pattern
        if (!socket.getTransportInfo().getEncryptionInfo().isEncrypted()) {
            // plain connection, reads are reads, writes are writes

            if (selectionKey.isReadable()) { // perhaps track read/write needs here per direction too
                LOGGER.debug("handleEvent taking read");
                try {
                    interestOps.remove(OP_READ);
                }
                catch (CancelledKeyException cke) {
                    LOGGER
                            .warn(
                                    "CancelledKeyException <{}> in handleEvent. Closing connection for PeerAddress <{}> PeerPort <{}>",
                                    cke.getMessage(), socket.getTransportInfo().getPeerAddress(),
                                    socket.getTransportInfo().getPeerPort()
                            );
                    close();
                    return;
                }
                LOGGER.debug("handleEvent submitting new runnable for read");
                try {
                    executorService.submit(ingress);
                }
                catch (RejectedExecutionException ree) {
                    LOGGER.error("executorService.submit threw <{}> for read", ree.getMessage());
                    throw ree;
                }
                LOGGER.debug("handleEvent exiting read");
            }

            if (selectionKey.isWritable()) {
                LOGGER.debug("handleEvent taking write");
                try {
                    interestOps.remove(OP_WRITE);
                }
                catch (CancelledKeyException cke) {
                    LOGGER
                            .warn(
                                    "CancelledKeyException <{}> in handleEvent. Closing connection for PeerAddress <{}> PeerPort <{}>",
                                    cke.getMessage(), socket.getTransportInfo().getPeerAddress(),
                                    socket.getTransportInfo().getPeerPort()
                            );
                    close();
                    return;
                }
                LOGGER.debug("handleEvent submitting new runnable for write");
                try {
                    executorService.submit(egress);
                    LOGGER.debug("submitted write!");
                }
                catch (RejectedExecutionException ree) {
                    LOGGER.error("executorService.submit threw <{}> for write", ree.getMessage());
                    throw ree;
                }
                LOGGER.debug("handleEvent exiting write");
            }
        }
        else {
            // encrypted connections, reads may need writes too and vice versa
            if (selectionKey.isReadable()) {
                try {
                    interestOps.remove(OP_READ);
                }
                catch (CancelledKeyException cke) {
                    LOGGER
                            .warn(
                                    "CancelledKeyException <{}> in handleEvent. Closing connection for PeerAddress <{}> PeerPort <{}>",
                                    cke.getMessage(), socket.getTransportInfo().getPeerAddress(),
                                    socket.getTransportInfo().getPeerPort()
                            );
                    close();
                    return;
                }
                // socket write may be pending a tls read
                if (egress.needRead().compareAndSet(true, false)) {
                    executorService.submit(egress);
                }

                // read anyway
                executorService.submit(ingress);
            }

            if (selectionKey.isWritable()) {
                try {
                    interestOps.remove(OP_WRITE);
                }
                catch (CancelledKeyException cke) {
                    LOGGER
                            .warn(
                                    "CancelledKeyException <{}> in handleEvent. Closing connection for PeerAddress <{}> PeerPort <{}>",
                                    cke.getMessage(), socket.getTransportInfo().getPeerAddress(),
                                    socket.getTransportInfo().getPeerPort()
                            );
                    close();
                    return;
                }
                if (ingress.needWrite().compareAndSet(true, false)) {
                    // socket read may be pending a tls write
                    executorService.submit(ingress);
                }

                // write anyway
                executorService.submit(egress);
            }
        }
    }

    @Override
    public InterestOps interestOps() {
        return interestOps;
    }

    @Override
    public Socket socket() {
        return socket;
    }

    @Override
    public Ingress ingress() {
        return ingress;
    }

    @Override
    public Egress egress() {
        return egress;
    }

    @Override
    public boolean isStub() {
        return false;
    }

}
