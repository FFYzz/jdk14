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

package java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 * @author Doug Lea
 * @since 1.5
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     * <p>
     * Possible state transitions:
     * // 正常执行的逻辑
     * // 可能成功 NORMAL，也可能异常 EXCEPTIONAL
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * // 取消逻辑
     * // 未开始，则直接 CANCELLED
     * // task 在执行过程中，则先置为 INTERRUPTING，待任务执行完毕之后，将状态置为 INTERRUPTED
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    /**
     * state 为 NEW 的时候 task 可能已经在 running
     */
    private static final int NEW = 0;
    private static final int COMPLETING = 1;
    private static final int NORMAL = 2;
    private static final int EXCEPTIONAL = 3;
    private static final int CANCELLED = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED = 6;

    /**
     * The underlying callable; nulled out after running
     */
    private Callable<V> callable;
    /**
     * task 返回的结果，可以是正常执行后的结果，也可以是异常信息
     * The result to return or exception to throw from get()
     */
    private Object outcome; // non-volatile, protected by state reads/writes
    /**
     * The thread running the callable; CASed during run()
     */
    private volatile Thread runner;
    /**
     * 获取结果的等待线程的链表
     * 头插法
     * Treiber stack of waiting threads
     */
    private volatile WaitNode waiters;

    /**
     * 返回 task 执行的任务结果或者 task 执行出问题则抛出异常
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL)
            return (V) x;
        if (s >= CANCELLED)
            throw new CancellationException();
        throw new ExecutionException((Throwable) x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        // 将状态置为 NEW
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result   the result to return on successful completion. If
     *                 you don't need a particular result, consider using
     *                 constructions of the form:
     *                 {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        // 封装成 callable
        this.callable = Executors.callable(runnable, result);
        // 将状态置为 NEW
        this.state = NEW;       // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    /**
     * 这里的 Done 并不一定指正常执行完成，还有可能是异常或者取消
     *
     * @return
     */
    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        // 当前状态不为 NEW 或者 不能通过 cas 将 state 的状态由 NEW 改变为 INTERRUPTING 或者 CANCELLED
        // 则直接返回 false
        // 说明当前 state 不为 NEW，任务正在执行或者已经执行完成
        if (!(state == NEW && STATE.compareAndSet
                (this, NEW, mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            // 如果允许任务执行过程中被中断
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        // 发起中断
                        t.interrupt();
                } finally { // final state
                    // 将 state 置为 INTERRUPTED
                    STATE.setRelease(this, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * 不带超时版本
     *
     * @throws CancellationException {@inheritDoc}
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        // 如果状态还处于 NEW 状态或者 处于 COMPLETING 状态
        // 说明任务还在执行过程中
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        // await 之后则返回
        return report(s);
    }

    /**
     * 带超时版本
     *
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            // 超时之后抛出异常
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() {
    }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        // 先将 state 更新为 COMPLETING
        if (STATE.compareAndSet(this, NEW, COMPLETING)) {
            // 保存结果
            outcome = v;
            // 将最终状态设置为 NORMAL
            STATE.setRelease(this, NORMAL); // final state
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        // 异常处理
        // 先将 state 置为 COMPLETING
        if (STATE.compareAndSet(this, NEW, COMPLETING)) {
            outcome = t;
            // 将 state 设置成 EXCEPTIONAL
            STATE.setRelease(this, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

    public void run() {
        // 执行 run 方法需要 state 为 NEW 且将成功将 runner 引用设置成指向 当前线程
        if (state != NEW ||
                !RUNNER.compareAndSet(this, null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;
                // 执行结果的标志
                boolean ran;
                try {
                    // 执行业务(task)逻辑
                    // 这是一个阻塞操作
                    result = c.call();
                    ran = true;
                    // 如果 task 在执行过程中出现了异常
                } catch (Throwable ex) {
                    // 异常处理
                    result = null;
                    ran = false;
                    setException(ex);
                }
                // 如果正常结束
                if (ran)
                    // 设置正常执行之后的结果
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            // 执行之后的清理
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            // 如果 task 执行过程中被中断，则进行中断处理
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
                !RUNNER.compareAndSet(this, null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                // 让出 CPU，-> Runnable 状态
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * 一个单链表
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;

        WaitNode() {
            thread = Thread.currentThread();
        }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null; ) {
            if (WAITERS.weakCompareAndSet(this, q, null)) {
                for (; ; ) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        // 唤醒 t 线程
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }

        done();

        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion or at timeout
     */
    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException {
        // The code below is very delicate, to achieve these goals:
        // - call nanoTime exactly once for each call to park
        // - if nanos <= 0L, return promptly without allocation or nanoTime
        // - if nanos == Long.MIN_VALUE, don't underflow
        // - if nanos == Long.MAX_VALUE, and nanoTime is non-monotonic
        //   and we suffer a spurious wakeup, we will do no worse than
        //   to park-spin for a while
        long startTime = 0L;    // Special value 0L means not yet parked
        WaitNode q = null;
        boolean queued = false;
        // 死循环
        for (; ; ) {
            int s = state;
            // task 已经有执行结果
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null;
                // 返回结果
                return s;
                // 还处于 COMPLETING 状态
            } else if (s == COMPLETING)
                // We may have already promised (via isDone) that we are done
                // so never return empty-handed or throw InterruptedException
                // 暂时放弃 CPU 等待重新调度
                // 当状态为 COMPLETING 的情况下，task 马上就会有结果
                // 所以只需让出 CPU 时间
                Thread.yield();
                // 检查中断
            else if (Thread.interrupted()) {
                // 如果被中断了，则取消获取(get)
                removeWaiter(q);
                throw new InterruptedException();
            } else if (q == null) {
                // 如果超时，则返回
                if (timed && nanos <= 0L)
                    return s;
                // 初始化 WaitNode 节点
                q = new WaitNode();
                // 如果没有入队，这里的队列指的是 WaitNode 构成的单链表
            } else if (!queued)
                // 入队操作
                // 头插法
                queued = WAITERS.weakCompareAndSet(this, q.next = waiters, q);
                // 如果设置了超时时间
            else if (timed) {
                final long parkNanos;
                // 第一次
                if (startTime == 0L) { // first time
                    // 获取当前 startTime
                    startTime = System.nanoTime();
                    //
                    if (startTime == 0L)
                        startTime = 1L;
                    // 记录超时时间
                    parkNanos = nanos;
                    // 不是第一次
                } else {
                    // 流逝时间 elapsed
                    long elapsed = System.nanoTime() - startTime;
                    // 如果流逝时间大于 传入超时时间
                    // 说明超时了
                    if (elapsed >= nanos) {
                        // 将等待节点移除
                        removeWaiter(q);
                        // 返回当前状态
                        return state;
                    }
                    // 没有超时，更新剩余超时时间
                    parkNanos = nanos - elapsed;
                }
                // nanoTime may be slow; recheck before parking
                if (state < COMPLETING)
                    // 根据剩余的时间进行超时阻塞
                    LockSupport.parkNanos(this, parkNanos);
            } else
                // 没有设置超时时间则无限制阻塞，直到被中断
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            // 清理链表
            retry:
            for (; ; ) {          // restart on removeWaiter race
                // 从头结点开始往后遍历
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    // 后继节点
                    s = q.next;
                    // 如果当前节点不为 null
                    if (q.thread != null)
                        // 将前驱指针指向当前节点
                        pred = q;
                        // 如果前驱节点不为空
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    } else if (!WAITERS.compareAndSet(this, q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    /**
     * Returns a string representation of this FutureTask.
     *
     * @return a string representation of this FutureTask
     * @implSpec The default implementation returns a string identifying this
     * FutureTask, as well as its completion state.  The state, in
     * brackets, contains one of the strings {@code "Completed Normally"},
     * {@code "Completed Exceptionally"}, {@code "Cancelled"}, or {@code
     * "Not completed"}.
     */
    public String toString() {
        final String status;
        switch (state) {
            case NORMAL:
                status = "[Completed normally]";
                break;
            case EXCEPTIONAL:
                status = "[Completed exceptionally: " + outcome + "]";
                break;
            case CANCELLED:
            case INTERRUPTING:
            case INTERRUPTED:
                status = "[Cancelled]";
                break;
            default:
                final Callable<?> callable = this.callable;
                status = (callable == null)
                        ? "[Not completed]"
                        : "[Not completed, task = " + callable + "]";
        }
        return super.toString() + status;
    }

    // VarHandle mechanics
    private static final VarHandle STATE;
    private static final VarHandle RUNNER;
    private static final VarHandle WAITERS;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(FutureTask.class, "state", int.class);
            RUNNER = l.findVarHandle(FutureTask.class, "runner", Thread.class);
            WAITERS = l.findVarHandle(FutureTask.class, "waiters", WaitNode.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        // Reduce the risk of rare disastrous classloading in first call to
        // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> ensureLoaded = LockSupport.class;
    }

}
