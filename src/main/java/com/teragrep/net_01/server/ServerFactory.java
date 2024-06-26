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
package com.teragrep.net_01.server;

import com.teragrep.net_01.channel.context.ClockFactory;
import com.teragrep.net_01.eventloop.EventLoop;
import com.teragrep.net_01.channel.context.ListenContext;
import com.teragrep.net_01.channel.context.ListenContextFactory;
import com.teragrep.net_01.channel.socket.SocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;

/**
 * Factory for creating {@link Server}s
 */
public final class ServerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerFactory.class);

    private final EventLoop eventLoop;
    private final ExecutorService executorService;
    private final SocketFactory socketFactory;
    private final ClockFactory clockFactory;

    /**
     * Primary constructor
     *
     * @param eventLoop       which {@link EventLoop} {@link ListenContext} will be registered with
     * @param executorService which {@link Server}s use to run received network connection events with
     * @param socketFactory   which is used to create {@link Server}'s connections
     * @param clockFactory    which is used to create clocks
     */
    public ServerFactory(
            EventLoop eventLoop,
            ExecutorService executorService,
            SocketFactory socketFactory,
            ClockFactory clockFactory
    ) {
        this.eventLoop = eventLoop;
        this.executorService = executorService;
        this.socketFactory = socketFactory;
        this.clockFactory = clockFactory;
    }

    public Server create(int port) throws IOException {

        ListenContextFactory listenContextFactory = new ListenContextFactory(
                executorService,
                socketFactory,
                clockFactory
        );

        ListenContext listenContext = listenContextFactory.open(new InetSocketAddress(port));
        LOGGER.debug("registering to eventLoop <{}>", eventLoop);
        eventLoop.register(listenContext);
        LOGGER.debug("registered to eventLoop <{}>", eventLoop);
        return new Server(listenContext);
    }
}
