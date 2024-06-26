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
package com.teragrep.net_01.eventloop;

import com.teragrep.net_01.channel.context.ConnectContext;
import com.teragrep.net_01.channel.context.EstablishedContext;
import com.teragrep.net_01.channel.context.Context;
import com.teragrep.net_01.channel.context.ListenContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link EventLoop} is used to {@link Selector#select()} events from network connections which are registered with it
 */
public final class EventLoop implements AutoCloseable, Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventLoop.class);

    private final Selector selector;
    private final ConcurrentLinkedQueue<Context> pendingContextRegistrations;
    private final AtomicBoolean stop;

    EventLoop(Selector selector) {
        this.selector = selector;

        this.pendingContextRegistrations = new ConcurrentLinkedQueue<>();
        this.stop = new AtomicBoolean();
    }

    /**
     * Register network connection with this {@link EventLoop}. Forces the {@link EventLoop} to run once after
     * registration.
     * 
     * @param context to register
     */
    public void register(Context context) {
        pendingContextRegistrations.add(context);
        wakeup();
    }

    private void registerPendingRegistrations() {
        while (true) {
            Context context = pendingContextRegistrations.poll();
            if (context != null) {
                try {
                    context.socketChannel().register(selector, context.initialSelectionKey(), context);
                }
                catch (ClosedChannelException closedChannelException) {
                    LOGGER.warn("attempted to register closed context <{}>", context);
                    context.close();
                }
            }
            else {
                // no more contexts
                break;
            }
        }
    }

    /**
     * Polls events from network connections via {@link Selector#select()}
     * 
     * @throws IOException If an I/O error occurs during select()
     */
    public void poll() throws IOException {
        int readyKeys = selector.select();

        registerPendingRegistrations();

        LOGGER.debug("readyKeys <{}>", readyKeys);

        Set<SelectionKey> selectionKeys = selector.selectedKeys();
        LOGGER.debug("selectionKeys <{}> ", selectionKeys);
        for (SelectionKey selectionKey : selectionKeys) {
            try {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER
                            .debug(
                                    "selectionKey <{}>: isValid <{}>, isConnectable <{}>, isAcceptable <{}>, isReadable <{}>, isWritable <{}>",
                                    selectionKey, selectionKey.isValid(), selectionKey.isConnectable(),
                                    selectionKey.isAcceptable(), selectionKey.isReadable(), selectionKey.isWritable()
                            );
                }
                if (selectionKey.isAcceptable()) {
                    // ListenContext
                    ListenContext listenContext = (ListenContext) selectionKey.attachment();
                    listenContext.handleEvent(selectionKey);
                }
                else if (selectionKey.isConnectable()) {
                    ConnectContext connectContext = (ConnectContext) selectionKey.attachment();
                    connectContext.handleEvent(selectionKey);
                }
                else { // (selectionKey.isReadable() || selectionKey.isWritable())
                    EstablishedContext establishedContext = (EstablishedContext) selectionKey.attachment();
                    try {
                        establishedContext.handleEvent(selectionKey);
                    }
                    catch (CancelledKeyException cke) {
                        if (!establishedContext.isStub()) {
                            establishedContext.close();
                        }
                        throw cke;

                    }
                }
            }
            catch (CancelledKeyException cke) {
                // TODO implement better exception handling in ConnectContext, ListenContext and EstablishedContext
                LOGGER.warn("Detected unexpectedly closed channel");
            }
        }

        selectionKeys.clear();
    }

    /**
     * Terminates the {@link EventLoop}
     */
    @Override
    public void close() {
        for (SelectionKey selectionKey : selector.keys()) {
            Context context = ((Context) selectionKey.attachment());
            context.close();
        }
        try {
            selector.close();
        }
        catch (IOException ioException) {
            LOGGER.warn("Selector.close() threw <{}>", ioException.getMessage(), ioException);
        }
    }

    /**
     * Forces the {@link EventLoop} to execute one cycle
     */
    public void wakeup() {
        selector.wakeup();
    }

    @Override
    public void run() {
        try {
            LOGGER.debug("Started");
            while (!stop.get()) {
                poll();
            }
        }
        catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
        finally {
            close();
        }
        LOGGER.debug("Stopped");
    }

    public void stop() {
        LOGGER.debug("stopping");
        stop.set(true);
        wakeup();
    }
}
