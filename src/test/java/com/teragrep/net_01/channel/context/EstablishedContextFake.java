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

import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;

public class EstablishedContextFake implements EstablishedContext {

    private final InterestOps interestOps;
    private final Socket socket;

    private final Ingress ingress;
    private final Egress egress;

    EstablishedContextFake(InterestOps interestOps, Socket socket, Ingress ingress, Egress egress) {
        this.interestOps = interestOps;
        this.socket = socket;
        this.ingress = ingress;
        this.egress = egress;
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public AbstractSelectableChannel socketChannel() {
        return socket.socketChannel();
    }

    @Override
    public int initialSelectionKey() {
        return 0; // none
    }

    @Override
    public void handleEvent(SelectionKey selectionKey) {
        // no-op
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
