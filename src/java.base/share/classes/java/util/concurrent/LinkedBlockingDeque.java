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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An optionally-bounded {@linkplain BlockingDeque blocking deque} based on
 * linked nodes.
 *
 * <p>The optional capacity bound constructor argument serves as a
 * way to prevent excessive expansion. The capacity, if unspecified,
 * is equal to {@link Integer#MAX_VALUE}.  Linked nodes are
 * dynamically created upon each insertion unless this would bring the
 * deque above capacity.
 *
 * <p>Most operations run in constant time (ignoring time spent
 * blocking).  Exceptions include {@link #remove(Object) remove},
 * {@link #removeFirstOccurrence removeFirstOccurrence}, {@link
 * #removeLastOccurrence removeLastOccurrence}, {@link #contains
 * contains}, {@link #iterator iterator.remove()}, and the bulk
 * operations, all of which run in linear time.
 *
 * <p>This class and its iterator implement all of the <em>optional</em>
 * methods of the {@link Collection} and {@link Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/java.base/java/util/package-summary.html#CollectionsFramework">
 * Java Collections Framework</a>.
 *
 * @param <E> the type of elements held in this deque
 * @author Doug Lea
 * @since 1.6
 */

/**
 * 1. 底层存储结构是什么？
 * 2. 无界还是有界？
 *
 * @param <E>
 */
public class LinkedBlockingDeque<E>
        extends AbstractQueue<E>
        implements BlockingDeque<E>, java.io.Serializable {

    /*
     * Implemented as a simple doubly-linked list protected by a
     * single lock and using conditions to manage blocking.
     *
     * 弱一致性迭代器
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
     * self-link implicitly means to jump to "first" (for next links)
     * or "last" (for prev links).
     */

    /*
     * We have "diamond" multiple interface/abstract class inheritance
     * here, and that introduces ambiguities. Often we want the
     * BlockingDeque javadoc combined with the AbstractQueue
     * implementation, so a lot of method specs are duplicated here.
     */

    private static final long serialVersionUID = -387911632671998426L;

    /**
     * 内部节点
     * <p>
     * Doubly-linked list node class
     */
    static final class Node<E> {
        /**
         * 保存的对象
         * <p>
         * The item, or null if this node has been removed.
         */
        E item;

        /**
         * 前驱结点
         * <p>
         * One of:
         * - the real predecessor Node
         * - this Node, meaning the predecessor is tail
         * - null, meaning there is no predecessor
         */
        Node<E> prev;

        /**
         * 后继节点
         * <p>
         * One of:
         * - the real successor Node
         * - this Node, meaning the successor is head
         * - null, meaning there is no successor
         */
        Node<E> next;

        Node(E x) {
            item = x;
        }
    }

    /**
     * Pointer to first node.
     * Invariant: (first == null && last == null) ||
     * (first.prev == null && first.item != null)
     */
    transient Node<E> first;

    /**
     * Pointer to last node.
     * Invariant: (first == null && last == null) ||
     * (last.next == null && last.item != null)
     */
    transient Node<E> last;

    /**
     * deque 中元素的个数
     * <p>
     * Number of items in the deque
     */
    private transient int count;

    /**
     * deque 能够存放的最多的元素个数
     * <p>
     * Maximum number of items in the deque
     */
    private final int capacity;

    /**
     * Main lock guarding all access
     */
    final ReentrantLock lock = new ReentrantLock();

    /**
     * Condition for waiting takes
     */
    @SuppressWarnings("serial") // Classes implementing Condition may be serializable.
    private final Condition notEmpty = lock.newCondition();

    /**
     * Condition for waiting puts
     */
    @SuppressWarnings("serial") // Classes implementing Condition may be serializable.
    private final Condition notFull = lock.newCondition();

    /**
     * Creates a {@code LinkedBlockingDeque} with a capacity of
     * {@link Integer#MAX_VALUE}.
     */
    public LinkedBlockingDeque() {
        this(Integer.MAX_VALUE);
    }

    /**
     * 传入队列的 capacity
     * <p>
     * Creates a {@code LinkedBlockingDeque} with the given (fixed) capacity.
     *
     * @param capacity the capacity of this deque
     * @throws IllegalArgumentException if {@code capacity} is less than 1
     */
    public LinkedBlockingDeque(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
    }

    /**
     * 创建一个 LinkedBlockingDeque，将 Collection 中的元素全部都加入到 deque 中
     * <p>
     * Creates a {@code LinkedBlockingDeque} with a capacity of
     * {@link Integer#MAX_VALUE}, initially containing the elements of
     * the given collection, added in traversal order of the
     * collection's iterator.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *                              of its elements are null
     */
    public LinkedBlockingDeque(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        addAll(c);
    }


    // Basic linking and unlinking operations, called only while holding lock

    /**
     * 元素入队头
     * <p>
     * Links node as first element, or returns false if full.
     */
    private boolean linkFirst(Node<E> node) {
        // assert lock.isHeldByCurrentThread();
        if (count >= capacity)
            return false;
        Node<E> f = first;
        node.next = f;
        first = node;
        // 队列为 null
        if (last == null)
            last = node;
            // 队列不为 null
        else
            f.prev = node;
        ++count;
        notEmpty.signal();
        return true;
    }

    /**
     * 将节点放入到队尾
     * <p>
     * Links node as last element, or returns false if full.
     */
    private boolean linkLast(Node<E> node) {
        // assert lock.isHeldByCurrentThread();
        if (count >= capacity)
            return false;
        Node<E> l = last;
        node.prev = l;
        last = node;
        // 队列为空
        if (first == null)
            first = node;
            // 队列非空
        else
            l.next = node;
        // 计数 + 1
        ++count;
        notEmpty.signal();
        return true;
    }

    /**
     * 内部处理移除队头元素方法
     * <p>
     * Removes and returns first element, or null if empty.
     */
    private E unlinkFirst() {
        // assert lock.isHeldByCurrentThread();
        Node<E> f = first;
        // 队列为空
        if (f == null)
            return null;
        Node<E> n = f.next;
        E item = f.item;
        f.item = null;
        // next 指向自己，帮助 GC
        f.next = f; // help GC
        // first 指向 n
        first = n;
        // 如果队列没有元素了，则置为 null
        if (n == null)
            last = null;
        else
            n.prev = null;
        --count;
        notFull.signal();
        return item;
    }

    /**
     * Removes and returns last element, or null if empty.
     */
    private E unlinkLast() {
        // assert lock.isHeldByCurrentThread();
        Node<E> l = last;
        if (l == null)
            return null;
        Node<E> p = l.prev;
        E item = l.item;
        l.item = null;
        l.prev = l; // help GC
        last = p;
        if (p == null)
            first = null;
        else
            p.next = null;
        --count;
        notFull.signal();
        return item;
    }

    /**
     * 移除队列中的指定 Node
     * <p>
     * Unlinks x.
     */
    void unlink(Node<E> x) {
        // assert lock.isHeldByCurrentThread();
        // assert x.item != null;
        // 保存前驱
        Node<E> p = x.prev;
        // 保存后继
        Node<E> n = x.next;
        // 前驱为 null，相当于 unlinkFirst
        if (p == null) {
            unlinkFirst();
            // 后继为 null，相当于 unlinkLast
        } else if (n == null) {
            unlinkLast();
            // unlink 中间节点
        } else {
            // 更新指针
            p.next = n;
            n.prev = p;
            x.item = null;
            // Don't mess with x's links.  They may still be in use by
            // an iterator.
            --count;
            notFull.signal();
        }
    }

    // BlockingDeque methods

    /**
     * 向队头加入元素
     * 队满抛异常
     *
     * @throws IllegalStateException if this deque is full
     * @throws NullPointerException  {@inheritDoc}
     */
    public void addFirst(E e) {
        if (!offerFirst(e))
            throw new IllegalStateException("Deque full");
    }

    /**
     * 将元素加入到队尾
     * 调用 offerLast 方法
     *
     * @throws IllegalStateException if this deque is full
     * @throws NullPointerException  {@inheritDoc}
     */
    public void addLast(E e) {
        // 如果入队未成功，则抛出 IllegalStateException 异常
        if (!offerLast(e))
            throw new IllegalStateException("Deque full");
    }

    /**
     * 向队头加入元素
     *
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offerFirst(E e) {
        if (e == null) throw new NullPointerException();
        Node<E> node = new Node<E>(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return linkFirst(node);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将元素加入到队尾
     *
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offerLast(E e) {
        // 不能加入 null 元素
        if (e == null) throw new NullPointerException();
        // 封装成 Node
        Node<E> node = new Node<E>(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 调用 linkLast 方法
            return linkLast(node);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 元素加入到队头
     *
     * @throws NullPointerException {@inheritDoc}
     * @throws InterruptedException {@inheritDoc}
     */
    public void putFirst(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        Node<E> node = new Node<E>(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            while (!linkFirst(node))
                // 阻塞等待
                // 1. 中断
                // 2. signal
                notFull.await();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将元素放入队尾
     *
     * @throws NullPointerException {@inheritDoc}
     * @throws InterruptedException {@inheritDoc}
     */
    public void putLast(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        Node<E> node = new Node<E>(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            while (!linkLast(node))
                // 无限制阻塞等待
                // 直到被中断或者被 signal
                notFull.await();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 带超时时间的 offerFirst
     *
     * @throws NullPointerException {@inheritDoc}
     * @throws InterruptedException {@inheritDoc}
     */
    public boolean offerFirst(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (e == null) throw new NullPointerException();
        Node<E> node = new Node<E>(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            // 第一次入队失败
            while (!linkFirst(node)) {
                // 且还未超时
                if (nanos <= 0L)
                    return false;
                // 阻塞
                // 时间到唤醒或者 signal 唤醒
                nanos = notFull.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 带超时的 offerLast
     *
     * @throws NullPointerException {@inheritDoc}
     * @throws InterruptedException {@inheritDoc}
     */
    public boolean offerLast(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (e == null) throw new NullPointerException();
        Node<E> node = new Node<E>(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            // 当第一次 link 失败的是偶
            while (!linkLast(node)) {
                // 如果还未超时
                if (nanos <= 0L)
                    return false;
                // 阻塞超时时间
                // 可能时间到唤醒，也可能 signal 唤醒
                nanos = notFull.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除队头元素
     * 队空抛异常
     *
     * @throws NoSuchElementException {@inheritDoc}
     */
    public E removeFirst() {
        E x = pollFirst();
        if (x == null) throw new NoSuchElementException();
        return x;
    }

    /**
     * 移除队列的最后一个元素
     * 调用 pollLast，队空抛异常
     *
     * @throws NoSuchElementException {@inheritDoc}
     */
    public E removeLast() {
        E x = pollLast();
        if (x == null) throw new NoSuchElementException();
        return x;
    }

    /**
     * 移除队头元素
     * 队空返回 null
     *
     * @return
     */
    public E pollFirst() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return unlinkFirst();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除队尾元素
     *
     * @return
     */
    public E pollLast() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return unlinkLast();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从队头取元素
     *
     * @return
     * @throws InterruptedException
     */
    public E takeFirst() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            E x;
            while ((x = unlinkFirst()) == null)
                // 阻塞等待队列非空
                notEmpty.await();
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 去队列中最后一个元素
     *
     * @return
     * @throws InterruptedException
     */
    public E takeLast() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            E x;
            while ((x = unlinkLast()) == null)
                // 阻塞等待
                notEmpty.await();
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 带超时时间的队头移除元素
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public E pollFirst(long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            E x;
            while ((x = unlinkFirst()) == null) {
                if (nanos <= 0L)
                    // 如果阻塞时间过了之后还是队空，则返回 null
                    return null;
                // 阻塞指定时间
                nanos = notEmpty.awaitNanos(nanos);
            }
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 带超时时间的移除队尾元素
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public E pollLast(long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            E x;
            while ((x = unlinkLast()) == null) {
                if (nanos <= 0L)
                    return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回队头元素
     * 队空抛异常
     *
     * @throws NoSuchElementException {@inheritDoc}
     */
    public E getFirst() {
        E x = peekFirst();
        if (x == null) throw new NoSuchElementException();
        return x;
    }

    /**
     * 获取队尾元素
     * 队空抛异常
     *
     * @throws NoSuchElementException {@inheritDoc}
     */
    public E getLast() {
        E x = peekLast();
        if (x == null) throw new NoSuchElementException();
        return x;
    }

    /**
     * 返回队头元素
     * 队空返回 null
     *
     * @return
     */
    public E peekFirst() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return (first == null) ? null : first.item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回队尾元素
     * 队空返回 null
     *
     * @return
     */
    public E peekLast() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return (last == null) ? null : last.item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除第一个出现的元素
     *
     * @param o
     * @return
     */
    public boolean removeFirstOccurrence(Object o) {
        if (o == null) return false;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 从 first 开始遍历
            for (Node<E> p = first; p != null; p = p.next) {
                if (o.equals(p.item)) {
                    unlink(p);
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除元素出现在最后的那个实例
     *
     * @param o
     * @return
     */
    public boolean removeLastOccurrence(Object o) {
        if (o == null) return false;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 从最后开始遍历，遇到第一个匹配的就移除
            for (Node<E> p = last; p != null; p = p.prev) {
                if (o.equals(p.item)) {
                    unlink(p);
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    // BlockingQueue methods

    /**
     * 将元素加入到队尾
     * 调用 addLast 方法
     * <p>
     * Inserts the specified element at the end of this deque unless it would
     * violate capacity restrictions.  When using a capacity-restricted deque,
     * it is generally preferable to use method {@link #offer(Object) offer}.
     *
     * <p>This method is equivalent to {@link #addLast}.
     *
     * @throws IllegalStateException if this deque is full
     * @throws NullPointerException  if the specified element is null
     */
    public boolean add(E e) {
        addLast(e);
        return true;
    }

    /**
     * 往队尾中加入元素 e
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        return offerLast(e);
    }

    /**
     * 将元素放入队尾
     *
     * @throws NullPointerException {@inheritDoc}
     * @throws InterruptedException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        putLast(e);
    }

    /**
     * 往队尾添加元素，支持超时
     *
     * @throws NullPointerException {@inheritDoc}
     * @throws InterruptedException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        return offerLast(e, timeout, unit);
    }

    /**
     * 移除队头元素
     * 调用 removeFirst，队空抛异常，在 removeFirst 抛出
     * <p>
     * Retrieves and removes the head of the queue represented by this deque.
     * This method differs from {@link #poll() poll()} only in that it throws an
     * exception if this deque is empty.
     *
     * <p>This method is equivalent to {@link #removeFirst() removeFirst}.
     *
     * @return the head of the queue represented by this deque
     * @throws NoSuchElementException if this deque is empty
     */
    public E remove() {
        return removeFirst();
    }

    /**
     * 移除队头元素
     *
     * @return
     */
    public E poll() {
        return pollFirst();
    }

    /**
     * 从队头取元素
     *
     * @return
     * @throws InterruptedException
     */
    public E take() throws InterruptedException {
        return takeFirst();
    }

    /**
     * 带超时时间的移除队头元素
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return pollFirst(timeout, unit);
    }

    /**
     * 返回队头的元素
     * 队空抛异常
     * <p>
     * Retrieves, but does not remove, the head of the queue represented by
     * this deque.  This method differs from {@link #peek() peek()} only in that
     * it throws an exception if this deque is empty.
     *
     * <p>This method is equivalent to {@link #getFirst() getFirst}.
     *
     * @return the head of the queue represented by this deque
     * @throws NoSuchElementException if this deque is empty
     */
    public E element() {
        return getFirst();
    }

    /**
     * 返回队头元素
     * 队空返回 null
     *
     * @return
     */
    public E peek() {
        return peekFirst();
    }

    /**
     * 返回剩余的容量
     * <p>
     * Returns the number of additional elements that this deque can ideally
     * (in the absence of memory or resource constraints) accept without
     * blocking. This is always equal to the initial capacity of this deque
     * less the current {@code size} of this deque.
     *
     * <p>Note that you <em>cannot</em> always tell if an attempt to insert
     * an element will succeed by inspecting {@code remainingCapacity}
     * because it may be the case that another thread is about to
     * insert or remove an element.
     */
    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
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
     * 将队列中的元素取出（从队头取）保存到 Collection 中
     *
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = Math.min(maxElements, count);
            for (int i = 0; i < n; i++) {
                // 先加入 Collection 中
                c.add(first.item);   // In this order, in case add() throws.
                // 直接 unlinkFirst
                unlinkFirst();
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    // Stack methods

    /**
     * 元素入队头
     * 队满抛异常
     *
     * @throws IllegalStateException if this deque is full
     * @throws NullPointerException  {@inheritDoc}
     */
    public void push(E e) {
        addFirst(e);
    }

    /**
     * 弹出队头元素
     * 队空抛异常
     *
     * @throws NoSuchElementException {@inheritDoc}
     */
    public E pop() {
        return removeFirst();
    }

    // Collection methods

    /**
     * 移除第一个出现的元素
     * <p>
     * Removes the first occurrence of the specified element from this deque.
     * If the deque does not contain the element, it is unchanged.
     * More formally, removes the first element {@code e} such that
     * {@code o.equals(e)} (if such an element exists).
     * Returns {@code true} if this deque contained the specified element
     * (or equivalently, if this deque changed as a result of the call).
     *
     * <p>This method is equivalent to
     * {@link #removeFirstOccurrence(Object) removeFirstOccurrence}.
     *
     * @param o element to be removed from this deque, if present
     * @return {@code true} if this deque changed as a result of the call
     */
    public boolean remove(Object o) {
        return removeFirstOccurrence(o);
    }

    /**
     * 返回 deque 中元素的个数
     * <p>
     * Returns the number of elements in this deque.
     *
     * @return the number of elements in this deque
     */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回 deque 中是否包含指定对象 o
     * <p>
     * Returns {@code true} if this deque contains the specified element.
     * More formally, returns {@code true} if and only if this deque contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this deque
     * @return {@code true} if this deque contains the specified element
     */
    public boolean contains(Object o) {
        if (o == null) return false;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 从 first 开始遍历
            for (Node<E> p = first; p != null; p = p.next)
                if (o.equals(p.item))
                    return true;
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将 Collection 中所有的元素加入到 Deque 中
     * 如果 Collection 中存在 null 元素，在加入过程中会抛出 IllegalArgumentException
     * <p>
     * Appends all of the elements in the specified collection to the end of
     * this deque, in the order that they are returned by the specified
     * collection's iterator.  Attempts to {@code addAll} of a deque to
     * itself result in {@code IllegalArgumentException}.
     *
     * @param c the elements to be inserted into this deque
     * @return {@code true} if this deque changed as a result of the call
     * @throws NullPointerException     if the specified collection or any
     *                                  of its elements are null
     * @throws IllegalArgumentException if the collection is this deque
     * @throws IllegalStateException    if this deque is full
     * @see #add(Object)
     */
    public boolean addAll(Collection<? extends E> c) {
        // Deque 中不能存放 null 元素
        if (c == this)
            // As historically specified in AbstractQueue#addAll
            throw new IllegalArgumentException();

        // Copy c into a private chain of Nodes
        // beg 标记是否有元素存放进入
        Node<E> beg = null, end = null;
        // 计数，实际放入的元素个数
        int n = 0;
        for (E e : c) {
            Objects.requireNonNull(e);
            n++;
            // 封装节点
            Node<E> newNode = new Node<E>(e);
            if (beg == null)
                beg = end = newNode;
            else {
                end.next = newNode;
                newNode.prev = end;
                end = newNode;
            }
        }
        if (beg == null)
            return false;

        // Atomically append the chain at the end
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count + n <= capacity) {
                beg.prev = last;
                if (first == null)
                    first = beg;
                else
                    last.next = beg;
                last = end;
                count += n;
                notEmpty.signalAll();
                return true;
            }
        } finally {
            lock.unlock();
        }
        // Fall back to historic non-atomic implementation, failing
        // with IllegalStateException when the capacity is exceeded.
        return super.addAll(c);
    }

    /**
     * Returns an array containing all of the elements in this deque, in
     * proper sequence (from first to last element).
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this deque.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this deque
     */
    @SuppressWarnings("unchecked")
    public Object[] toArray() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] a = new Object[count];
            int k = 0;
            for (Node<E> p = first; p != null; p = p.next)
                a[k++] = p.item;
            return a;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this deque, in
     * proper sequence; the runtime type of the returned array is that of
     * the specified array.  If the deque fits in the specified array, it
     * is returned therein.  Otherwise, a new array is allocated with the
     * runtime type of the specified array and the size of this deque.
     *
     * <p>If this deque fits in the specified array with room to spare
     * (i.e., the array has more elements than this deque), the element in
     * the array immediately following the end of the deque is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>Suppose {@code x} is a deque known to contain only strings.
     * The following code can be used to dump the deque into a newly
     * allocated array of {@code String}:
     *
     * <pre> {@code String[] y = x.toArray(new String[0]);}</pre>
     * <p>
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the deque are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this deque
     * @throws ArrayStoreException  if the runtime type of the specified array
     *                              is not a supertype of the runtime type of every element in
     *                              this deque
     * @throws NullPointerException if the specified array is null
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (a.length < count)
                a = (T[]) java.lang.reflect.Array.newInstance
                        (a.getClass().getComponentType(), count);

            int k = 0;
            for (Node<E> p = first; p != null; p = p.next)
                a[k++] = (T) p.item;
            if (a.length > k)
                a[k] = null;
            return a;
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        return Helpers.collectionToString(this);
    }

    /**
     * 原子地移除 deque 中所有的元素
     * <p>
     * Atomically removes all of the elements from this deque.
     * The deque will be empty after this call returns.
     */
    public void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 从 first 开始遍历
            for (Node<E> f = first; f != null; ) {
                f.item = null;
                Node<E> n = f.next;
                f.prev = null;
                f.next = null;
                f = n;
            }
            first = last = null;
            count = 0;
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取后继节点
     * <p>
     * Used for any element traversal that is not entirely under lock.
     * Such traversals must handle both:
     * - dequeued nodes (p.next == p)
     * - (possibly multiple) interior removed nodes (p.item == null)
     */
    Node<E> succ(Node<E> p) {
        if (p == (p = p.next))
            p = first;
        return p;
    }

    /**
     * 返回正序迭代器
     * <p>
     * Returns an iterator over the elements in this deque in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this deque in proper sequence
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    /**
     * 返回逆序的迭代器
     * <p>
     * Returns an iterator over the elements in this deque in reverse
     * sequential order.  The elements will be returned in order from
     * last (tail) to first (head).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this deque in reverse order
     */
    public Iterator<E> descendingIterator() {
        return new DescendingItr();
    }

    /**
     * LinkedBlockingDeque 内部定义的 迭代器
     * <p>
     * Base class for LinkedBlockingDeque iterators.
     */
    private abstract class AbstractItr implements Iterator<E> {
        /**
         * 调用 next() 方法返回的 Node
         * <p>
         * The next node to return in next().
         */
        Node<E> next;

        /**
         * next() 方法返回的 item
         * <p>
         * nextItem holds on to item fields because once we claim that
         * an element exists in hasNext(), we must return item read
         * under lock even if it was in the process of being removed
         * when hasNext() was called.
         */
        E nextItem;

        /**
         * 上一次迭代到的 Node
         * <p>
         * Node returned by most recent call to next. Needed by remove.
         * Reset to null if this element is deleted by a call to remove.
         */
        private Node<E> lastRet;

        /**
         * 迭代器的首个 Node
         *
         * @return
         */
        abstract Node<E> firstNode();

        /**
         * 当前迭代的下一个 Node，是一个抽象方法
         * 根据具体实现决定行为，可能是向前也可能是向后
         *
         * @param n
         * @return
         */
        abstract Node<E> nextNode(Node<E> n);

        private Node<E> succ(Node<E> p) {
            if (p == (p = nextNode(p)))
                p = firstNode();
            return p;
        }

        /**
         * 构造方法
         */
        AbstractItr() {
            // set to initial position
            final ReentrantLock lock = LinkedBlockingDeque.this.lock;
            lock.lock();
            try {
                // 构造方法中初始化 next 以及 nextItem
                // 迭代的首个 Node
                // 可能是 first，也可能是 last，看具体实现
                if ((next = firstNode()) != null)
                    nextItem = next.item;
            } finally {
                lock.unlock();
            }
        }

        public boolean hasNext() {
            return next != null;
        }

        public E next() {
            Node<E> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            // 保存上一次迭代的 Node
            lastRet = p;
            // 保存上一次迭代的 Object
            E x = nextItem;
            final ReentrantLock lock = LinkedBlockingDeque.this.lock;
            lock.lock();
            try {
                E e = null;
                // 迭代到下一个有效的 node
                for (p = nextNode(p); p != null && (e = p.item) == null; )
                    p = succ(p);
                // 更新 next
                next = p;
                // 更新 nextItem
                nextItem = e;
            } finally {
                lock.unlock();
            }
            return x;
        }

        /**
         * 对当前迭代元素的后续元素执行 action
         *
         * @param action
         */
        public void forEachRemaining(Consumer<? super E> action) {
            // A variant of forEachFrom
            Objects.requireNonNull(action);
            Node<E> p;
            // 初始化 p
            if ((p = next) == null) return;
            // 记录上次迭代的 Node
            lastRet = p;
            next = null;
            final ReentrantLock lock = LinkedBlockingDeque.this.lock;
            final int batchSize = 64;
            Object[] es = null;
            // len 至少为 1，因为 (p = next) != null
            int n, len = 1;
            // 一批最多处理 64
            do {
                lock.lock();
                try {
                    // 第一轮循环进来的时候
                    if (es == null) {
                        // 下一个 node
                        p = nextNode(p);
                        // 统计满足条件的 node 个数
                        for (Node<E> q = p; q != null; q = succ(q))
                            if (q.item != null && ++len == batchSize)
                                break;
                        // 初始化 es 数组
                        es = new Object[len];
                        // 将 nextItem 放到第一个位置
                        es[0] = nextItem;
                        nextItem = null;
                        // 计数为 1
                        n = 1;
                    } else
                        n = 0;
                    // p 已经后移了
                    for (; p != null && n < len; p = succ(p))
                        // 保存到 es 数组中
                        if ((es[n] = p.item) != null) {
                            // 更新 lastRet
                            lastRet = p;
                            // 计数++
                            n++;
                        }
                } finally {
                    // 解锁
                    lock.unlock();
                }
                // 执行消费逻辑
                for (int i = 0; i < n; i++) {
                    @SuppressWarnings("unchecked") E e = (E) es[i];
                    action.accept(e);
                }
                // 开始下一轮循环
            } while (n > 0 && p != null);
        }

        /**
         * 迭代过程中移除元素
         */
        public void remove() {
            // 读取上一次遍历到的元素，也就是执行 next() 方法时的返回值
            Node<E> n = lastRet;
            if (n == null)
                throw new IllegalStateException();
            // remove 后将 lastRet 置为 null
            lastRet = null;
            final ReentrantLock lock = LinkedBlockingDeque.this.lock;
            lock.lock();
            try {
                if (n.item != null)
                    // 从队列中移除
                    unlink(n);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 正序迭代器
     * 主要重写了 firstNode 方法和 nextNode 方法
     * <p>
     * Forward iterator
     */
    private class Itr extends AbstractItr {
        Itr() {
        }                        // prevent access constructor creation

        Node<E> firstNode() {
            return first;
        }

        Node<E> nextNode(Node<E> n) {
            return n.next;
        }
    }

    /**
     * 逆序迭代器
     * <p>
     * Descending iterator
     */
    private class DescendingItr extends AbstractItr {
        DescendingItr() {
        }              // prevent access constructor creation

        Node<E> firstNode() {
            return last;
        }

        Node<E> nextNode(Node<E> n) {
            return n.prev;
        }
    }

    /**
     * A customized variant of Spliterators.IteratorSpliterator.
     * Keep this class in sync with (very similar) LBQSpliterator.
     */
    private final class LBDSpliterator implements Spliterator<E> {
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        Node<E> current;    // current node; null until initialized
        int batch;          // batch size for splits
        boolean exhausted;  // true when no more nodes
        long est = size();  // size estimate

        LBDSpliterator() {
        }

        public long estimateSize() {
            return est;
        }

        public Spliterator<E> trySplit() {
            Node<E> h;
            if (!exhausted &&
                    ((h = current) != null || (h = first) != null)
                    && h.next != null) {
                int n = batch = Math.min(batch + 1, MAX_BATCH);
                Object[] a = new Object[n];
                final ReentrantLock lock = LinkedBlockingDeque.this.lock;
                int i = 0;
                Node<E> p = current;
                lock.lock();
                try {
                    if (p != null || (p = first) != null)
                        for (; p != null && i < n; p = succ(p))
                            if ((a[i] = p.item) != null)
                                i++;
                } finally {
                    lock.unlock();
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
                final ReentrantLock lock = LinkedBlockingDeque.this.lock;
                lock.lock();
                try {
                    Node<E> p;
                    if ((p = current) != null || (p = first) != null)
                        do {
                            e = p.item;
                            p = succ(p);
                        } while (e == null && p != null);
                    if ((current = p) == null)
                        exhausted = true;
                } finally {
                    lock.unlock();
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
     * Returns a {@link Spliterator} over the elements in this deque.
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#ORDERED}, and {@link Spliterator#NONNULL}.
     *
     * @return a {@code Spliterator} over the elements in this deque
     * @implNote The {@code Spliterator} implements {@code trySplit} to permit limited
     * parallelism.
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return new LBDSpliterator();
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        forEachFrom(action, null);
    }

    /**
     * 遍历执行 action
     * <p>
     * Runs action on each element found during a traversal starting at p.
     * If p is null, traversal starts at head.
     */
    void forEachFrom(Consumer<? super E> action, Node<E> p) {
        // Extract batches of elements while holding the lock; then
        // run the action on the elements while not
        final ReentrantLock lock = this.lock;
        // 一批最多处理 64 个元素
        final int batchSize = 64;       // max number of elements per batch
        Object[] es = null;             // container for batch of elements
        int n, len = 0;
        do {
            lock.lock();
            try {
                if (es == null) {
                    if (p == null) p = first;
                    // 统计 len
                    for (Node<E> q = p; q != null; q = succ(q))
                        if (q.item != null && ++len == batchSize)
                            break;
                    // 初始化 es
                    es = new Object[len];
                }
                // 将 node 保存到 es 中
                for (n = 0; p != null && n < len; p = succ(p))
                    if ((es[n] = p.item) != null)
                        n++;
            } finally {
                lock.unlock();
            }
            // 执行消费
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
     * 批量删除
     * <p>
     * Implementation of bulk remove methods.
     */
    @SuppressWarnings("unchecked")
    private boolean bulkRemove(Predicate<? super E> filter) {
        // 是否移除元素
        boolean removed = false;
        final ReentrantLock lock = this.lock;
        Node<E> p = null;
        Node<E>[] nodes = null;
        int n, len = 0;
        // 一次最多处理 64 个 node
        do {
            // 1. Extract batch of up to 64 elements while holding the lock.
            lock.lock();
            try {
                if (nodes == null) {  // first batch; initialize
                    p = first;
                    for (Node<E> q = p; q != null; q = succ(q))
                        if (q.item != null && ++len == 64)
                            break;
                    // 初始化 nodes 数组，最长的长度为 64
                    nodes = (Node<E>[]) new Node<?>[len];
                }
                // 将节点保存到 nodes 数组中
                for (n = 0; p != null && n < len; p = succ(p))
                    nodes[n++] = p;
            } finally {
                lock.unlock();
            }

            // 2. Run the filter on the elements while lock is free.
            // 无锁的环境下判断是否满足 filter 条件
            // 仅用一个 long 型的变量来处理要删除的 node
            long deathRow = 0L;       // "bitset" of size 64
            // 遍历是否满足条件的 node
            for (int i = 0; i < n; i++) {
                final E e;
                if ((e = nodes[i].item) != null && filter.test(e))
                    deathRow |= 1L << i;
            }

            // 3. Remove any filtered elements while holding the lock.
            // 在持有锁的情况下进行删除
            if (deathRow != 0) {
                lock.lock();
                try {
                    for (int i = 0; i < n; i++) {
                        final Node<E> q;
                        if ((deathRow & (1L << i)) != 0L
                                // 指向的 item != null 说明在 test 的时候没有被其他线程 remove
                                // 可以在这里执行 remove
                                && (q = nodes[i]).item != null) {
                            // 移除该节点
                            unlink(q);
                            removed = true;
                        }
                        nodes[i] = null; // help GC
                    }
                } finally {
                    lock.unlock();
                }
            }
        } while (n > 0 && p != null);
        return removed;
    }

    /**
     * Saves this deque to a stream (that is, serializes it).
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData The capacity (int), followed by elements (each an
     * {@code Object}) in the proper order, followed by a null
     */
    private void writeObject(java.io.ObjectOutputStream s)
            throws java.io.IOException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // Write out capacity and any hidden stuff
            s.defaultWriteObject();
            // Write out all elements in the proper order.
            for (Node<E> p = first; p != null; p = p.next)
                s.writeObject(p.item);
            // Use trailing null as sentinel
            s.writeObject(null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reconstitutes this deque from a stream (that is, deserializes it).
     *
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *                                could not be found
     * @throws java.io.IOException    if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        count = 0;
        first = null;
        last = null;
        // Read in all elements and place in queue
        for (; ; ) {
            @SuppressWarnings("unchecked") E item = (E) s.readObject();
            if (item == null)
                break;
            add(item);
        }
    }

    void checkInvariants() {
        // assert lock.isHeldByCurrentThread();
        // Nodes may get self-linked or lose their item, but only
        // after being unlinked and becoming unreachable from first.
        for (Node<E> p = first; p != null; p = p.next) {
            // assert p.next != p;
            // assert p.item != null;
        }
    }

}
