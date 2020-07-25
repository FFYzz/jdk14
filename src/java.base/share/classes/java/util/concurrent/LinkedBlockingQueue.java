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

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 基于链表的可选的/无界的阻塞队列，FIFO 队列。链表队列比基于数据的队列具有更高的吞吐量，但是在并发场景下，
 * 基于数组的队列具有更高的性能。
 * <p>
 * An optionally-bounded {@linkplain BlockingQueue blocking queue} based on
 * linked nodes.
 * This queue orders elements FIFO (first-in-first-out).
 * The <em>head</em> of the queue is that element that has been on the
 * queue the longest time.
 * The <em>tail</em> of the queue is that element that has been on the
 * queue the shortest time. New elements
 * are inserted at the tail of the queue, and the queue retrieval
 * operations obtain elements at the head of the queue.
 * Linked queues typically have higher throughput than array-based queues but
 * less predictable performance in most concurrent applications.
 * <p>
 * 构造函数中可以指定 capacity。不指定的话默认的 capacity 为 Integer#MAX_VALUE。
 *
 * <p>The optional capacity bound constructor argument serves as a
 * way to prevent excessive queue expansion. The capacity, if unspecified,
 * is equal to {@link Integer#MAX_VALUE}.  Linked nodes are
 * dynamically created upon each insertion unless this would bring the
 * queue above capacity.
 *
 * <p>This class and its iterator implement all of the <em>optional</em>
 * methods of the {@link Collection} and {@link Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/java.base/java/util/package-summary.html#CollectionsFramework">
 * Java Collections Framework</a>.
 *
 * @param <E> the type of elements held in this queue
 * @author Doug Lea
 * @since 1.5
 */

/**
 * 问题：
 * 1. LinkedBlockingQueue 是无界的吗？
 * 不是无界的，最大的长度了 Integer.MAX_INTEGER
 * 2. LinkedBlockingQueue 指定容量之后继续添加会怎么样？
 * 阻塞等待/返回 false/ 抛出异常
 * 3. LinkedBlockingQueue 是否有 dummy node
 * 有一个 dummy head
 * 4. 超时之后如何处理？
 * 超时之后还未入队成功则返回 false，不会排除超时异常
 * 5. put/offer/add | take/poll/remove | peek/element
 * put/offer/take/poll 都是单独实现，add/remove的实现基于 offer/poll
 * put/take 阻塞直到执行成功或者被中断
 * offer/poll 返回操作是否成功 true/false
 * add/remove 失败则抛异常
 * peek 返回元素 element 调用 peek，获取失败则抛异常
 * 6. little tips
 * 匹配数组中匹配命中的位置，可以使用位操作的方法，声明一个 long 类型的变量，通过左移来标记。
 * 如果匹配到了，则 64 位变量上相应位置的 bite 值为 1.
 *
 * @param <E>
 */
public class LinkedBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -6903933977591709194L;

    /*
     * two lock queue 的一个变体实现。
     *
     * A variant of the "two lock queue" algorithm.  The putLock gates
     * entry to put (and offer), and has an associated condition for
     * waiting puts.  Similarly for the takeLock.  The "count" field
     * that they both rely on is maintained as an atomic to avoid
     * needing to get both locks in most cases. Also, to minimize need
     * for puts to get takeLock and vice-versa, cascading notifies are
     * used. When a put notices that it has enabled at least one take,
     * it signals taker. That taker in turn signals others if more
     * items have been entered since the signal. And symmetrically for
     * takes signalling puts. Operations such as remove(Object) and
     * iterators acquire both locks.
     *
     * Visibility between writers and readers is provided as follows:
     *
     * 元素入队，需要请求 putLock，并且更新 count。
     *
     * Whenever an element is enqueued, the putLock is acquired and
     * count updated.  A subsequent reader guarantees visibility to the
     * enqueued Node by either acquiring the putLock (via fullyLock)
     * or by acquiring the takeLock, and then reading n = count.get();
     * this gives visibility to the first n items.
     *
     * To implement weakly consistent iterators, it appears we need to
     * keep all Nodes GC-reachable from a predecessor dequeued Node.
     * That would cause two problems:
     * - allow a rogue Iterator to cause unbounded memory retention
     * - cause cross-generational linking of old Nodes to new Nodes if
     *   a Node was tenured while live, which generational GCs have a
     *   hard time dealing with, causing repeated major collections.
     * However, only non-deleted Nodes need to be reachable from
     * dequeued Nodes, and reachability does not necessarily have to
     * be of the kind understood by the GC.  We use the trick of
     * linking a Node that has just been dequeued to itself.  Such a
     * self-link implicitly means to advance to head.next.
     */

    /**
     * 描述链表节点的数据结构
     * <p>
     * Linked list node class.
     */
    static class Node<E> {
        E item;

        /**
         * One of:
         * 存在一个真正的后继节点
         * - the real successor Node
         * 指向自己，猴急节点是 head 的后继节点
         * - this Node, meaning the successor is head.next
         * null，最后一个节点
         * - null, meaning there is no successor (this is the last node)
         */
        Node<E> next;

        Node(E x) {
            item = x;
        }
    }

    /**
     * 指定的容量
     * <p>
     * The capacity bound, or Integer.MAX_VALUE if none
     */
    private final int capacity;

    /**
     * 当前 queue 中的容量
     * <p>
     * Current number of elements
     */
    private final AtomicInteger count = new AtomicInteger();

    /**
     * 头结点
     * <p>
     * Head of linked list.
     * Invariant: head.item == null
     */
    transient Node<E> head;

    /**
     * 队尾节点
     * <p>
     * Tail of linked list.
     * Invariant: last.next == null
     */
    private transient Node<E> last;

    /**
     * take、poll 锁
     * <p>
     * Lock held by take, poll, etc
     */
    private final ReentrantLock takeLock = new ReentrantLock();

    /**
     * 取操作的等待队列，当队列为空的时候等待
     * <p>
     * Wait queue for waiting takes
     */
    @SuppressWarnings("serial") // Classes implementing Condition may be serializable.
    private final Condition notEmpty = takeLock.newCondition();

    /**
     * put、offer 锁
     * <p>
     * Lock held by put, offer, etc
     */
    private final ReentrantLock putLock = new ReentrantLock();

    /**
     * 放操作的等待队列，当队列为满的时候等待
     * <p>
     * Wait queue for waiting puts
     */
    @SuppressWarnings("serial") // Classes implementing Condition may be serializable.
    private final Condition notFull = putLock.newCondition();

    /**
     * 非空 signal
     * <p>
     * Signals a waiting take. Called only from put/offer (which do not
     * otherwise ordinarily lock takeLock.)
     */
    private void signalNotEmpty() {
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * 非满 signal
     * <p>
     * Signals a waiting put. Called only from take/poll.
     */
    private void signalNotFull() {
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            notFull.signal();
        } finally {
            putLock.unlock();
        }
    }

    /**
     * 入队
     * <p>
     * Links node at end of queue.
     *
     * @param node the node
     */
    private void enqueue(Node<E> node) {
        // assert putLock.isHeldByCurrentThread();
        // assert last.next == null;
        last = last.next = node;
    }

    /**
     * 出队，返回的是 head.next 的值
     * <p>
     * Removes a node from head of queue.
     *
     * @return the node
     */
    private E dequeue() {
        // assert takeLock.isHeldByCurrentThread();
        // assert head.item == null;
        Node<E> h = head;
        Node<E> first = h.next;
        // 断开 head 的相关引用
        h.next = h; // help GC
        head = first;
        E x = first.item;
        first.item = null;
        return x;
    }

    /**
     * 存取都加锁
     * <p>
     * Locks to prevent both puts and takes.
     */
    void fullyLock() {
        putLock.lock();
        takeLock.lock();
    }

    /**
     * 存取都解锁
     * <p>
     * Unlocks to allow both puts and takes.
     */
    void fullyUnlock() {
        takeLock.unlock();
        putLock.unlock();
    }

    /**
     * 无参构造方法，默认 capacity 为 Integer#MAX_VALUE
     * <p>
     * Creates a {@code LinkedBlockingQueue} with a capacity of
     * {@link Integer#MAX_VALUE}.
     */
    public LinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * 指定 capacity 构造方法
     * 同时会初始化一个 dummy head
     * <p>
     * Creates a {@code LinkedBlockingQueue} with the given (fixed) capacity.
     *
     * @param capacity the capacity of this queue
     * @throws IllegalArgumentException if {@code capacity} is not greater
     *                                  than zero
     */
    public LinkedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        // dummy head
        last = head = new Node<E>(null);
    }

    /**
     * 创建一个 capacity 为 Integer#MAX_VALUE 的 Queue
     * 并且将 Collection 中的元素入队
     * <p>
     * Creates a {@code LinkedBlockingQueue} with a capacity of
     * {@link Integer#MAX_VALUE}, initially containing the elements of the
     * given collection,
     * added in traversal order of the collection's iterator.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *                              of its elements are null
     */
    public LinkedBlockingQueue(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        final ReentrantLock putLock = this.putLock;
        putLock.lock(); // Never contended, but necessary for visibility
        try {
            int n = 0;
            // 遍历
            for (E e : c) {
                if (e == null)
                    throw new NullPointerException();
                if (n == capacity)
                    throw new IllegalStateException("Queue full");
                enqueue(new Node<E>(e));
                ++n;
            }
            count.set(n);
        } finally {
            putLock.unlock();
        }
    }

    // this doc comment is overridden to remove the reference to collections
    // greater in size than Integer.MAX_VALUE

    /**
     * LinkedBlokingQueue 的容量
     * <p>
     * Returns the number of elements in this queue.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        return count.get();
    }

    // this doc comment is a modified copy of the inherited doc comment,
    // without the reference to unlimited queues.

    /**
     * 理论上返回剩余的 capacity
     * 加上"理论上"的原因是 Queue 的容量受外部物理机的内存或者其他资源的限制
     * <p>
     * Returns the number of additional elements that this queue can ideally
     * (in the absence of memory or resource constraints) accept without
     * blocking. This is always equal to the initial capacity of this queue
     * less the current {@code size} of this queue.
     *
     * <p>Note that you <em>cannot</em> always tell if an attempt to insert
     * an element will succeed by inspecting {@code remainingCapacity}
     * because it may be the case that another thread is about to
     * insert or remove an element.
     */
    public int remainingCapacity() {
        return capacity - count.get();
    }

    /**
     * public 给外部调用的方法
     * <p>
     * Inserts the specified element at the tail of this queue, waiting if
     * necessary for space to become available.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        // 不能入队 null 元素
        if (e == null) throw new NullPointerException();
        final int c;
        // 封装成 Node
        final Node<E> node = new Node<E>(e);
        final ReentrantLock putLock = this.putLock;
        final AtomicInteger count = this.count;
        // 支持响应中断
        putLock.lockInterruptibly();
        try {
            /*
             * Note that count is used in wait guard even though it is
             * not protected by lock. This works because count can
             * only decrease at this point (all other puts are shut
             * out by lock), and we (or some other waiting put) are
             * signalled if it ever changes from capacity. Similarly
             * for all other uses of count in other wait guards.
             */
            // 队满等到
            while (count.get() == capacity) {
                notFull.await();
            }
            // 入队
            enqueue(node);
            // 计数 + 1
            c = count.getAndIncrement();
            // 未满
            if (c + 1 < capacity)
                notFull.signal();
        } finally {
            putLock.unlock();
        }
        if (c == 0)
            signalNotEmpty();
    }

    /**
     * 入队操作，支持超时入队
     * <p>
     * Inserts the specified element at the tail of this queue, waiting if
     * necessary up to the specified wait time for space to become available.
     *
     * @return {@code true} if successful, or {@code false} if
     * the specified waiting time elapses before space is available
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {

        if (e == null) throw new NullPointerException();
        // 转为 nanos
        long nanos = unit.toNanos(timeout);
        final int c;
        final ReentrantLock putLock = this.putLock;
        final AtomicInteger count = this.count;
        putLock.lockInterruptibly();
        try {
            while (count.get() == capacity) {
                if (nanos <= 0L)
                    // 如果时间到了还没有入队，则返回 false
                    return false;
                // 阻塞等待 nano 时间
                nanos = notFull.awaitNanos(nanos);
            }
            // 入队
            enqueue(new Node<E>(e));
            c = count.getAndIncrement();
            if (c + 1 < capacity)
                notFull.signal();
        } finally {
            putLock.unlock();
        }
        if (c == 0)
            signalNotEmpty();
        return true;
    }

    /**
     * 不带超时时间的 offer
     * <p>
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately without exceeding the queue's capacity,
     * returning {@code true} upon success and {@code false} if this queue
     * is full.
     * When using a capacity-restricted queue, this method is generally
     * preferable to method {@link BlockingQueue#add add}, which can fail to
     * insert an element only by throwing an exception.
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        final AtomicInteger count = this.count;
        if (count.get() == capacity)
            return false;
        final int c;
        final Node<E> node = new Node<E>(e);
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            if (count.get() == capacity)
                return false;
            enqueue(node);
            c = count.getAndIncrement();
            if (c + 1 < capacity)
                notFull.signal();
        } finally {
            putLock.unlock();
        }
        if (c == 0)
            signalNotEmpty();
        return true;
    }

    /**
     * 出队操作
     *
     * @return
     * @throws InterruptedException
     */
    public E take() throws InterruptedException {
        final E x;
        final int c;
        final AtomicInteger count = this.count;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();
        try {
            while (count.get() == 0) {
                notEmpty.await();
            }
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }

    /**
     * 出队操作，poll 带超时时间
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        final E x;
        final int c;
        long nanos = unit.toNanos(timeout);
        final AtomicInteger count = this.count;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();
        try {
            while (count.get() == 0) {
                if (nanos <= 0L)
                    return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }

    /**
     * 不带超时时间的 poll。
     * 马上返回
     *
     * @return
     */
    public E poll() {
        final AtomicInteger count = this.count;
        if (count.get() == 0)
            return null;
        final E x;
        final int c;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            if (count.get() == 0)
                return null;
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }

    /**
     * 获取队头元素，队空直接返回 null
     *
     * @return
     */
    public E peek() {
        final AtomicInteger count = this.count;
        if (count.get() == 0)
            return null;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            return (count.get() > 0) ? head.next.item : null;
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * 将节点 p 从链表中移除
     * <p>
     * Unlinks interior Node p with predecessor pred.
     */
    void unlink(Node<E> p, Node<E> pred) {
        // assert putLock.isHeldByCurrentThread();
        // assert takeLock.isHeldByCurrentThread();
        // p.next is not changed, to allow iterators that are
        // traversing p to maintain their weak-consistency guarantee.
        p.item = null;
        // 前驱节点指向 p 的后继节点
        pred.next = p.next;
        // 如果 p 是尾节点，那么 last 引用直接指向 pred
        if (last == p)
            last = pred;
        // count - 1
        // TODO 为什么是 == capacity，元素减少还检查 == capacity???
        // TODO 不应该是 == 0 ?
        // 因为 getAndDecrement 返回 decrement 之前的值
        if (count.getAndDecrement() == capacity)
            notFull.signal();
    }

    /**
     * 从队列中移除某一个元素
     * <p>
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        if (o == null) return false;
        fullyLock();
        try {
            // 从头节点开始遍历
            for (Node<E> pred = head, p = pred.next;
                 p != null;
                 pred = p, p = p.next) {
                if (o.equals(p.item)) {
                    unlink(p, pred);
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * 队列中是否包含某个元素
     * <p>
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     */
    public boolean contains(Object o) {
        if (o == null) return false;
        fullyLock();
        try {
            // 从头节点开始遍历
            for (Node<E> p = head.next; p != null; p = p.next)
                if (o.equals(p.item))
                    return true;
            return false;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * 队列转成数组
     * <p>
     * Returns an array containing all of the elements in this queue, in
     * proper sequence.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    public Object[] toArray() {
        fullyLock();
        try {
            int size = count.get();
            Object[] a = new Object[size];
            int k = 0;
            for (Node<E> p = head.next; p != null; p = p.next)
                a[k++] = p.item;
            return a;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence; the runtime type of the returned array is that of
     * the specified array.  If the queue fits in the specified array, it
     * is returned therein.  Otherwise, a new array is allocated with the
     * runtime type of the specified array and the size of this queue.
     *
     * <p>If this queue fits in the specified array with room to spare
     * (i.e., the array has more elements than this queue), the element in
     * the array immediately following the end of the queue is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>Suppose {@code x} is a queue known to contain only strings.
     * The following code can be used to dump the queue into a newly
     * allocated array of {@code String}:
     *
     * <pre> {@code String[] y = x.toArray(new String[0]);}</pre>
     * <p>
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException  if the runtime type of the specified array
     *                              is not a supertype of the runtime type of every element in
     *                              this queue
     * @throws NullPointerException if the specified array is null
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        fullyLock();
        try {
            int size = count.get();
            if (a.length < size)
                a = (T[]) java.lang.reflect.Array.newInstance
                        (a.getClass().getComponentType(), size);

            int k = 0;
            for (Node<E> p = head.next; p != null; p = p.next)
                a[k++] = (T) p.item;
            // 一般不会出现 > k 的情况
            if (a.length > k)
                a[k] = null;
            return a;
        } finally {
            fullyUnlock();
        }
    }

    public String toString() {
        return Helpers.collectionToString(this);
    }

    /**
     * 清除队列中的所有元素
     * <p>
     * Atomically removes all of the elements from this queue.
     * The queue will be empty after this call returns.
     */
    public void clear() {
        fullyLock();
        try {
            for (Node<E> p, h = head; (p = h.next) != null; h = p) {
                h.next = h;
                p.item = null;
            }
            head = last;
            // assert head.item == null && head.next == null;
            if (count.getAndSet(0) == capacity)
                notFull.signal();
        } finally {
            fullyUnlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        Objects.requireNonNull(c);
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        boolean signalNotFull = false;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            int n = Math.min(maxElements, count.get());
            // count.get provides visibility to first n Nodes
            Node<E> h = head;
            int i = 0;
            try {
                while (i < n) {
                    Node<E> p = h.next;
                    c.add(p.item);
                    p.item = null;
                    // 将头节点的 next 域指向自己，从队列中移除
                    h.next = h;
                    // h 指向 p
                    h = p;
                    ++i;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                // 如果有发生移动
                if (i > 0) {
                    // assert h.item == null;
                    // 更新 head 指向 h
                    head = h;
                    // 更新 count
                    // 是否满
                    // getAndAdd 返回之前的 count
                    signalNotFull = (count.getAndAdd(-i) == capacity);
                }
            }
        } finally {
            takeLock.unlock();
            // 如果满
            if (signalNotFull)
                // 需要调用 notFull.signal();
                signalNotFull();
        }
    }

    /**
     * 在无锁环境下获取节点的 p 的后继节点
     * <p>
     * Used for any element traversal that is not entirely under lock.
     * Such traversals must handle both:
     * - dequeued nodes (p.next == p)
     * - (possibly multiple) interior removed nodes (p.item == null)
     */
    Node<E> succ(Node<E> p) {
        if (p == (p = p.next))
            p = head.next;
        return p;
    }

    /**
     * 返回一个迭代器
     * <p>
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    /**
     * 弱一致性迭代器的实现
     * <p>
     * Weakly-consistent iterator.
     * <p>
     * Lazily updated ancestor field provides expected O(1) remove(),
     * but still O(n) in the worst case, whenever the saved ancestor
     * is concurrently deleted.
     */
    private class Itr implements Iterator<E> {
        private Node<E> next;           // Node holding nextItem
        private E nextItem;             // next item to hand out
        /**
         * 上一次遍历到的 Node
         */
        private Node<E> lastRet;
        private Node<E> ancestor;       // Helps unlink lastRet on remove()

        /**
         * 构造方法
         */
        Itr() {
            fullyLock();
            try {
                // 初始化 next 以及 nextItem
                if ((next = head.next) != null)
                    nextItem = next.item;
            } finally {
                fullyUnlock();
            }
        }

        public boolean hasNext() {
            return next != null;
        }

        public E next() {
            Node<E> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            // 保存上一次遍历的 Node
            lastRet = p;
            // 当前调用返回的值
            E x = nextItem;
            fullyLock();
            try {
                E e = null;
                // TODO 这里的 e 为什么会等于 null？
                for (p = p.next; p != null && (e = p.item) == null; )
                    p = succ(p);
                // 往后移动
                next = p;
                nextItem = e;
            } finally {
                fullyUnlock();
            }
            return x;
        }

        /**
         * @param action
         */
        public void forEachRemaining(Consumer<? super E> action) {
            // A variant of forEachFrom
            Objects.requireNonNull(action);
            Node<E> p;
            // 队列中的第一个有效节点
            if ((p = next) == null) return;
            lastRet = p;
            next = null;
            // 一次批量处理的大小
            // 为什么要用 64 ？因为后续使用 long 类型来表示 deathRow。是 64 位的。
            final int batchSize = 64;
            Object[] es = null;
            int n, len = 1;
            // 一次最多处理 64 个
            do {
                fullyLock();
                try {
                    if (es == null) {
                        p = p.next;
                        // 计算 len
                        for (Node<E> q = p; q != null; q = succ(q))
                            if (q.item != null && ++len == batchSize)
                                break;
                        // 分配数组
                        es = new Object[len];
                        // 保存到数组中
                        es[0] = nextItem;
                        nextItem = null;
                        n = 1;
                    } else
                        n = 0;
                    //
                    for (; p != null && n < len; p = succ(p))
                        // 保存到 es 数组中
                        if ((es[n] = p.item) != null) {
                            // 更新 lastRet，记录遍历的位置
                            lastRet = p;
                            // 计数
                            n++;
                        }
                } finally {
                    fullyUnlock();
                }
                // 开始消费
                for (int i = 0; i < n; i++) {
                    @SuppressWarnings("unchecked") E e = (E) es[i];
                    action.accept(e);
                }
            } while (n > 0 && p != null);
        }

        public void remove() {
            // 当前迭代的上一个元素
            Node<E> p = lastRet;
            if (p == null)
                throw new IllegalStateException();
            // 将其置为 null，这个元素为要 remove 的元素
            lastRet = null;
            fullyLock();
            try {
                if (p.item != null) {
                    // 如果 ancestor 未初始化
                    if (ancestor == null)
                        // 指向头节点
                        ancestor = head;
                    // 找到 p 的前驱结点
                    ancestor = findPred(p, ancestor);
                    // 将 p 从链表中移除
                    unlink(p, ancestor);
                }
            } finally {
                fullyUnlock();
            }
        }
    }

    /**
     * A customized variant of Spliterators.IteratorSpliterator.
     * Keep this class in sync with (very similar) LBDSpliterator.
     */
    private final class LBQSpliterator implements Spliterator<E> {
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        Node<E> current;    // current node; null until initialized
        int batch;          // batch size for splits
        boolean exhausted;  // true when no more nodes
        long est = size();  // size estimate

        LBQSpliterator() {
        }

        public long estimateSize() {
            return est;
        }

        public Spliterator<E> trySplit() {
            Node<E> h;
            if (!exhausted &&
                    ((h = current) != null || (h = head.next) != null)
                    && h.next != null) {
                int n = batch = Math.min(batch + 1, MAX_BATCH);
                Object[] a = new Object[n];
                int i = 0;
                Node<E> p = current;
                fullyLock();
                try {
                    if (p != null || (p = head.next) != null)
                        for (; p != null && i < n; p = succ(p))
                            if ((a[i] = p.item) != null)
                                i++;
                } finally {
                    fullyUnlock();
                }
                if ((current = p) == null) {
                    est = 0L;
                    exhausted = true;
                } else if ((est -= i) < 0L)
                    est = 0L;
                if (i > 0)
                    return Spliterators.spliterator
                            (a, 0, i, (Spliterator.ORDERED |
                                    Spliterator.NONNULL |
                                    Spliterator.CONCURRENT));
            }
            return null;
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            if (!exhausted) {
                E e = null;
                fullyLock();
                try {
                    Node<E> p;
                    if ((p = current) != null || (p = head.next) != null)
                        do {
                            e = p.item;
                            p = succ(p);
                        } while (e == null && p != null);
                    if ((current = p) == null)
                        exhausted = true;
                } finally {
                    fullyUnlock();
                }
                if (e != null) {
                    action.accept(e);
                    return true;
                }
            }
            return false;
        }

        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            if (!exhausted) {
                exhausted = true;
                Node<E> p = current;
                current = null;
                forEachFrom(action, p);
            }
        }

        public int characteristics() {
            return (Spliterator.ORDERED |
                    Spliterator.NONNULL |
                    Spliterator.CONCURRENT);
        }
    }

    /**
     * Returns a {@link Spliterator} over the elements in this queue.
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#ORDERED}, and {@link Spliterator#NONNULL}.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @implNote The {@code Spliterator} implements {@code trySplit} to permit limited
     * parallelism.
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return new LBQSpliterator();
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        forEachFrom(action, null);
    }

    /**
     * 遍历，consumer，可以指定从某个节点开始
     * <p>
     * Runs action on each element found during a traversal starting at p.
     * If p is null, traversal starts at head.
     */
    void forEachFrom(Consumer<? super E> action, Node<E> p) {
        // Extract batches of elements while holding the lock; then
        // run the action on the elements while not
        final int batchSize = 64;       // max number of elements per batch
        Object[] es = null;             // container for batch of elements
        int n, len = 0;
        do {
            fullyLock();
            try {
                if (es == null) {
                    if (p == null) p = head.next;
                    for (Node<E> q = p; q != null; q = succ(q))
                        if (q.item != null && ++len == batchSize)
                            break;
                    es = new Object[len];
                }
                for (n = 0; p != null && n < len; p = succ(p))
                    if ((es[n] = p.item) != null)
                        n++;
            } finally {
                fullyUnlock();
            }
            for (int i = 0; i < n; i++) {
                @SuppressWarnings("unchecked") E e = (E) es[i];
                action.accept(e);
            }
        } while (n > 0 && p != null);
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return bulkRemove(filter);
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> c.contains(e));
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> !c.contains(e));
    }

    /**
     * 返回 p 节点的前驱节点，赋值给 ancestor
     * <p>
     * Returns the predecessor of live node p, given a node that was
     * once a live ancestor of p (or head); allows unlinking of p.
     */
    Node<E> findPred(Node<E> p, Node<E> ancestor) {
        // assert p.item != null;
        if (ancestor.item == null)
            ancestor = head;
        // Fails with NPE if precondition not satisfied
        for (Node<E> q; (q = ancestor.next) != p; )
            ancestor = q;
        return ancestor;
    }

    /**
     * 返回是否成功移除元素
     * <p>
     * Implementation of bulk remove methods.
     */
    @SuppressWarnings("unchecked")
    private boolean bulkRemove(Predicate<? super E> filter) {
        boolean removed = false;
        Node<E> p = null, ancestor = head;
        Node<E>[] nodes = null;
        int n, len = 0;
        do {
            // 1. Extract batch of up to 64 elements while holding the lock.
            fullyLock();
            try {
                if (nodes == null) {  // first batch; initialize
                    // 第一个有效的元素
                    p = head.next;
                    // 往后遍历，主要为了计算得到 len 的长度
                    // 如果 len == 64，说明可能还需要下一轮循环
                    for (Node<E> q = p; q != null; q = succ(q))
                        if (q.item != null && ++len == 64)
                            break;
                    // 创建一个 len 长度的 Node 类型的数组
                    nodes = (Node<E>[]) new Node<?>[len];
                }
                // 将链表中的数据保存到数组 nodes 中。
                for (n = 0; p != null && n < len; p = succ(p))
                    nodes[n++] = p;
            } finally {
                fullyUnlock();
            }

            // 2. Run the filter on the elements while lock is free.
            //
            long deathRow = 0L;       // "bitset" of size 64
            for (int i = 0; i < n; i++) {
                final E e;
                // 取出元素，执行 filter
                if ((e = nodes[i].item) != null && filter.test(e))
                    // 最多左移 64 位
                    deathRow |= 1L << i;
            }

            // 3. Remove any filtered elements while holding the lock.
            // deathRow != 0 说明有满足 filter 的节点
            if (deathRow != 0) {
                fullyLock();
                try {
                    for (int i = 0; i < n; i++) {
                        final Node<E> q;
                        // 开始找到满足条件的 node
                        if ((deathRow & (1L << i)) != 0L
                                && (q = nodes[i]).item != null) {
                            // 找到该节点的前驱节点
                            ancestor = findPred(q, ancestor);
                            // 将该节点从链表中移除
                            unlink(q, ancestor);
                            removed = true;
                        }
                        nodes[i] = null; // help GC
                    }
                } finally {
                    fullyUnlock();
                }
            }
        } while (n > 0 && p != null);
        return removed;
    }

    /**
     * Saves this queue to a stream (that is, serializes it).
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData The capacity is emitted (int), followed by all of
     * its elements (each an {@code Object}) in the proper order,
     * followed by a null
     */
    private void writeObject(java.io.ObjectOutputStream s)
            throws java.io.IOException {

        fullyLock();
        try {
            // Write out any hidden stuff, plus capacity
            s.defaultWriteObject();

            // Write out all elements in the proper order.
            for (Node<E> p = head.next; p != null; p = p.next)
                s.writeObject(p.item);

            // Use trailing null as sentinel
            s.writeObject(null);
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     *
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *                                could not be found
     * @throws java.io.IOException    if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
        // Read in capacity, and any hidden stuff
        s.defaultReadObject();

        count.set(0);
        last = head = new Node<E>(null);

        // Read in all elements and place in queue
        for (; ; ) {
            @SuppressWarnings("unchecked")
            E item = (E) s.readObject();
            if (item == null)
                break;
            add(item);
        }
    }
}
