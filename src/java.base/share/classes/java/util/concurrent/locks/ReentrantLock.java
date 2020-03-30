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
 * A reentrant mutual exclusion {@link Lock} with the same basic
 * behavior and semantics as the implicit monitor lock accessed using
 * {@code synchronized} methods and statements, but with extended
 * capabilities.
 * <p>
 * 类似于 monitor lock，并且在 monitor lock 的基础上有其他扩展的能力。
 *
 * <p>A {@code ReentrantLock} is <em>owned</em> by the thread last
 * successfully locking, but not yet unlocking it. A thread invoking
 * {@code lock} will return, successfully acquiring the lock, when
 * the lock is not owned by another thread. The method will return
 * immediately if the current thread already owns the lock. This can
 * be checked using methods {@link #isHeldByCurrentThread}, and {@link
 * #getHoldCount}.
 *
 * <p>The constructor for this class accepts an optional
 * <em>fairness</em> parameter.  When set {@code true}, under
 * contention, locks favor granting access to the longest-waiting
 * thread.  Otherwise this lock does not guarantee any particular
 * access order.  Programs using fair locks accessed by many threads
 * may display lower overall throughput (i.e., are slower; often much
 * slower) than those using the default setting, but have smaller
 * variances in times to obtain locks and guarantee lack of
 * starvation. Note however, that fairness of locks does not guarantee
 * fairness of thread scheduling. Thus, one of many threads using a
 * fair lock may obtain it multiple times in succession while other
 * active threads are not progressing and not currently holding the
 * lock.
 * Also note that the untimed {@link #tryLock()} method does not
 * honor the fairness setting. It will succeed if the lock
 * is available even if other threads are waiting.
 * <p>
 * 构造方法可以可选传入一个 fairness 的参数，用于创建公平锁或者非公平锁。
 * 当传入 true 时，表示公平锁，存在竞争的情况下，（未入队线程与队头节点竞争）
 * 则在 sync queue 中等待时间最长的线程会获取到锁。
 *
 * <p>It is recommended practice to <em>always</em> immediately
 * follow a call to {@code lock} with a {@code try} block, most
 * typically in a before/after construction such as:
 * <p>
 * 推荐使用方法：
 * lock 方法后面跟上
 * try .. finally
 *
 * <pre> {@code
 * class X {
 *   private final ReentrantLock lock = new ReentrantLock();
 *   // ...
 *
 *   public void m() {
 *     lock.lock();  // block until condition holds
 *     try {
 *       // ... method body
 *     } finally {
 *       lock.unlock()
 *     }
 *   }
 * }}</pre>
 *
 * <p>In addition to implementing the {@link Lock} interface, this
 * class defines a number of {@code public} and {@code protected}
 * methods for inspecting the state of the lock .  Some of these
 * methods are only useful for instrumentation and monitoring.
 * <p>
 * 此外实现了 Lock 接口，定义了一些检查锁状态的方法。
 *
 * <p>Serialization of this class behaves in the same way as built-in
 * locks: a deserialized lock is in the unlocked state, regardless of
 * its state when serialized.
 *
 * <p>This lock supports a maximum of 2147483647 recursive locks by
 * the same thread. Attempts to exceed this limit result in
 * {@link Error} throws from locking methods.
 *
 * @author Doug Lea
 * @since 1.5
 */
public class ReentrantLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = 7373984872572414699L;
    /**
     * Synchronizer providing all implementation mechanics
     * <p>
     * 同步器，是一个内部类，继承自 AbstractQueuedSynchronizer
     */
    private final Sync sync;

    /**
     * Base of synchronization control for this lock. Subclassed
     * into fair and nonfair versions below. Uses AQS state to
     * represent the number of holds on the lock.
     * <p>
     * 抽象类，具体的实现在其子类 FairSync 和 NonfairSync
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = -5179523762034025860L;

        /**
         * Performs non-fair tryLock.
         * <p>
         * 非公平锁的 tryLock 实现
         */
        @ReservedStackAccess
        final boolean tryLock() {
            // 调用线程
            Thread current = Thread.currentThread();
            // 获取同步的状态
            int c = getState();
            // 如果 state == 0，说明锁没有被占有
            if (c == 0) {
                // cas 尝试将 state 设置为 1
                if (compareAndSetState(0, 1)) {
                    // 如果设置成功，说明获取到锁，设置锁的占有线程为当前线程
                    setExclusiveOwnerThread(current);
                    return true;
                }
                // 如果 state 不为 0，说明锁已经被占有
                // 判断锁的持有线程是否为当前线程，如果是的话则说明是重入锁
            } else if (getExclusiveOwnerThread() == current) {
                // state ++
                // 检查是否越界，如果重入次数实在太多可以考虑使用 AbstractQueuedLongSynchronizer
                if (++c < 0) // overflow
                    // 抛出异常
                    throw new Error("Maximum lock count exceeded");
                // 设置新的 state
                setState(c);
                return true;
            }
            // 否则获取失败，返回 false
            return false;
        }

        /**
         * Checks for reentrancy and acquires if lock immediately
         * available under fair vs nonfair rules. Locking methods
         * perform initialTryLock check before relaying to
         * corresponding AQS acquire methods.
         * <p>
         * 检查可重入性以及是否在 fair 和 nonfair 规则下可以马上获取到锁。
         * 在调用 AQS 中的 acquire 方法前一般需要先调用该方法
         * 该方法由具体子类实现，是一个 prescreen 预筛选方法
         */
        abstract boolean initialTryLock();

        /**
         * 获取锁方法
         */
        @ReservedStackAccess
        final void lock() {
            // 尝试是否可以快速获取
            if (!initialTryLock())
                // 快速获取失败，调用 AQS 的 acquire 方法获取
                acquire(1);
        }

        /**
         * 可处理中断地获取锁
         *
         * @throws InterruptedException
         */
        @ReservedStackAccess
        final void lockInterruptibly() throws InterruptedException {
            // 检查中断
            // 如果未开始获取前发生了中断
            if (Thread.interrupted())
                // 直接抛出异常
                throw new InterruptedException();
            // 尝试快速获取
            if (!initialTryLock())
                // 调用 AQS 的 acquireInterruptibly
                acquireInterruptibly(1);
        }

        /**
         * 可处理超时的获取锁
         *
         * @param nanos
         * @return
         * @throws InterruptedException
         */
        @ReservedStackAccess
        final boolean tryLockNanos(long nanos) throws InterruptedException {
            // 如果未获取之前发生了中断，直接抛异常结束
            if (Thread.interrupted())
                throw new InterruptedException();
            // 先快速尝试获取锁，获取失败再尝试使用 AQS 中的方法获取
            return initialTryLock() || tryAcquireNanos(1, nanos);
        }

        /**
         * 释放锁
         *
         * @param releases
         * @return
         */
        @ReservedStackAccess
        protected final boolean tryRelease(int releases) {
            // 释放锁之后的 state
            int c = getState() - releases;
            // 如果调用释放锁的方法并没有持有锁，则抛出异常
            if (getExclusiveOwnerThread() != Thread.currentThread())
                throw new IllegalMonitorStateException();
            // 如果是完全释放锁
            boolean free = (c == 0);
            if (free)
                // 需要将独占锁的线程标识置为 null
                setExclusiveOwnerThread(null);
            // 设置新的 state
            setState(c);
            // 返回是否完全释放锁
            return free;
        }

        /**
         * 返回锁是否被调用线程独占
         *
         * @return
         */
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        /**
         * 创建一个 ConditionObject 对象，也即创建一个 condition queue 队列
         *
         * @return
         */
        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        // Methods relayed from outer class

        /**
         * 获取当前持有锁的线程
         *
         * @return
         */
        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        /**
         * 获得当前锁的重入层数
         * 如果是持有锁的线程调用该方法，则返回锁的层数。
         * 如果调用该方法的线程未持有锁，则返回 0.
         *
         * @return
         */
        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        /**
         * 返回锁是否被持有
         *
         * @return
         */
        final boolean isLocked() {
            return getState() != 0;
        }

        /**
         * Reconstitutes the instance from a stream (that is, deserializes it).
         * <p>
         * 反序列化方法
         */
        private void readObject(java.io.ObjectInputStream s)
                throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // reset to unlocked state
        }
    }

    /**
     * Sync object for non-fair locks
     * <p>
     * 非公平锁的实现，继承及 Sync 类
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;

        /**
         * 实现 Sync 类中的抽象方法
         * 快速获取锁
         *
         * @return
         */
        final boolean initialTryLock() {
            // 调用线程
            Thread current = Thread.currentThread();
            // 快速尝试获取锁
            // 更新 state 为 1
            if (compareAndSetState(0, 1)) { // first attempt is unguarded
                // 如果设置成功
                // 设置 ExclusiveOwnerThread 为当前线程
                setExclusiveOwnerThread(current);
                // 并返回
                return true;
                // 如果是重入操作
            } else if (getExclusiveOwnerThread() == current) {
                // state + 1
                int c = getState() + 1;
                // 如果 state 越界，抛异常
                if (c < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                // 设置新的 state
                setState(c);
                // 返回成功标识
                return true;
            } else
                // 返回 false，快速获取失败
                return false;
        }

        /**
         * Acquire for non-reentrant cases after initialTryLock prescreen
         * <p>
         * 不可重入情况下的获取方法，在 initialTryLock 返回 失败后
         */
        protected final boolean tryAcquire(int acquires) {
            if (getState() == 0 && compareAndSetState(0, acquires)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
    }

    /**
     * Sync object for fair locks
     * <p>
     * 公平锁
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;

        /**
         * Acquires only if reentrant or queue is empty.
         * <p>
         * 当且仅当锁是可重入的或者 sync queue 是空的
         */
        final boolean initialTryLock() {
            // 当前线程
            Thread current = Thread.currentThread();
            // 当前的 state
            int c = getState();
            // 如果 state == 0 ，说明锁未被持有
            if (c == 0) {
                // 如果队列中没有等待线程 && 成功将 state 设置为 1
                if (!hasQueuedThreads() && compareAndSetState(0, 1)) {
                    // 表明获取到锁
                    // 设置独占线程为当前线程
                    setExclusiveOwnerThread(current);
                    // 返回获取锁成功
                    return true;
                }
                // 如果锁已经被持有
                // 如果是当前线程持有
                // 则表示在重入
            } else if (getExclusiveOwnerThread() == current) {
                // 检查 state 是否越界
                if (++c < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                // 设置新的 state
                setState(c);
                // 返回获取锁成功
                return true;
            }
            // 预获取锁失败
            return false;
        }

        /**
         * Acquires only if thread is first waiter or empty
         * <p>
         * 只有当 sync queue 是空的或者当前线程是第一个等待的线程才可以 acquire
         */
        protected final boolean tryAcquire(int acquires) {
            // 当前锁未被持有 && 前驱没有等待时间更久的线程 && 成功通过 cas 将 state 更新
            if (getState() == 0 && !hasQueuedPredecessors() &&
                    compareAndSetState(0, acquires)) {
                // 设置独占线程为当前线程
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            // 获取失败
            return false;
        }
    }

    /**
     * Creates an instance of {@code ReentrantLock}.
     * This is equivalent to using {@code ReentrantLock(false)}.
     * <p>
     * 构造函数，默认创建的是非公平的实例
     */
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    /**
     * Creates an instance of {@code ReentrantLock} with the
     * given fairness policy.
     * <p>
     * 构造函数，通过传入参数指定创建公平锁或非公平锁。
     * true -> 公平锁
     * false -> 非公平锁
     *
     * @param fair {@code true} if this lock should use a fair ordering policy
     */
    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

    /**
     * Acquires the lock.
     * <p>
     * 请求锁
     *
     * <p>Acquires the lock if it is not held by another thread and returns
     * immediately, setting the lock hold count to one.
     * <p>
     * 将 state 设为 1。
     *
     * <p>If the current thread already holds the lock then the hold
     * count is incremented by one and the method returns immediately.
     * <p>
     * 如果当前线程早已持有锁，那么 state + 1
     *
     * <p>If the lock is held by another thread then the
     * current thread becomes disabled for thread scheduling
     * purposes and lies dormant until the lock has been acquired,
     * at which time the lock hold count is set to one.
     * <p>
     * 如果锁已经被其他线程持有，那么当前线程将会失去调度，并进入 sync 队列等待。
     */
    public void lock() {
        sync.lock();
    }

    /**
     * Acquires the lock unless the current thread is
     * {@linkplain Thread#interrupt interrupted}.
     * <p>
     * 获取锁，除非被中断。
     *
     * <p>Acquires the lock if it is not held by another thread and returns
     * immediately, setting the lock hold count to one.
     * <p>
     * 将 state 设为 1。
     *
     * <p>If the current thread already holds this lock then the hold count
     * is incremented by one and the method returns immediately.
     * <p>
     * 可重入，state + 1。
     *
     * <p>If the lock is held by another thread then the
     * current thread becomes disabled for thread scheduling
     * purposes and lies dormant until one of two things happens:
     * <p>
     * 如果锁被其他线程占有，那么将会失去 CPU 的调度且阻塞，直到以下两种情况发生：
     *
     * <ul>
     * 锁被当前线程获得；或者其他线程中断了当前线程。
     * <li>The lock is acquired by the current thread; or
     *
     * <li>Some other thread {@linkplain Thread#interrupt interrupts} the
     * current thread.
     *
     * </ul>
     * 当前线程获得了锁且 state 被设为 1.
     * <p>If the lock is acquired by the current thread then the lock hold
     * count is set to one.
     *
     * <p>If the current thread:
     *
     * <ul>
     *
     * <li>has its interrupted status set on entry to this method; or
     * <p>
     * 如果当前线程在进入该方法前就已经设置了中断状态，或者
     *
     * <li>is {@linkplain Thread#interrupt interrupted} while acquiring
     * the lock,
     * <p>
     * 在获取锁的收被中断了。(这里指的是在获取锁的方法中会检查中断标记)
     *
     * </ul>
     * <p>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     * <p>
     * 那么将会抛出 InterruptedException 异常并且清除中断标识。
     *
     * <p>In this implementation, as this method is an explicit
     * interruption point, preference is given to responding to the
     * interrupt over normal or reentrant acquisition of the lock.
     *
     * @throws InterruptedException if the current thread is interrupted
     */
    public void lockInterruptibly() throws InterruptedException {
        sync.lockInterruptibly();
    }

    /**
     * Acquires the lock only if it is not held by another thread at the time
     * of invocation.
     * <p>
     * 获取锁，只要在该方法被调用时锁没有被其他线程持有。
     *
     * <p>Acquires the lock if it is not held by another thread and
     * returns immediately with the value {@code true}, setting the
     * lock hold count to one. Even when this lock has been set to use a
     * fair ordering policy, a call to {@code tryLock()} <em>will</em>
     * immediately acquire the lock if it is available, whether or not
     * other threads are currently waiting for the lock.
     * This &quot;barging&quot; behavior can be useful in certain
     * circumstances, even though it breaks fairness. If you want to honor
     * the fairness setting for this lock, then use
     * {@link #tryLock(long, TimeUnit) tryLock(0, TimeUnit.SECONDS)}
     * which is almost equivalent (it also detects interruption).
     * <p>
     * 当锁没有被其他线程持有的时候会立刻返回 true，并且将 state 设为 1。
     * !!!tryLock 方法在公平锁中也会立刻去尝试获取锁，不管当前的 sync queue 中有没有等待的线程。!!!
     * 该方法破坏了 fairness。如果想使用该方法，但是是以公平的方式，那么可以使用如下调用方法。
     * {@link #tryLock(long, TimeUnit) tryLock(0, TimeUnit.SECONDS)}
     *
     * <p>If the current thread already holds this lock then the hold
     * count is incremented by one and the method returns {@code true}.
     * <p>
     * 重入，state + 1。
     *
     * <p>If the lock is held by another thread then this method will return
     * immediately with the value {@code false}.
     * <p>
     * 如果锁被其他线程占有，那么会立刻返回 false。
     *
     * @return {@code true} if the lock was free and was acquired by the
     * current thread, or the lock was already held by the current
     * thread; and {@code false} otherwise
     */
    public boolean tryLock() {
        return sync.tryLock();
    }

    /**
     * 参考 {@link #tryLock()}
     * <p>
     * Acquires the lock if it is not held by another thread within the given
     * waiting time and the current thread has not been
     * {@linkplain Thread#interrupt interrupted}.
     * <p>
     * 在给定的等待时间内 state == 0，就能成功获取锁。并且这段时间内不被中断。
     *
     * <p>Acquires the lock if it is not held by another thread and returns
     * immediately with the value {@code true}, setting the lock hold count
     * to one. If this lock has been set to use a fair ordering policy then
     * an available lock <em>will not</em> be acquired if any other threads
     * are waiting for the lock. This is in contrast to the {@link #tryLock()}
     * method. If you want a timed {@code tryLock} that does permit barging on
     * a fair lock then combine the timed and un-timed forms together:
     * <p>
     * 如果需要在公平锁下同时尝试待超时时间的 tryLock 和不带超时时间的 tryLock，则使用如下的写法。
     *
     * <pre> {@code
     * if (lock.tryLock() ||
     *     lock.tryLock(timeout, unit)) {
     *   ...
     * }}</pre>
     *
     * <p>If the current thread
     * already holds this lock then the hold count is incremented by one and
     * the method returns {@code true}.
     *
     * <p>If the lock is held by another thread then the
     * current thread becomes disabled for thread scheduling
     * purposes and lies dormant until one of three things happens:
     * <p>
     * 三种情况结束阻塞：
     *
     * <ul>
     *
     * <li>The lock is acquired by the current thread; or
     * 1. 获取到锁
     *
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * 2. 其他线程中断当前线程
     *
     * <li>The specified waiting time elapses
     * 3. 超时时间到了
     *
     * </ul>
     *
     * <p>If the lock is acquired then the value {@code true} is returned and
     * the lock hold count is set to one.
     *
     * <p>If the current thread:
     *
     * <ul>
     *
     * <li>has its interrupted status set on entry to this method; or
     * 1. 在进入该方法的时候已经设置了中断标识
     *
     * <li>is {@linkplain Thread#interrupt interrupted} while
     * acquiring the lock,
     * 2. 在获取的时候被中断
     *
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     * 抛出异常并清除中断标识
     *
     * <p>If the specified waiting time elapses then the value {@code false}
     * is returned.  If the time is less than or equal to zero, the method
     * will not wait at all.
     * 等待时间过了指定时间，那么将会返回 false，如果传入的时间 <= 0，那么该方法不会等待。
     *
     * <p>In this implementation, as this method is an explicit
     * interruption point, preference is given to responding to the
     * interrupt over normal or reentrant acquisition of the lock, and
     * over reporting the elapse of the waiting time.
     *
     * @param timeout the time to wait for the lock
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if the lock was free and was acquired by the
     * current thread, or the lock was already held by the current
     * thread; and {@code false} if the waiting time elapsed before
     * the lock could be acquired
     * @throws InterruptedException if the current thread is interrupted
     * @throws NullPointerException if the time unit is null
     */
    public boolean tryLock(long timeout, TimeUnit unit)
            throws InterruptedException {
        return sync.tryLockNanos(unit.toNanos(timeout));
    }

    /**
     * Attempts to release this lock.
     * <p>
     * 尝试释放锁
     *
     * <p>If the current thread is the holder of this lock then the hold
     * count is decremented.  If the hold count is now zero then the lock
     * is released.  If the current thread is not the holder of this
     * lock then {@link IllegalMonitorStateException} is thrown.
     * <p>
     * 如果当前线程是锁的持有者，那么 state - 1。如果 state == 0 了，那么释放锁。
     * 如果当前线程不是锁的持有者，那么将会抛出 IllegalMonitorStateException 异常。
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
     * <p>
     * 返回一个 Lock 实例对应的 Condition 实例
     * 一个 Lock 实例可以有多个 Condition 实例
     *
     * <p>The returned {@link Condition} instance supports the same
     * usages as do the {@link Object} monitor methods ({@link
     * Object#wait() wait}, {@link Object#notify notify}, and {@link
     * Object#notifyAll notifyAll}) when used with the built-in
     * monitor lock.
     * <p>
     * Condition 对标 Object 类中的 monitor 方法。
     *
     * <ul>
     *
     * <li>If this lock is not held when any of the {@link Condition}
     * {@linkplain Condition#await() waiting} or {@linkplain
     * Condition#signal signalling} methods are called, then an {@link
     * IllegalMonitorStateException} is thrown.
     * <p>
     * 如果锁未被持有，但是调用了 Condition 的方法，就会抛出 IllegalMonitorStateException
     *
     * <li>When the condition {@linkplain Condition#await() waiting}
     * methods are called the lock is released and, before they
     * return, the lock is reacquired and the lock hold count restored
     * to what it was when the method was called.
     * <p>
     * 当 {@linkplain Condition#await() waiting} 方法还在等待的时候锁被释放了，
     * 锁会被重新获取，且 state 计数会被恢复到 {@linkplain Condition#await() waiting}
     * 方法被调用的时候的值。
     *
     * <li>If a thread is {@linkplain Thread#interrupt interrupted}
     * while waiting then the wait will terminate, an {@link
     * InterruptedException} will be thrown, and the thread's
     * interrupted status will be cleared.
     * <p>
     * 当在等待的时候线程被中断，那么会抛出一个中断异常，并且清除中断标识。
     *
     * <li>Waiting threads are signalled in FIFO order.
     * <p>
     * condition queue 以 FIFO 的顺序被唤醒
     *
     * <li>The ordering of lock reacquisition for threads returning
     * from waiting methods is the same as for threads initially
     * acquiring the lock, which is in the default case not specified,
     * but for <em>fair</em> locks favors those threads that have been
     * waiting the longest.
     * <p>
     * 从等待方法中返回的线程重新获取锁的顺序与线程初始化获取锁的顺序一致。
     *
     * </ul>
     *
     * @return the Condition object
     */
    public Condition newCondition() {
        return sync.newCondition();
    }

    /**
     * Queries the number of holds on this lock by the current thread.
     * <p>
     * 查询当前的 state
     *
     * <p>A thread has a hold on a lock for each lock action that is not
     * matched by an unlock action.
     *
     * <p>The hold count information is typically only used for testing and
     * debugging purposes. For example, if a certain section of code should
     * not be entered with the lock already held then we can assert that
     * fact:
     * <p>
     * 一般用于测试和 debug 的目的。
     *
     * <pre> {@code
     * class X {
     *   ReentrantLock lock = new ReentrantLock();
     *   // ...
     *   public void m() {
     *     assert lock.getHoldCount() == 0;
     *     lock.lock();
     *     try {
     *       // ... method body
     *     } finally {
     *       lock.unlock();
     *     }
     *   }
     * }}</pre>
     *
     * @return the number of holds on this lock by the current thread,
     * or zero if this lock is not held by the current thread
     */
    public int getHoldCount() {
        return sync.getHoldCount();
    }

    /**
     * Queries if this lock is held by the current thread.
     * <p>
     * 返回锁是否被当前线程持有，委托给了 isHeldExclusively() 方法
     *
     * <p>Analogous to the {@link Thread#holdsLock(Object)} method for
     * built-in monitor locks, this method is typically used for
     * debugging and testing. For example, a method that should only be
     * called while a lock is held can assert that this is the case:
     *
     * <pre> {@code
     * class X {
     *   ReentrantLock lock = new ReentrantLock();
     *   // ...
     *
     *   public void m() {
     *       assert lock.isHeldByCurrentThread();
     *       // ... method body
     *   }
     * }}</pre>
     *
     * <p>It can also be used to ensure that a reentrant lock is used
     * in a non-reentrant manner, for example:
     *
     * <pre> {@code
     * class X {
     *   ReentrantLock lock = new ReentrantLock();
     *   // ...
     *
     *   public void m() {
     *       assert !lock.isHeldByCurrentThread();
     *       lock.lock();
     *       try {
     *           // ... method body
     *       } finally {
     *           lock.unlock();
     *       }
     *   }
     * }}</pre>
     *
     * @return {@code true} if current thread holds this lock and
     * {@code false} otherwise
     */
    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * Queries if this lock is held by any thread. This method is
     * designed for use in monitoring of the system state,
     * not for synchronization control.
     * <p>
     * 返回锁是否被持有，主要用于监控同步器的状态，不用于同步控制。
     *
     * @return {@code true} if any thread holds this lock and
     * {@code false} otherwise
     */
    public boolean isLocked() {
        return sync.isLocked();
    }

    /**
     * Returns {@code true} if this lock has fairness set true.
     * <p>
     * 当前锁实例是否是公平锁
     *
     * @return {@code true} if this lock has fairness set true
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * Returns the thread that currently owns this lock, or
     * {@code null} if not owned. When this method is called by a
     * thread that is not the owner, the return value reflects a
     * best-effort approximation of current lock status. For example,
     * the owner may be momentarily {@code null} even if there are
     * threads trying to acquire the lock but have not yet done so.
     * This method is designed to facilitate construction of
     * subclasses that provide more extensive lock monitoring
     * facilities.
     * <p>
     * 返回当前持有锁的线程。
     *
     * @return the owner, or {@code null} if not owned
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * Queries whether any threads are waiting to acquire this lock. Note that
     * because cancellations may occur at any time, a {@code true}
     * return does not guarantee that any other thread will ever
     * acquire this lock.  This method is designed primarily for use in
     * monitoring of the system state.
     * <p>
     * 返回是否有线程在等待获取锁。不一定准确
     *
     * @return {@code true} if there may be other threads waiting to
     * acquire the lock
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * Queries whether the given thread is waiting to acquire this
     * lock. Note that because cancellations may occur at any time, a
     * {@code true} return does not guarantee that this thread
     * will ever acquire this lock.  This method is designed primarily for use
     * in monitoring of the system state.
     * <p>
     * 返回指定线程是否在等待获取锁
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
     * this lock.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization control.
     * <p>
     * 返回等待队列中的线程数
     *
     * @return the estimated number of threads waiting for this lock
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire this lock.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     * <p>
     * 返回等待队列中线程的集合
     *
     * @return the collection of threads
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this lock. Note that because timeouts and
     * interrupts may occur at any time, a {@code true} return does
     * not guarantee that a future {@code signal} will awaken any
     * threads.  This method is designed primarily for use in
     * monitoring of the system state.
     * <p>
     * 返回在指定的 Condition 中是否有等待节点
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if this lock is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this lock
     * @throws NullPointerException         if the condition is null
     */
    public boolean hasWaiters(Condition condition) {
        // 检查 condition
        if (condition == null)
            throw new NullPointerException();
        // 检查类型
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        // 委托给 AQS 中的实现
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this lock. Note that because
     * timeouts and interrupts may occur at any time, the estimate
     * serves only as an upper bound on the actual number of waiters.
     * This method is designed for use in monitoring of the system
     * state, not for synchronization control.
     * <p>
     * 返回 condition 实例对应的 condition queue 中等到节点的个数
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
        // 同样委托给 AQS 中的实现
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this lock.
     * Because the actual set of threads may change dynamically while
     * constructing this result, the returned collection is only a
     * best-effort estimate. The elements of the returned collection
     * are in no particular order.  This method is designed to
     * facilitate construction of subclasses that provide more
     * extensive condition monitoring facilities.
     * <p>
     * 返回给定 condition 实例对应的 condition queue 中的等待节点绑定的线程的集合
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
        // 委托给 AQS
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    /**
     * Returns a string identifying this lock, as well as its lock state.
     * The state, in brackets, includes either the String {@code "Unlocked"}
     * or the String {@code "Locked by"} followed by the
     * {@linkplain Thread#getName name} of the owning thread.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                "[Unlocked]" :
                "[Locked by thread " + o.getName() + "]");
    }
}
