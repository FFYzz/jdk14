/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;

import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.ReservedStackAccess;

import java.util.concurrent.TimeUnit;

/**
 * A capability-based lock with three modes for controlling read/write
 * access.  The state of a StampedLock consists of a version and mode.
 * Lock acquisition methods return a stamp that represents and
 * controls access with respect to a lock state; "try" versions of
 * these methods may instead return the special value zero to
 * represent failure to acquire access. Lock release and conversion
 * methods require stamps as arguments, and fail if they do not match
 * the state of the lock. The three modes are:
 * <p>
 * 一个支持三种模式的基于容量的读写访问控制锁。
 * StampedLock 的 state 由 version 和 mode 组成。
 * 获取锁的方法会返回一个代表锁状态和访问控制的 stamp。
 * 带 try 的方法会以返回 0 代表获取失败。锁的释放和竞争需要将 stamps 作为参数传入。
 * 三种模式分别是：
 *
 * <ul>
 *
 *  <li><b>Writing.</b> Method {@link #writeLock} possibly blocks
 *   waiting for exclusive access, returning a stamp that can be used
 *   in method {@link #unlockWrite} to release the lock. Untimed and
 *   timed versions of {@code tryWriteLock} are also provided. When
 *   the lock is held in write mode, no read locks may be obtained,
 *   and all optimistic read validations will fail.
 * <p>
 *   写模式，{@link #writeLock} 方法在获取独占锁失败时会被阻塞。该方法返回的 stamp
 *   在调用 {@link #unlockWrite} 时会用到。同样提供了带超时版本和不带超时版本的
 *   {@code tryWriteLock} 方法。
 *   当锁在 write mode 下持有时，read locks 不会被持有，所有的 optimistic read validations
 *   都会失败。
 *
 *  <li><b>Reading.</b> Method {@link #readLock} possibly blocks
 *   waiting for non-exclusive access, returning a stamp that can be
 *   used in method {@link #unlockRead} to release the lock. Untimed
 *   and timed versions of {@code tryReadLock} are also provided.
 * <p>
 *   读模式，{@link #readLock} 方法在非独占访问的时候可能会被阻塞等待。该方法返回一个 stamp，
 *   该 stamp 在调用 {@link #unlockRead} 时会被用到。
 *
 *  <li><b>Optimistic Reading.</b> Method {@link #tryOptimisticRead}
 *   returns a non-zero stamp only if the lock is not currently held in
 *   write mode.  Method {@link #validate} returns true if the lock has not
 *   been acquired in write mode since obtaining a given stamp, in which
 *   case all actions prior to the most recent write lock release
 *   happen-before actions following the call to {@code tryOptimisticRead}.
 *   This mode can be thought of as an extremely weak version of a
 *   read-lock, that can be broken by a writer at any time.  The use of
 *   optimistic read mode for short read-only code segments often reduces
 *   contention and improves throughput.  However, its use is inherently
 *   fragile.  Optimistic read sections should only read fields and hold
 *   them in local variables for later use after validation. Fields read
 *   while in optimistic read mode may be wildly inconsistent, so usage
 *   applies only when you are familiar enough with data representations to
 *   check consistency and/or repeatedly invoke method {@code validate()}.
 *   For example, such steps are typically required when first reading an
 *   object or array reference, and then accessing one of its fields,
 *   elements or methods.
 * <p>
 *   乐观读模式，{@link #tryOptimisticRead} 返回一个非零的 stamp 仅当锁当前没有被
 *   write mode 下持有。
 *
 * </ul>
 *
 * <p>This class also supports methods that conditionally provide
 * conversions across the three modes. For example, method {@link
 * #tryConvertToWriteLock} attempts to "upgrade" a mode, returning
 * a valid write stamp if (1) already in writing mode (2) in reading
 * mode and there are no other readers or (3) in optimistic read mode
 * and the lock is available. The forms of these methods are designed to
 * help reduce some of the code bloat that otherwise occurs in
 * retry-based designs.
 *
 * <p>StampedLocks are designed for use as internal utilities 内部工具 in the
 * development of thread-safe components 线程安全的组件. Their use relies on
 * knowledge of the internal properties of the data, objects, and
 * methods they are protecting.  They are not reentrant 不可重入, so locked
 * bodies should not call other unknown methods that may try to
 * re-acquire locks (although you may pass a stamp to other methods
 * that can use or convert it).  The use of read lock modes relies on
 * the associated code sections being side-effect-free.  Unvalidated
 * optimistic read sections cannot call methods that are not known to
 * tolerate potential inconsistencies.  Stamps use finite
 * representations, and are not cryptographically secure (i.e., a
 * valid stamp may be guessable). Stamp values may recycle after (no
 * sooner than) one year of continuous operation. A stamp held without
 * use or validation for longer than this period may fail to validate
 * correctly.  StampedLocks are serializable, but always deserialize
 * into initial unlocked state, so they are not useful for remote
 * locking.
 *
 * <p>Like {@link java.util.concurrent.Semaphore Semaphore}, but unlike most
 * {@link Lock} implementations, StampedLocks have no notion of ownership.
 * 没有标记 ownership
 * Locks acquired in one thread can be released or converted 修改的 in another.
 *
 * <p>The scheduling policy of StampedLock does not consistently
 * prefer readers over writers or vice versa.  All "try" methods are
 * best-effort and do not necessarily conform to any scheduling or
 * fairness policy. A zero return from any "try" method for acquiring
 * or converting locks does not carry any information about the state
 * of the lock; a subsequent invocation may succeed.
 *
 * <p>Because it supports coordinated usage across multiple lock
 * modes, this class does not directly implement the {@link Lock} or
 * {@link ReadWriteLock} interfaces. However, a StampedLock may be
 * viewed {@link #asReadLock()}, {@link #asWriteLock()}, or {@link
 * #asReadWriteLock()} in applications requiring only the associated
 * set of functionality.
 *
 * <p><b>Memory Synchronization.</b> Methods with the effect of
 * successfully locking in any mode have the same memory
 * synchronization effects as a <em>Lock</em> action, as described in
 * Chapter 17 of <cite>The Java&trade; Language Specification</cite>.
 * Methods successfully unlocking in write mode have the same memory
 * synchronization effects as an <em>Unlock</em> action.  In optimistic
 * read usages, actions prior to the most recent write mode unlock action
 * are guaranteed to happen-before those following a tryOptimisticRead
 * only if a later validate returns true; otherwise there is no guarantee
 * that the reads between tryOptimisticRead and validate obtain a
 * consistent snapshot.
 *
 * <p><b>Sample Usage.</b> The following illustrates some usage idioms
 * in a class that maintains simple two-dimensional points. The sample
 * code illustrates some try/catch conventions even though they are
 * not strictly needed here because no exceptions can occur in their
 * bodies.
 *
 * <pre> {@code
 * class Point {
 *   private double x, y;
 *   private final StampedLock sl = new StampedLock();
 *
 *   // an exclusively locked method
 *   void move(double deltaX, double deltaY) {
 *     long stamp = sl.writeLock();
 *     try {
 *       x += deltaX;
 *       y += deltaY;
 *     } finally {
 *       sl.unlockWrite(stamp);
 *     }
 *   }
 *
 *   // a read-only method
 *   // upgrade from optimistic read to read lock
 *   double distanceFromOrigin() {
 *     long stamp = sl.tryOptimisticRead();
 *     try {
 *       retryHoldingLock: for (;; stamp = sl.readLock()) {
 *         if (stamp == 0L)
 *           continue retryHoldingLock;
 *         // possibly racy reads
 *         double currentX = x;
 *         double currentY = y;
 *         if (!sl.validate(stamp))
 *           continue retryHoldingLock;
 *         return Math.hypot(currentX, currentY);
 *       }
 *     } finally {
 *       if (StampedLock.isReadLockStamp(stamp))
 *         sl.unlockRead(stamp);
 *     }
 *   }
 *
 *   // upgrade from optimistic read to write lock
 *   void moveIfAtOrigin(double newX, double newY) {
 *     long stamp = sl.tryOptimisticRead();
 *     try {
 *       retryHoldingLock: for (;; stamp = sl.writeLock()) {
 *         if (stamp == 0L)
 *           continue retryHoldingLock;
 *         // possibly racy reads
 *         double currentX = x;
 *         double currentY = y;
 *         if (!sl.validate(stamp))
 *           continue retryHoldingLock;
 *         if (currentX != 0.0 || currentY != 0.0)
 *           break;
 *         stamp = sl.tryConvertToWriteLock(stamp);
 *         if (stamp == 0L)
 *           continue retryHoldingLock;
 *         // exclusive access
 *         x = newX;
 *         y = newY;
 *         return;
 *       }
 *     } finally {
 *       if (StampedLock.isWriteLockStamp(stamp))
 *         sl.unlockWrite(stamp);
 *     }
 *   }
 *
 *   // Upgrade read lock to write lock
 *   void moveIfAtOrigin(double newX, double newY) {
 *     long stamp = sl.readLock();
 *     try {
 *       while (x == 0.0 && y == 0.0) {
 *         long ws = sl.tryConvertToWriteLock(stamp);
 *         if (ws != 0L) {
 *           stamp = ws;
 *           x = newX;
 *           y = newY;
 *           break;
 *         }
 *         else {
 *           sl.unlockRead(stamp);
 *           stamp = sl.writeLock();
 *         }
 *       }
 *     } finally {
 *       sl.unlock(stamp);
 *     }
 *   }
 * }}</pre>
 *
 * @author Doug Lea
 * @jls 17.4 Memory Model
 * @since 1.8
 */
public class StampedLock implements java.io.Serializable {
    /*
     * Algorithmic notes:
     *
     * The design employs elements of Sequence locks 序列锁
     * (as used in linux kernels; see Lameter's
     * http://www.lameter.com/gelato2005.pdf
     * and elsewhere; see
     * Boehm's http://www.hpl.hp.com/techreports/2012/HPL-2012-68.html)
     * and Ordered RW locks (see Shirako et al
     * http://dl.acm.org/citation.cfm?id=2312015)
     *
     * Conceptually 概念上, the primary state of the lock includes a sequence
     * number that is odd 奇数 when write-locked and even 偶数 otherwise.
     * However, this is offset by a reader count that is non-zero when
     * read-locked.  The read count is ignored when validating
     * "optimistic" seqlock-reader-style stamps.  Because we must use
     * a small finite number of bits (currently 7) for readers, a
     * supplementary reader overflow word is used when the number of
     * readers exceeds the count field. We do this by treating the max
     * reader count value (RBITS) as a spinlock protecting overflow
     * updates.
     *
     * Waiters use a modified form of CLH lock used in
     * AbstractQueuedSynchronizer (AQS; see its internal documentation
     * for a fuller account), where each node is either a ReaderNode
     * or WriterNode. Implementation of queued Writer mode is
     * identical to AQS except for lock-state operations.  Sets of
     * waiting readers are grouped (linked) under a common node (field
     * cowaiters) so act as a single node with respect to most CLH
     * mechanics.  This simplifies the scheduling policy to a
     * mainly-FIFO scheme that incorporates elements of Phase-Fair
     * locks (see Brandenburg & Anderson, especially
     * http://www.cs.unc.edu/~bbb/diss/).  Method release does not
     * itself wake up cowaiters. This is done by the primary thread,
     * but helped by other cowaiters as they awaken.
     *
     * These rules apply to threads actually queued. Threads may also
     * try to acquire locks before or in the process of enqueueing
     * regardless of preference rules, and so may "barge" their way
     * in.  Methods writeLock and readLock (but not the other variants
     * of each) first unconditionally try to CAS state, falling back
     * to test-and-test-and-set retries on failure, slightly shrinking
     * race windows on initial attempts, thus making success more
     * likely. Also, when some threads cancel (via interrupt or
     * timeout), phase-fairness is at best roughly approximated.
     *
     * Nearly all of these mechanics are carried out in methods
     * acquireWrite and acquireRead, that, as typical of such code,
     * sprawl out because actions and retries rely on consistent sets
     * of locally cached reads.
     *
     * For an explanation of the use of acquireFence, see
     * http://gee.cs.oswego.edu/dl/html/j9mm.html as well as Boehm's
     * paper (above). Note that sequence validation (mainly method
     * validate()) requires stricter ordering rules than apply to
     * normal volatile reads (of "state").  To ensure that writeLock
     * acquisitions strictly precede subsequent writes in cases where
     * this is not already forced, we use a storeStoreFence.
     *
     * The memory layout keeps lock state and queue pointers together
     * (normally on the same cache line). This usually works well for
     * read-mostly loads. In most other cases, the natural tendency of
     * CLH locks to reduce memory contention lessens motivation to
     * further spread out contended locations, but might be subject to
     * future improvements.
     */

    private static final long serialVersionUID = -6001602636862214147L;

    /**
     * The number of bits to use for reader count before overflowing
     * <p>
     * 读计数的计数比特数，低七位
     * 最多的计数为 127
     */
    private static final int LG_READERS = 7; // 127 readers

    // Values for lock state and stamp operations
    /**
     * 读 单位
     * 因为是低 7 位，所以单位是 1.
     */
    private static final long RUNIT = 1L;
    /**
     * stamp = write mode
     * 写标志
     * = 00000000 00000000 00000000 00000000 00000000 00000000 00000000 10000000
     * = 128
     */
    private static final long WBIT = 1L << LG_READERS;
    /**
     * 读计数掩码
     * = 00000000 00000000 00000000 00000000 00000000 00000000 00000000 01111111
     * = 127
     */
    private static final long RBITS = WBIT - 1L;
    /**
     * 最大读的个数
     * = 00000000 00000000 00000000 00000000 00000000 00000000 00000000 01111110
     * = 126
     */
    private static final long RFULL = RBITS - 1L;
    /**
     * 读写掩码
     * ~ABITS = 11111111 11111111 11111111 11111111 11111111 11111111 11111111 00000000
     * 00000000 00000000 00000000 00000000 00000000 00000000 00000000 11111111
     * = 255
     */
    private static final long ABITS = RBITS | WBIT;
    /**
     * Stamp BITS
     * 读计数掩码取反
     * 11111111 11111111 11111111 11111111 11111111 11111111 11111111 10000000 补码
     * = -128
     */
    private static final long SBITS = ~RBITS; // note overlap with ABITS
    // not writing and conservatively non-overflowing
    /**
     * (3L << (LG_READERS - 1)) = 00000000 00000000 00000000 00000000 00000000 00000000 00000000 11000000
     * ~(3L << (LG_READERS - 1)) = 11111111 11111111 11111111 11111111 11111111 11111111 11111111 00111111
     * = -193
     * 在修改读的 state 的时候用来确保读的计数没有超出范围
     */
    private static final long RSAFE = ~(3L << (LG_READERS - 1));

    /*
     * 3 stamp modes can be distinguished 区分 by examining (m = stamp & ABITS):
     * write mode: m == WBIT
     * optimistic read mode: m == 0L (even when read lock is held)
     * read mode: m > 0L && m <= RFULL m >0L && m <= 126 (the stamp is a copy of state, but the
     * read hold count in the stamp is unused other than to determine mode)
     *
     * This differs slightly from the encoding of state:
     * (state & ABITS) == 0L indicates the lock is currently unlocked.
     * (state & ABITS) == RBITS is a special transient value
     * indicating spin-locked to manipulate reader bits overflow.
     */

    /**
     * Initial value for lock state; avoids failure value zero.
     * <p>
     * 锁状态的初始值
     * = 256
     * = 1 00000000
     */
    private static final long ORIGIN = WBIT << 1;

    // Special value from cancelled acquire methods so caller can throw IE
    /**
     * 特殊的标志位
     */
    private static final long INTERRUPTED = 1L;

    // Bits for Node.status
    /**
     * WAITING 状态标志
     */
    static final int WAITING = 1;
    /**
     * CANCELLED 状态标志
     * 1000 000...00
     */
    static final int CANCELLED = 0x80000000; // must be negative

    /**
     * CLH nodes
     */
    abstract static class Node {
        volatile Node prev;       // initially attached via casTail
        volatile Node next;       // visibly nonnull when signallable
        Thread waiter;            // visibly nonnull when enqueued
        volatile int status;      // written by owner, atomic bit ops by others

        // methods for atomic operations
        final boolean casPrev(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, PREV, c, v);
        }

        final boolean casNext(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, NEXT, c, v);
        }

        final int getAndUnsetStatus(int v) {     // for signalling
            return U.getAndBitwiseAndInt(this, STATUS, ~v);
        }

        final void setPrevRelaxed(Node p) {      // for off-queue assignment
            U.putReference(this, PREV, p);
        }

        final void setStatusRelaxed(int s) {     // for off-queue assignment
            U.putInt(this, STATUS, s);
        }

        final void clearStatus() {               // for reducing unneeded signals
            U.putIntOpaque(this, STATUS, 0);
        }

        private static final long STATUS
                = U.objectFieldOffset(Node.class, "status");
        private static final long NEXT
                = U.objectFieldOffset(Node.class, "next");
        private static final long PREV
                = U.objectFieldOffset(Node.class, "prev");
    }

    /**
     * 读节点
     */
    static final class WriterNode extends Node { // node for writers
    }

    /**
     * 写节点
     */
    static final class ReaderNode extends Node { // node for readers
        // 是一个单向链表
        volatile ReaderNode cowaiters;           // list of linked readers

        final boolean casCowaiters(ReaderNode c, ReaderNode v) {
            return U.weakCompareAndSetReference(this, COWAITERS, c, v);
        }

        final void setCowaitersRelaxed(ReaderNode p) {
            U.putReference(this, COWAITERS, p);
        }

        private static final long COWAITERS
                = U.objectFieldOffset(ReaderNode.class, "cowaiters");
    }

    /**
     * Head of CLH queue
     */
    private transient volatile Node head;
    /**
     * Tail (last) of CLH queue
     */
    private transient volatile Node tail;

    // views
    transient ReadLockView readLockView;
    transient WriteLockView writeLockView;
    transient ReadWriteLockView readWriteLockView;

    /**
     * Lock sequence/state
     */
    private transient volatile long state;
    /**
     * extra reader count when state read count saturated
     */
    private transient int readerOverflow;

    /**
     * Creates a new lock, initially in unlocked state.
     */
    public StampedLock() {
        state = ORIGIN;
    }

    // internal lock methods

    private boolean casState(long expect, long update) {
        return U.compareAndSetLong(this, STATE, expect, update);
    }

    @ReservedStackAccess
    private long tryAcquireWrite() {
        long s, nextState;
        // 如果当前的 state 为 ORIGIN = 256 && 成功将 state 修改为  s | WBIT
        // 如果上述条件都满足，则表示获取到 write
        // 能获取 write 的前提是当前的 state 为 ORIGIN
        if (((s = state) & ABITS) == 0L && casState(s, nextState = s | WBIT)) {
            U.storeStoreFence();
            return nextState;
        }
        return 0L;
    }

    @ReservedStackAccess
    private long tryAcquireRead() {
        for (long s, m, nextState; ; ) {
            // 当前未获取 write 且 读的计数小于 RFULL
            if ((m = (s = state) & ABITS) < RFULL) {
                if (casState(s, nextState = s + RUNIT))
                    return nextState;
                // 如果当前的 state 为写状态
            } else if (m == WBIT)
                return 0L;
                // 可能是 读 的计数超出了范围，尝试更新
            else if ((nextState = tryIncReaderOverflow(s)) != 0L)
                return nextState;
        }
    }

    /**
     * Returns an unlocked state, incrementing the version and
     * avoiding special failure value 0L.
     * 只有两个方法会调用该方法
     * {@link #releaseWrite(long)}
     * {@link #tryConvertToReadLock(long)}
     *
     * @param s a write-locked state (or stamp)
     */
    private static long unlockWriteState(long s) {
        return ((s += WBIT) == 0L) ? ORIGIN : s;
    }

    private long releaseWrite(long s) {
        long nextState = state = unlockWriteState(s);
        // 唤醒下一个节点
        signalNext(head);
        return nextState;
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a write stamp that can be used to unlock or convert mode
     */
    @ReservedStackAccess
    public long writeLock() {
        // try unconditional CAS confirming weak read
        long s = U.getLongOpaque(this, STATE) & ~ABITS, nextState;
        // 首先尝试 cas 设置 state 为 s | WBIT
        // 前提为当前的 s 值确实等于 U.getLongOpaque(this, STATE) & ~ABITS 且 能够通过 cas 更新成功
        if (casState(s, nextState = s | WBIT)) {
            U.storeStoreFence();
            return nextState;
        }
        // 获取失败会调用 acquireWrite 去获取
        return acquireWrite(false, false, 0L);
    }

    /**
     * Exclusively acquires the lock if it is immediately available.
     *
     * @return a write stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryWriteLock() {
        return tryAcquireWrite();
    }

    /**
     * Exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a write stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     *                              before acquiring the lock
     */
    public long tryWriteLock(long time, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(time);
        // 如果在获取之前被中断，直接抛 IE
        if (!Thread.interrupted()) {
            long nextState;
            // 先调用 tryAcquireWrite 尝试一个获取
            if ((nextState = tryAcquireWrite()) != 0L)
                return nextState;
            if (nanos <= 0L)
                return 0L;
            nextState = acquireWrite(true, true, System.nanoTime() + nanos);
            if (nextState != INTERRUPTED)
                return nextState;
        }
        throw new InterruptedException();
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     * 调用该方法的调用方可以根据返回值 判断是否获取成功，返回 0 表示获取失败。
     *
     * @return a write stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     *                              before acquiring the lock
     */
    public long writeLockInterruptibly() throws InterruptedException {
        long nextState;
        // 在获取之前当前线程未被中断 &&
        // 通过 tryAcquireWrite 获取成功 || 通过 acquireWrite 不是被 INTERRUPTED
        // acquireWrite 还有一个可能就是 acquire cancelled，返回 0。
        if (!Thread.interrupted() &&
                ((nextState = tryAcquireWrite()) != 0L ||
                        (nextState = acquireWrite(true, false, 0L)) != INTERRUPTED))
            return nextState;
        // 抛出中断异常
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a read stamp that can be used to unlock or convert mode
     */
    @ReservedStackAccess
    public long readLock() {
        // unconditionally optimistically try non-overflow case once
        // 可以获得到一个确保未 overflow 的 state 值
        long s = U.getLongOpaque(this, STATE) & RSAFE, nextState;
        // 先尝试一次未越界的更新
        if (casState(s, nextState = s + RUNIT))
            return nextState;
        else
            return acquireRead(false, false, 0L);
    }

    /**
     * Non-exclusively acquires the lock if it is immediately available.
     *
     * @return a read stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryReadLock() {
        return tryAcquireRead();
    }

    /**
     * Non-exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a read stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     *                              before acquiring the lock
     */
    public long tryReadLock(long time, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(time);
        // 检查中断
        if (!Thread.interrupted()) {
            long nextState;
            // 队列为空 && tryAcquireRead 获取成功
            if (tail == head && (nextState = tryAcquireRead()) != 0L)
                return nextState;
            if (nanos <= 0L)
                return 0L;
            nextState = acquireRead(true, true, System.nanoTime() + nanos);
            if (nextState != INTERRUPTED)
                return nextState;
        }
        // 若被中断，则抛出 IE
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a read stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     *                              before acquiring the lock
     */
    public long readLockInterruptibly() throws InterruptedException {
        long nextState;
        // 获取前检查中断 &&
        // tryAcquireRead 获取成功 || acquireRead 获取未被中断
        if (!Thread.interrupted() &&
                ((nextState = tryAcquireRead()) != 0L ||
                        (nextState = acquireRead(true, false, 0L)) != INTERRUPTED))
            return nextState;
        // 抛出 IE
        throw new InterruptedException();
    }

    /**
     * Returns a stamp that can later be validated, or zero
     * if exclusively locked.
     * <p>
     * 乐观读
     *
     * @return a valid optimistic read stamp, or zero if exclusively locked
     */
    public long tryOptimisticRead() {
        long s;
        // 是否已持有写锁
        // 已持有读锁的话表示已被独占，返回 0
        // 为持有写锁的话返回一个 stamp
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }

    /**
     * Returns true if the lock has not been exclusively acquired
     * since issuance of the given stamp. Always returns false if the
     * stamp is zero. Always returns true if the stamp represents a
     * currently held lock. Invoking this method with a value not
     * obtained from {@link #tryOptimisticRead} or a locking method
     * for this lock has no defined effect or result.
     * <p>
     * 验证是否发生修改
     *
     * @param stamp a stamp
     * @return {@code true} if the lock has not been exclusively acquired
     * since issuance of the given stamp; else false
     */
    public boolean validate(long stamp) {
        U.loadFence();
        return (stamp & SBITS) == (state & SBITS);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * exclusive lock.
     *
     * @param stamp a stamp returned by a write-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *                                      not match the current state of this lock
     */
    @ReservedStackAccess
    public void unlockWrite(long stamp) {
        if (state != stamp || (stamp & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        releaseWrite(stamp);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * non-exclusive lock.
     * <p>
     * 乐观读的锁释放
     *
     * @param stamp a stamp returned by a read-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *                                      not match the current state of this lock
     */
    @ReservedStackAccess
    public void unlockRead(long stamp) {
        long s, m;
        // 存在读锁
        if ((stamp & RBITS) != 0L) {
            // 验证通过 && 读锁计数 > 0
            while (((s = state) & SBITS) == (stamp & SBITS) &&
                    ((m = s & RBITS) != 0L)) {
                if (m < RFULL) {
                    if (casState(s, s - RUNIT)) {
                        if (m == RUNIT)
                            signalNext(head);
                        return;
                    }
                } else if (tryDecReaderOverflow(s) != 0L)
                    return;
            }
        }
        throw new IllegalMonitorStateException();
    }

    /**
     * If the lock state matches the given stamp, releases the
     * corresponding mode of the lock.
     * <p>
     * 根据 state 释放相应的锁
     *
     * @param stamp a stamp returned by a lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *                                      not match the current state of this lock
     */
    public void unlock(long stamp) {
        if ((stamp & WBIT) != 0L)
            unlockWrite(stamp);
        else
            unlockRead(stamp);
    }

    /**
     * If the lock state matches the given stamp, atomically performs one of
     * the following actions. If the stamp represents holding a write
     * lock, returns it.  Or, if a read lock, if the write lock is
     * available, releases the read lock and returns a write stamp.
     * Or, if an optimistic read, returns a write stamp only if
     * immediately available. This method returns zero in all other
     * cases.
     *
     * @param stamp a stamp
     * @return a valid write stamp, or zero on failure
     */
    public long tryConvertToWriteLock(long stamp) {
        long a = stamp & ABITS, m, s, nextState;
        // 当验证通过
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            // 无锁
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    // 直接返回
                    break;
                // 获取写锁
                if (casState(s, nextState = s | WBIT)) {
                    U.storeStoreFence();
                    return nextState;
                }
                // 如果是写锁，直接返回
            } else if (m == WBIT) {
                if (a != m)
                    break;
                return stamp;
                // 如果是读锁
            } else if (m == RUNIT && a != 0L) {
                // 转为写锁
                if (casState(s, nextState = s - RUNIT + WBIT))
                    return nextState;
            } else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp, atomically performs one of
     * the following actions. If the stamp represents holding a write
     * lock, releases it and obtains a read lock.  Or, if a read lock,
     * returns it. Or, if an optimistic read, acquires a read lock and
     * returns a read stamp only if immediately available. This method
     * returns zero in all other cases.
     *
     * @param stamp a stamp
     * @return a valid read stamp, or zero on failure
     */
    public long tryConvertToReadLock(long stamp) {
        long a, s, nextState;
        // match
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            // 如果持有写锁
            if ((a = stamp & ABITS) >= WBIT) {
                if (s != stamp) // write stamp
                    break;
                // 释放写锁后持有读锁
                nextState = state = unlockWriteState(s) + RUNIT;
                signalNext(head);
                return nextState;
                // 乐观读
            } else if (a == 0L) { // optimistic read stamp
                if ((s & ABITS) < RFULL) {
                    if (casState(s, nextState = s + RUNIT))
                        return nextState;
                } else if ((nextState = tryIncReaderOverflow(s)) != 0L)
                    return nextState;
                // 原来就是读锁
            } else { // already a read stamp
                if ((s & ABITS) == 0L)
                    break;
                return stamp;
            }
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp then, atomically, if the stamp
     * represents holding a lock, releases it and returns an
     * observation stamp.  Or, if an optimistic read, returns it if
     * validated. This method returns zero in all other cases, and so
     * may be useful as a form of "tryUnlock".
     *
     * @param stamp a stamp
     * @return a valid optimistic read stamp, or zero on failure
     */
    public long tryConvertToOptimisticRead(long stamp) {
        long a, m, s, nextState;
        U.loadFence();
        // match
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((a = stamp & ABITS) >= WBIT) {
                if (s != stamp)   // write stamp
                    break;
                return releaseWrite(s);
            } else if (a == 0L) { // already an optimistic read stamp
                return stamp;
            } else if ((m = s & ABITS) == 0L) { // invalid read stamp
                break;
            } else if (m < RFULL) {
                // 释放读锁
                if (casState(s, nextState = s - RUNIT)) {
                    if (m == RUNIT)
                        signalNext(head);
                    return nextState & SBITS;
                }
                // 释放越界读锁
            } else if ((nextState = tryDecReaderOverflow(s)) != 0L)
                return nextState & SBITS;
        }
        return 0L;
    }

    /**
     * Releases the write lock if it is held, without requiring a
     * stamp value. This method may be useful for recovery after
     * errors.
     *
     * @return {@code true} if the lock was held, else false
     */
    @ReservedStackAccess
    public boolean tryUnlockWrite() {
        long s;
        // 如果持有写锁
        if (((s = state) & WBIT) != 0L) {
            releaseWrite(s);
            return true;
        }
        return false;
    }

    /**
     * Releases one hold of the read lock if it is held, without
     * requiring a stamp value. This method may be useful for recovery
     * after errors.
     *
     * @return {@code true} if the read lock was held, else false
     */
    @ReservedStackAccess
    public boolean tryUnlockRead() {
        long s, m;
        // 持有读锁且不为写锁
        while ((m = (s = state) & ABITS) != 0L && m < WBIT) {
            // 未越界处理
            if (m < RFULL) {
                if (casState(s, s - RUNIT)) {
                    if (m == RUNIT)
                        signalNext(head);
                    return true;
                }
                // 越界处理
            } else if (tryDecReaderOverflow(s) != 0L)
                return true;
        }
        return false;
    }

    // status monitoring methods

    /**
     * Returns combined state-held and overflow read count for given
     * state s.
     */
    private int getReadLockCount(long s) {
        long readers;
        if ((readers = s & RBITS) >= RFULL)
            readers = RFULL + readerOverflow;
        return (int) readers;
    }

    /**
     * Returns {@code true} if the lock is currently held exclusively.
     *
     * @return {@code true} if the lock is currently held exclusively
     */
    public boolean isWriteLocked() {
        return (state & WBIT) != 0L;
    }

    /**
     * Returns {@code true} if the lock is currently held non-exclusively.
     *
     * @return {@code true} if the lock is currently held non-exclusively
     */
    public boolean isReadLocked() {
        return (state & RBITS) != 0L;
    }

    /**
     * Tells whether a stamp represents holding a lock exclusively.
     * This method may be useful in conjunction with
     * {@link #tryConvertToWriteLock}, for example: <pre> {@code
     * long stamp = sl.tryOptimisticRead();
     * try {
     *   ...
     *   stamp = sl.tryConvertToWriteLock(stamp);
     *   ...
     * } finally {
     *   if (StampedLock.isWriteLockStamp(stamp))
     *     sl.unlockWrite(stamp);
     * }}</pre>
     *
     * @param stamp a stamp returned by a previous StampedLock operation
     * @return {@code true} if the stamp was returned by a successful
     * write-lock operation
     * @since 10
     */
    public static boolean isWriteLockStamp(long stamp) {
        return (stamp & ABITS) == WBIT;
    }

    /**
     * Tells whether a stamp represents holding a lock non-exclusively.
     * This method may be useful in conjunction with
     * {@link #tryConvertToReadLock}, for example: <pre> {@code
     * long stamp = sl.tryOptimisticRead();
     * try {
     *   ...
     *   stamp = sl.tryConvertToReadLock(stamp);
     *   ...
     * } finally {
     *   if (StampedLock.isReadLockStamp(stamp))
     *     sl.unlockRead(stamp);
     * }}</pre>
     *
     * @param stamp a stamp returned by a previous StampedLock operation
     * @return {@code true} if the stamp was returned by a successful
     * read-lock operation
     * @since 10
     */
    public static boolean isReadLockStamp(long stamp) {
        return (stamp & RBITS) != 0L;
    }

    /**
     * Tells whether a stamp represents holding a lock.
     * This method may be useful in conjunction with
     * {@link #tryConvertToReadLock} and {@link #tryConvertToWriteLock},
     * for example: <pre> {@code
     * long stamp = sl.tryOptimisticRead();
     * try {
     *   ...
     *   stamp = sl.tryConvertToReadLock(stamp);
     *   ...
     *   stamp = sl.tryConvertToWriteLock(stamp);
     *   ...
     * } finally {
     *   if (StampedLock.isLockStamp(stamp))
     *     sl.unlock(stamp);
     * }}</pre>
     *
     * @param stamp a stamp returned by a previous StampedLock operation
     * @return {@code true} if the stamp was returned by a successful
     * read-lock or write-lock operation
     * @since 10
     */
    public static boolean isLockStamp(long stamp) {
        return (stamp & ABITS) != 0L;
    }

    /**
     * Tells whether a stamp represents a successful optimistic read.
     *
     * @param stamp a stamp returned by a previous StampedLock operation
     * @return {@code true} if the stamp was returned by a successful
     * optimistic read operation, that is, a non-zero return from
     * {@link #tryOptimisticRead()} or
     * {@link #tryConvertToOptimisticRead(long)}
     * @since 10
     */
    public static boolean isOptimisticReadStamp(long stamp) {
        return (stamp & ABITS) == 0L && stamp != 0L;
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     *
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return getReadLockCount(state);
    }

    /**
     * Returns a string identifying this lock, as well as its lock
     * state.  The state, in brackets, includes the String {@code
     * "Unlocked"} or the String {@code "Write-locked"} or the String
     * {@code "Read-locks:"} followed by the current number of
     * read-locks held.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        long s = state;
        return super.toString() +
                ((s & ABITS) == 0L ? "[Unlocked]" :
                        (s & WBIT) != 0L ? "[Write-locked]" :
                                "[Read-locks:" + getReadLockCount(s) + "]");
    }

    // views

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #readLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link Lock#newCondition()}
     * throws {@code UnsupportedOperationException}.
     * <p>
     * 返回一个 readLockView
     *
     * @return the lock
     */
    public Lock asReadLock() {
        ReadLockView v;
        if ((v = readLockView) != null) return v;
        return readLockView = new ReadLockView();
    }

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #writeLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link Lock#newCondition()}
     * throws {@code UnsupportedOperationException}.
     * <p>
     * 返回一个 writeLockView
     *
     * @return the lock
     */
    public Lock asWriteLock() {
        WriteLockView v;
        if ((v = writeLockView) != null) return v;
        return writeLockView = new WriteLockView();
    }

    /**
     * Returns a {@link ReadWriteLock} view of this StampedLock in
     * which the {@link ReadWriteLock#readLock()} method is mapped to
     * {@link #asReadLock()}, and {@link ReadWriteLock#writeLock()} to
     * {@link #asWriteLock()}.
     *
     * @return the lock
     */
    public ReadWriteLock asReadWriteLock() {
        ReadWriteLockView v;
        if ((v = readWriteLockView) != null) return v;
        return readWriteLockView = new ReadWriteLockView();
    }

    // view classes

    /**
     * 暴露给外部使用的 Read Lock 的 View
     */
    final class ReadLockView implements Lock {
        public void lock() {
            readLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }

        public boolean tryLock() {
            return tryReadLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit)
                throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }

        public void unlock() {
            unstampedUnlockRead();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 暴露给外部的 Write Lock 的 View
     */
    final class WriteLockView implements Lock {
        public void lock() {
            writeLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }

        public boolean tryLock() {
            return tryWriteLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit)
                throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }

        public void unlock() {
            unstampedUnlockWrite();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 暴露给外部的 Read Write Lock 的 View
     */
    final class ReadWriteLockView implements ReadWriteLock {
        public Lock readLock() {
            return asReadLock();
        }

        public Lock writeLock() {
            return asWriteLock();
        }
    }

    // Unlock methods without stamp argument checks for view classes.
    // Needed because view-class lock methods throw away stamps.

    final void unstampedUnlockWrite() {
        long s;
        // 如果未持有 write 锁，则抛异常
        if (((s = state) & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        releaseWrite(s);
    }

    final void unstampedUnlockRead() {
        long s, m;
        // 当存在读锁，读锁的计数 > 0
        while ((m = (s = state) & RBITS) > 0L) {
            // 如果没有越界
            if (m < RFULL) {
                if (casState(s, s - RUNIT)) {
                    if (m == RUNIT)
                        // 注意这里将 state 减到 1 之后才会去 signalNext
                        signalNext(head);
                    return;
                }
                // 如果越界了，交给 tryDecReaderOverflow 处理
            } else if (tryDecReaderOverflow(s) != 0L)
                return;
        }
        throw new IllegalMonitorStateException();
    }

    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        state = ORIGIN; // reset to unlocked state
    }

    // overflow handling methods

    /**
     * Tries to increment readerOverflow by first setting state
     * access bits value to RBITS, indicating hold of spinlock,
     * then updating, then releasing.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryIncReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        // 可能中途其他线程释放了，因此自旋等待一段时间
        if ((s & ABITS) != RFULL)
            Thread.onSpinWait();
            // 首先尝试将 state 更新为 RBITS
        else if (casState(s, s | RBITS)) {
            // 超出的计数变量 + 1
            ++readerOverflow;
            return state = s;
        }
        return 0L;
    }

    /**
     * Tries to decrement readerOverflow.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryDecReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) != RFULL)
            Thread.onSpinWait();
        else if (casState(s, s | RBITS)) {
            int r;
            long nextState;
            // 如果 readerOverflow 还在使用
            if ((r = readerOverflow) > 0) {
                readerOverflow = r - 1;
                nextState = s;
                // 对 state 进行更新
            } else
                nextState = s - RUNIT;
            return state = nextState;
        }
        return 0L;
    }

    // release methods

    /**
     * Wakes up the successor of given node, if one exists, and unsets its
     * WAITING status to avoid park race. This may fail to wake up an
     * eligible thread when one or more have been cancelled, but
     * cancelAcquire ensures liveness.
     */
    static final void signalNext(Node h) {
        Node s;
        if (h != null && (s = h.next) != null && s.status > 0) {
            s.getAndUnsetStatus(WAITING);
            LockSupport.unpark(s.waiter);
        }
    }

    /**
     * Removes and unparks all cowaiters of node, if it exists.
     */
    private static void signalCowaiters(ReaderNode node) {
        if (node != null) {
            for (ReaderNode c; (c = node.cowaiters) != null; ) {
                // 从 cowaiters 链表中移除 c
                if (node.casCowaiters(c, c.cowaiters))
                    // 将 c 绑定的线程进行唤醒
                    LockSupport.unpark(c.waiter);
            }
        }
    }

    // queue link methods
    private boolean casTail(Node c, Node v) {
        return U.compareAndSetReference(this, TAIL, c, v);
    }

    /**
     * tries once to CAS a new dummy node for head
     */
    private void tryInitializeHead() {
        Node h = new WriterNode();
        // 同时更新 head 和 tail
        if (U.compareAndSetReference(this, HEAD, null, h))
            tail = h;
    }

    /**
     * For explanation, see above and AbstractQueuedSynchronizer
     * internal documentation.
     *
     * @param interruptible true if should check interrupts and if so
     *                      return INTERRUPTED
     * @param timed         if true use timed waits
     * @param time          the System.nanoTime value to timeout at (and return zero)
     * @return next state, or INTERRUPTED
     */
    private long acquireWrite(boolean interruptible, boolean timed, long time) {
        byte spins = 0, postSpins = 0;   // retries upon unpark of first thread
        // first: 是否为队列中的第一个有效节点，不是 head 指向的节点
        boolean interrupted = false, first = false;
        WriterNode node = null;
        Node pred = null;
        for (long s, nextState; ; ) {
            // 当 node 已经进队，且不是队列中的第一个有效节点时，会进入该判断条件
            if (!first && (pred = (node == null) ? null : node.prev) != null &&
                    // 只有这一个地方可以更新 first
                    !(first = (head == pred))) {
                // 表示前驱节点 cancelled
                if (pred.status < 0) {
                    // 需要清理队列
                    cleanQueue();           // predecessor cancelled
                    continue;
                    // 成为了 head 节点
                } else if (pred.prev == null) {
                    Thread.onSpinWait();    // ensure serialization
                    continue;
                }
            }
            if ((first || pred == null) && ((s = state) & ABITS) == 0L &&
                    casState(s, nextState = s | WBIT)) {
                // 进入这里表示获取 write lock 成功
                U.storeStoreFence();
                if (first) {
                    node.prev = null;
                    head = node;
                    pred.next = null;
                    node.waiter = null;
                    if (interrupted)
                        // 主动中断并清除中断标志
                        Thread.currentThread().interrupt();
                }
                return nextState;
            } else if (node == null) {          // retry before enqueuing
                // 在进队列之前将 node 初始化。走上面的条件再重试一次。
                node = new WriterNode();
            } else if (pred == null) {          // try to enqueue
                // 进队
                Node t = tail;
                node.setPrevRelaxed(t);
                // 队列未初始化
                if (t == null)
                    tryInitializeHead();
                else if (!casTail(t, node))
                    // cas 更新 tail 失败，则回滚 pre 指针
                    node.setPrevRelaxed(null);  // back out
                else
                    // cas 更新成功，则将原来的 tail 节点的 next 指向新加入的 node
                    // 入队成功
                    t.next = node;
                // 后续的 if 都是在 node 已经入队的情况
            } else if (first && spins != 0) {   // reduce unfairness
                // 自旋等待
                --spins;
                Thread.onSpinWait();
            } else if (node.status == 0) {      // enable signal
                // 设置 waiter 属性
                if (node.waiter == null)
                    node.waiter = Thread.currentThread();
                // 设置状态为 WAITING
                node.status = WAITING;
            } else {
                long nanos;
                // 自旋次数变化规律:1->3->7->15->31->63->127->255
                spins = postSpins = (byte) ((postSpins << 1) | 1);
                // 如果未设置了等待超时
                if (!timed)
                    // 直接 park
                    LockSupport.park(this);
                    // 阻塞 nanos 时间
                else if ((nanos = time - System.nanoTime()) > 0L)
                    LockSupport.parkNanos(this, nanos);
                else
                    // break 之后就到外面调用 cancelAcquire
                    break;
                node.clearStatus();
                // 检查是否被中断 以及判断当前的 acquire 是否可中断
                // 如果当前 acquire 可中断且被中断了，则直接 break，然后取消获取
                if ((interrupted |= Thread.interrupted()) && interruptible)
                    break;
            }
        }
        // 取消获取
        return cancelAcquire(node, interrupted);
    }

    /**
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     *                      return INTERRUPTED
     * @param timed         if true use timed waits
     * @param time          the System.nanoTime value to timeout at (and return zero)
     * @return next state, or INTERRUPTED
     */
    private long acquireRead(boolean interruptible, boolean timed, long time) {
        boolean interrupted = false;
        ReaderNode node = null;
        /*
         * Loop:
         *   if empty, try to acquire
         *   if tail is Reader, try to cowait; restart if leader stale or cancels
         *   else try to create and enqueue node, and wait in 2nd loop below
         */
        for (; ; ) {
            ReaderNode leader;
            long nextState;
            Node tailPred = null, t = tail;
            // 队列为空，调用 tryAcquireRead 尝试获取
            if ((t == null || (tailPred = t.prev) == null) &&
                    (nextState = tryAcquireRead()) != 0L) // try now if empty
                return nextState;
                // 初始化队列
            else if (t == null)
                tryInitializeHead();
                // 队列中只有一个 head 节点 或者
                // tail 尾节点若不是 ReaderNode 类型
            else if (tailPred == null || !(t instanceof ReaderNode)) {
                if (node == null)
                    // 初始化 node 节点
                    node = new ReaderNode();
                // 加入队尾
                if (tail == t) {
                    node.setPrevRelaxed(t);
                    if (casTail(t, node)) {
                        t.next = node;
                        break; // node is leader; wait in loop below
                    }
                    // 上面 cas 更新失败之后回滚
                    node.setPrevRelaxed(null);
                }
                // 如果队尾是 ReaderNode 类型节点
            } else if ((leader = (ReaderNode) t) == tail) { // try to cowait
                for (boolean attached = false; ; ) {
                    // leader 被 cancelled 或者 leader 为 head 节点
                    if (leader.status < 0 || leader.prev == null)
                        break;
                        // node 还未初始化
                    else if (node == null)
                        // 初始化
                        node = new ReaderNode();
                        // 绑定线程
                    else if (node.waiter == null)
                        node.waiter = Thread.currentThread();
                        // 将 leader 的 cowaiters 指向新加入的 node，将 node 的 cowaiters 指向 leader 原来的 cowaiters
                    else if (!attached) {
                        ReaderNode c = leader.cowaiters;
                        node.setCowaitersRelaxed(c);
                        attached = leader.casCowaiters(c, node);
                        if (!attached)
                            node.setCowaitersRelaxed(null);
                    } else {
                        long nanos = 0L;
                        if (!timed)
                            LockSupport.park(this);
                        else if ((nanos = time - System.nanoTime()) > 0L)
                            LockSupport.parkNanos(this, nanos);
                        interrupted |= Thread.interrupted();
                        // 如果支持中断且被中断 或者 超时
                        if ((interrupted && interruptible) ||
                                (timed && nanos <= 0L))
                            // 取消 Cowaiter
                            return cancelCowaiter(node, leader, interrupted);
                    }
                }
                if (node != null)
                    node.waiter = null;
                long ns = tryAcquireRead();
                signalCowaiters(leader);
                if (interrupted)
                    Thread.currentThread().interrupt();
                if (ns != 0L)
                    return ns;
                else
                    node = null; // restart if stale, missed, or leader cancelled
            }
        }

        // node is leader of a cowait group; almost same as acquireWrite
        byte spins = 0, postSpins = 0;   // retries upon unpark of first thread
        // pred 是否为 head 节点
        boolean first = false;
        Node pred = null;
        for (long nextState; ; ) {
            if (!first && (pred = node.prev) != null &&
                    !(first = (head == pred))) {
                if (pred.status < 0) {
                    cleanQueue();           // predecessor cancelled
                    continue;
                } else if (pred.prev == null) {
                    Thread.onSpinWait();    // ensure serialization
                    continue;
                }
            }
            // pred 是 head 节点或者 pred 是 null
            if ((first || pred == null) &&
                    // 尝试获取成功
                    (nextState = tryAcquireRead()) != 0L) {
                if (first) {
                    // node 成为 head 节点
                    node.prev = null;
                    head = node;
                    pred.next = null;
                    node.waiter = null;
                }
                // 唤醒 node 的 cowaiters
                signalCowaiters(node);
                if (interrupted)
                    Thread.currentThread().interrupt();
                return nextState;
            } else if (first && spins != 0) {
                --spins;
                Thread.onSpinWait();
                // 未初始化，进行初始化
            } else if (node.status == 0) {
                if (node.waiter == null)
                    node.waiter = Thread.currentThread();
                node.status = WAITING;
            } else {
                long nanos;
                // 自旋次数:1->3->7->15 ....
                spins = postSpins = (byte) ((postSpins << 1) | 1);
                if (!timed)
                    LockSupport.park(this);
                else if ((nanos = time - System.nanoTime()) > 0L)
                    LockSupport.parkNanos(this, nanos);
                else
                    break;
                node.clearStatus();
                if ((interrupted |= Thread.interrupted()) && interruptible)
                    break;
            }
        }
        // 取消获取
        return cancelAcquire(node, interrupted);
    }

    // Cancellation support

    /**
     * Possibly repeatedly traverses from tail, unsplicing cancelled
     * nodes until none are found. Unparks nodes that may have been
     * relinked to be next eligible acquirer.
     * <p>
     * 只处理一个 cancelled 节点
     */
    private void cleanQueue() {
        for (; ; ) {                               // restart point
            // 从 tail 开始往前处理
            for (Node q = tail, s = null, p, n; ; ) { // (p, q, s) triples
                // 队列为空 || q 为 head 节点
                // p 表示 当前节点的前驱节点
                // s 表示 当前节点的后继节点，如果 s 为 null，表示当前节点指向 tail 节点
                if (q == null || (p = q.prev) == null)
                    // 不处理，直接 break
                    return;                      // end of list
                //
                if (s == null ? tail != q : (s.prev != q || s.status < 0))
                    // 已被其他线程处理
                    break;                       // inconsistent
                // 节点 q 被取消，找到了 cancelled 的节点，处理完就退出
                if (q.status < 0) {              // cancelled
                    // 判断当前节点是否为 tail 节点，如果是的话，则更新 tail，如果不是的话则更新后继节点的 pre 指向
                    if ((s == null ? casTail(q, p) : s.casPrev(q, p)) &&
                            q.prev == p) {
                        p.casNext(q, s);         // OK if fails
                        // 如果 p 是 head 节点了，那么唤醒下一个节点
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                // 可以处理上面的  OK if fails
                if ((n = p.next) != q) {         // help finish
                    if (n != null && q.prev == p && q.status >= 0) {
                        p.casNext(n, q);
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                // 往前找
                s = q;
                q = q.prev;
            }
        }
    }

    /**
     * If leader exists, possibly repeatedly traverses cowaiters,
     * unsplicing the given cancelled node until not found.
     * <p>
     * 将 node 从 cowaiter 链表中删去
     */
    private void unlinkCowaiter(ReaderNode node, ReaderNode leader) {
        if (leader != null) {
            while (leader.prev != null && leader.status >= 0) {
                // 往后找
                for (ReaderNode p = leader, q; ; p = q) {
                    if ((q = p.cowaiters) == null)
                        return;
                    // 找到 node，进行替换
                    if (q == node) {
                        p.casCowaiters(q, q.cowaiters);
                        break;  // recheck even if succeeded
                    }
                }
            }
        }
    }

    /**
     * If node non-null, forces cancel status and unsplices it from
     * queue, wakes up any cowaiters, and possibly wakes up successor
     * to recheck status.
     *
     * @param node        the waiter (may be null if not yet enqueued)
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    private long cancelAcquire(Node node, boolean interrupted) {
        if (node != null) {
            node.waiter = null;
            node.status = CANCELLED;
            // 清理队列
            cleanQueue();
            // 如果是 ReaderNode，还需额外处理
            if (node instanceof ReaderNode)
                signalCowaiters((ReaderNode) node);
        }
        // 返回是否是中断取消
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    /**
     * If node non-null, forces cancel status and unsplices from
     * leader's cowaiters list unless/until it is also cancelled.
     *
     * @param node        if non-null, the waiter
     * @param leader      if non-null, the node heading cowaiters list
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    private long cancelCowaiter(ReaderNode node, ReaderNode leader,
                                boolean interrupted) {
        if (node != null) {
            node.waiter = null;
            node.status = CANCELLED;
            unlinkCowaiter(node, leader);
        }
        // 返回是否由于中断取消
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    // Unsafe
    // VarHandle
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long STATE
            = U.objectFieldOffset(StampedLock.class, "state");
    private static final long HEAD
            = U.objectFieldOffset(StampedLock.class, "head");
    private static final long TAIL
            = U.objectFieldOffset(StampedLock.class, "tail");

    static {
        Class<?> ensureLoaded = LockSupport.class;
    }
}
