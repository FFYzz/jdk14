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

import jdk.internal.vm.annotation.ReservedStackAccess;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * An implementation of {@link ReadWriteLock} supporting similar
 * semantics to {@link ReentrantLock}.
 * <p>This class has the following properties:
 * <p>
 * {@link ReadWriteLock} 接口的实现，功能类似于 {@link ReentrantLock}
 *
 * <ul>
 * <li><b>Acquisition order</b>
 * <p>
 * 获取顺序，支持公平与非公平
 *
 * <p>This class does not impose a reader or writer preference
 * ordering for lock access.  However, it does support an optional
 * <em>fairness</em> policy.
 *
 * <dl>
 * <dt><b><i>Non-fair mode (default)</i></b>
 * <dd>When constructed as non-fair (the default), the order of entry
 * to the read and write lock is unspecified, subject to reentrancy
 * constraints.  A nonfair lock that is continuously contended may
 * indefinitely 模糊的/无限期的 postpone 延期/延迟 one or more reader or writer threads, but
 * will normally have higher throughput than a fair lock.
 *
 * <dt><b><i>Fair mode</i></b>
 * <dd>When constructed as fair, threads contend for entry using an
 * approximately arrival-order policy 到达顺序策略. When the currently held lock
 * is released, either the longest-waiting single writer thread will
 * be assigned the write lock, or if there is a group of reader threads
 * waiting longer than all waiting writer threads, that group will be
 * assigned the read lock.
 *
 * <p>A thread that tries to acquire a fair read lock (non-reentrantly)
 * will block if either the write lock is held, or there is a waiting
 * writer thread. The thread will not acquire the read lock until
 * after the oldest currently waiting writer thread has acquired and
 * released the write lock. Of course, if a waiting writer abandons
 * its wait, leaving one or more reader threads as the longest waiters
 * in the queue with the write lock free, then those readers will be
 * assigned the read lock.
 *
 * <p>A thread that tries to acquire a fair write lock (non-reentrantly)
 * will block unless both the read lock and write lock are free (which
 * implies there are no waiting threads).  (Note that the non-blocking
 * {@link ReadLock#tryLock()} and {@link WriteLock#tryLock()} methods
 * do not honor this fair setting and will immediately acquire the lock
 * if it is possible, regardless of waiting threads.)
 * </dl>
 *
 * <li><b>Reentrancy</b>
 *
 * <p>This lock allows both readers and writers to reacquire read or
 * write locks in the style of a {@link ReentrantLock}. Non-reentrant
 * readers are not allowed until all write locks held by the writing
 * thread have been released.
 *
 * <p>Additionally, a writer can acquire the read lock, but not
 * vice-versa.  Among other applications, reentrancy can be useful
 * when write locks are held during calls or callbacks to methods that
 * perform reads under read locks.  If a reader tries to acquire the
 * write lock it will never succeed.
 *
 * <li><b>Lock downgrading</b>
 * <p>Reentrancy also allows downgrading from the write lock to a read lock,
 * by acquiring the write lock, then the read lock and then releasing the
 * write lock. However, upgrading from a read lock to the write lock is
 * <b>not</b> possible.
 *
 * <li><b>Interruption of lock acquisition</b>
 * <p>The read lock and write lock both support interruption during lock
 * acquisition.
 *
 * <li><b>{@link Condition} support</b>
 * <p>The write lock provides a {@link Condition} implementation that
 * behaves in the same way, with respect to the write lock, as the
 * {@link Condition} implementation provided by
 * {@link ReentrantLock#newCondition} does for {@link ReentrantLock}.
 * This {@link Condition} can, of course, only be used with the write lock.
 *
 * <p>The read lock does not support a {@link Condition} and
 * {@code readLock().newCondition()} throws
 * {@code UnsupportedOperationException}.
 *
 * <li><b>Instrumentation</b>
 * <p>This class supports methods to determine whether locks
 * are held or contended. These methods are designed for monitoring
 * system state, not for synchronization control.
 * </ul>
 *
 * <p>Serialization of this class behaves in the same way as built-in
 * locks: a deserialized lock is in the unlocked state, regardless of
 * its state when serialized.
 *
 * <p><b>Sample usages</b>. Here is a code sketch showing how to perform
 * lock downgrading after updating a cache (exception handling is
 * particularly tricky when handling multiple locks in a non-nested
 * fashion):
 *
 * <pre> {@code
 * class CachedData {
 *   Object data;
 *   boolean cacheValid;
 *   final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
 *
 *   void processCachedData() {
 *     rwl.readLock().lock();
 *     if (!cacheValid) {
 *       // Must release read lock before acquiring write lock
 *       rwl.readLock().unlock();
 *       rwl.writeLock().lock();
 *       try {
 *         // Recheck state because another thread might have
 *         // acquired write lock and changed state before we did.
 *         if (!cacheValid) {
 *           data = ...
 *           cacheValid = true;
 *         }
 *         // Downgrade by acquiring read lock before releasing write lock
 *         rwl.readLock().lock();
 *       } finally {
 *         rwl.writeLock().unlock(); // Unlock write, still hold read
 *       }
 *     }
 *
 *     try {
 *       use(data);
 *     } finally {
 *       rwl.readLock().unlock();
 *     }
 *   }
 * }}</pre>
 * <p>
 * ReentrantReadWriteLocks can be used to improve concurrency in some
 * uses of some kinds of Collections. This is typically worthwhile
 * only when the collections are expected to be large, accessed by
 * more reader threads than writer threads, and entail operations with
 * overhead that outweighs synchronization overhead. For example, here
 * is a class using a TreeMap that is expected to be large and
 * concurrently accessed.
 *
 * <pre> {@code
 * class RWDictionary {
 *   private final Map<String, Data> m = new TreeMap<>();
 *   private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
 *   private final Lock r = rwl.readLock();
 *   private final Lock w = rwl.writeLock();
 *
 *   public Data get(String key) {
 *     r.lock();
 *     try { return m.get(key); }
 *     finally { r.unlock(); }
 *   }
 *   public List<String> allKeys() {
 *     r.lock();
 *     try { return new ArrayList<>(m.keySet()); }
 *     finally { r.unlock(); }
 *   }
 *   public Data put(String key, Data value) {
 *     w.lock();
 *     try { return m.put(key, value); }
 *     finally { w.unlock(); }
 *   }
 *   public void clear() {
 *     w.lock();
 *     try { m.clear(); }
 *     finally { w.unlock(); }
 *   }
 * }}</pre>
 *
 * <h2>Implementation Notes</h2>
 *
 * <p>This lock supports a maximum of 65535 recursive write locks
 * and 65535 read locks. Attempts to exceed these limits result in
 * {@link Error} throws from locking methods.
 *
 * @author Doug Lea
 * @since 1.5
 */
public class ReentrantReadWriteLock
        implements ReadWriteLock, java.io.Serializable {
    private static final long serialVersionUID = -6992448646407690164L;
    /**
     * Inner class providing readlock
     * <p>
     * 内部类，读锁/共享锁
     */
    private final ReentrantReadWriteLock.ReadLock readerLock;
    /**
     * Inner class providing writelock
     * <p>
     * 内部类，写锁/排它锁/独占锁
     */
    private final ReentrantReadWriteLock.WriteLock writerLock;
    /**
     * Performs all synchronization mechanics
     * <p>
     * 同步器
     */
    final Sync sync;

    /**
     * Creates a new {@code ReentrantReadWriteLock} with
     * default (nonfair) ordering properties.
     * <p>
     * 构造方法，默认为非公平锁
     */
    public ReentrantReadWriteLock() {
        this(false);
    }

    /**
     * Creates a new {@code ReentrantReadWriteLock} with
     * the given fairness policy.
     * <p>
     * 构造方法，可指定公平或者非公平。
     * true 表示 公平， false 非公平。
     * 会同时创建写锁与读锁
     *
     * @param fair {@code true} if this lock should use a fair ordering policy
     */
    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }

    /**
     * 返回写锁
     *
     * @return
     */
    public ReentrantReadWriteLock.WriteLock writeLock() {
        return writerLock;
    }

    /**
     * 返回读锁
     *
     * @return
     */
    public ReentrantReadWriteLock.ReadLock readLock() {
        return readerLock;
    }

    /**
     * Synchronization implementation for ReentrantReadWriteLock.
     * Subclassed into fair and nonfair versions.
     * <p>
     * 同步器抽象类，继承自 AbstractQueuedSynchronizer。
     * 是否公平通过 writerShouldBlock 和 readerShouldBlock 来控制
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 6317671515068378041L;

        /*
         * Read vs write count extraction constants and functions.
         * Lock state is logically divided into two unsigned shorts:
         * 锁的状态在逻辑上将 state 分成无符号的两部分
         * The lower one representing the exclusive (writer) lock hold count,
         * 低 16 位表示写锁（独占锁）的持有数
         * and the upper the shared (reader) hold count.
         * 高 16 位表示读锁（共享锁）的持有数
         */

        // 移位常量
        static final int SHARED_SHIFT = 16;
        // 相当于读锁中的 1
        static final int SHARED_UNIT = (1 << SHARED_SHIFT);
        // 持有锁的最大的数量
        static final int MAX_COUNT = (1 << SHARED_SHIFT) - 1;
        // 独占模式的掩码
        // 低 16 位全是 1，高 16 位全是 0
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /**
         * Returns the number of shared holds represented in count.
         * <p>
         * 返回读锁持有的数量
         */
        static int sharedCount(int c) {
            // 无符号右移
            return c >>> SHARED_SHIFT;
        }

        /**
         * Returns the number of exclusive holds represented in count.
         * <p>
         * 返回写锁持有的数量
         */
        static int exclusiveCount(int c) {
            // 与上掩码
            return c & EXCLUSIVE_MASK;
        }

        /**
         * A counter for per-thread read hold counts.
         * Maintained as a ThreadLocal; cached in cachedHoldCounter.
         * <p>
         * 仅用于 读锁
         * 静态类，保存一个计数器，由 ThreadLocal 维护。
         * 保存每个线程持有的 读锁 的个数
         */
        static final class HoldCounter {
            // 初始为 0
            int count;          // initially 0
            // Use id, not reference, to avoid garbage retention
            final long tid = LockSupport.getThreadId(Thread.currentThread());
        }

        /**
         * ThreadLocal subclass. Easiest to explicitly define for sake
         * of deserialization mechanics.
         * <p>
         * 静态类，继承了 ThreadLocal 类。里面保存 HoldCounter 对象。
         * value 为 HoldCounter
         */
        static final class ThreadLocalHoldCounter
                extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        /**
         * The number of reentrant read locks held by current thread.
         * Initialized only in constructor and readObject.
         * Removed whenever a thread's read hold count drops to 0.
         * <p>
         * 当前线程持有的可重入 读锁 的锁的个数
         * 只能通过 构造方法 和 反序列化初始化。
         */
        private transient ThreadLocalHoldCounter readHolds;

        /**
         * The hold count of the last thread to successfully acquire
         * readLock. This saves ThreadLocal lookup in the common case
         * where the next thread to release is the last one to
         * acquire. This is non-volatile since it is just used
         * as a heuristic, and would be great for threads to cache.
         * <p>
         * 最后一个成功获取到读锁的线程的 ThreadLocalHoldCounter 中 value 的缓存
         * 最后一个成功获得读锁的线程持有的锁的个数。
         *
         * <p>Can outlive the Thread for which it is caching the read
         * hold count, but avoids garbage retention by not retaining a
         * reference to the Thread.
         * <p>
         * 该缓存能比 Thread 存活更长的时间。
         *
         * <p>Accessed via a benign data race; relies on the memory
         * model's final field and out-of-thin-air guarantees.
         */
        private transient HoldCounter cachedHoldCounter;

        /**
         * firstReader is the first thread to have acquired the read lock.
         * firstReaderHoldCount is firstReader's hold count.
         * <p>
         * 持有读锁的队列中的第一个线程。第一个获取读锁的线程。
         * 第一个读锁持有线程 持有的读锁的个数。
         *
         * <p>More precisely, firstReader is the unique thread that last
         * changed the shared count from 0 to 1, and has not released the
         * read lock since then; null if there is no such thread.
         *
         * <p>Cannot cause garbage retention unless the thread terminated
         * without relinquishing its read locks, since tryReleaseShared
         * sets it to null.
         *
         * <p>Accessed via a benign data race; relies on the memory
         * model's out-of-thin-air guarantees for references.
         *
         * <p>This allows tracking of read holds for uncontended read
         * locks to be very cheap.
         */
        private transient Thread firstReader;
        private transient int firstReaderHoldCount;

        /**
         * 构造方法
         */
        Sync() {
            // 初始化当前线程的读锁计数器
            readHolds = new ThreadLocalHoldCounter();
            // 设置 sync 的 state
            // 在具体的实现类应该会重写下面的方法
            setState(getState()); // ensures visibility of readHolds
        }

        /*
         * Acquires and releases use the same code for fair and
         * nonfair locks, but differ in whether/how they allow barging
         * when queues are non-empty.
         */

        /**
         * 是否能够抢占
         * <p>
         * Returns true if the current thread, when trying to acquire
         * the read lock, and otherwise eligible 合格的/有资格的 to do so, should block
         * because of policy for overtaking 超车/赶上 other waiting threads.
         */
        abstract boolean readerShouldBlock();

        /**
         * 是否能够抢占
         * <p>
         * Returns true if the current thread, when trying to acquire
         * the write lock, and otherwise eligible to do so, should block
         * because of policy for overtaking other waiting threads.
         */
        abstract boolean writerShouldBlock();

        /*
         * Note that tryRelease and tryAcquire can be called by
         * Conditions. So it is possible that their arguments contain
         * both read and write holds that are all released during a
         * condition wait and re-established in tryAcquire.
         */

        /**
         * 释放独占锁
         *
         * @param releases
         * @return
         */
        @ReservedStackAccess
        protected final boolean tryRelease(int releases) {
            // 锁是否被当前线程独占
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // 释放之后的 state 值
            int nextc = getState() - releases;
            // 如果写锁的计数为 0
            boolean free = exclusiveCount(nextc) == 0;
            // 完全释放
            if (free)
                // 设置独占线程为 null
                setExclusiveOwnerThread(null);
            // 更新 state
            setState(nextc);
            // 返回是否完全释放
            return free;
        }

        /**
         * 获取独占锁
         *
         * @param acquires
         * @return
         */
        @ReservedStackAccess
        protected final boolean tryAcquire(int acquires) {
            /*
             * Walkthrough:
             * 1. If read count nonzero or write count nonzero
             *    and owner is a different thread, fail.
             *    如果读锁计数或者写锁计数不为 0，且 owner 是不同的线程，则获取失败
             * 2. If count would saturate, fail. (This can only
             *    happen if count is already nonzero.)
             * 3. Otherwise, this thread is eligible for lock if
             *    it is either a reentrant acquire or
             *    queue policy allows it. If so, update state
             *    and set owner.
             */
            Thread current = Thread.currentThread();
            // 没有具体意义，可以判断当前是否有锁
            int c = getState();
            // 独占锁的个数
            int w = exclusiveCount(c);
            // 如果当前有锁
            if (c != 0) {
                // (Note: if c != 0 and w == 0 then shared count != 0)
                // w == 0 表示写锁没被持有
                // 当前线程不是持有锁的线程
                if (w == 0 || current != getExclusiveOwnerThread())
                    // 获取失败
                    return false;
                // 检查独占锁个数是否超出限制
                if (w + exclusiveCount(acquires) > MAX_COUNT)
                    // 超出则抛出异常
                    throw new Error("Maximum lock count exceeded");
                // Reentrant acquire
                // 否则可重入获取
                // 更新 state
                setState(c + acquires);
                // 获取成功
                return true;
            }
            // c == 0 说明锁未被持有
            // 如果 写锁 需要阻塞 或者 cas 更新 state 失败
            if (writerShouldBlock() ||
                    !compareAndSetState(c, c + acquires))
                return false;
            // 设置独占线程为当前线程
            // 走到下面说明 state 更新成功了
            setExclusiveOwnerThread(current);
            // 返回获取成功
            return true;
        }

        /**
         * 释放读锁
         *
         * @param unused
         * @return
         */
        @ReservedStackAccess
        protected final boolean tryReleaseShared(int unused) {
            Thread current = Thread.currentThread();
            // 如果当前线程是第一个获取读锁的线程
            if (firstReader == current) {
                // assert firstReaderHoldCount > 0;
                // 如果持有的读锁的个数 == 1
                if (firstReaderHoldCount == 1)
                    // 将 firstReader 置为 null
                    // firstReaderHoldCount 怎么没处理 ？
                    // 不用处理，只要把 firstReader 置为 null 就可以了，因为优先使用 firstReader
                    firstReader = null;
                else
                    // firstReaderHoldCount - 1
                    firstReaderHoldCount--;
                // 不是 firstReader
            } else {
                // 暂时将缓存的 cachedHoldCounter 赋值给 rh
                HoldCounter rh = cachedHoldCounter;
                // 为初始化 HoldCounter
                // 如果缓存的 cachedHoldCounter 为空或者 当前线程不是最后一个获取读锁的线程
                // 则需要从当前线程的 readHolds 中获取。
                if (rh == null ||
                        rh.tid != LockSupport.getThreadId(current))
                    // 获取 HoldCounter
                    rh = readHolds.get();
                // 获取计数值
                int count = rh.count;
                // 表明 可以移除该 HoldCounter 了
                // 相当于该线程完全释放了读锁
                if (count <= 1) {
                    // 移除该 Entry
                    readHolds.remove();
                    // 如果 count <= 0 的话，表明无法释放读锁
                    // 出现了异常
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                // count - 1
                --rh.count;
            }
            for (; ; ) {
                int c = getState();
                // 读锁计数u - 1
                int nextc = c - SHARED_UNIT;
                // 更新新的 state
                if (compareAndSetState(c, nextc))
                    // Releasing the read lock has no effect on readers,
                    // 释放读锁对其他读线程没有影响
                    // but it may allow waiting writers to proceed if
                    // 但是如果存在写锁在等待获取锁，那么读锁的释放就对写锁产生影响了
                    // both read and write locks are now free.
                    // 返回是否完全释放了锁
                    return nextc == 0;
            }
        }

        private static IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                    "attempt to unlock read lock, not locked by current thread");
        }

        /**
         * 获取读锁
         *
         * @param unused
         * @return
         */
        @ReservedStackAccess
        protected final int tryAcquireShared(int unused) {
            /*
             * Walkthrough:
             * 1. If write lock held by another thread, fail.
             * 2. Otherwise, this thread is eligible for
             *    lock wrt state, so ask if it should block
             *    because of queue policy. If not, try
             *    to grant by CASing state and updating count.
             *    Note that step does not check for reentrant
             *    acquires, which is postponed to full version
             *    to avoid having to check hold count in
             *    the more typical non-reentrant case.
             * 3. If step 2 fails either because thread
             *    apparently not eligible or CAS fails or count
             *    saturated, chain to version with full retry loop.
             */
            Thread current = Thread.currentThread();
            int c = getState();
            // 写锁被持有
            // 持有锁的线程不是当前线程
            // 说明持有写锁的线程可以在持有写锁的基础上获取读锁
            if (exclusiveCount(c) != 0 &&
                    getExclusiveOwnerThread() != current)
                // 获取失败
                return -1;
            // 当前读锁的个数
            int r = sharedCount(c);
            // 读不会被阻塞 && 读锁个数 < MAX_COUNT && 成功将 state + 1(这个 1 是读锁的单位 1 )
            if (!readerShouldBlock() &&
                    r < MAX_COUNT &&
                    compareAndSetState(c, c + SHARED_UNIT)) {
                // 如果之前的读锁未被持有
                if (r == 0) {
                    // 将当前线程设成第一个线程
                    firstReader = current;
                    // 计数 + 1
                    firstReaderHoldCount = 1;
                    // 如果当前线程是第一个获取读锁的线程
                } else if (firstReader == current) {
                    // 计数 + 1
                    firstReaderHoldCount++;
                } else {
                    // 否则更新 ThreadLocalHoldCounter
                    // 同样先尝试检查当前线程是否是最后一个获取到读锁的线程，
                    HoldCounter rh = cachedHoldCounter;
                    // 如果 HoldCounter 未初始化 或者  rh 的 tid 不是当前线程的 tid
                    if (rh == null ||
                            rh.tid != LockSupport.getThreadId(current))
                        // 从 threadLocal 从获取，并赋值给 cachedHoldCounter
                        // 更新 cachedHoldCounter
                        cachedHoldCounter = rh = readHolds.get();
                    else if (rh.count == 0)
                        // 设置当前线程的 threadLocal
                        readHolds.set(rh);
                    // 计数 ++
                    rh.count++;
                }
                // 返回表示获取成功
                return 1;
            }
            return fullTryAcquireShared(current);
        }

        /**
         * Full version of acquire for reads, that handles CAS misses
         * and reentrant reads not dealt with in tryAcquireShared.
         * <p>
         * CAS 操作失败或者 tryAcquireShared 方法中可重入读未被处理的情况会调用该方法
         */
        final int fullTryAcquireShared(Thread current) {
            /*
             * This code is in part redundant with that in
             * tryAcquireShared but is simpler overall by not
             * complicating tryAcquireShared with interactions between
             * retries and lazily reading hold counts.
             */
            HoldCounter rh = null;
            for (; ; ) {
                // 当前的 state
                int c = getState();
                // 如果被存在写锁
                if (exclusiveCount(c) != 0) {
                    // 如果不是当前线程占有，则返回 -1，表示失败
                    if (getExclusiveOwnerThread() != current)
                        return -1;
                    // else we hold the exclusive lock; blocking here
                    // would cause deadlock.
                    // 否则说明当前线程持有写锁，到后面处理。
                    // 公平锁下：队列中是否有其他线程
                    // 非公平锁下：队列的第一个节点是否是独占节点
                } else if (readerShouldBlock()) {
                    // Make sure we're not acquiring read lock reentrantly
                    // 下面是重入逻辑
                    if (firstReader == current) {
                        // 如果当前线程就是 firstReader，那么就是重入。
                        // assert firstReaderHoldCount > 0;
                    } else {
                        // 不是第一个获取到读锁的线程
                        // 也有可能 firstReader == null，说明没有获取到读锁的线程存在。
                        if (rh == null) {
                            // 检查是否是最后一个获取到读锁的线程
                            rh = cachedHoldCounter;
                            // 如果不是
                            if (rh == null ||
                                    rh.tid != LockSupport.getThreadId(current)) {
                                // 初始化一个
                                rh = readHolds.get();
                                // 如果未持有 读锁，则 remove
                                if (rh.count == 0)
                                    readHolds.remove();
                            }
                        }
                        // 如果 count == 0，说明不持有读锁，无法重入，需要到队列中等待获取锁
                        if (rh.count == 0)
                            return -1;
                    }
                }
                // 检查读锁个数是否达到最大
                if (sharedCount(c) == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // cas 尝试修改 state
                // 如果成功
                // if 里面更新 firstReader / firstReaderHoldCount / HoldCounter
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    // 如果读锁的个数为 0
                    // 则将 firstReader 指向当前线程
                    // 更新 firstReaderHoldCount 为 1
                    if (sharedCount(c) == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                        // 如果当前线程就是第一个获取读锁的线程
                    } else if (firstReader == current) {
                        // 计数 ++
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null ||
                                rh.tid != LockSupport.getThreadId(current))
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    return 1;
                }
            }
        }

        /**
         * Performs tryLock for write, enabling barging in both modes.
         * This is identical in effect to tryAcquire except for lack
         * of calls to writerShouldBlock.
         * <p>
         * 尝试加写锁，允许抢占。
         */
        @ReservedStackAccess
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if (c != 0) {
                // 写锁的个数
                int w = exclusiveCount(c);
                // w == 0 表明锁未被持有
                // current != getExclusiveOwnerThread() 表明当前线程被没有持有写锁
                if (w == 0 || current != getExclusiveOwnerThread())
                    // 直接返回 false
                    return false;
                // 如果 写锁 个数已达最大
                if (w == MAX_COUNT)
                    // 抛异常
                    throw new Error("Maximum lock count exceeded");
            }
            // 尝试更新 cas
            if (!compareAndSetState(c, c + 1))
                // 未成功则返回 false
                return false;
            // 能到这里说明更新 state 成功，设置独占线程为当前线程
            setExclusiveOwnerThread(current);
            // 返回获取锁成功
            return true;
        }

        /**
         * Performs tryLock for read, enabling barging in both modes.
         * This is identical in effect to tryAcquireShared except for
         * lack of calls to readerShouldBlock.
         * <p>
         * 尝试加 读锁
         */
        @ReservedStackAccess
        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            for (; ; ) {
                int c = getState();
                // 如果有写锁 且 写锁的持有线程非当前线程
                // 持有写锁的线程可以获取读锁
                if (exclusiveCount(c) != 0 &&
                        getExclusiveOwnerThread() != current)
                    return false;
                // 读锁的个数
                int r = sharedCount(c);
                // 检查读锁个数是否达到最大
                if (r == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // 尝试更新读锁的 state
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    // 如果读锁之前未被持有
                    if (r == 0) {
                        // 更新 firstReader
                        // 更新 firstReaderHoldCount
                        firstReader = current;
                        firstReaderHoldCount = 1;
                        // 如果为当前线程
                    } else if (firstReader == current) {
                        // 计数 + 1
                        firstReaderHoldCount++;
                        // 一般处理
                        // 更新当前线程的 HoldCounter，计数 + 1
                    } else {
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null ||
                                rh.tid != LockSupport.getThreadId(current))
                            cachedHoldCounter = rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
                // 更新失败竟然没有处理?????
            }
        }

        /**
         * 写锁是否被当前线程独占
         *
         * @return
         */
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        // Methods relayed to outer class

        /**
         * 条件等待队列
         *
         * @return
         */
        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        /**
         * 返回写锁的持有线程
         *
         * @return
         */
        final Thread getOwner() {
            // Must read state before owner to ensure memory consistency
            // 必须得先读 state，已保证内存一致性
            // 是否存在写锁
            return ((exclusiveCount(getState()) == 0) ?
                    null :
                    // 获取独占线程
                    getExclusiveOwnerThread());
        }

        /**
         * 返回读锁的个数
         *
         * @return
         */
        final int getReadLockCount() {
            return sharedCount(getState());
        }

        /**
         * 返回写锁是否被持有
         *
         * @return
         */
        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        /**
         * 返回写锁的个数
         * 如果当前线程不是持有写锁的线程，则会返回 0
         *
         * @return
         */
        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        /**
         * 返回当前线程持有的读锁的个数
         *
         * @return
         */
        final int getReadHoldCount() {
            if (getReadLockCount() == 0)
                return 0;

            Thread current = Thread.currentThread();
            // 如果是第一个持有读锁的线程
            if (firstReader == current)
                return firstReaderHoldCount;

            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == LockSupport.getThreadId(current))
                return rh.count;

            int count = readHolds.get().count;
            if (count == 0) readHolds.remove();
            return count;
        }

        /**
         * Reconstitutes the instance from a stream (that is, deserializes it).
         * <p>
         * 反序列化方法
         */
        private void readObject(java.io.ObjectInputStream s)
                throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            // 在这里可以初始化 readHolds
            readHolds = new ThreadLocalHoldCounter();
            setState(0); // reset to unlocked state
        }

        /**
         * 返回 state 的个数，读锁 + 写锁
         * getState 应该是被重写了的
         *
         * @return
         */
        final int getCount() {
            return getState();
        }
    }

    /**
     * Nonfair version of Sync
     * <p>
     * 非公平同步器
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;

        /**
         * 重写了 writerShouldBlock 方法
         *
         * @return
         */
        final boolean writerShouldBlock() {
            // 直接返回 false
            // 表明用于支持抢占
            return false; // writers can always barge
        }

        /**
         * 返回第一个节点是否为独占类型
         *
         * @return
         */
        final boolean readerShouldBlock() {
            /* As a heuristic to avoid indefinite writer starvation,
             * block if the thread that momentarily appears to be head
             * of queue, if one exists, is a waiting writer.  This is
             * only a probabilistic effect since a new reader will not
             * block if there is a waiting writer behind other enabled
             * readers that have not yet drained from the queue.
             */
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    /**
     * Fair version of Sync
     * <p>
     * 公平同步器
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;

        final boolean writerShouldBlock() {
            // 返回队列中是否有更待时间更久的线程
            return hasQueuedPredecessors();
        }

        final boolean readerShouldBlock() {
            return hasQueuedPredecessors();
        }
    }

    /**
     * The lock returned by method {@link ReentrantReadWriteLock#readLock}.
     * <p>
     * 读锁的内部实现，实现了 Lock 接口。
     */
    public static class ReadLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -5992448646407690164L;
        /**
         * 同步器
         */
        private final Sync sync;

        /**
         * Constructor for use by subclasses.
         * <p>
         * 构造方法，指定同步器
         *
         * @param lock the outer lock object
         * @throws NullPointerException if the lock is null
         */
        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * Acquires the read lock.
         * <p>
         * 获取读锁
         *
         * <p>Acquires the read lock if the write lock is not held by
         * another thread and returns immediately.
         * <p>
         * 如果写锁未被其他线程持有，则立刻返回
         *
         * <p>If the write lock is held by another thread then
         * the current thread becomes disabled for thread scheduling
         * purposes and lies dormant until the read lock has been acquired.
         * <p>
         * 如果写锁被其他线程持有，那么当前线程会进入队列等待
         */
        public void lock() {
            sync.acquireShared(1);
        }

        /**
         * Acquires the read lock unless the current thread is
         * {@linkplain Thread#interrupt interrupted}.
         * <p>
         * 获取读锁，除非当前线程被中断。可响应中断。
         *
         * <p>Acquires the read lock if the write lock is not held
         * by another thread and returns immediately.
         * <p>
         * 如果写锁未被其他线程持有，则立刻返回
         *
         * <p>If the write lock is held by another thread then the
         * current thread becomes disabled for thread scheduling
         * purposes and lies dormant until one of two things happens:
         * <p>
         * 如果写锁被其他线程持有，那么当前线程将会在队列中阻塞直到以下两种情况发生：
         *
         * <ul>
         *
         * <li>The read lock is acquired by the current thread; or
         * <p>
         * 1. 当前线程获取到了锁
         * <li>Some other thread {@linkplain Thread#interrupt interrupts}
         * the current thread.
         * <p>
         * 2. 其他线程中断可当前线程
         * </ul>
         *
         * <p>If the current thread:
         *
         * <ul>
         *
         * <li>has its interrupted status set on entry to this method; or
         * <p>
         * 如果当前线程的中断状态在进入方法的时候就被设置了，或者
         * <li>is {@linkplain Thread#interrupt interrupted} while
         * acquiring the read lock,
         * <p>
         * 正在获取读锁的时候被设置了(acquire 方法中有检测是否中断)
         *
         * </ul>
         * <p>
         * then {@link InterruptedException} is thrown and the current
         * thread's interrupted status is cleared.
         * <p>
         * 那么将会抛出 InterruptedException 异常。并且当前的中断标志会被清除。
         *
         * <p>In this implementation, as this method is an explicit
         * interruption point, preference is given to responding to
         * the interrupt over normal or reentrant acquisition of the
         * lock.
         *
         * @throws InterruptedException if the current thread is interrupted
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        /**
         * Acquires the read lock only if the write lock is not held by
         * another thread at the time of invocation.
         * <p>
         * 获取读锁，当且仅当写锁未被其他线程持有。
         * 该方法即使是在 公平的策略下，也允许抢占。
         *
         * <p>Acquires the read lock if the write lock is not held by
         * another thread and returns immediately with the value
         * {@code true}. Even when this lock has been set to use a
         * fair ordering policy, a call to {@code tryLock()}
         * <em>will</em> immediately acquire the read lock if it is
         * available, whether or not other threads are currently
         * waiting for the read lock.  This &quot;barging&quot; behavior
         * can be useful in certain circumstances, even though it
         * breaks fairness. If you want to honor the fairness setting
         * for this lock, then use {@link #tryLock(long, TimeUnit)
         * tryLock(0, TimeUnit.SECONDS)} which is almost equivalent
         * (it also detects interruption).
         *
         * <p>If the write lock is held by another thread then
         * this method will return immediately with the value
         * {@code false}.
         *
         * @return {@code true} if the read lock was acquired
         */
        public boolean tryLock() {
            return sync.tryReadLock();
        }

        /**
         * Acquires the read lock if the write lock is not held by
         * another thread within the given waiting time and the
         * current thread has not been {@linkplain Thread#interrupt
         * interrupted}.
         * <p>
         * 支持等待时间
         * 与 {@link #tryLock()} 不同，该方法指定了时间，如果有其他线程在
         * 等到锁，那么使用该方法的线程不会抢占。
         *
         * <p>Acquires the read lock if the write lock is not held by
         * another thread and returns immediately with the value
         * {@code true}. If this lock has been set to use a fair
         * ordering policy then an available lock <em>will not</em> be
         * acquired if any other threads are waiting for the
         * lock. This is in contrast to the {@link #tryLock()}
         * method. If you want a timed {@code tryLock} that does
         * permit barging on a fair lock then combine the timed and
         * un-timed forms together:
         * <p>
         * 使用如下的形式可以满足在指定时间内执行抢占
         *
         * <pre> {@code
         * if (lock.tryLock() ||
         *     lock.tryLock(timeout, unit)) {
         *   ...
         * }}</pre>
         *
         * <p>If the write lock is held by another thread then the
         * current thread becomes disabled for thread scheduling
         * purposes and lies dormant until one of three things happens:
         *
         * <ul>
         *
         * <li>The read lock is acquired by the current thread; or
         * <p>
         * 1. 获取到锁
         * <li>Some other thread {@linkplain Thread#interrupt interrupts}
         * the current thread; or
         * <p>
         * 2. 被其他线程中断
         * <li>The specified waiting time elapses.
         * <p>
         * 3. 等待超时
         * </ul>
         *
         * <p>If the read lock is acquired then the value {@code true} is
         * returned.
         *
         * <p>If the current thread:
         *
         * <ul>
         *
         * <li>has its interrupted status set on entry to this method; or
         *
         * <li>is {@linkplain Thread#interrupt interrupted} while
         * acquiring the read lock,
         *
         * </ul> then {@link InterruptedException} is thrown and the
         * current thread's interrupted status is cleared.
         *
         * <p>If the specified waiting time elapses then the value
         * {@code false} is returned.  If the time is less than or
         * equal to zero, the method will not wait at all.
         *
         * <p>In this implementation, as this method is an explicit
         * interruption point, preference is given to responding to
         * the interrupt over normal or reentrant acquisition of the
         * lock, and over reporting the elapse of the waiting time.
         *
         * @param timeout the time to wait for the read lock
         * @param unit    the time unit of the timeout argument
         * @return {@code true} if the read lock was acquired
         * @throws InterruptedException if the current thread is interrupted
         * @throws NullPointerException if the time unit is null
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        /**
         * Attempts to release this lock.
         * <p>
         * 尝试释放读锁
         *
         * <p>If the number of readers is now zero then the lock
         * is made available for write lock attempts. If the current
         * thread does not hold this lock then {@link
         * IllegalMonitorStateException} is thrown.
         *
         * @throws IllegalMonitorStateException if the current thread
         *                                      does not hold this lock
         */
        public void unlock() {
            sync.releaseShared(1);
        }

        /**
         * Throws {@code UnsupportedOperationException} because
         * {@code ReadLocks} do not support conditions.
         * <p>
         * 读锁不支持创建 Condition
         *
         * @throws UnsupportedOperationException always
         */
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        /**
         * Returns a string identifying this lock, as well as its lock state.
         * The state, in brackets, includes the String {@code "Read locks ="}
         * followed by the number of held read locks.
         *
         * @return a string identifying this lock, as well as its lock state
         */
        public String toString() {
            int r = sync.getReadLockCount();
            return super.toString() +
                    "[Read locks = " + r + "]";
        }
    }

    /**
     * The lock returned by method {@link ReentrantReadWriteLock#writeLock}.
     */
    public static class WriteLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -4992448646407690164L;
        private final Sync sync;

        /**
         * Constructor for use by subclasses.
         *
         * @param lock the outer lock object
         * @throws NullPointerException if the lock is null
         */
        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * Acquires the write lock.
         * <p>
         * 获取写锁
         *
         * <p>Acquires the write lock if neither the read nor write lock
         * are held by another thread
         * and returns immediately, setting the write lock hold count to
         * one.
         * <p>
         * 当读写锁没有被其他线程持有时，获取写锁，并将锁持有数 + 1
         *
         * <p>If the current thread already holds the write lock then the
         * hold count is incremented by one and the method returns
         * immediately.
         * <p>
         * 如果当前线程早已经持有写锁，那么将会立刻返回，并计数 + 1
         *
         * <p>If the lock is held by another thread then the current
         * thread becomes disabled for thread scheduling purposes and
         * lies dormant until the write lock has been acquired, at which
         * time the write lock hold count is set to one.
         * <p>
         * 如果锁被其他线程持有，那么将会阻塞，直到获取到锁。
         */
        public void lock() {
            sync.acquire(1);
        }

        /**
         * Acquires the write lock unless the current thread is
         * {@linkplain Thread#interrupt interrupted}.
         * <p>
         * 可响应中断
         *
         * <p>Acquires the write lock if neither the read nor write lock
         * are held by another thread
         * and returns immediately, setting the write lock hold count to
         * one.
         *
         * <p>If the current thread already holds this lock then the
         * hold count is incremented by one and the method returns
         * immediately.
         *
         * <p>If the lock is held by another thread then the current
         * thread becomes disabled for thread scheduling purposes and
         * lies dormant until one of two things happens:
         *
         * <ul>
         *
         * <li>The write lock is acquired by the current thread; or
         * <p>
         * 1. 当前线程获取到锁
         * <li>Some other thread {@linkplain Thread#interrupt interrupts}
         * the current thread.
         * <p>
         * 2. 其他线程中断了该线程
         * </ul>
         *
         * <p>If the write lock is acquired by the current thread then the
         * lock hold count is set to one.
         *
         * <p>If the current thread:
         *
         * <ul>
         *
         * <li>has its interrupted status set on entry to this method;
         * or
         *
         * <li>is {@linkplain Thread#interrupt interrupted} while
         * acquiring the write lock,
         *
         * </ul>
         * <p>
         * then {@link InterruptedException} is thrown and the current
         * thread's interrupted status is cleared.
         *
         * <p>In this implementation, as this method is an explicit
         * interruption point, preference is given to responding to
         * the interrupt over normal or reentrant acquisition of the
         * lock.
         *
         * @throws InterruptedException if the current thread is interrupted
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        /**
         * Acquires the write lock only if it is not held by another thread
         * at the time of invocation.
         * <p>
         * 破坏公平策略的获取锁方法
         *
         * <p>Acquires the write lock if neither the read nor write lock
         * are held by another thread
         * and returns immediately with the value {@code true},
         * setting the write lock hold count to one. Even when this lock has
         * been set to use a fair ordering policy, a call to
         * {@code tryLock()} <em>will</em> immediately acquire the
         * lock if it is available, whether or not other threads are
         * currently waiting for the write lock.  This &quot;barging&quot;
         * behavior can be useful in certain circumstances, even
         * though it breaks fairness. If you want to honor the
         * fairness setting for this lock, then use {@link
         * #tryLock(long, TimeUnit) tryLock(0, TimeUnit.SECONDS)}
         * which is almost equivalent (it also detects interruption).
         *
         * <p>If the current thread already holds this lock then the
         * hold count is incremented by one and the method returns
         * {@code true}.
         *
         * <p>If the lock is held by another thread then this method
         * will return immediately with the value {@code false}.
         *
         * @return {@code true} if the lock was free and was acquired
         * by the current thread, or the write lock was already held
         * by the current thread; and {@code false} otherwise.
         */
        public boolean tryLock() {
            return sync.tryWriteLock();
        }

        /**
         * Acquires the write lock if it is not held by another thread
         * within the given waiting time and the current thread has
         * not been {@linkplain Thread#interrupt interrupted}.
         *
         * <p>Acquires the write lock if neither the read nor write lock
         * are held by another thread
         * and returns immediately with the value {@code true},
         * setting the write lock hold count to one. If this lock has been
         * set to use a fair ordering policy then an available lock
         * <em>will not</em> be acquired if any other threads are
         * waiting for the write lock. This is in contrast to the {@link
         * #tryLock()} method. If you want a timed {@code tryLock}
         * that does permit barging on a fair lock then combine the
         * timed and un-timed forms together:
         *
         * <pre> {@code
         * if (lock.tryLock() ||
         *     lock.tryLock(timeout, unit)) {
         *   ...
         * }}</pre>
         *
         * <p>If the current thread already holds this lock then the
         * hold count is incremented by one and the method returns
         * {@code true}.
         *
         * <p>If the lock is held by another thread then the current
         * thread becomes disabled for thread scheduling purposes and
         * lies dormant until one of three things happens:
         *
         * <ul>
         *
         * <li>The write lock is acquired by the current thread; or
         * <p>
         * 1. 被当前线程获取到锁
         * <li>Some other thread {@linkplain Thread#interrupt interrupts}
         * the current thread; or
         * <p>
         * 2. 其他线程中断
         * <li>The specified waiting time elapses
         * <p>
         * 2. 超时
         *
         * </ul>
         *
         * <p>If the write lock is acquired then the value {@code true} is
         * returned and the write lock hold count is set to one.
         *
         * <p>If the current thread:
         *
         * <ul>
         *
         * <li>has its interrupted status set on entry to this method;
         * or
         *
         * <li>is {@linkplain Thread#interrupt interrupted} while
         * acquiring the write lock,
         *
         * </ul>
         * <p>
         * then {@link InterruptedException} is thrown and the current
         * thread's interrupted status is cleared.
         *
         * <p>If the specified waiting time elapses then the value
         * {@code false} is returned.  If the time is less than or
         * equal to zero, the method will not wait at all.
         *
         * <p>In this implementation, as this method is an explicit
         * interruption point, preference is given to responding to
         * the interrupt over normal or reentrant acquisition of the
         * lock, and over reporting the elapse of the waiting time.
         *
         * @param timeout the time to wait for the write lock
         * @param unit    the time unit of the timeout argument
         * @return {@code true} if the lock was free and was acquired
         * by the current thread, or the write lock was already held by the
         * current thread; and {@code false} if the waiting time
         * elapsed before the lock could be acquired.
         * @throws InterruptedException if the current thread is interrupted
         * @throws NullPointerException if the time unit is null
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        /**
         * Attempts to release this lock.
         * <p>
         * 尝试释放锁
         *
         * <p>If the current thread is the holder of this lock then
         * the hold count is decremented. If the hold count is now
         * zero then the lock is released.  If the current thread is
         * not the holder of this lock then {@link
         * IllegalMonitorStateException} is thrown.
         *
         * @throws IllegalMonitorStateException if the current thread does not
         *                                      hold this lock
         */
        public void unlock() {
            sync.release(1);
        }

        /**
         * Returns a {@link Condition} instance for use with this
         * {@link Lock} instance.
         * <p>The returned {@link Condition} instance supports the same
         * usages as do the {@link Object} monitor methods ({@link
         * Object#wait() wait}, {@link Object#notify notify}, and {@link
         * Object#notifyAll notifyAll}) when used with the built-in
         * monitor lock.
         * <p>
         * 返回一个 Condition 实例。包含 condition queue
         *
         * <ul>
         *
         * <li>If this write lock is not held when any {@link
         * Condition} method is called then an {@link
         * IllegalMonitorStateException} is thrown.  (Read locks are
         * held independently of write locks, so are not checked or
         * affected. However it is essentially always an error to
         * invoke a condition waiting method when the current thread
         * has also acquired read locks, since other threads that
         * could unblock it will not be able to acquire the write
         * lock.)
         *
         * <li>When the condition {@linkplain Condition#await() waiting}
         * methods are called the write lock is released and, before
         * they return, the write lock is reacquired and the lock hold
         * count restored to what it was when the method was called.
         *
         * <li>If a thread is {@linkplain Thread#interrupt interrupted} while
         * waiting then the wait will terminate, an {@link
         * InterruptedException} will be thrown, and the thread's
         * interrupted status will be cleared.
         *
         * <li>Waiting threads are signalled in FIFO order.
         *
         * <li>The ordering of lock reacquisition for threads returning
         * from waiting methods is the same as for threads initially
         * acquiring the lock, which is in the default case not specified,
         * but for <em>fair</em> locks favors those threads that have been
         * waiting the longest.
         *
         * </ul>
         *
         * @return the Condition object
         */
        public Condition newCondition() {
            return sync.newCondition();
        }

        /**
         * Returns a string identifying this lock, as well as its lock
         * state.  The state, in brackets includes either the String
         * {@code "Unlocked"} or the String {@code "Locked by"}
         * followed by the {@linkplain Thread#getName name} of the owning thread.
         *
         * @return a string identifying this lock, as well as its lock state
         */
        public String toString() {
            Thread o = sync.getOwner();
            return super.toString() + ((o == null) ?
                    "[Unlocked]" :
                    "[Locked by thread " + o.getName() + "]");
        }

        /**
         * Queries if this write lock is held by the current thread.
         * Identical in effect to {@link
         * ReentrantReadWriteLock#isWriteLockedByCurrentThread}.
         * <p>
         * 返回当前线程是否持有写锁
         *
         * @return {@code true} if the current thread holds this lock and
         * {@code false} otherwise
         * @since 1.6
         */
        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        /**
         * Queries the number of holds on this write lock by the current
         * thread.  A thread has a hold on a lock for each lock action
         * that is not matched by an unlock action.  Identical in effect
         * to {@link ReentrantReadWriteLock#getWriteHoldCount}.
         * <p>
         * 返回当前线程持有的 写锁 的个数
         *
         * @return the number of holds on this lock by the current thread,
         * or zero if this lock is not held by the current thread
         * @since 1.6
         */
        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }

    // Instrumentation and status

    /**
     * Returns {@code true} if this lock has fairness set true.
     * <p>
     * 返回是否为公平锁
     *
     * @return {@code true} if this lock has fairness set true
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * Returns the thread that currently owns the write lock, or
     * {@code null} if not owned. When this method is called by a
     * thread that is not the owner, the return value reflects a
     * best-effort approximation of current lock status. For example,
     * the owner may be momentarily 暂时地 {@code null} even if there are
     * threads trying to acquire the lock but have not yet done so.
     * This method is designed to facilitate construction of
     * subclasses that provide more extensive lock monitoring
     * facilities.
     * <p>
     * 返回当前写锁的持有线程
     *
     * @return the owner, or {@code null} if not owned
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * <p>
     * 返回读锁的个数
     *
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    /**
     * Queries if the write lock is held by any thread. This method is
     * designed for use in monitoring system state, not for
     * synchronization control.
     * <p>
     * 返回写锁是否被其他线程持有
     *
     * @return {@code true} if any thread holds the write lock and
     * {@code false} otherwise
     */
    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    /**
     * Queries if the write lock is held by the current thread.
     * <p>
     * 返回写锁是否被当前线程持有
     *
     * @return {@code true} if the current thread holds the write lock and
     * {@code false} otherwise
     */
    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * Queries the number of reentrant write holds on this lock by the
     * current thread.  A writer thread has a hold on a lock for
     * each lock action that is not matched by an unlock action.
     * <p>
     * 返回当前线程持有的写锁的个数
     *
     * @return the number of holds on the write lock by the current thread,
     * or zero if the write lock is not held by the current thread
     */
    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    /**
     * Queries the number of reentrant read holds on this lock by the
     * current thread.  A reader thread has a hold on a lock for
     * each lock action that is not matched by an unlock action.
     * <p>
     * 返回当前线程持有读锁的个数
     *
     * @return the number of holds on the read lock by the current thread,
     * or zero if the read lock is not held by the current thread
     * @since 1.6
     */
    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire the write lock.  Because the actual set of threads may
     * change dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive lock monitoring facilities.
     * <p>
     * 返回等待写锁的所有线程的集合
     *
     * @return the collection of threads
     */
    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire the read lock.  Because the actual set of threads may
     * change dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive lock monitoring facilities.
     * <p>
     * 返回当前等待读锁的多有线程的集合
     *
     * @return the collection of threads
     */
    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

    /**
     * Queries whether any threads are waiting to acquire the read or
     * write lock. Note that because cancellations may occur at any
     * time, a {@code true} return does not guarantee that any other
     * thread will ever acquire a lock.  This method is designed
     * primarily for use in monitoring of the system state.
     * <p>
     * 是否有线程在等待获取读锁或者写锁
     *
     * @return {@code true} if there may be other threads waiting to
     * acquire the lock
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * Queries whether the given thread is waiting to acquire either
     * the read or write lock. Note that because cancellations may
     * occur at any time, a {@code true} return does not guarantee
     * that this thread will ever acquire a lock.  This method is
     * designed primarily for use in monitoring of the system state.
     * <p>
     * 给定线程是否在等待读锁或者写锁
     *
     * @param thread the thread
     * @return {@code true} if the given thread is queued waiting for this lock
     * @throws NullPointerException if the thread is null
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * Returns an estimate of the number of threads waiting to acquire
     * either the read or write lock.  The value is only an estimate
     * because the number of threads may change dynamically while this
     * method traverses internal data structures.  This method is
     * designed for use in monitoring system state, not for
     * synchronization control.
     * <p>
     * 返回等待队列的长度，无论队列中的读锁或者写锁。
     *
     * @return the estimated number of threads waiting for this lock
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire either the read or write lock.  Because the actual set
     * of threads may change dynamically while constructing this
     * result, the returned collection is only a best-effort estimate.
     * The elements of the returned collection are in no particular
     * order.  This method is designed to facilitate construction of
     * subclasses that provide more extensive monitoring facilities.
     * <p>
     * 返回等待队列的线程的集合
     *
     * @return the collection of threads
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with the write lock. Note that because timeouts and
     * interrupts may occur at any time, a {@code true} return does
     * not guarantee that a future {@code signal} will awaken any
     * threads.  This method is designed primarily for use in
     * monitoring of the system state.
     * <p>
     * 返回写锁等待队列中是否存在在指定 Condition 上等待的线程
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if this lock is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this lock
     * @throws NullPointerException         if the condition is null
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with the write lock. Note that because
     * timeouts and interrupts may occur at any time, the estimate
     * serves only as an upper bound on the actual number of waiters.
     * This method is designed for use in monitoring of the system
     * state, not for synchronization control.
     * <p>
     * 返回写锁等待队列的长度
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if this lock is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this lock
     * @throws NullPointerException         if the condition is null
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with the write lock.
     * Because the actual set of threads may change dynamically while
     * constructing this result, the returned collection is only a
     * best-effort estimate. The elements of the returned collection
     * are in no particular order.  This method is designed to
     * facilitate construction of subclasses that provide more
     * extensive condition monitoring facilities.
     * <p>
     * 返回写锁等待队列上的线程的集合
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if this lock is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this lock
     * @throws NullPointerException         if the condition is null
     */
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * Returns a string identifying this lock, as well as its lock state.
     * The state, in brackets, includes the String {@code "Write locks ="}
     * followed by the number of reentrantly held write locks, and the
     * String {@code "Read locks ="} followed by the number of held
     * read locks.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);

        return super.toString() +
                "[Write locks = " + w + ", Read locks = " + r + "]";
    }

}
