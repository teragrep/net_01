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
package com.teragrep.net_01.channel.buffer.access;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class Access implements Supplier<Lease> {

    private long accessCount;
    private boolean terminated;
    private final Lock lock;

    public Access() {
        this.accessCount = 0;
        this.terminated = false;
        this.lock = new ReentrantLock();
    }

    @Override
    public Lease get() {
        lock.lock();
        try {
            if (terminated()) {
                throw new IllegalStateException("Access already terminated");
            }

            accessCount++;
            return new Lease(this);
        }
        finally {
            lock.unlock();
        }
    }

    public void terminate() {
        if (lock.tryLock()) {
            try {
                if (accessCount != 0) {
                    throw new IllegalStateException("Open leases still exist");
                }
                else {
                    if (terminated) {
                        throw new IllegalStateException("Access already terminated");
                    }
                    terminated = true;
                }
            }
            finally {
                lock.unlock();
            }
        }
        else {
            throw new IllegalStateException("Lease operation in progress");
        }
    }

    public boolean terminated() {
        lock.lock();
        try {
            return terminated;
        }
        finally {
            lock.unlock();
        }
    }

    public void release(Lease lease) {
        if (lease.isOpen()) {
            throw new IllegalStateException("Can not be release an open lease");
        }
        lock.lock();
        try {
            long newAccessCount = accessCount - 1;
            if (newAccessCount < 0) {
                throw new IllegalStateException("AccessCount must not be negative");
            }
            accessCount = newAccessCount;
        }
        finally {
            lock.unlock();
        }
    }
}
