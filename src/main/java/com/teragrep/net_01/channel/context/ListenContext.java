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

import com.teragrep.net_01.channel.socket.Socket;
import com.teragrep.net_01.channel.socket.SocketFactory;
import com.teragrep.net_01.eventloop.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.nio.channels.*;
import java.util.concurrent.ExecutorService;

/**
 * Listen type {@link Context} that produces {@link EstablishedContext} for receiving incoming connections. Use
 * {@link EventLoop#register(Context)} to register it to the desired {@link EventLoop}.
 */
public final class ListenContext implements Context {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListenContext.class);
    private final ServerSocketChannel serverSocketChannel;
    private final ExecutorService executorService;
    private final SocketFactory socketFactory;
    private final ClockFactory clockFactory;
    private final EstablishedContextStub establishedContextStub;

    ListenContext(
            ServerSocketChannel serverSocketChannel,
            ExecutorService executorService,
            SocketFactory socketFactory,
            ClockFactory clockFactory
    ) {
        this.serverSocketChannel = serverSocketChannel;
        this.executorService = executorService;
        this.socketFactory = socketFactory;
        this.clockFactory = clockFactory;
        this.establishedContextStub = new EstablishedContextStub();
    }

    @Override
    public void handleEvent(SelectionKey selectionKey) {
        try {
            if (selectionKey.isAcceptable()) {
                // create the client socket for a newly received connection

                SocketChannel clientSocketChannel = serverSocketChannel.accept();

                if (LOGGER.isDebugEnabled()) {
                    // getLocalAddress() can throw so log and ignore as that isn't hard error
                    try {
                        SocketAddress localAddress = serverSocketChannel.getLocalAddress();
                        SocketAddress remoteAddress = clientSocketChannel.getRemoteAddress();
                        LOGGER.debug("ServerSocket <{}> accepting ClientSocket <{}> ", localAddress, remoteAddress);
                    }
                    catch (IOException ioException) {
                        LOGGER.warn("Exception while getAddress", ioException);
                    }
                }

                // tls/plain wrapper
                Socket socket = socketFactory.create(clientSocketChannel);

                // non-blocking
                clientSocketChannel.configureBlocking(false);

                SelectionKey clientSelectionKey = clientSocketChannel
                        .register(
                                selectionKey.selector(), 0, // interestOps: none at this point
                                establishedContextStub
                        );

                InterestOps interestOps = new InterestOpsImpl(clientSelectionKey);

                EstablishedContext establishedContext = new EstablishedContextImpl(
                        executorService,
                        socket,
                        interestOps
                );

                clientSelectionKey.attach(establishedContext);

                establishedContext.ingress().register(clockFactory.create(establishedContext));
            }
        }
        catch (CancelledKeyException cke) {
            // thrown by accessing cancelled SelectionKey
            LOGGER.warn("SocketPoll.poll CancelledKeyException caught <{}>", cke.getMessage());
            try {
                selectionKey.channel().close();
            }
            catch (IOException ignored) {

            }
        }
        catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
    }

    @Override
    public void close() {
        if (LOGGER.isDebugEnabled()) {
            try {
                LOGGER.debug("close serverSocketChannel <{}>", serverSocketChannel.getLocalAddress());
            }
            catch (IOException ignored) {

            }
        }

        try {
            serverSocketChannel.close();
        }
        catch (IOException ioException) {
            LOGGER.warn("serverSocketChannel <{}> close threw", serverSocketChannel, ioException);
        }

    }

    @Override
    public ServerSocketChannel socketChannel() {
        return serverSocketChannel;
    }

    @Override
    public int initialSelectionKey() {
        return SelectionKey.OP_ACCEPT;
    }
}
