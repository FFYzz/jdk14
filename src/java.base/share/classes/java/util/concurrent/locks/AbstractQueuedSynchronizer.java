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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic {@code int} value to represent state. Subclasses
 * must define the protected methods that change this state, and which
 * define what that state means in terms of this object being acquired
 * or released.  Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 * <p>
 * AQS 是实现锁和同步器的框架。
 * AQS 中有一个 FIFO 等待队列。队列有 head 指针和 tail 指针。此外还有一个 state 变量，
 * 用于表示同步器的状态。state 状态用于表示当前的同步器已经 被获取 或者 被释放。
 * 可通过 {@link #getState}, {@link #setState} 和 {@link #compareAndSetState} 修改/获取 state
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 * <p>
 * 子类需要实现一个非公开的内部类。AQS 没有实现任何同步接口。
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 * <p>
 * AQS 支持 exclusive 模式和 shared 模式。在 exclusive 模式中已经获取到同步器，
 * 其他线程尝试去获取同步器时不能成功。在 shared 模式中多个线程尝试获取可以都获取成功。
 * 子类可以同时实现两种模式，比如 RWLock，但是一般情况下只实现一种模式。
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread, method {@link #release}
 * invoked with the current {@link #getState} value fully releases
 * this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.  No
 * {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 * <p>
 * 需要手动序列化 thread queues
 *
 * <h2>Usage</h2>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li>{@link #tryAcquire}
 * <li>{@link #tryRelease}
 * <li>{@link #tryAcquireShared}
 * <li>{@link #tryReleaseShared}
 * <li>{@link #isHeldExclusively}
 * </ul>
 * <p>
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 * <p>
 * 重写如下几个方法是使用该类的唯一途径，其他方法要么声明为 final，要么声明为 private。
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 * <p>
 * AbstractOwnableSynchronizer 的方法用于跟踪当前持有锁的线程
 *
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 * <p>
 * 虽然是一个 FIFO 的队列，但是入队和出队不一定严格按照 FIFO 的策略，因为存在抢占。
 *
 * <pre>
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 * <p>
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * <h2>Usage Examples</h2>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes some instrumentation methods:
 *
 * <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (!isHeldExclusively())
 *         throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Reports whether in locked state
 *     public boolean isLocked() {
 *       return getState() != 0;
 *     }
 *
 *     public boolean isHeldExclusively() {
 *       // a data race, but safe due to out-of-thin-air guarantees
 *       return getExclusiveOwnerThread() == Thread.currentThread();
 *     }
 *
 *     // Provides a Condition
 *     public Condition newCondition() {
 *       return new ConditionObject();
 *     }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()              { sync.acquire(1); }
 *   public boolean tryLock()        { return sync.tryAcquire(1); }
 *   public void unlock()            { sync.release(1); }
 *   public Condition newCondition() { return sync.newCondition(); }
 *   public boolean isLocked()       { return sync.isLocked(); }
 *   public boolean isHeldByCurrentThread() {
 *     return sync.isHeldExclusively();
 *   }
 *   public boolean hasQueuedThreads() {
 *     return sync.hasQueuedThreads();
 *   }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 * <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @author Doug Lea
 * @since 1.5
 */
public abstract class AbstractQueuedSynchronizer
        extends AbstractOwnableSynchronizer
        implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     * <p>
     * state 初始化值为 0
     */
    protected AbstractQueuedSynchronizer() {
    }

    /*
     * Overview.
     *
     * The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers by
     * including explicit ("prev" and "next") links plus a "status"
     * field that allow nodes to signal successors when releasing
     * locks, and handle cancellation due to interrupts and timeouts.
     * The status field includes bits that track whether a thread
     * needs a signal (using LockSupport.unpark). Despite these
     * additions, we maintain most CLH locality properties.
     *
     * To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you set the head field, so the next eligible
     * waiter becomes first.
     *
     *  +------+  prev +-------+       +------+
     *  | head | <---- | first | <---- | tail |
     *  +------+       +-------+       +------+
     *
     * Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple point of demarcation
     * from unqueued to queued. The "next" link of the predecessor is
     * set by the enqueuing thread after successful CAS. Even though
     * non-atomic, this suffices to ensure that any blocked thread is
     * signalled by a predecessor when eligible (although in the case
     * of cancellation, possibly with the assistance of a signal in
     * method cleanQueue). Signalling is based in part on a
     * Dekker-like scheme in which the to-be waiting thread indicates
     * WAITING status, then retries acquiring, and then rechecks
     * status before blocking. The signaller atomically clears WAITING
     * status when unparking.
     *
     * Dequeuing on acquire involves detaching (nulling) a node's
     * "prev" node and then updating the "head". Other threads check
     * if a node is or was dequeued by checking "prev" rather than
     * head. We enforce the nulling then setting order by spin-waiting
     * if necessary. Because of this, the lock algorithm is not itself
     * strictly "lock-free" because an acquiring thread may need to
     * wait for a previous acquire to make progress. When used with
     * exclusive locks, such progress is required anyway. However
     * Shared mode may (uncommonly) require a spin-wait before
     * setting head field to ensure proper propagation. (Historical
     * note: This allows some simplifications and efficiencies
     * compared to previous versions of this class.)
     *
     * A node's predecessor can change due to cancellation while it is
     * waiting, until the node is first in queue, at which point it
     * cannot change. The acquire methods cope with this by rechecking
     * "prev" before waiting. The prev and next fields are modified
     * only via CAS by cancelled nodes in method cleanQueue. The
     * unsplice strategy is reminiscent of Michael-Scott queues in
     * that after a successful CAS to prev field, other threads help
     * fix next fields.  Because cancellation often occurs in bunches
     * that complicate decisions about necessary signals, each call to
     * cleanQueue traverses the queue until a clean sweep. Nodes that
     * become relinked as first are unconditionally unparked
     * (sometimes unnecessarily, but those cases are not worth
     * avoiding).
     *
     * A thread may try to acquire if it is first (frontmost) in the
     * queue, and sometimes before.  Being first does not guarantee
     * success; it only gives the right to contend. We balance
     * throughput, overhead, and fairness by allowing incoming threads
     * to "barge" and acquire the synchronizer while in the process of
     * enqueuing, in which case an awakened first thread may need to
     * rewait.  To counteract possible repeated unlucky rewaits, we
     * exponentially increase retries (up to 256) to acquire each time
     * a thread is unparked. Except in this case, AQS locks do not
     * spin; they instead interleave attempts to acquire with
     * bookkeeping steps. (Users who want spinlocks can use
     * tryAcquire.)
     *
     * To improve garbage collectibility, fields of nodes not yet on
     * list are null. (It is not rare to create and then throw away a
     * node without using it.) Fields of nodes coming off the list are
     * nulled out as soon as possible. This accentuates the challenge
     * of externally determining the first waiting thread (as in
     * method getFirstQueuedThread). This sometimes requires the
     * fallback of traversing backwards from the atomically updated
     * "tail" when fields appear null. (This is never needed in the
     * process of signalling though.)
     *
     * CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * Shared mode operations differ from Exclusive in that an acquire
     * signals the next waiter to try to acquire if it is also
     * Shared. The tryAcquireShared API allows users to indicate the
     * degree of propagation, but in most applications, it is more
     * efficient to ignore this, allowing the successor to try
     * acquiring in any case.
     *
     * Threads waiting on Conditions use nodes with an additional
     * link to maintain the (FIFO) list of conditions. Conditions only
     * need to link nodes in simple (non-concurrent) linked queues
     * because they are only accessed when exclusively held.  Upon
     * await, a node is inserted into a condition queue.  Upon signal,
     * the node is enqueued on the main queue.  A special status field
     * value is used to track and atomically trigger this.
     *
     * Accesses to fields head, tail, and state use full Volatile
     * mode, along with CAS. Node fields status, prev and next also do
     * so while threads may be signallable, but sometimes use weaker
     * modes otherwise. Accesses to field "waiter" (the thread to be
     * signalled) are always sandwiched between other atomic accesses
     * so are used in Plain mode. We use jdk.internal Unsafe versions
     * of atomic access methods rather than VarHandles to avoid
     * potential VM bootstrap issues.
     *
     * Most of the above is performed by primary internal method
     * acquire, that is invoked in some way by all exported acquire
     * methods.  (It is usually easy for compilers to optimize
     * call-site specializations when heavily used.)
     *
     * There are several arbitrary decisions about when and how to
     * check interrupts in both acquire and await before and/or after
     * blocking. The decisions are less arbitrary in implementation
     * updates because some users appear to rely on original behaviors
     * in ways that are racy and so (rarely) wrong in general but hard
     * to justify changing.
     *
     * Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */

    // Node status bits, also used as argument and return values
    // 用来描述 Node status 的常量，以掩码的形式，主要不是看具体的值，而是二进制表示下 1 的位置
    static final int WAITING = 1;          // must be 1
    static final int CANCELLED = 0x80000000; // must be negative
    static final int COND = 2;          // in a condition wait

    /**
     * CLH Nodes
     */
    abstract static class Node {
        /**
         * 指向前驱节点
         */
        volatile Node prev;       // initially attached via casTail
        /**
         * 指向后继节点
         */
        volatile Node next;       // visibly nonnull when signallable
        /**
         * 当前节点绑定的线程
         */
        Thread waiter;            // visibly nonnull when enqueued
        /**
         * Node 的 status
         */
        volatile int status;      // written by owner, atomic bit ops by others

        // methods for atomic operations

        /**
         * cas 更新节点的 pre 指针
         *
         * @param c
         * @param v
         * @return
         */
        final boolean casPrev(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, PREV, c, v);
        }

        /**
         * cas 更新节点的 next 指针
         *
         * @param c
         * @param v
         * @return
         */
        final boolean casNext(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, NEXT, c, v);
        }

        /**
         * 将节点的 status 设置为 STATUS & ~v
         *
         * @param v
         * @return 返回修改之前的 status
         */
        final int getAndUnsetStatus(int v) {     // for signalling
            return U.getAndBitwiseAndInt(this, STATUS, ~v);
        }

        /**
         * 设置节点的 pre 指针指向 p 节点
         *
         * @param p
         */
        final void setPrevRelaxed(Node p) {      // for off-queue assignment
            U.putReference(this, PREV, p);
        }

        /**
         * 将 status 的值设为 s
         *
         * @param s
         */
        final void setStatusRelaxed(int s) {     // for off-queue assignment
            U.putInt(this, STATUS, s);
        }

        /**
         * 将 status 设为 0
         */
        final void clearStatus() {               // for reducing unneeded signals
            U.putIntOpaque(this, STATUS, 0);
        }

        /**
         * varhandle 机制
         */
        private static final long STATUS
                = U.objectFieldOffset(Node.class, "status");
        private static final long NEXT
                = U.objectFieldOffset(Node.class, "next");
        private static final long PREV
                = U.objectFieldOffset(Node.class, "prev");
    }

    // Concrete classes tagged by type

    /**
     * exclusive 类型的节点
     */
    static final class ExclusiveNode extends Node {
    }

    /**
     * shared 类型的节点
     */
    static final class SharedNode extends Node {
    }

    /**
     * condition queue 中的节点
     * 是一个单向链表
     */
    static final class ConditionNode extends Node
            implements ForkJoinPool.ManagedBlocker {
        /**
         * 指向下一个等待的节点
         */
        ConditionNode nextWaiter;            // link to next waiting node

        /**
         * Allows Conditions to be used in ForkJoinPools without
         * risking fixed pool exhaustion. This is usable only for
         * untimed Condition waits, not timed versions.
         */
        public final boolean isReleasable() {
            return status <= 1 || Thread.currentThread().isInterrupted();
        }

        public final boolean block() {
            while (!isReleasable()) LockSupport.park();
            return true;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.
     * <p>
     * CLH 队列的头指针
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue. After initialization, modified only via casTail.
     * <p>
     * CLH 队列的尾指针
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     * <p>
     * 同步的 state
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     * <p>
     * volatile 读 state
     *
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * <p>
     * volatile 写更新 state
     *
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     * <p>
     * cas 更新 state
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     * value was not equal to the expected value.
     */
    protected final boolean compareAndSetState(int expect, int update) {
        return U.compareAndSetInt(this, STATE, expect, update);
    }

    // Queuing utilities
    // 队列操作

    /**
     * cas 更新 tail 指针
     *
     * @param c
     * @param v
     * @return
     */
    private boolean casTail(Node c, Node v) {
        return U.compareAndSetReference(this, TAIL, c, v);
    }

    /**
     * tries once to CAS a new dummy node for head
     * <p>
     * 创建一个 dummy head node 以及初始化 tail 指针
     */
    private void tryInitializeHead() {
        // 初始化节点为 Exclusive 类型的节点
        Node h = new ExclusiveNode();
        if (U.compareAndSetReference(this, HEAD, null, h))
            tail = h;
    }

    /**
     * Enqueues the node unless null. (Currently used only for
     * ConditionNodes; other cases are interleaved with acquires.)
     * <p>
     * 当前仅用于 ConditionNodes 类型的入队，入 sync queue。
     */
    final void enqueue(Node node) {
        if (node != null) {
            for (; ; ) {
                // 保存当前的 taild 节点
                Node t = tail;
                // 将 node 的 pre 指针指向 t
                node.setPrevRelaxed(t);        // avoid unnecessary fence
                // 如果队列未初始化
                if (t == null)                 // initialize
                    // 初始化队列
                    tryInitializeHead();
                    // 队列已初始化
                    // cas 更新 tail 指针，指向 node
                else if (casTail(t, node)) {
                    // 更新 t 的 next 指针指向 node
                    t.next = node;
                    // 如果 Node t 的 status < 0，也即 status = cancelled
                    if (t.status < 0)          // wake up to clean link
                        // 则 unpark 当前节点的后继节点
                        LockSupport.unpark(node.waiter);
                    break;
                }
            }
        }
    }

    /**
     * Returns true if node is found in traversal from tail
     * <p>
     * 返回节点是否在队列中，从 tail 往前遍历
     */
    final boolean isEnqueued(Node node) {
        for (Node t = tail; t != null; t = t.prev)
            if (t == node)
                return true;
        return false;
    }

    /**
     * Wakes up the successor of given node, if one exists, and unsets its
     * WAITING status to avoid park race. This may fail to wake up an
     * eligible thread when one or more have been cancelled, but
     * cancelAcquire ensures liveness.
     * <p>
     * 唤醒给定节点的后继节点，如果后继节点存在，则取消其 WAITTING status 以避免 park 竞争。
     */
    private static void signalNext(Node h) {
        Node s;
        // 当前节点不为 null 且 当前节点的后继节点不为 null 且 后继节点的 status 不为 0 ，0 表示是初始化状态
        if (h != null && (s = h.next) != null && s.status != 0) {
            // 修改 s 节点的 status
            s.getAndUnsetStatus(WAITING);
            // unpark 节点 s 的后继节点
            LockSupport.unpark(s.waiter);
        }
    }

    /**
     * Wakes up the next node of given node if in shared mode
     * <p>
     * 如果是 shared 模式下则唤醒给定的节点
     */
    private static void signalNextIfShared(Node h) {
        // 保存给定节点的后继节点
        Node s;
        // 如果给定节点不为 null 且 给定节点的后继节点不为 null 且 后继节点是 sharedNode 且 后继节点的 status 不为 0.
        if (h != null && (s = h.next) != null &&
                (s instanceof SharedNode) && s.status != 0) {
            // 设置 status
            s.getAndUnsetStatus(WAITING);
            // unpark 节点 s 的后继节点
            LockSupport.unpark(s.waiter);
        }
    }

    /**
     * Main acquire method, invoked by all exported acquire methods.
     * <p>
     * 主要的 acquire 方法，被所有导出到外部的 acquire 方法调用
     *
     * @param node          null unless a reacquiring Condition
     * @param arg           the acquire argument
     * @param shared        true if shared mode else exclusive
     * @param interruptible if abort and return negative on interrupt
     * @param timed         if true use timed waits
     * @param time          if timed, the System.nanoTime value to timeout
     * @return positive if acquired, 0 if timed out, negative if interrupted
     */
    final int acquire(Node node, int arg, boolean shared,
                      boolean interruptible, boolean timed, long time) {
        // 保存当前线程
        Thread current = Thread.currentThread();
        // 自旋次数相关变量
        byte spins = 0, postSpins = 0;   // retries upon unpark of first thread
        // 中断标志
        // 是否为第一次
        boolean interrupted = false, first = false;
        // 保存前驱节点变量
        Node pred = null;                // predecessor of node when enqueued

        /*
         * Repeatedly:
         *  Check if node now first
         *  首先检查节点是不是队头节点
         *    if so, ensure head stable, else ensure valid predecessor
         *  if node is first or not yet enqueued, try acquiring
         *  队头节点或者还未入队，则尝试 acquiring
         *  else if node not yet created, create it
         *  节点还未创建，则创建
         *  else if not yet enqueued, try once to enqueue
         *  节点还未入队，则尝试一次入队
         *  else if woken from park, retry (up to postSpins times)
         *  从 park 中醒来，直到自旋次数到了后尝试重新 acquire
         *  else if WAITING status not set, set and retry
         *  未设置为 WAITING status，设置为 WAITING status 并重试
         *  else park and clear WAITING status, and check cancellation
         *  park 并且 清楚 WAITING status，检查是否 cancelled
         */

        for (; ; ) {
            if (!first && (pred = (node == null) ? null : node.prev) != null &&
                    !(first = (head == pred))) {
                // 如果 pred 的 status 为 cancelled
                if (pred.status < 0) {
                    // 清理 cancelled 的节点
                    cleanQueue();           // predecessor cancelled
                    continue;
                    // pred 的前驱节点的 null
                } else if (pred.prev == null) {
                    // 自旋等待
                    Thread.onSpinWait();    // ensure serialization
                    continue;
                }
            }
            if (first || pred == null) {
                boolean acquired;
                try {
                    if (shared)
                        acquired = (tryAcquireShared(arg) >= 0);
                    else
                        acquired = tryAcquire(arg);
                } catch (Throwable ex) {
                    cancelAcquire(node, interrupted, false);
                    throw ex;
                }
                if (acquired) {
                    if (first) {
                        node.prev = null;
                        head = node;
                        pred.next = null;
                        node.waiter = null;
                        if (shared)
                            signalNextIfShared(node);
                        if (interrupted)
                            current.interrupt();
                    }
                    return 1;
                }
            }
            if (node == null) {                 // allocate; retry before enqueue
                if (shared)
                    node = new SharedNode();
                else
                    node = new ExclusiveNode();
            } else if (pred == null) {          // try to enqueue
                node.waiter = current;
                Node t = tail;
                node.setPrevRelaxed(t);         // avoid unnecessary fence
                if (t == null)
                    tryInitializeHead();
                else if (!casTail(t, node))
                    node.setPrevRelaxed(null);  // back out
                else
                    t.next = node;
            } else if (first && spins != 0) {
                --spins;                        // reduce unfairness on rewaits
                Thread.onSpinWait();
            } else if (node.status == 0) {
                node.status = WAITING;          // enable signal and recheck
            } else {
                long nanos;
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
        return cancelAcquire(node, interrupted, interruptible);
    }

    /**
     * Possibly repeatedly traverses from tail, unsplicing cancelled
     * nodes until none are found. Unparks nodes that may have been
     * relinked to be next eligible acquirer.
     * cancel node 的时候会调用
     */
    private void cleanQueue() {
        for (; ; ) {                               // restart point
            // (p,q,s)元组，q 是当前节点，p 是当前节点的前驱节点，s 是当前节点的后继节点
            for (Node q = tail, s = null, p, n; ; ) { // (p, q, s) triples
                // 跳出当前方法的出口
                // 如果队列未初始化 或者 遍历到了 dummy head node，则直接返回
                // 这里会将 p 指向 q 的前驱节点
                if (q == null || (p = q.prev) == null)
                    return;                      // end of list
                // 第一次循环不会 break
                if (s == null ? tail != q : (s.prev != q || s.status < 0))
                    break;                       // inconsistent
                // 主要逻辑一
                // 如果 q 被 cancelled
                if (q.status < 0) {              // cancelled
                    // 如果 s 为 null，说明当前节点为 tail 节点，则 tail 指针从 q 指向 p，也即将 tail 指针指向 q 的前驱节点
                    // 如果 s 不为 null，说明当前节点不是 tail 节点，则更新 s 的 pre 指针从 q 指向 p，也即跳过 p。
                    if ((s == null ? casTail(q, p) : s.casPrev(q, p)) &&
                            // 如果 cas 修改成功
                            q.prev == p) {
                        // 修改 p 的 next 指针指向 s
                        p.casNext(q, s);         // OK if fails
                        // 如果 p 节点是 dummy head node，则唤醒该节点的后续节点
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                // 主要逻辑二
                // 如果 q 的前驱节点 p 的后继节点不等于 q ???
                if ((n = p.next) != q) {         // help finish
                    if (n != null && q.prev == p) {
                        // 更新节点 p 的 next 指针指向 q
                        p.casNext(n, q);
                        // 如果 p 节点是 dummy head node，则唤醒该节点的后续节点
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                // 指针往前走
                // s 指向原来的 q
                // q 指向其前驱节点
                s = q;
                q = q.prev;
            }
        }
    }

    /**
     * Cancels an ongoing attempt to acquire.
     * 取消 正在 尝试的 acquire
     *
     * @param node          the node (may be null if cancelled before enqueuing)
     * @param interrupted   true if thread interrupted
     * @param interruptible if should report interruption vs reset
     */
    private int cancelAcquire(Node node, boolean interrupted,
                              boolean interruptible) {
        if (node != null) {
            // 将 node 的 waiter 属性置为 null
            node.waiter = null;
            // 将 node 的 status 设为 CANCELLED
            node.status = CANCELLED;
            // 如果 node 的前驱节点不为 null ，则需要 cleanQueue
            if (node.prev != null)
                // 清理队列
                cleanQueue();
        }
        // 如果线程被中断
        if (interrupted) {
            // 如果线程允许被中断
            if (interruptible)
                return CANCELLED;
                // 如果线程不可被中断
            else
                // 强制中断线程
                Thread.currentThread().interrupt();
        }
        return 0;
    }

    // Main exported methods
    // 主要导出的方法，基于 AQS 的同步器一般需要重写下述的方法

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     * <p>
     * exclusive 模式下尝试调用 acquire 方法，需要根据 state 的状态来判断是否需要执行
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     * <p>
     * 该方法被总是被执行 acquire 方法的线程调用。如果返回 false，那么调用线程如果没有进入 CLH
     * 队列，那么会进队。
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *            passed to an acquire method, or is the value saved on entry
     *            to a condition wait.  The value is otherwise uninterpreted
     *            and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     * been acquired.
     * @throws IllegalMonitorStateException  if acquiring would place this
     *                                       synchronizer in an illegal state. This exception must be
     *                                       thrown in a consistent fashion for synchronization to work
     *                                       correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     * <p>
     * exclusive 模式下尝试释放锁
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *            passed to a release method, or the current state value upon
     *            entry to a condition wait.  The value is otherwise
     *            uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     * state, so that any waiting threads may attempt to acquire;
     * and {@code false} otherwise.
     * @throws IllegalMonitorStateException  if releasing would place this
     *                                       synchronizer in an illegal state. This exception must be
     *                                       thrown in a consistent fashion for synchronization to work
     *                                       correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     * <p>
     * shared 模式下尝试获取锁
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *            passed to an acquire method, or is the value saved on entry
     *            to a condition wait.  The value is otherwise uninterpreted
     *            and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     * mode succeeded but no subsequent shared-mode acquire can
     * succeed; and a positive value if acquisition in shared
     * mode succeeded and subsequent shared-mode acquires might
     * also succeed, in which case a subsequent waiting thread
     * must check availability. (Support for three different
     * return values enables this method to be used in contexts
     * where acquires only sometimes act exclusively.)  Upon
     * success, this object has been acquired.
     * @throws IllegalMonitorStateException  if acquiring would place this
     *                                       synchronizer in an illegal state. This exception must be
     *                                       thrown in a consistent fashion for synchronization to work
     *                                       correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     * <p>
     * shared 模式下释放锁
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *            passed to a release method, or the current state value upon
     *            entry to a condition wait.  The value is otherwise
     *            uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     * waiting acquire (shared or exclusive) to succeed; and
     * {@code false} otherwise
     * @throws IllegalMonitorStateException  if releasing would place this
     *                                       synchronizer in an illegal state. This exception must be
     *                                       thrown in a consistent fashion for synchronization to work
     *                                       correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a {@link ConditionObject} method.
     * <p>
     * 如果同步器当前被调用线程独占则返回 true，否则返回 false
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     * {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     * <p>
     * exclusive 模式下尝试 acquire，忽略中断。
     *
     * @param arg the acquire argument.  This value is conveyed to
     *            {@link #tryAcquire} but is otherwise uninterpreted and
     *            can represent anything you like.
     */
    public final void acquire(int arg) {
        if (!tryAcquire(arg))
            acquire(null, arg, false, false, false, 0L);
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     * <p>
     * exclusive 模式下尝试 acquire，如果被中断则终止。
     *
     * @param arg the acquire argument.  This value is conveyed to
     *            {@link #tryAcquire} but is otherwise uninterpreted and
     *            can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted() ||
                (!tryAcquire(arg) && acquire(null, arg, false, true, false, 0L) < 0))
            throw new InterruptedException();
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     * <p>
     * exclusive 模式下尝试 acquire，中断则终止，如果超时则返回 false。
     * 首先检查中断状态，再至少调用一次 tryAcquire。如果 tryAcquire 失败，则进队。
     * 进队之后可能多次电泳 tryAcquire，直到成功，或者被中断或者超时取消。
     *
     * @param arg          the acquire argument.  This value is conveyed to
     *                     {@link #tryAcquire} but is otherwise uninterpreted and
     *                     can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // 检查中断状态
        if (!Thread.interrupted()) {
            // 尝试 acquire
            if (tryAcquire(arg))
                return true;
            // 检查参数
            if (nanosTimeout <= 0L)
                return false;
            // acquire
            int stat = acquire(null, arg, false, true, true,
                    System.nanoTime() + nanosTimeout);
            // 返回 acquire 状态
            if (stat > 0)
                return true;
            if (stat == 0)
                return false;
        }
        throw new InterruptedException();
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     * <p>
     * exclusive 模式下释放锁
     *
     * @param arg the release argument.  This value is conveyed to
     *            {@link #tryRelease} but is otherwise uninterpreted and
     *            can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            // 唤醒 dummy head node 节点的后继节点
            signalNext(head);
            return true;
        }
        return false;
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     * <p>
     * shared 模式下释放锁
     *
     * @param arg the acquire argument.  This value is conveyed to
     *            {@link #tryAcquireShared} but is otherwise uninterpreted
     *            and can represent anything you like.
     */
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            acquire(null, arg, true, false, false, 0L);
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * <p>
     * shared 模式下尝试获取 acquire，如果被中断则终止。
     *
     * @param arg the acquire argument.
     *            This value is conveyed to {@link #tryAcquireShared} but is
     *            otherwise uninterpreted and can represent anything
     *            you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted() ||
                (tryAcquireShared(arg) < 0 &&
                        acquire(null, arg, true, true, false, 0L) < 0))
            throw new InterruptedException();
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     * <p>
     * shared 模式下尝试获取 acquire，如果被中断则终止，如果超时则返回 false。
     *
     * @param arg          the acquire argument.  This value is conveyed to
     *                     {@link #tryAcquireShared} but is otherwise uninterpreted
     *                     and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // 检查中断状态
        if (!Thread.interrupted()) {
            // 尝试获取
            if (tryAcquireShared(arg) >= 0)
                return true;
            // 检查时间参数
            if (nanosTimeout <= 0L)
                return false;
            // acquire
            int stat = acquire(null, arg, true, true, true,
                    System.nanoTime() + nanosTimeout);
            // 根据 acquire 状态返回是否 tryAcquireSharedNanos 成功
            if (stat > 0)
                return true;
            if (stat == 0)
                return false;
        }
        throw new InterruptedException();
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     * <p>
     * shared 模式下释放锁
     *
     * @param arg the release argument.  This value is conveyed to
     *            {@link #tryReleaseShared} but is otherwise uninterpreted
     *            and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            // 释放之后需要通知后继节点，也就是通知 dummy head node 的后继节点
            signalNext(head);
            return true;
        }
        return false;
    }

    // Queue inspection methods
    // 队列检查方法

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     * <p>
     * 返回队列中中是否有其他线程在等待
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        for (Node p = tail, h = head; p != h && p != null; p = p.prev)
            // status > 0 表示 status != cancelled
            if (p.status >= 0)
                return true;
        return false;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is, if an acquire method has ever blocked.
     * <p>
     * 返回是否存在过竞争，判断依据为是否初始化队列
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     * <p>
     * 返回 CLH 队列中等待时间最长的线程/dummy head 之后的第一个未被 cancelled 的 Node
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        Thread first = null, w;
        Node h, s;
        // 前提条件：队列已初始化
        // dummy head node 的后继节点 s 为 null
        // s 绑定的线程为 null
        // s 的前驱节点为 null
        if ((h = head) != null && ((s = h.next) == null ||
                (first = s.waiter) == null ||
                s.prev == null)) {
            // traverse from tail on stale reads
            // 往前找，一直找到 dummy head 节点 的后一个节点
            for (Node p = tail, q; p != null && (q = p.prev) != null; p = q)
                if ((w = p.waiter) != null)
                    first = w;
        }
        return first;
    }

    /**
     * Returns true if the given thread is currently queued.
     * <p>
     * 返回给定线程是否在 CLH 队列中等待
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.waiter == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     * <p>
     * 仅在 ReentrantReadWriteLock 中使用
     * 返回第一个节点是否为 exclusive type
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        // head 节点不为 null ：队列已初始化
        // 第一个节点 s 不为 null：存在等待的线程
        // s 节点不为 shared 模式
        // s 节点绑定的线程不为 null
        return (h = head) != null && (s = h.next) != null &&
                !(s instanceof SharedNode) && s.waiter != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     * <p>
     * 返回是否有其他线程比当前线程等待更长时间
     * true：表示存在
     * false：表示不存在
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     * 该方法与下述方法的调用等效，但是该方法会更高效。
     * <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread()
     *   && hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     * 返回 true 并不能保证正确性
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer.html#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     * <p>
     * 该方法用于禁止抢占。公平的同步器
     * 如果该方法返回 true，那么独占模式下的 {@link #tryAcquire} 返回 false，
     * 共享模式下的 {@link #tryAcquireShared} 返回一个负数。初始是重入的情况。
     * 例子如下：
     *
     * <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     * current thread, and {@code false} if the current thread
     * is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        Thread first = null;
        Node h, s;
        // 队列已初始化
        // dummy head node 的后继节点 s 为 null
        // 节点 s 绑定的线程为 null
        // 节点 s 的前驱节点为 null
        if ((h = head) != null && ((s = h.next) == null ||
                (first = s.waiter) == null ||
                s.prev == null))
            // CLH 队列中的等待时间最长的线程
            first = getFirstQueuedThread(); // retry via getFirstQueuedThread
        // 存在等待时间最长的线程且当前线程不是等待时间最长的线程
        return first != null && first != Thread.currentThread();
    }

    // Instrumentation and monitoring methods
    // 检测和监控方法

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization control.
     * <p>
     * 返回 CLH 队列中等待线程的数量的估计值，因为在遍历的时候可能节点会动态的变化
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        // 计数值
        int n = 0;
        // 从 tail 向前遍历
        for (Node p = tail; p != null; p = p.prev) {
            // Node 绑定的线程不为 null
            if (p.waiter != null)
                // 计数 + 1
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     * <p>
     * 返回 CLH 队列中所有等待线程的集合。
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        // 用 list 保存所有等待线程的集合
        ArrayList<Thread> list = new ArrayList<>();
        // 从 tail 开始向前遍历
        for (Node p = tail; p != null; p = p.prev) {
            // 当前 Node 绑定的 thread
            Thread t = p.waiter;
            // 不为 null
            if (t != null)
                // 加入到 list 中
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     * <p>
     * 返回 CLH 队列中所有 exclusive 模式等待的节点锁绑定的线程的集合
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        // List 用于保存结果
        ArrayList<Thread> list = new ArrayList<>();
        // 从 tail 开始向前遍历
        for (Node p = tail; p != null; p = p.prev) {
            // 节点 p 不是 SharedNode 类型
            if (!(p instanceof SharedNode)) {
                // 得到绑定的线程
                Thread t = p.waiter;
                if (t != null)
                    // 加入到 list 中
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     * <p>
     * 返回 CLH 队列中所有 shared 模式等待的节点锁绑定的线程的集合
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        // List 用于保存结果
        ArrayList<Thread> list = new ArrayList<>();
        // 从 tail 开始向前遍历
        for (Node p = tail; p != null; p = p.prev) {
            // 节点 p 是 SharedNode 类型
            if (p instanceof SharedNode) {
                // 得到绑定的线程
                Thread t = p.waiter;
                if (t != null)
                    // 加入到 list 中
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        return super.toString()
                + "[State = " + getState() + ", "
                + (hasQueuedThreads() ? "non" : "") + "empty queue]";
    }

    // Instrumentation methods for conditions
    // conditions 的检测方法

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *                                      is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this synchronizer
     * @throws NullPointerException         if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring system
     * state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *                                      is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this synchronizer
     * @throws NullPointerException         if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *                                      is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this synchronizer
     * @throws NullPointerException         if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition implementation for a {@link AbstractQueuedSynchronizer}
     * serving as the basis of a {@link Lock} implementation.
     * <p>
     * ConditionObject 实现了 Condition 接口 和 Serializable 接口
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /**
         * First node of condition queue.
         * <p>
         * condition queue 中的头指针，每个 Condition 实例维护一个 condition queue
         */
        private transient ConditionNode firstWaiter;
        /**
         * Last node of condition queue.
         * <p>
         * condition queue 中的队尾指针
         */
        private transient ConditionNode lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         * <p>
         * 构造方法
         */
        public ConditionObject() {
        }

        // Signalling methods

        /**
         * Removes and transfers one or all waiters to sync queue.
         * <p>
         * 将 Node 从 condition queue 中移除，并转换成 sync queue 中的 Node 并入 sync queue
         */
        private void doSignal(ConditionNode first, boolean all) {
            while (first != null) {
                // 保存当前处理 Node 的下一个 Node
                ConditionNode next = first.nextWaiter;
                // 将 next 赋值给 firstWaiter，表示把 first 指向的 Node 出队
                // 如果 if (true)，则队列已空，将 lastWaiter 也指向 null
                if ((firstWaiter = next) == null)
                    lastWaiter = null;
                // 获取 first Node 的 status，并将其 Node 的 status 设置为
                // TODO
                if ((first.getAndUnsetStatus(COND) & COND) != 0) {
                    // 进 sync 队列
                    enqueue(first);
                    // 用于控制是否 signal 所有 condition queue 中的 Node
                    if (!all)
                        break;
                }
                // 指针后移
                first = next;
            }
        }

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        public final void signal() {
            // condition queue 的队头结点
            ConditionNode first = firstWaiter;
            // 如果调用线程未持有锁，则抛出异常
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // 进行 signal
            if (first != null)
                doSignal(first, false);
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        public final void signalAll() {
            // condition queue 的头节点
            ConditionNode first = firstWaiter;
            // 保证调用 signalAll 方法的线程(当前线程)持有锁
            // 如果没有持有锁，则抛出异常
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // 如果队列不为空，则进行 signal
            if (first != null)
                doSignal(first, true);
        }

        // Waiting methods
        // 等到方法

        /**
         * Adds node to condition list and releases lock.
         * <p>
         * 将 Node 加入到 condition queue 中，并释放锁。
         *
         * @param node the node
         * @return savedState to reacquire after wait
         */
        private int enableWait(ConditionNode node) {
            // 如果当前调用线程独占锁
            if (isHeldExclusively()) {
                // 设置 node 的绑定线程为 当前线程
                node.waiter = Thread.currentThread();
                // 设置 node 的 status 为 COND | WAITING  = 3
                node.setStatusRelaxed(COND | WAITING);
                // 暂存原先 condition queue 中的最后一个 Node
                ConditionNode last = lastWaiter;
                // 如果 last == null，说明 condition queue 为空
                if (last == null)
                    // 头指针也指向当前节点
                    firstWaiter = node;
                else
                    // 原先尾指针的 nextWaiter 指向新加入的 node
                    last.nextWaiter = node;
                // lastWaiter 指针指向新的 condition queue 中的最后一个节点
                lastWaiter = node;
                // 获取当前同步器的 state
                int savedState = getState();
                // 释放锁
                if (release(savedState))
                    return savedState;
            }
            // 当前线程并没有持有锁
            // 将节点的 status 设置为 cancelle
            node.status = CANCELLED; // lock not held or inconsistent
            // 并抛出异常
            throw new IllegalMonitorStateException();
        }

        /**
         * Returns true if a node that was initially placed on a condition
         * queue is now ready to reacquire on sync queue.
         * <p>
         * 返回 node 节点是否
         * 1. 一开始在 condition queue 中等待 且
         * 2.现在在 sync queue 中
         *
         * @param node the node
         * @return true if is reacquiring
         */
        private boolean canReacquire(ConditionNode node) {
            // check links, not status to avoid enqueue race
            // pre 指针不为 null 且现在在 sync queue 中
            return node != null && node.prev != null && isEnqueued(node);
        }

        /**
         * Unlinks the given node and other non-waiting nodes from
         * condition queue unless already unlinked.
         * <p>
         * 将传入的 node 从 condition queue 中移除，且将 condition queue 中的已经 cancelled 的节点移除队列。
         */
        private void unlinkCancelledWaiters(ConditionNode node) {
            if (node == null || node.nextWaiter != null || node == lastWaiter) {
                // 暂存第一个节点 , w 指向工作节点
                ConditionNode w = firstWaiter, trail = null;
                // 第一个节点不为 null，开始遍历
                while (w != null) {
                    // 保存下一个节点
                    ConditionNode next = w.nextWaiter;
                    // 如果节点的 status 不是 COND 都将会 == 0
                    if ((w.status & COND) == 0) {
                        // 将 nextWaiter 置为 null，从队列中移除
                        w.nextWaiter = null;
                        // 如果还在处理 firstWaiter，则需要将 firstWaiter 指向 next 节点
                        if (trail == null)
                            firstWaiter = next;
                            // 如果当前处理的 非 firstWaiter Node，则更新 trail。
                        else
                            trail.nextWaiter = next;
                        // 判断是否已经建立到最后一个节点
                        // 如果是最后一个节点，更新 lastWaiter 指针，指向 trail
                        // 结束循环
                        if (next == null)
                            lastWaiter = trail;
                        // w 的 status 为 COND 则不处理，直接跳过
                    } else
                        // 记录轨迹，也可以看成是前驱指针
                        trail = w;
                    w = next;
                }
            }
        }

        /**
         * Implements uninterruptible condition wait.
         * <p>
         * 实现了一个不响应中断的 condition wait
         *
         * <ol>
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * </ol>
         */
        public final void awaitUninterruptibly() {
            // 创建一个 ConditionNode
            ConditionNode node = new ConditionNode();
            // 将 node 进 condition queue ，并释放锁
            // 将 node 的 status 设为 COND | WAITING)
            int savedState = enableWait(node);
            // 设置阻塞的 blocker
            LockSupport.setCurrentBlocker(this); // for back-compatibility
            // 中断标志
            boolean interrupted = false;
            // 是否可以再次 acquire
            while (!canReacquire(node)) {
                // 查看是否被中断，如果被中断则恢复中断标识
                if (Thread.interrupted())
                    // 修改中断标记为 true
                    interrupted = true;
                    // 没有发生中断
                    // node 的 status 没有发生变化，还是为 COND | WAITING
                else if ((node.status & COND) != 0) {
                    try {
                        // 根据 blocker 进行阻塞
                        ForkJoinPool.managedBlock(node);
                    } catch (InterruptedException ie) {
                        // 如果抛出中断异常则更新中断标识
                        interrupted = true;
                    }
                    // 自旋
                } else
                    Thread.onSpinWait();    // awoke while enqueuing
            }
            // 阻塞结束，删除 blocker
            LockSupport.setCurrentBlocker(null);
            // 清楚 node 的 status，设置为 0
            node.clearStatus();
            // 尝试获取
            acquire(node, savedState, false, false, false, 0L);
            // 如果中断
            if (interrupted)
                // 中断当前线程
                Thread.currentThread().interrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <p>
         * 只有持有锁的线程才能调用 await() 进等待队列
         * <ol>
         * <li>If current thread is interrupted, throw InterruptedException.
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled or interrupted.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * <li>If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final void await() throws InterruptedException {
            // 如果被设置了中断标识，则抛出异常，并清除中断标识
            if (Thread.interrupted())
                throw new InterruptedException();
            // 新建一个 ConditionNode 类型的节点，准备入 condition queue。
            ConditionNode node = new ConditionNode();
            // savedState 释放锁之前的同步状态 state
            int savedState = enableWait(node);
            // 设置当前线程的阻塞对象为当前的 实例 (向后兼容)
            LockSupport.setCurrentBlocker(this); // for back-compatibility
            // 中断标志 是否取消标志
            boolean interrupted = false, cancelled = false;
            // 如果返回 true，就直接不仅如此循环
            // 说明如果 node 已经在 condition queue 中等待过，现在在 sync queue 中等待，那么将不再 await
            while (!canReacquire(node)) {
                // 判断是否被中断
                if (interrupted |= Thread.interrupted()) {
                    // 如果被中断，判断是否被取消
                    // 判断节点是否被取消，如果被取消则直接 break
                    // 将 Node 的 status 设为 COND
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                        // 如果被取消，直接跳出循环
                        break;              // else interrupted after signal
                    // 如果没有被中断 && node 的 status 包含 COND
                } else if ((node.status & COND) != 0) {
                    try {
                        // 根据给定的 blocker (this) 进行 block
                        // 如果阻塞过程中被中断，则 catch 住异常，将 interrupted 置为 true
                        ForkJoinPool.managedBlock(node);
                    } catch (InterruptedException ie) {
                        interrupted = true;
                    }
                } else
                    // 自旋等待
                    Thread.onSpinWait();    // awoke while enqueuing
            }
            // 唤醒之后，设置 blocker 为 null
            LockSupport.setCurrentBlocker(null);
            // 将 status 设置 0
            node.clearStatus();
            // 尝试获取
            acquire(node, savedState, false, false, false, 0L);
            // 如果发生过中断
            if (interrupted) {
                // 如果被取消
                if (cancelled) {
                    // 则清理等待队列中的节点
                    unlinkCancelledWaiters(node);
                    // 抛出一个 中断异常
                    throw new InterruptedException();
                }
                // 中断当前线程
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Implements timed condition wait.
         * <p>
         * 可参考 {@link #await()}
         *
         * <ol>
         * <li>If current thread is interrupted, throw InterruptedException.
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled, interrupted, or timed out.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * <li>If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            ConditionNode node = new ConditionNode();
            int savedState = enableWait(node);
            // 检查传入参数
            long nanos = (nanosTimeout < 0L) ? 0L : nanosTimeout;
            // 计算得到 deadline
            long deadline = System.nanoTime() + nanos;
            boolean cancelled = false, interrupted = false;
            while (!canReacquire(node)) {
                // 如果超时 或者 中断
                if ((interrupted |= Thread.interrupted()) ||
                        (nanos = deadline - System.nanoTime()) <= 0L) {
                    // cancelled 为 true，则跳出循环。
                    // 其实 不一定是指 cancelled，因为时间超时之后，也没有收到 signal ，status 的值为 WAITTING|COND
                    // cancelled 的结果也为 true，但其实它并不是 cancelled
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                        break;
                } else
                    // 阻塞
                    LockSupport.parkNanos(this, nanos);
            }
            // 清楚状态 status 设为 0
            node.clearStatus();
            // 尝试获取
            acquire(node, savedState, false, false, false, 0L);
            // 如果是 cancelled
            if (cancelled) {
                // 则从 condition queue 中进行清除
                // 顺带清除其他 cancelled 的 Node
                unlinkCancelledWaiters(node);
                // 如果中断
                if (interrupted)
                    throw new InterruptedException();
                // 如果是中断，但是没有 cancelled
            } else if (interrupted)
                // 抛出中断
                Thread.currentThread().interrupt();
            // 记录剩余时间
            long remaining = deadline - System.nanoTime(); // avoid overflow
            // 返回剩余时间
            return (remaining <= nanosTimeout) ? remaining : Long.MIN_VALUE;
        }

        /**
         * Implements absolute timed condition wait.
         * <p>
         * 参考 {@link #awaitNanos(long)}
         *
         * <ol>
         * <li>If current thread is interrupted, throw InterruptedException.
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled, interrupted, or timed out.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * <li>If interrupted while blocked in step 4, throw InterruptedException.
         * <li>If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            // 获取时间
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            ConditionNode node = new ConditionNode();
            int savedState = enableWait(node);
            boolean cancelled = false, interrupted = false;
            while (!canReacquire(node)) {
                if ((interrupted |= Thread.interrupted()) ||
                        System.currentTimeMillis() >= abstime) {
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                        break;
                } else
                    LockSupport.parkUntil(this, abstime);
            }
            node.clearStatus();
            acquire(node, savedState, false, false, false, 0L);
            if (cancelled) {
                unlinkCancelledWaiters(node);
                if (interrupted)
                    throw new InterruptedException();
            } else if (interrupted)
                Thread.currentThread().interrupt();
            return !cancelled;
        }

        /**
         * Implements timed condition wait.
         * <p>
         * {@link #awaitNanos(long)}
         *
         * <ol>
         * <li>If current thread is interrupted, throw InterruptedException.
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled, interrupted, or timed out.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * <li>If interrupted while blocked in step 4, throw InterruptedException.
         * <li>If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            // 转成纳秒
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            ConditionNode node = new ConditionNode();
            int savedState = enableWait(node);
            long nanos = (nanosTimeout < 0L) ? 0L : nanosTimeout;
            long deadline = System.nanoTime() + nanos;
            boolean cancelled = false, interrupted = false;
            while (!canReacquire(node)) {
                if ((interrupted |= Thread.interrupted()) ||
                        (nanos = deadline - System.nanoTime()) <= 0L) {
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                        break;
                } else
                    LockSupport.parkNanos(this, nanos);
            }
            node.clearStatus();
            acquire(node, savedState, false, false, false, 0L);
            if (cancelled) {
                unlinkCancelledWaiters(node);
                if (interrupted)
                    throw new InterruptedException();
            } else if (interrupted)
                Thread.currentThread().interrupt();
            return !cancelled;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         * <p>
         * 返回 Condition 对象是否由传入的 AQS 创建
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         * <p>
         * 返回 condition queue 中是否有有效的等待节点
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        protected final boolean hasWaiters() {
            // 如果当前线程并没有独占锁
            if (!isHeldExclusively())
                // 抛出异常
                throw new IllegalMonitorStateException();
            // 从头开始遍历
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                // 存在有效等待节点
                if ((w.status & COND) != 0)
                    // 返回 true
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         * <p>
         * 返回 condition queue 中等待的线程数的估计值
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        protected final int getWaitQueueLength() {
            // 锁是否被当前线程独占
            if (!isHeldExclusively())
                // 抛出异常
                throw new IllegalMonitorStateException();
            // 计数
            int n = 0;
            // 从头开始遍历
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                // 存在有效节点
                if ((w.status & COND) != 0)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         * <p>
         * 返回 condition queue 中等待的线程的集合(也是一个估计值)
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<>();
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                // 存在有效的等待节点
                if ((w.status & COND) != 0) {
                    Thread t = w.waiter;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    // Unsafe
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long STATE
            = U.objectFieldOffset(AbstractQueuedSynchronizer.class, "state");
    private static final long HEAD
            = U.objectFieldOffset(AbstractQueuedSynchronizer.class, "head");
    private static final long TAIL
            = U.objectFieldOffset(AbstractQueuedSynchronizer.class, "tail");

    static {
        Class<?> ensureLoaded = LockSupport.class;
    }
}
