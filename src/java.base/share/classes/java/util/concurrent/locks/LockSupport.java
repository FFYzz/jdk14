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

/**
 * Basic thread blocking primitives for creating locks and other
 * synchronization classes.
 * <p>
 * 提供了基础的线程阻塞原语操作
 *
 * <p>This class associates, with each thread that uses it, a permit
 * (in the sense of the {@link java.util.concurrent.Semaphore
 * Semaphore} class). A call to {@code park} will return immediately
 * if the permit is available, consuming it in the process; otherwise
 * it <em>may</em> block.  A call to {@code unpark} makes the permit
 * available, if it was not already available. (Unlike with Semaphores
 * though, permits do not accumulate. There is at most one.)
 * Reliable usage requires the use of volatile (or atomic) variables
 * to control when to park or unpark.  Orderings of calls to these
 * methods are maintained with respect to volatile variable accesses,
 * but not necessarily non-volatile variable accesses.
 * <p>
 * permit 默认为 0。
 * 每个使用 LockSupport 的线程都关联到一个 permit(某种意义上类似于 Semaphore 类)。
 * 调用 park 方法，如果 permit 是 available(可以理解为当前 permit 的计数是 1 )，那么该 permit
 * 会立即被使用(permit - 1 = 0，permit 的最小值只能为 0)，并且方法会立即返回，线程不会被阻塞。 如果 permit 为 0 ，调用
 * park 方法，则线程会被阻塞。如果当前线程关联的 permit 不是为 available 的，
 * unpark 方法会使得 permit available (permit + 1，但是 permit 的最大值也只能为 1)，
 *
 * <p>Methods {@code park} and {@code unpark} provide efficient
 * means of blocking and unblocking threads that do not encounter the
 * problems that cause the deprecated methods {@code Thread.suspend}
 * and {@code Thread.resume} to be unusable for such purposes: Races
 * between one thread invoking {@code park} and another thread trying
 * to {@code unpark} it will preserve liveness, due to the
 * permit. Additionally, {@code park} will return if the caller's
 * thread was interrupted, and timeout versions are supported. The
 * {@code park} method may also return at any other time, for "no
 * reason", so in general must be invoked within a loop that rechecks
 * conditions upon return. In this sense {@code park} serves as an
 * optimization of a "busy wait" that does not waste as much time
 * spinning, but must be paired with an {@code unpark} to be
 * effective.
 * park 和 unpark 方法提供了高效的阻塞和解除阻塞的方法，并且不会出现 {@code Thread.suspend}
 * 和 {@code Thread.resume} 方法存在的问题。
 *
 * <p>The three forms of {@code park} each also support a
 * {@code blocker} object parameter. This object is recorded while
 * the thread is blocked to permit monitoring and diagnostic tools to
 * identify the reasons that threads are blocked. (Such tools may
 * access blockers using method {@link #getBlocker(Thread)}.)
 * The use of these forms rather than the original forms without this
 * parameter is strongly encouraged. The normal argument to supply as
 * a {@code blocker} within a lock implementation is {@code this}.
 *
 * <p>These methods are designed to be used as tools for creating
 * higher-level synchronization utilities, and are not in themselves
 * useful for most concurrency control applications.  The {@code park}
 * method is designed for use only in constructions of the form:
 *
 * <pre> {@code
 * while (!canProceed()) {
 *   // ensure request to unpark is visible to other threads
 *   ...
 *   LockSupport.park(this);
 * }}</pre>
 * <p>
 * where no actions by the thread publishing a request to unpark,
 * prior to the call to {@code park}, entail locking or blocking.
 * Because only one permit is associated with each thread, any
 * intermediary uses of {@code park}, including implicitly via class
 * loading, could lead to an unresponsive thread (a "lost unpark").
 *
 * <p><b>Sample Usage.</b> Here is a sketch of a first-in-first-out
 * non-reentrant lock class:
 * <pre> {@code
 * class FIFOMutex {
 *   private final AtomicBoolean locked = new AtomicBoolean(false);
 *   private final Queue<Thread> waiters
 *     = new ConcurrentLinkedQueue<>();
 *
 *   public void lock() {
 *     boolean wasInterrupted = false;
 *     // publish current thread for unparkers
 *     waiters.add(Thread.currentThread());
 *
 *     // Block while not first in queue or cannot acquire lock
 *     while (waiters.peek() != Thread.currentThread() ||
 *            !locked.compareAndSet(false, true)) {
 *       LockSupport.park(this);
 *       // ignore interrupts while waiting
 *       if (Thread.interrupted())
 *         wasInterrupted = true;
 *     }
 *
 *     waiters.remove();
 *     // ensure correct interrupt status on return
 *     if (wasInterrupted)
 *       Thread.currentThread().interrupt();
 *   }
 *
 *   public void unlock() {
 *     locked.set(false);
 *     LockSupport.unpark(waiters.peek());
 *   }
 *
 *   static {
 *     // Reduce the risk of "lost unpark" due to classloading
 *     Class<?> ensureLoaded = LockSupport.class;
 *   }
 * }}</pre>
 *
 * @since 1.5
 */
public class LockSupport {
    // 不能被实例化
    private LockSupport() {
    } // Cannot be instantiated.

    /**
     * 设置线程的阻塞对象，用于记录线程是被哪个对象阻塞的
     * 只有在 parkXXX 类方法中会被调用
     *
     * @param t
     * @param arg
     */
    private static void setBlocker(Thread t, Object arg) {
        U.putReferenceOpaque(t, PARKBLOCKER, arg);
    }

    /**
     * Sets the object to be returned by invocations of {@link
     * #getBlocker getBlocker} for the current thread. This method may
     * be used before invoking the no-argument version of {@link
     * LockSupport#park() park()} from non-public objects, allowing
     * more helpful diagnostics 诊断, or retaining compatibility with
     * previous implementations of blocking methods.  Previous values
     * of the blocker are not automatically restored after blocking.
     * To obtain the effects of {@code park(b}}, use {@code
     * setCurrentBlocker(b); park(); setCurrentBlocker(null);}
     * <p>
     * 设置线程的阻塞对象
     *
     * @param blocker the blocker object
     * @since 14
     */
    public static void setCurrentBlocker(Object blocker) {
        U.putReferenceOpaque(Thread.currentThread(), PARKBLOCKER, blocker);
    }

    /**
     * Makes available the permit for the given thread, if it
     * was not already available.  If the thread was blocked on
     * {@code park} then it will unblock.  Otherwise, its next call
     * to {@code park} is guaranteed not to block. This operation
     * is not guaranteed to have any effect at all if the given
     * thread has not been started.
     * <p>
     * 1. 如果 permit 不是 available (permit = 0) 的，那么调用之后会将 permit 设为 available (permit = 1)。
     * 2. 如果线程是被 blocked 的，那么会 unblock。
     * 以上两种情况不是同一种情况
     *
     * @param thread the thread to unpark, or {@code null}, in which case
     *               this operation has no effect 要进行 unpark 操作的线程 / 如果传入为 null 则无效
     */
    public static void unpark(Thread thread) {
        if (thread != null)
            U.unpark(thread);
    }

    /**
     * Disables the current thread for thread scheduling purposes unless the
     * permit is available.
     * <p>
     * 使当前线程阻塞，除非当前的 permit = 1
     *
     * <p>If the permit is available then it is consumed and the call returns
     * immediately; otherwise
     * the current thread becomes disabled for thread scheduling
     * purposes and lies dormant 休眠/阻塞 until one of three things happens:
     *
     * <ul>
     * <li>Some other thread invokes {@link #unpark unpark} with the
     * current thread as the target; or
     * <p>
     * 1. 其他线程调用 unpark 将其唤醒
     *
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <p>
     * 2. 其中线程发起中断
     *
     * <li>The call spuriously (that is, for no reason) returns.
     * </ul>
     * <p>
     * 3. 调用无理由返回
     *
     * <p>This method does <em>not</em> report which of these caused the
     * method to return. Callers should re-check the conditions which caused
     * the thread to park in the first place. Callers may also determine,
     * for example, the interrupt status of the thread upon return.
     *
     * @param blocker the synchronization object responsible for this
     *                thread parking 该线程的同步对象
     * @since 1.6
     */
    public static void park(Object blocker) {
        Thread t = Thread.currentThread();
        setBlocker(t, blocker);
        U.park(false, 0L);
        // 线程接触阻塞后清除 blocker 标志
        setBlocker(t, null);
    }

    /**
     * Disables the current thread for thread scheduling purposes, for up to
     * the specified waiting time, unless the permit is available.
     * <p>
     * 阻塞等待时间
     *
     * <p>If the specified waiting time is zero or negative, the
     * method does nothing. Otherwise, if the permit is available then
     * it is consumed and the call returns immediately; otherwise the
     * current thread becomes disabled for thread scheduling purposes
     * and lies dormant until one of four things happens:
     * <p>
     * 指定的时间参数为 0 或者 负数，则相当于没有调用该方法。
     *
     * <ul>
     * <li>Some other thread invokes {@link #unpark unpark} with the
     * current thread as the target; or
     * <p>
     * 参考前面 1.
     *
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <p>
     * 参考前面 2.
     *
     * <li>The specified waiting time elapses; or
     * <p>
     * 阻塞的时间过去
     *
     * <li>The call spuriously (that is, for no reason) returns.
     * </ul>
     * <p>
     * 参考前面 3.
     *
     * <p>This method does <em>not</em> report which of these caused the
     * method to return. Callers should re-check the conditions which caused
     * the thread to park in the first place. Callers may also determine,
     * for example, the interrupt status of the thread, or the elapsed time
     * upon return.
     *
     * @param blocker the synchronization object responsible for this
     *                thread parking
     * @param nanos   the maximum number of nanoseconds to wait 最大等待的 nanoseconds 时间
     * @since 1.6
     */
    public static void parkNanos(Object blocker, long nanos) {
        if (nanos > 0) {
            Thread t = Thread.currentThread();
            setBlocker(t, blocker);
            U.park(false, nanos);
            // 线程接触阻塞后清除 blocker 标志
            setBlocker(t, null);
        }
    }

    /**
     * Disables the current thread for thread scheduling purposes, until
     * the specified deadline, unless the permit is available.
     *
     * <p>If the permit is available then it is consumed and the call
     * returns immediately; otherwise the current thread becomes disabled
     * for thread scheduling purposes and lies dormant until one of four
     * things happens:
     *
     * <ul>
     * <li>Some other thread invokes {@link #unpark unpark} with the
     * current thread as the target; or
     *
     * <li>Some other thread {@linkplain Thread#interrupt interrupts} the
     * current thread; or
     *
     * <li>The specified deadline passes; or
     *
     * <li>The call spuriously (that is, for no reason) returns.
     * </ul>
     *
     * <p>This method does <em>not</em> report which of these caused the
     * method to return. Callers should re-check the conditions which caused
     * the thread to park in the first place. Callers may also determine,
     * for example, the interrupt status of the thread, or the current time
     * upon return.
     *
     * @param blocker  the synchronization object responsible for this
     *                 thread parking
     * @param deadline the absolute time, in milliseconds from the Epoch,
     *                 to wait until 绝对时间， 可以用 System.currentTimeMillis() + timeLength 来控制时间
     *                 deadline 就是 System.currentTimeMillis() 时间
     * @since 1.6
     */
    public static void parkUntil(Object blocker, long deadline) {
        Thread t = Thread.currentThread();
        setBlocker(t, blocker);
        U.park(true, deadline);
        setBlocker(t, null);
    }

    /**
     * Returns the blocker object supplied to the most recent
     * invocation of a park method that has not yet unblocked, or null
     * if not blocked.  The value returned is just a momentary
     * snapshot -- the thread may have since unblocked or blocked on a
     * different blocker object.
     * <p>
     * 返回阻塞该线程的对象
     *
     * @param t the thread
     * @return the blocker
     * @throws NullPointerException if argument is null
     * @since 1.6
     */
    public static Object getBlocker(Thread t) {
        if (t == null)
            throw new NullPointerException();
        return U.getReferenceOpaque(t, PARKBLOCKER);
    }

    /**
     * Disables the current thread for thread scheduling purposes unless the
     * permit is available.
     *
     * <p>If the permit is available then it is consumed and the call
     * returns immediately; otherwise the current thread becomes disabled
     * for thread scheduling purposes and lies dormant until one of three
     * things happens:
     *
     * <ul>
     *
     * <li>Some other thread invokes {@link #unpark unpark} with the
     * current thread as the target; or
     *
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     *
     * <li>The call spuriously (that is, for no reason) returns.
     * </ul>
     *
     * <p>This method does <em>not</em> report which of these caused the
     * method to return. Callers should re-check the conditions which caused
     * the thread to park in the first place. Callers may also determine,
     * for example, the interrupt status of the thread upon return.
     */
    public static void park() {
        U.park(false, 0L);
    }

    /**
     * Disables the current thread for thread scheduling purposes, for up to
     * the specified waiting time, unless the permit is available.
     *
     * <p>If the specified waiting time is zero or negative, the
     * method does nothing. Otherwise, if the permit is available then
     * it is consumed and the call returns immediately; otherwise the
     * current thread becomes disabled for thread scheduling purposes
     * and lies dormant until one of four things happens:
     *
     * <ul>
     * <li>Some other thread invokes {@link #unpark unpark} with the
     * current thread as the target; or
     *
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     *
     * <li>The specified waiting time elapses; or
     *
     * <li>The call spuriously (that is, for no reason) returns.
     * </ul>
     *
     * <p>This method does <em>not</em> report which of these caused the
     * method to return. Callers should re-check the conditions which caused
     * the thread to park in the first place. Callers may also determine,
     * for example, the interrupt status of the thread, or the elapsed time
     * upon return.
     *
     * @param nanos the maximum number of nanoseconds to wait
     */
    public static void parkNanos(long nanos) {
        if (nanos > 0)
            U.park(false, nanos);
    }

    /**
     * Disables the current thread for thread scheduling purposes, until
     * the specified deadline, unless the permit is available.
     *
     * <p>If the permit is available then it is consumed and the call
     * returns immediately; otherwise the current thread becomes disabled
     * for thread scheduling purposes and lies dormant until one of four
     * things happens:
     *
     * <ul>
     * <li>Some other thread invokes {@link #unpark unpark} with the
     * current thread as the target; or
     *
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     *
     * <li>The specified deadline passes; or
     *
     * <li>The call spuriously (that is, for no reason) returns.
     * </ul>
     *
     * <p>This method does <em>not</em> report which of these caused the
     * method to return. Callers should re-check the conditions which caused
     * the thread to park in the first place. Callers may also determine,
     * for example, the interrupt status of the thread, or the current time
     * upon return.
     *
     * @param deadline the absolute time, in milliseconds from the Epoch,
     *                 to wait until
     */
    public static void parkUntil(long deadline) {
        U.park(true, deadline);
    }

    /**
     * Returns the thread id for the given thread.  We must access
     * this directly rather than via method Thread.getId() because
     * getId() has been known to be overridden in ways that do not
     * preserve unique mappings.
     */
    static final long getThreadId(Thread thread) {
        return U.getLong(thread, TID);
    }

    // Hotspot implementation via intrinsics API
    // 以下三个变量都是 Thread 类中的成员变量
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long PARKBLOCKER
            = U.objectFieldOffset(Thread.class, "parkBlocker");
    private static final long TID
            = U.objectFieldOffset(Thread.class, "tid");

}
