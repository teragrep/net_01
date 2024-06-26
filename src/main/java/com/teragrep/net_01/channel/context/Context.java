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

import java.io.Closeable;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;

/**
 * Context is a network connection element, it may be one of the: initiate {@link ConnectContext}, listen
 * {@link ListenContext} or established {@link EstablishedContext} types.
 */
public interface Context extends Closeable {

    /**
     * Handles this context's {@link SelectionKey} events. Providing a non-related key will result in non-foreseen
     * issues, and this is a programming error.
     *
     * @param selectionKey key of this context to handle an event for.
     */
    void handleEvent(SelectionKey selectionKey);

    /**
     * Closes the underlying network connection element and frees resources attached to it.
     */
    @Override
    void close(); // no exception is thrown

    /**
     * @return {@link AbstractSelectableChannel} of the network connection element.
     */
    AbstractSelectableChannel socketChannel();

    /**
     * @return initial state of the {@link SelectionKey} which the network connection element starts with.
     */
    int initialSelectionKey();
}
