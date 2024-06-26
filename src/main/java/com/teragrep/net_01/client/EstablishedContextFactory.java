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
package com.teragrep.net_01.client;

import com.teragrep.net_01.channel.context.ClockFactory;
import com.teragrep.net_01.channel.context.ConnectContext;
import com.teragrep.net_01.channel.context.ConnectContextFactory;
import com.teragrep.net_01.channel.context.EstablishedContext;
import com.teragrep.net_01.eventloop.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Factory for creating an {@link EstablishedContext} also known as a connection initiation or a client.
 */
public final class EstablishedContextFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(EstablishedContextFactory.class);
    private final ConnectContextFactory connectContextFactory;
    private final EventLoop eventLoop;
    private final ClockFactory clockFactory;

    /**
     * Main for Constructor for {@link EstablishedContextFactory}
     * 
     * @param connectContextFactory {@link ConnectContextFactory} for creating new connections
     * @param eventLoop             {@link EventLoop} to register new connections with
     */
    public EstablishedContextFactory(
            ConnectContextFactory connectContextFactory,
            EventLoop eventLoop,
            ClockFactory clockFactory
    ) {
        this.connectContextFactory = connectContextFactory;
        this.eventLoop = eventLoop;
        this.clockFactory = clockFactory;
    }

    /**
     * Opens up a new connection. Registers the connection to provided {@link EventLoop}. Note that the
     * {@link EventLoop} needs to run in order to proceed with the connection.
     * 
     * @param inetSocketAddress destination {@link InetSocketAddress} to connect to.
     * @return an {@link EstablishedContext} {@link CompletableFuture}.
     */
    public CompletableFuture<EstablishedContext> open(InetSocketAddress inetSocketAddress) {
        // this is for returning ready connection
        CompletableFuture<EstablishedContext> readyContextFuture = new CompletableFuture<>();
        Consumer<EstablishedContext> establishedContextConsumer = readyContextFuture::complete;

        ConnectContext connectContext;
        try {
            connectContext = connectContextFactory.create(inetSocketAddress, clockFactory, establishedContextConsumer);
            LOGGER.debug("registering to eventLoop <{}>", eventLoop);
            eventLoop.register(connectContext);
            LOGGER.debug("registered to eventLoop <{}>", eventLoop);
        }
        catch (IOException ioException) {
            readyContextFuture.completeExceptionally(ioException);
        }

        return readyContextFuture;
    }
}
