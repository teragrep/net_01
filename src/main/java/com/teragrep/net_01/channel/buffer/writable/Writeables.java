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
package com.teragrep.net_01.channel.buffer.writable;

import java.nio.ByteBuffer;

public final class Writeables implements Writeable {

    private final Writeable[] writeables;

    public Writeables(Writeable ... writeables) {
        this.writeables = writeables;
    }

    @Override
    public ByteBuffer[] buffers() {
        long totalBuffers = 0;
        for (Writeable writeable : writeables) {
            totalBuffers = totalBuffers + writeable.buffers().length;
        }

        if (totalBuffers > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Too many writeable buffers, exceeded Integer.MAX_VALUE <" + Integer.MAX_VALUE + ">"
            );
        }

        ByteBuffer[] bufferArray = new ByteBuffer[(int) totalBuffers];
        int written = 0;
        for (final Writeable writeable : writeables) {
            int bufferLength = writeable.buffers().length;
            System.arraycopy(writeable.buffers(), 0, bufferArray, written, bufferLength);
            written += bufferLength;
        }

        return bufferArray;
    }

    @Override
    public boolean hasRemaining() {
        boolean hasRemaining = false;
        for (Writeable writeable : writeables) {
            if (writeable.hasRemaining()) {
                hasRemaining = true;
                break;
            }
        }
        return hasRemaining;
    }

    @Override
    public boolean isStub() {
        return false;
    }

    @Override
    public void close() {
        for (Writeable writeable : writeables) {
            writeable.close();
        }
    }

}
