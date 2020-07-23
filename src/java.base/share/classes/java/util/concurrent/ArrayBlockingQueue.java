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

import java.lang.ref.WeakReference;
import java.util.AbstractQueue;
import java.util.Arrays;
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
 * 有界队列
 * <p>
 * A bounded {@linkplain BlockingQueue blocking queue} backed by an
 * array.  This queue orders elements FIFO (first-in-first-out).  The
 * <em>head</em> of the queue is that element that has been on the
 * queue the longest time.  The <em>tail</em> of the queue is that
 * element that has been on the queue the shortest time. New elements
 * are inserted at the tail of the queue, and the queue retrieval
 * operations obtain elements at the head of the queue.
 * <p>
 * 创建之后 Queue 的容量不能被改变
 * put/take 操作会阻塞
 *
 * <p>This is a classic &quot;bounded buffer&quot;, in which a
 * fixed-sized array holds elements inserted by producers and
 * extracted by consumers.  Once created, the capacity cannot be
 * changed.  Attempts to {@code put} an element into a full queue
 * will result in the operation blocking; attempts to {@code take} an
 * element from an empty queue will similarly block.
 * <p>
 * 该队列支持可选的公平策略。对于不同的生产者消费者线程在生产或者消费时
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order. Fairness
 * generally decreases throughput but reduces variability and avoids
 * starvation.
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
public class ArrayBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {

    /*
     * Much of the implementation mechanics, especially the unusual
     * nested loops, are shared and co-maintained with ArrayDeque.
     */

    /**
     * Serialization ID. This class relies on default serialization
     * even for the items array, which is default-serialized, even if
     * it is empty. Otherwise it could not be declared final, which is
     * necessary here.
     */
    private static final long serialVersionUID = -817911632652898426L;

    /**
     * 存放元素的数组
     * <p>
     * The queued items
     */
    @SuppressWarnings("serial") // Conditionally serializable
    final Object[] items;

    /**
     * 指向当前要取出的位置的索引
     * <p>
     * items index for next take, poll, peek or remove
     */
    int takeIndex;

    /**
     * 指向当前要放入的位置索引
     * <p>
     * items index for next put, offer, or add
     */
    int putIndex;

    /**
     * 元素的个数
     * <p>
     * Number of elements in the queue
     */
    int count;

    /*
     * Concurrency control uses the classic two-condition algorithm
     * found in any textbook.
     */

    /**
     * 锁
     * <p>
     * Main lock guarding all access
     */
    final ReentrantLock lock;

    /**
     * 消费的时候使用的等待队列
     * 非空条件等待队列
     * <p>
     * Condition for waiting takes
     */
    @SuppressWarnings("serial")  // Classes implementing Condition may be serializable.
    private final Condition notEmpty;

    /**
     * 生产的时候使用的等待队列
     * 非满条件等待队列
     * <p>
     * Condition for waiting puts
     */
    @SuppressWarnings("serial")  // Classes implementing Condition may be serializable.
    private final Condition notFull;

    /**
     * Shared state for currently active iterators, or null if there
     * are known not to be any.  Allows queue operations to update
     * iterator state.
     */
    transient Itrs itrs;

    // Internal helper methods

    /**
     * 模加
     * <p>
     * Increments i, mod modulus.
     * Precondition and postcondition: 0 <= i < modulus.
     */
    static final int inc(int i, int modulus) {
        if (++i >= modulus) i = 0;
        return i;
    }

    /**
     * 模减
     * <p>
     * Decrements i, mod modulus.
     * Precondition and postcondition: 0 <= i < modulus.
     */
    static final int dec(int i, int modulus) {
        if (--i < 0) i = modulus - 1;
        return i;
    }

    /**
     * 返回在 i 位置的元素
     * 底层存储使用数组
     * <p>
     * Returns item at index i.
     */
    @SuppressWarnings("unchecked")
    final E itemAt(int i) {
        return (E) items[i];
    }

    /**
     * 不怎么推荐使用
     * <p>
     * Returns element at array index i.
     * This is a slight abuse of generics, accepted by javac.
     */
    @SuppressWarnings("unchecked")
    static <E> E itemAt(Object[] items, int i) {
        return (E) items[i];
    }

    /**
     * 私有方法
     * 入队操作，已检查队列非满，直接入队
     * <p>
     * Inserts element at current put position, advances, and signals.
     * Call only when holding lock.
     */
    private void enqueue(E e) {
        // assert lock.isHeldByCurrentThread();
        // assert lock.getHoldCount() == 1;
        // assert items[putIndex] == null;
        final Object[] items = this.items;
        // 放入到 putIndex 位置
        items[putIndex] = e;
        // 更新 putIndex
        if (++putIndex == items.length) putIndex = 0;
        // 更新计数
        count++;
        // 非空通知
        notEmpty.signal();
    }

    /**
     * 私有方法
     * 出队操作，已检查队列非空，直接出队
     * <p>
     * Extracts element at current take position, advances, and signals.
     * Call only when holding lock.
     */
    private E dequeue() {
        // assert lock.isHeldByCurrentThread();
        // assert lock.getHoldCount() == 1;
        // assert items[takeIndex] != null;
        final Object[] items = this.items;
        @SuppressWarnings("unchecked")
        // 直接取 takeIndex 位置的元素
                E e = (E) items[takeIndex];
        // 将取出后的位置置 null
        items[takeIndex] = null;
        // 更新 takeIndex
        if (++takeIndex == items.length) takeIndex = 0;
        // 计数-1
        count--;
        // 维护迭代器
        // TODO 具体后续分析
        if (itrs != null)
            itrs.elementDequeued();
        // 非满通知
        notFull.signal();
        return e;
    }

    /**
     * 移除指定 index 的元素
     * <p>
     * Deletes item at array index removeIndex.
     * Utility for remove(Object) and iterator.remove.
     * Call only when holding lock.
     */
    void removeAt(final int removeIndex) {
        // assert lock.isHeldByCurrentThread();
        // assert lock.getHoldCount() == 1;
        // assert items[removeIndex] != null;
        // assert removeIndex >= 0 && removeIndex < items.length;
        final Object[] items = this.items;
        // 如果移除元素是队头元素
        if (removeIndex == takeIndex) {
            // removing front item; just advance
            items[takeIndex] = null;
            // 更新 takeIndex
            if (++takeIndex == items.length) takeIndex = 0;
            // 更新队列总的元素个数
            count--;
            // 如果迭代器链表不为 null
            if (itrs != null)
                itrs.elementDequeued();
            // 不是队头元素
        } else {
            // an "interior" remove

            // slide over all others up through putIndex.
            for (int i = removeIndex, putIndex = this.putIndex; ; ) {
                int pred = i;
                // 如果移除元素在数组的最后一位
                if (++i == items.length) i = 0;
                // 如果遍历到了队尾元素
                if (i == putIndex) {
                    // 队尾元素为 null
                    items[pred] = null;
                    // 更新 putIndex
                    this.putIndex = pred;
                    // 结束循环
                    break;
                }
                // 前移
                items[pred] = items[i];
            }
            // 计数 - 1
            count--;
            // 如果迭代器链表不为空
            if (itrs != null)
                // 通知迭代器
                itrs.removedAt(removeIndex);
        }
        // 移除元素之后通知一下非满
        notFull.signal();
    }

    /**
     * 默认采用非公平策略
     * 公平策略应用于可重入锁中上
     * <p>
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity and default access policy.
     *
     * @param capacity the capacity of this queue
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public ArrayBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity and the specified access policy.
     *
     * @param capacity the capacity of this queue
     * @param fair     if {@code true} then queue accesses for threads blocked
     *                 on insertion or removal, are processed in FIFO order;
     *                 if {@code false} the access order is unspecified.
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public ArrayBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0)
            throw new IllegalArgumentException();
        this.items = new Object[capacity];
        lock = new ReentrantLock(fair);
        notEmpty = lock.newCondition();
        notFull = lock.newCondition();
    }

    /**
     * Creates an {@code ArrayBlockingQueue} with the given (fixed)
     * capacity, the specified access policy and initially containing the
     * elements of the given collection,
     * added in traversal order of the collection's iterator.
     *
     * @param capacity the capacity of this queue
     * @param fair     if {@code true} then queue accesses for threads blocked
     *                 on insertion or removal, are processed in FIFO order;
     *                 if {@code false} the access order is unspecified.
     * @param c        the collection of elements to initially contain
     * @throws IllegalArgumentException if {@code capacity} is less than
     *                                  {@code c.size()}, or less than 1.
     * @throws NullPointerException     if the specified collection or any
     *                                  of its elements are null
     */
    public ArrayBlockingQueue(int capacity, boolean fair,
                              Collection<? extends E> c) {
        this(capacity, fair);

        final ReentrantLock lock = this.lock;
        lock.lock(); // Lock only for visibility, not mutual exclusion
        try {
            final Object[] items = this.items;
            int i = 0;
            try {
                for (E e : c)
                    items[i++] = Objects.requireNonNull(e);
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw new IllegalArgumentException();
            }
            count = i;
            putIndex = (i == capacity) ? 0 : i;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately without exceeding the queue's capacity,
     * returning {@code true} upon success and throwing an
     * {@code IllegalStateException} if this queue is full.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws IllegalStateException if this queue is full
     * @throws NullPointerException  if the specified element is null
     */
    public boolean add(E e) {
        return super.add(e);
    }

    /**
     * 将元素加入到队尾
     * <p>
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately without exceeding the queue's capacity,
     * returning {@code true} upon success and {@code false} if this queue
     * is full.  This method is generally preferable to method {@link #add},
     * which can fail to insert an element only by throwing an exception.
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 队满返回 false
            if (count == items.length)
                return false;
            else {
                // 入队
                enqueue(e);
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将元素加入到队尾
     * <p>
     * Inserts the specified element at the tail of this queue, waiting
     * for space to become available if the queue is full.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            // 队满阻塞等待
            while (count == items.length)
                notFull.await();
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 待等待时间的入队，超时则返回 false
     * <p>
     * Inserts the specified element at the tail of this queue, waiting
     * up to the specified wait time for space to become available if
     * the queue is full.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {

        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (nanos <= 0L)
                    return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回队头元素，队列为空则返回 null
     *
     * @return
     */
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return (count == 0) ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取队头元素，若队头为 kong，则阻塞等待
     *
     * @return
     * @throws InterruptedException
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0)
                notEmpty.await();
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 带等待时间的取队头元素
     * 时间到了还没取到就返回 null
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (nanos <= 0L)
                    return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取队头元素，不出队
     *
     * @return
     */
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return itemAt(takeIndex); // null when queue is empty
        } finally {
            lock.unlock();
        }
    }

    // this doc comment is overridden to remove the reference to collections
    // greater in size than Integer.MAX_VALUE

    /**
     * 返回队列的元素个数
     * <p>
     * Returns the number of elements in this queue.
     *
     * @return the number of elements in this queue
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

    // this doc comment is a modified copy of the inherited doc comment,
    // without the reference to unlimited queues.

    /**
     * 返回存储元素的数组剩余的空间
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return items.length - count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除指定对象元素，只移除一个
     * <p>
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * <p>Removal of interior elements in circular array based queues
     * is an intrinsically slow and disruptive operation, so should
     * be undertaken only in exceptional circumstances, ideally
     * only when the queue is known not to be accessible by other
     * threads.
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        if (o == null) return false;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final Object[] items = this.items;
                // 有两种情况
                // takeIndex < putIndex : 队头在队尾的左边，常规队列
                // takeIndex > putIndex : 队头在队尾的右边，循环队列
                // 外层循环最多只会两次，第二次将 i 更新为 0，to 更新为 end
                for (int i = takeIndex, end = putIndex,
                     to = (i < end) ? end : items.length;
                        ; i = 0, to = end) {
                    // 先遍历到 to
                    for (; i < to; i++)
                        if (o.equals(items[i])) {
                            removeAt(i);
                            return true;
                        }
                    if (to == end) break;
                }
            }
            // 找不到，返回 false
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查找某个元素，与 remove object 是类似的
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final Object[] items = this.items;
                for (int i = takeIndex, end = putIndex,
                     to = (i < end) ? end : items.length;
                        ; i = 0, to = end) {
                    for (; i < to; i++)
                        if (o.equals(items[i]))
                            return true;
                    if (to == end) break;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 转为对象数组
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] items = this.items;
            final int end = takeIndex + count;
            // 数组拷贝操作
            // 主要处理循环数组问题
            final Object[] a = Arrays.copyOfRange(items, takeIndex, end);
            if (end != putIndex)
                System.arraycopy(items, 0, a, items.length - takeIndex, putIndex);
            return a;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 转为对象数组
     * 指定返回类型
     * <p>
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] items = this.items;
            final int count = this.count;
            // 如果是常规数组，那么 items.length - takeIndex > count
            // 如果是循环数组，那么 items.length - takeIndex < count
            final int firstLeg = Math.min(items.length - takeIndex, count);
            // 传入的数组长度不够
            if (a.length < count) {
                // 新建一个长度为 count 的数组
                // 并进行元素拷贝
                a = (T[]) Arrays.copyOfRange(items, takeIndex, takeIndex + count,
                        a.getClass());
                // 传入的数组长度够了，直接拷贝
            } else {
                System.arraycopy(items, takeIndex, a, 0, firstLeg);
                if (a.length > count)
                    a[count] = null;
            }
            // 循环数组还需要额外处理
            if (firstLeg < count)
                System.arraycopy(items, 0, a, firstLeg, putIndex);
            return a;
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        return Helpers.collectionToString(this);
    }

    /**
     * 移除队列中的所有元素
     * <p>
     * Atomically removes all of the elements from this queue.
     * The queue will be empty after this call returns.
     */
    public void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k;
            if ((k = count) > 0) {
                // 一次性全部清理
                circularClear(items, takeIndex, putIndex);
                takeIndex = putIndex;
                count = 0;
                // 清理迭代器
                if (itrs != null)
                    // 通知迭代器链表进行清理
                    itrs.queueIsEmpty();
                // 通知等待的生产者线程
                for (; k > 0 && lock.hasWaiters(notFull); k--)
                    notFull.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除 i 到 end 或者 i 到 items 数组末尾的元素
     * 如果是 end < items.length，那么还会清理从 0 到 end 的数据
     * <p>
     * Nulls out slots starting at array index i, upto index end.
     * Condition i == end means "full" - the entire array is cleared.
     */
    private static void circularClear(Object[] items, int i, int end) {
        // assert 0 <= i && i < items.length;
        // assert 0 <= end && end < items.length;
        for (int to = (i < end) ? end : items.length;
                ; i = 0, to = end) {
            for (; i < to; i++) items[i] = null;
            if (to == end) break;
        }
    }

    /**
     * 移除队列中的所有元素，并将元素放入到传入的 Collection 中
     * 需要注意的一点，需要处理 Iterators
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * 具体实现
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
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 队列中实际的元素数量
            int n = Math.min(maxElements, count);
            // 队首元素位置
            int take = takeIndex;
            int i = 0;
            try {
                // 移动
                while (i < n) {
                    @SuppressWarnings("unchecked")
                    E e = (E) items[take];
                    c.add(e);
                    items[take] = null;
                    // 最后 take 的值应该等于 putIndex
                    if (++take == items.length) take = 0;
                    i++;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                // i > 0 时表示已经有一部分元素从 Queue 移到了 Collection 中
                if (i > 0) {
                    // count 还剩多少元素没有移动
                    count -= i;
                    // 新的 takeIndex
                    takeIndex = take;
                    if (itrs != null) {
                        // 正常处理完了
                        if (count == 0)
                            // 通知迭代器链表处理
                            itrs.queueIsEmpty();
                            // 前面 Collection add 的时候抛异常了
                            // 没有正常处理完
                            // i > take 表示是一个循环数组，且循环数组已经遍历到了 0
                        else if (i > take)
                            // 遍历到了 0 ，因此需要调用一下方法
                            itrs.takeIndexWrapped();
                    }
                    // 通知 waiters
                    for (; i > 0 && lock.hasWaiters(notFull); i--)
                        notFull.signal();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回一个迭代器对象 Itr，该类是 ArrayBlockingQueue 内部私有的实现类
     * 自己设计了一套迭代器方法
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
     * Shared data between iterators and their queue, allowing queue
     * modifications to update iterators when elements are removed.
     * <p>
     * This adds a lot of complexity for the sake of correctly
     * handling some uncommon operations, but the combination of
     * circular-arrays and supporting interior removes (i.e., those
     * not at head) would cause iterators to sometimes lose their
     * places and/or (re)report elements they shouldn't.  To avoid
     * this, when a queue has one or more iterators, it keeps iterator
     * state consistent by:
     * <p>
     * (1) keeping track of the number of "cycles", that is, the
     * number of times takeIndex has wrapped around to 0.
     * (2) notifying all iterators via the callback removedAt whenever
     * an interior element is removed (and thus other elements may
     * be shifted).
     * <p>
     * These suffice to eliminate iterator inconsistencies, but
     * unfortunately add the secondary responsibility of maintaining
     * the list of iterators.  We track all active iterators in a
     * simple linked list (accessed only when the queue's lock is
     * held) of weak references to Itr.  The list is cleaned up using
     * 3 different mechanisms:
     * <p>
     * (1) Whenever a new iterator is created, do some O(1) checking for
     * stale list elements.
     * <p>
     * (2) Whenever takeIndex wraps around to 0, check for iterators
     * that have been unused for more than one wrap-around cycle.
     * <p>
     * (3) Whenever the queue becomes empty, all iterators are notified
     * and this entire data structure is discarded.
     * <p>
     * So in addition to the removedAt callback that is necessary for
     * correctness, iterators have the shutdown and takeIndexWrapped
     * callbacks that help remove stale iterators from the list.
     * <p>
     * Whenever a list element is examined, it is expunged if either
     * the GC has determined that the iterator is discarded, or if the
     * iterator reports that it is "detached" (does not need any
     * further state updates).  Overhead is maximal when takeIndex
     * never advances, iterators are discarded before they are
     * exhausted, and all removals are interior removes, in which case
     * all stale iterators are discovered by the GC.  But even in this
     * case we don't increase the amortized complexity.
     * <p>
     * Care must be taken to keep list sweeping methods from
     * reentrantly invoking another such method, causing subtle
     * corruption bugs.
     */
    /**
     * 内部实现的迭代器类
     * 维护着一个 Itr 组成的链表，保证多个迭代器共享队列的元素，保证迭代器中元素数据的一致性
     */
    class Itrs {

        /**
         * 迭代器内部链表节点 Node
         * 继承自 WeakReference
         * <p>
         * Node in a linked list of weak iterator references.
         */
        private class Node extends WeakReference<Itr> {
            // 指向下一个 Node
            Node next;

            // 构造方法
            Node(Itr iterator, Node next) {
                super(iterator);
                this.next = next;
            }
        }

        /**
         * 所有迭代器一共迭代的次数
         * <p>
         * Incremented whenever takeIndex wraps around to 0
         */
        int cycles;

        /**
         * 链表的头结点
         * <p>
         * Linked list of weak iterator references
         */
        private Node head;

        /**
         * 上次探测到的节点
         * <p>
         * Used to expunge stale iterators
         */
        private Node sweeper;

        /**
         * 探测范围
         */
        private static final int SHORT_SWEEP_PROBES = 4;
        /**
         * 探测范围
         */
        private static final int LONG_SWEEP_PROBES = 16;

        /**
         * 构造方法
         * 并将 Itr 注册到该 Itrs 中
         *
         * @param initial
         */
        Itrs(Itr initial) {
            register(initial);
        }

        /**
         * 清理 Itrs 链中失效的 iterator
         * 如何判断 iterator 失效。1.iterator 为 null 2. iterator 已经 detached
         * 如果至少找到一个失效的 iterator，则扩大探测的范围
         * 遇到一次，则继续乡下探测 16 个位置
         * <p>
         * Sweeps itrs, looking for and expunging stale iterators.
         * If at least one was found, tries harder to find more.
         * Called only from iterating thread.
         *
         * @param tryHarder whether to start in try-harder mode, because
         *                  there is known to be at least one iterator to collect
         */
        void doSomeSweeping(boolean tryHarder) {
            // assert lock.isHeldByCurrentThread();
            // assert head != null;
            // 根据传入参数来确定探测的范围
            int probes = tryHarder ? LONG_SWEEP_PROBES : SHORT_SWEEP_PROBES;
            // o: p 的前一个节点
            // p: 当前遍历节点
            Node o, p;
            // 上次探测的结束节点
            final Node sweeper = this.sweeper;
            boolean passedGo;   // to limit search to one full sweep
            // 如果首次探测，则从头开始
            // 将 passedGo 设为 true
            if (sweeper == null) {
                o = null;
                p = head;
                passedGo = true;
                // 如果之前有过探测，则将 passedGo 设为 false
            } else {
                // o 指向上一个
                o = sweeper;
                // p 指向开始探测的节点
                p = o.next;
                passedGo = false;
            }
            // 开始探测
            for (; probes > 0; probes--) {
                // 遍历到尾节点了
                if (p == null) {
                    // 如果是从头结点开始遍历的，说明从头到尾都遍历一次了
                    if (passedGo)
                        // 直接 break
                        break;
                    // 还需要继续遍历，但是还得看外面 probes 的次数，由外面判断
                    // 回到头结点
                    o = null;
                    p = head;
                    // 标记从头开始遍历
                    passedGo = true;
                }
                // 获取到当前节点的迭代器
                final Itr it = p.get();
                // itrs 链表的后一个节点
                final Node next = p.next;
                // 如果迭代器已经被置为 null
                // 迭代器已经 Detached
                // 说明该节点需要清理了
                if (it == null || it.isDetached()) {
                    // found a discarded/exhausted iterator
                    // 扩大探测范围
                    probes = LONG_SWEEP_PROBES; // "try harder"
                    // 将 p 从链表中移除
                    // unlink p
                    p.clear();
                    p.next = null;
                    // 处理链表前置连接关系
                    if (o == null) {
                        head = next;
                        // 链表中已经没有节点了
                        if (next == null) {
                            // We've run out of iterators to track; retire
                            itrs = null;
                            return;
                        }
                    } else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }

            this.sweeper = (p == null) ? null : o;
        }

        /**
         * 新创建 Itr 的时候会封装成 Node 加入到链表中
         * 头插法
         * <p>
         * Adds a new iterator to the linked list of tracked iterators.
         */
        void register(Itr itr) {
            // assert lock.isHeldByCurrentThread();
            head = new Node(itr, head);
        }

        /**
         * 遍历到 takeIndex = 0 时会调用该方法
         * 清理所有的迭代器
         * <p>
         * Called whenever takeIndex wraps around to 0.
         * <p>
         * Notifies all iterators, and expunges any that are now stale.
         */
        void takeIndexWrapped() {
            // assert lock.isHeldByCurrentThread();
            // itrs 迭代次数 ++
            cycles++;
            // 从头开始遍历所有的 itr
            for (Node o = null, p = head; p != null; ) {
                // 当前 Itr
                final Itr it = p.get();
                // next Node
                final Node next = p.next;
                // 如果当前 it == null 或者 it 已经 detached
                // 从链表中删除该节点
                if (it == null || it.takeIndexWrapped()) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }
            if (head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * 当某一个 iterator 发生 removedAt 的时候，会通知所有的 iterators 执行 removedAt
         * 以保证数据的一致性
         * <p>
         * Called whenever an interior remove (not at takeIndex) occurred.
         * <p>
         * Notifies all iterators, and expunges any that are now stale.
         */
        void removedAt(int removedIndex) {
            // 遍历所有的迭代器
            // o 保存当前节点的前驱节点
            for (Node o = null, p = head; p != null; ) {
                // 获取迭代器
                final Itr it = p.get();
                // 下一个封装了迭代器的节点
                final Node next = p.next;
                // 迭代器不为 null || 迭代器进行更新，删除元素
                // TODO 为什么要将节点从链表中删除？？？
                if (it == null || it.removedAt(removedIndex)) {
                    // 从链表中移除元素逻辑
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                // 往后走
                p = next;
            }
            // 如果迭代器链表为 null
            if (head == null)   // no more iterators to track
                // 将 itrs 置 null
                itrs = null;
        }

        /**
         * 队列中无元素了会调用
         * <p>
         * Called whenever the queue becomes empty.
         * <p>
         * Notifies all active iterators that the queue is empty,
         * clears all weak refs, and unlinks the itrs datastructure.
         */
        void queueIsEmpty() {
            // assert lock.isHeldByCurrentThread();
            // 遍历链表
            for (Node p = head; p != null; p = p.next) {
                // 取出来迭代器
                Itr it = p.get();
                // 清理迭代器
                if (it != null) {
                    p.clear();
                    it.shutdown();
                }
            }
            // 链表处理
            head = null;
            itrs = null;
        }

        /**
         * 元素出队的时候会调用
         * <p>
         * Called whenever an element has been dequeued (at takeIndex).
         */
        void elementDequeued() {
            // assert lock.isHeldByCurrentThread();
            // 如果队列已经为空了
            if (count == 0)
                queueIsEmpty();
                // 队列头在数组的第一个位置
            else if (takeIndex == 0)
                takeIndexWrapped();
        }
    }

    /**
     * 内部私有类
     * 专为 ArrayBlockingQueue 定制的迭代器
     * 迭代的顺序为从队头到队尾进行迭代，这样更能保证一致性，
     * 因为迭代的过程中可能会有元素进队
     * <p>
     * Iterator for ArrayBlockingQueue.
     * <p>
     * 维护 put 和 take 操作时的弱一致性
     * To maintain weak consistency with respect to puts and takes, we
     * read ahead one slot, so as to not report hasNext true but then
     * not have an element to return.
     * <p>
     * We switch into "detached" mode (allowing prompt unlinking from
     * itrs without help from the GC) when all indices are negative, or
     * when hasNext returns false for the first time.  This allows the
     * iterator to track concurrent updates completely accurately,
     * except for the corner case of the user calling Iterator.remove()
     * after hasNext() returned false.  Even in this case, we ensure
     * that we don't remove the wrong element by keeping track of the
     * expected element to remove, in lastItem.  Yes, we may fail to
     * remove lastItem from the queue if it moved due to an interleaved
     * interior remove while in detached mode.
     * <p>
     * Method forEachRemaining, added in Java 8, is treated similarly
     * to hasNext returning false, in that we switch to detached mode,
     * but we regard it as an even stronger request to "close" this
     * iteration, and don't bother supporting subsequent remove().
     */
    private class Itr implements Iterator<E> {
        /**
         * 下标索引，查找下一个新的元素位置。
         * nextIndex 的下一个位置
         * <p>
         * Index to look for new nextItem; NONE at end
         */
        private int cursor;

        /**
         * 调用 next() 方法返回的元素
         * <p>
         * Element to be returned by next call to next(); null if none
         */
        private E nextItem;

        /**
         * nextItem 的下标索引
         * <p>
         * Index of nextItem; NONE if none, REMOVED if removed elsewhere
         */
        private int nextIndex;

        /**
         * 最后一次返回的元素
         * <p>
         * Last element returned; null if none or not detached.
         */
        private E lastItem;

        /**
         * lastItem 对应的元素的下标索引
         * <p>
         * Index of lastItem, NONE if none, REMOVED if removed elsewhere
         */
        private int lastRet;

        /**
         * 迭代器是否 detached 通过这个变量可以查看
         * 迭代开始位置
         * <p>
         * Previous value of takeIndex, or DETACHED when detached
         */
        private int prevTakeIndex;

        /**
         * 循环的次数
         * <p>
         * Previous value of iters.cycles
         */
        private int prevCycles;

        /**
         * 特殊的索引值表示数据不可用
         * cursor、nextIndex、lastRet 可能会更新为 NONE
         * <p>
         * Special index value indicating "not available" or "undefined"
         */
        private static final int NONE = -1;

        /**
         * 特殊的值，意味着被在其他地方呗 remove 了，不是调用本迭代器的 remove 方法
         * 标识 lastRet、nextIndex 数据过时
         * <p>
         * Special index value indicating "removed elsewhere", that is,
         * removed by some operation other than a call to this.remove().
         */
        private static final int REMOVED = -2;

        /**
         * detached 模式
         * preTakeIndex 变量会更新为这个值，用于标识迭代器是否处于 detached 状态
         * <p>
         * Special value for prevTakeIndex indicating "detached mode"
         */
        private static final int DETACHED = -3;

        /**
         * 构造方法
         * 会初始化 cursor nextIndex nextItem prevTakeIndex
         */
        Itr() {
            // 因为刚初始化，因此不存在上一次迭代的元素的下标索引
            lastRet = NONE;
            // 锁，该迭代器所在的 ArrayBlockingQueue 实例持有的锁对象
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            // 加锁
            lock.lock();
            try {
                // 如果队列中没有元素（不是重点）
                if (count == 0) {
                    // assert itrs == null;
                    cursor = NONE;
                    nextIndex = NONE;
                    prevTakeIndex = DETACHED;
                    // 队列中有元素
                } else {
                    // 获取 ArrayBlockingQueue 实例的 takeIndex 值，也就是当前队头元素的下标索引
                    final int takeIndex = ArrayBlockingQueue.this.takeIndex;
                    // prevTakeIndex 记录此次迭代开始的队头下标索引
                    prevTakeIndex = takeIndex;
                    // 更新 nextIndex 以及 nextItem
                    // 开始遍历的下标索引以及元素
                    nextItem = itemAt(nextIndex = takeIndex);
                    // 计算 下一个迭代的元素的位置
                    cursor = incCursor(takeIndex);
                    // 初始化 itrs 并将当前迭代器注册进去
                    if (itrs == null) {
                        itrs = new Itrs(this);
                    } else {
                        itrs.register(this); // in this order
                        itrs.doSomeSweeping(false);
                    }
                    // 更新当前迭代器的迭代次数
                    prevCycles = itrs.cycles;
                    // assert takeIndex >= 0;
                    // assert prevTakeIndex == takeIndex;
                    // assert nextIndex >= 0;
                    // assert nextItem != null;
                }
            } finally {
                // 释放锁
                lock.unlock();
            }
        }

        /**
         * 返回 迭代器 是否已经 Detached
         *
         * @return
         */
        boolean isDetached() {
            // assert lock.isHeldByCurrentThread();
            // prevTakeIndex 迭代开始的位置
            return prevTakeIndex < 0;
        }

        /**
         * Cursor 自增，返回下一个 cursor
         *
         * @param index
         * @return
         */
        private int incCursor(int index) {
            // assert lock.isHeldByCurrentThread();
            if (++index == items.length) index = 0;
            // cursor 移动到 putIndex，说明迭代到了结尾
            if (index == putIndex) index = NONE;
            return index;
        }

        /**
         * 检查传入的 index 是否已经过时，如果已经过时返回 true
         * <p>
         * Returns true if index is invalidated by the given number of
         * dequeues, starting from prevTakeIndex.
         */
        private boolean invalidated(int index, int prevTakeIndex,
                                    long dequeues, int length) {
            // 下标索引小于 0 ，表示不需要修改，有三种情况小于 0.
            // NONE REMOVED DETACHED
            if (index < 0)
                return false;
            // 计算传入的 index 与 prevTakeIndex 的距离，如果小于 0
            // 说明 prevTakeIndex 在右边， index 在左边，队列的中间一段是空的
            int distance = index - prevTakeIndex;
            // 如果小于 0
            if (distance < 0)
                // 需要加上 length
                distance += length;
            // dequeues = (long) (cycles - prevCycles) * len + (takeIndex - prevTakeIndex)
            // distance = index - prevTakeIndex
            // 如果 dequeues > distance，说明这些索引已经过时，返回 true
            return dequeues > distance;
        }

        /**
         * 调整相关的索引的值（出队引起的）
         * 保证相关索引的时效性
         * <p>
         * Adjusts indices to incorporate all dequeues since the last
         * operation on this iterator.  Call only from iterating thread.
         */
        private void incorporateDequeues() {
            // assert lock.isHeldByCurrentThread();
            // assert itrs != null;
            // assert !isDetached();
            // assert count > 0;

            // itrs 中保存的最新的 cycles 的值
            final int cycles = itrs.cycles;
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            // 当前迭代器中持有的 数据
            // 用于判断数据是否过时
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;

            // 需要调整的情况
            // 当前迭代器维护的数据与 itrs 维护的最新的数据不相等
            if (cycles != prevCycles || takeIndex != prevTakeIndex) {
                // 队列中元素的个数
                final int len = items.length;
                // how far takeIndex has advanced since the previous
                // operation of this iterator
                // takeIndex 与 prevTakeIndex 的距离
                // 可以得到已经出队的元素的个数
                long dequeues = (long) (cycles - prevCycles) * len
                        + (takeIndex - prevTakeIndex);

                // Check indices for invalidation
                // 检查 lastRet 、 nextIndex 、 cursor 是否已经过时
                if (invalidated(lastRet, prevTakeIndex, dequeues, len))
                    lastRet = REMOVED;
                if (invalidated(nextIndex, prevTakeIndex, dequeues, len))
                    nextIndex = REMOVED;
                if (invalidated(cursor, prevTakeIndex, dequeues, len))
                    cursor = takeIndex;
                // 如果迭代器遍历结束
                if (cursor < 0 && nextIndex < 0 && lastRet < 0)
                    // 则迭代器需要 detach
                    detach();
                    // 迭代器没有失效
                else {
                    // 更新 prevCycles 与 prevTakeIndex
                    this.prevCycles = cycles;
                    this.prevTakeIndex = takeIndex;
                }
            }
        }

        /**
         * Called when itrs should stop tracking this iterator, either
         * because there are no more indices to update (cursor < 0 &&
         * nextIndex < 0 && lastRet < 0) or as a special exception, when
         * lastRet >= 0, because hasNext() is about to return false for the
         * first time.  Call only from iterating thread.
         */
        private void detach() {
            // Switch to detached mode
            // assert lock.isHeldByCurrentThread();
            // assert cursor == NONE;
            // assert nextIndex < 0;
            // assert lastRet < 0 || nextItem == null;
            // assert lastRet < 0 ^ lastItem != null;
            // 如果 prevTakeIndex 还 >= 0，也就是处于有效的数值
            // 需要将其置为无效的值 DETACHED
            if (prevTakeIndex >= 0) {
                // assert itrs != null;
                // 将 prevTakeIndex 置为 DETACHED 即可
                prevTakeIndex = DETACHED;
                // try to unlink from itrs (but not too hard)
                // itr detached 之后需要清理 itrs
                itrs.doSomeSweeping(true);
            }
        }

        /**
         * 返回迭代是否结束
         * <p>
         * For performance reasons, we would like not to acquire a lock in
         * hasNext in the common case.  To allow for this, we only access
         * fields (i.e. nextItem) that are not modified by update operations
         * triggered by queue modifications.
         */
        public boolean hasNext() {
            if (nextItem != null)
                return true;
            // 如果没有下一个，则调用 noNext
            noNext();
            return false;
        }

        /**
         * 能够调用该方法说明已经遍历完了
         */
        private void noNext() {
            // 获取锁对象
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            // 加锁
            lock.lock();
            try {
                // assert cursor == NONE;
                // assert nextIndex == NONE;
                // 如果迭代器还没有标记为 detached ，则要对迭代器进行处理
                // 将其标记为 detached
                if (!isDetached()) {
                    // assert lastRet >= 0;
                    incorporateDequeues(); // might update lastRet
                    if (lastRet >= 0) {
                        // 记录最后一个遍历的元素
                        // 这里为什么需要记录?
                        // 因为在遍历结束之后，可能需要删除最后一个元素。但是迭代器已经 detached 了，但是还需要删除
                        // 所以就通过保存的 lastItem 来进行删除
                        lastItem = itemAt(lastRet);
                        // assert lastItem != null;
                        detach();
                    }
                }
                // assert isDetached();
                // assert lastRet < 0 ^ lastItem != null;
            } finally {
                lock.unlock();
            }
        }

        /**
         * next 方法，会维护 cursor nextIndex 和 nextItem 这三个变量
         *
         * @return
         */
        public E next() {
            // 保存 nextItem 的值，也就是当前调用的返回值
            final E e = nextItem;
            if (e == null)
                throw new NoSuchElementException();
            // 获取锁对象
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            // 加锁
            lock.lock();
            try {
                // 如果迭代器没有 detached
                if (!isDetached())
                    // 调整相关索引，保证相关的索引不是过时的
                    incorporateDequeues();
                // assert nextIndex != NONE;
                // assert lastItem == null;
                // 更新 lastRet，指向前一个 item 的下标索引
                lastRet = nextIndex;
                final int cursor = this.cursor;
                // 如果 cursor 处于正常状态
                // 一般会走这个流程
                if (cursor >= 0) {
                    // 更新 nextItem
                    nextItem = itemAt(nextIndex = cursor);
                    // assert nextItem != null;
                    // 更新 cursor
                    this.cursor = incCursor(cursor);
                    // cursor < 0 表示遍历完成
                } else {
                    // 更新 nextIndex 为 NONE
                    nextIndex = NONE;
                    // nextItem 指向 null
                    nextItem = null;
                    // 如果有发生 REMOVED，则将迭代器 detached
                    if (lastRet == REMOVED) detach();
                }x
            } finally {
                lock.unlock();
            }
            return e;
        }

        /**
         * 对队列中所有的元素执行 Consumer 中定义的 action
         *
         * @param action
         */
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                final E e = nextItem;
                if (e == null) return;
                // 如果迭代器没有 detached
                if (!isDetached())
                    // 更新相关索引
                    incorporateDequeues();
                // 执行 action
                action.accept(e);
                // 再次检查 detached
                if (isDetached() || cursor < 0) return;
                // 开始遍历 accept 剩余的 Element
                final Object[] items = ArrayBlockingQueue.this.items;
                for (int i = cursor, end = putIndex,
                     to = (i < end) ? end : items.length;
                        ; i = 0, to = end) {
                    for (; i < to; i++)
                        action.accept(itemAt(items, i));
                    if (to == end) break;
                }
            } finally {
                // Calling forEachRemaining is a strong hint that this
                // iteration is surely over; supporting remove() after
                // forEachRemaining() is more trouble than it's worth
                // 操作完之后将 Itr 置为 detached，同时更新相关变量
                cursor = nextIndex = lastRet = NONE;
                nextItem = lastItem = null;
                detach();
                lock.unlock();
            }
        }

        /**
         * public 方法，给外部调用，用于移除元素
         * 删除迭代器 lastRet 指向的元素
         */
        public void remove() {
            // 获取锁对象
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            // 加锁
            lock.lock();
            // assert lock.getHoldCount() == 1;
            try {
                // 迭代器没有 detached
                if (!isDetached())
                    // 调整索引
                    incorporateDequeues(); // might update lastRet or detach
                // 获取 lastRet
                final int lastRet = this.lastRet;
                // 将其置为 NONE，表示要 remove
                this.lastRet = NONE;
                // lastRet 是一个有效的索引
                if (lastRet >= 0) {
                    // 如果迭代器未 detach
                    if (!isDetached())
                        // 移除元素
                        removeAt(lastRet);
                        // 迭代器已经 detached
                        // 在 noNext 方法中会记录 lastItem
                    else {
                        final E lastItem = this.lastItem;
                        // assert lastItem != null;
                        // 将当前迭代器保存的 lastItem 置为 null
                        this.lastItem = null;
                        // 如果两者相等的话则会将其删除
                        if (itemAt(lastRet) == lastItem)
                            removeAt(lastRet);
                    }
                    // lastRet 不是一个有效的索引
                } else if (lastRet == NONE)
                    throw new IllegalStateException();
                // else lastRet == REMOVED and the last returned element was
                // previously asynchronously removed via an operation other
                // than this.remove(), so nothing to do.
                // 如果遍历结束，则 detach
                if (cursor < 0 && nextIndex < 0)
                    detach();
            } finally {
                lock.unlock();
                // assert lastRet == NONE;
                // assert lastItem == null;
            }
        }

        /**
         * 队列为空之后迭代器会被通知到，主要操作就是将迭代器的状态置为 detached
         * <p>
         * Called to notify the iterator that the queue is empty, or that it
         * has fallen hopelessly behind, so that it should abandon any
         * further iteration, except possibly to return one more element
         * from next(), as promised by returning true from hasNext().
         */
        void shutdown() {
            // assert lock.isHeldByCurrentThread();
            // 将 cursor nextIndex lastRet 置为 NONE
            // 并且将 lastItem 置为 null
            cursor = NONE;
            if (nextIndex >= 0)
                nextIndex = REMOVED;
            if (lastRet >= 0) {
                lastRet = REMOVED;
                lastItem = null;
            }
            // 将 prevTakeIndex 置为 DETACHED
            prevTakeIndex = DETACHED;
            // Don't set nextItem to null because we must continue to be
            // able to return it on next().
            //
            // Caller will unlink from itrs when convenient.
        }

        /**
         * 计算 index 与 prevTakeIndex 的距离
         *
         * @param index
         * @param prevTakeIndex
         * @param length
         * @return
         */
        private int distance(int index, int prevTakeIndex, int length) {
            int distance = index - prevTakeIndex;
            if (distance < 0)
                distance += length;
            return distance;
        }

        /**
         * 迭代器 remove 发生的时候会调用、
         * 返回迭代器是否需要从 itrs 中移除
         * <p>
         * Called whenever an interior remove (not at takeIndex) occurred.
         *
         * @return true if this iterator should be unlinked from itrs
         */
        boolean removedAt(int removedIndex) {
            // assert lock.isHeldByCurrentThread();
            // 如果迭代器 detached，那么需要移除
            if (isDetached())
                return true;
            // 最新的 takeIndex
            // 当前迭代器维护的 prevTakeIndex
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevTakeIndex = this.prevTakeIndex;
            // 队列数组的长度
            final int len = items.length;
            // distance from prevTakeIndex to removedIndex
            // removedIndex - prevTakeIndex
            // removedIndex 与 prevTakeIndex 的距离
            final int removedDistance =
                    len * (itrs.cycles - this.prevCycles
                            + ((removedIndex < takeIndex) ? 1 : 0))
                            + (removedIndex - prevTakeIndex);
            // assert itrs.cycles - this.prevCycles >= 0;
            // assert itrs.cycles - this.prevCycles <= 1;
            // assert removedDistance > 0;
            // assert removedIndex != takeIndex;
            int cursor = this.cursor;
            // cursor 是有效的
            if (cursor >= 0) {
                // 计算 cursor 与 prevTakeIndex 的距离
                int x = distance(cursor, prevTakeIndex, len);
                // 如果两个距离相等
                if (x == removedDistance) {
                    // 如果下一个要迭代的元素就是 putIndex，说明是迭代结束了
                    if (cursor == putIndex)
                        this.cursor = cursor = NONE;
                    // x 的距离大于 removedDistance 的距离
                } else if (x > removedDistance) {
                    // assert cursor != prevTakeIndex;
                    this.cursor = cursor = dec(cursor, len);
                }
            }
            int lastRet = this.lastRet;
            if (lastRet >= 0) {
                int x = distance(lastRet, prevTakeIndex, len);
                if (x == removedDistance)
                    this.lastRet = lastRet = REMOVED;
                else if (x > removedDistance)
                    this.lastRet = lastRet = dec(lastRet, len);
            }
            int nextIndex = this.nextIndex;
            if (nextIndex >= 0) {
                int x = distance(nextIndex, prevTakeIndex, len);
                if (x == removedDistance)
                    this.nextIndex = nextIndex = REMOVED;
                else if (x > removedDistance)
                    this.nextIndex = nextIndex = dec(nextIndex, len);
            }
            if (cursor < 0 && nextIndex < 0 && lastRet < 0) {
                this.prevTakeIndex = DETACHED;
                return true;
            }
            return false;
        }

        /**
         * 当 takeIndex 循环到 0 时调用
         * 返回 Itr 是否已经是 detached 状态
         * <p>
         * Called whenever takeIndex wraps around to zero.
         *
         * @return true if this iterator should be unlinked from itrs
         */
        boolean takeIndexWrapped() {
            // assert lock.isHeldByCurrentThread();
            if (isDetached())
                return true;
            // 如果迭代的次数落后超过了 1 次
            // 则关闭当前迭代，置为 detached
            if (itrs.cycles - prevCycles > 1) {
                // All the elements that existed at the time of the last
                // operation are gone, so abandon further iteration.
                shutdown();
                return true;
            }
            return false;
        }

//         /** Uncomment for debugging. */
//         public String toString() {
//             return ("cursor=" + cursor + " " +
//                     "nextIndex=" + nextIndex + " " +
//                     "lastRet=" + lastRet + " " +
//                     "nextItem=" + nextItem + " " +
//                     "lastItem=" + lastItem + " " +
//                     "prevCycles=" + prevCycles + " " +
//                     "prevTakeIndex=" + prevTakeIndex + " " +
//                     "size()=" + size() + " " +
//                     "remainingCapacity()=" + remainingCapacity());
//         }
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
        return Spliterators.spliterator
                (this, (Spliterator.ORDERED |
                        Spliterator.NONNULL |
                        Spliterator.CONCURRENT));
    }

    /**
     * 遍历所有的元素
     *
     * @throws NullPointerException {@inheritDoc}
     */
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final Object[] items = this.items;
                for (int i = takeIndex, end = putIndex,
                     to = (i < end) ? end : items.length;
                        ; i = 0, to = end) {
                    for (; i < to; i++)
                        action.accept(itemAt(items, i));
                    if (to == end) break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除满足某个条件 (filter) 元素
     *
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return bulkRemove(filter);
    }

    /**
     * 移除所有存在于 Collection 中的元素
     *
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> c.contains(e));
    }

    /**
     * 保留所有存在于 Collection 中的元素
     *
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> !c.contains(e));
    }

    /**
     * Implementation of bulk remove methods.
     */
    private boolean bulkRemove(Predicate<? super E> filter) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (itrs == null) { // check for active iterators
                if (count > 0) {
                    final Object[] items = this.items;
                    // Optimize for initial run of survivors
                    for (int i = takeIndex, end = putIndex,
                         to = (i < end) ? end : items.length;
                            ; i = 0, to = end) {
                        for (; i < to; i++)
                            // 如果满足条件
                            // 从这里看好像仅仅处理一个满足条件的情况
                            if (filter.test(itemAt(items, i)))
                                return bulkRemoveModified(filter, i);
                        if (to == end) break;
                    }
                }
                return false;
            }
        } finally {
            lock.unlock();
        }
        // Active iterators are too hairy!
        // Punting (for now) to the slow n^2 algorithm ...
        return super.removeIf(filter);
    }

    // A tiny bit set implementation

    /**
     * bit 操作
     * TODO 暂时不知道是干嘛的，右移 6 位
     *
     * @param n
     * @return
     */
    private static long[] nBits(int n) {
        return new long[((n - 1) >> 6) + 1];
    }

    private static void setBit(long[] bits, int i) {
        bits[i >> 6] |= 1L << i;
    }

    private static boolean isClear(long[] bits, int i) {
        return (bits[i >> 6] & (1L << i)) == 0;
    }

    /**
     * 返回 i 到 j 的循环距离
     * 循环距离指在循环数组下 i 到 j 的距离，此时 i > j。
     * <p>
     * Returns circular distance from i to j, disambiguating i == j to
     * items.length; never returns 0.
     */
    private int distanceNonEmpty(int i, int j) {
        if ((j -= i) <= 0) j += items.length;
        return j;
    }

    /**
     * Helper for bulkRemove, in case of at least one deletion.
     * Tolerate predicates that reentrantly access the collection for
     * read (but not write), so traverse once to find elements to
     * delete, a second pass to physically expunge.
     *
     * @param beg valid index of first element to be deleted
     */
    private boolean bulkRemoveModified(
            Predicate<? super E> filter, final int beg) {
        final Object[] es = items;
        final int capacity = items.length;
        final int end = putIndex;
        // deathRow 的最小容量为 1
        // 处理第一个满足 filter 的元素，也就是 beg 位置上的元素
        final long[] deathRow = nBits(distanceNonEmpty(beg, putIndex));
        deathRow[0] = 1L;   // set bit 0
        // 处理后续的数据
        for (int i = beg + 1, to = (i <= end) ? end : es.length, k = beg;
                ; i = 0, to = end, k -= capacity) {
            for (; i < to; i++)
                if (filter.test(itemAt(es, i)))
                    setBit(deathRow, i - k);
            if (to == end) break;
        }
        // a two-finger traversal, with hare i reading, tortoise w writing
        int w = beg;
        for (int i = beg + 1, to = (i <= end) ? end : es.length, k = beg;
                ; w = 0) { // w rejoins i on second leg
            // In this loop, i and w are on the same leg, with i > w
            for (; i < to; i++)
                if (isClear(deathRow, i - k))
                    es[w++] = es[i];
            if (to == end) break;
            // In this loop, w is on the first leg, i on the second
            for (i = 0, to = end, k -= capacity; i < to && w < capacity; i++)
                if (isClear(deathRow, i - k))
                    es[w++] = es[i];
            if (i >= to) {
                if (w == capacity) w = 0; // "corner" case
                break;
            }
        }
        count -= distanceNonEmpty(w, end);
        circularClear(es, putIndex = w, end);
        return true;
    }

    /**
     * debugging
     */
    void checkInvariants() {
        // meta-assertions
        // assert lock.isHeldByCurrentThread();
        if (!invariantsSatisfied()) {
            String detail = String.format(
                    "takeIndex=%d putIndex=%d count=%d capacity=%d items=%s",
                    takeIndex, putIndex, count, items.length,
                    Arrays.toString(items));
            System.err.println(detail);
            throw new AssertionError(detail);
        }
    }

    private boolean invariantsSatisfied() {
        // Unlike ArrayDeque, we have a count field but no spare slot.
        // We prefer ArrayDeque's strategy (and the names of its fields!),
        // but our field layout is baked into the serial form, and so is
        // too annoying to change.
        //
        // putIndex == takeIndex must be disambiguated by checking count.
        int capacity = items.length;
        return capacity > 0
                && items.getClass() == Object[].class
                && (takeIndex | putIndex | count) >= 0
                && takeIndex < capacity
                && putIndex < capacity
                && count <= capacity
                && (putIndex - takeIndex - count) % capacity == 0
                && (count == 0 || items[takeIndex] != null)
                && (count == capacity || items[putIndex] == null)
                && (count == 0 || items[dec(putIndex, capacity)] != null);
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     *
     * @param s the stream
     * @throws ClassNotFoundException         if the class of a serialized object
     *                                        could not be found
     * @throws java.io.InvalidObjectException if invariants are violated
     * @throws java.io.IOException            if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {

        // Read in items array and various fields
        s.defaultReadObject();

        if (!invariantsSatisfied())
            throw new java.io.InvalidObjectException("invariants violated");
    }
}
