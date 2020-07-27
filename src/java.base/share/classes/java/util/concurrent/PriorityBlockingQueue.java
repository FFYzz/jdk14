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

import java.lang.Object;
import java.lang.Object;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.SortedSet;
import java.util.Spliterator;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

import jdk.internal.access.SharedSecrets;

/**
 * 无界阻塞队列。与 PriorityQueue 类似，此外提供了阻塞获取操作。
 * 逻辑上是无界的，受 OOM 影响。不允许 null 值。只允许加入 Comparable 类型的值
 * <p>
 * An unbounded {@linkplain BlockingQueue blocking queue} that uses
 * the same ordering rules as class {@link PriorityQueue} and supplies
 * blocking retrieval operations.  While this queue is logically
 * unbounded, attempted additions may fail due to resource exhaustion
 * (causing {@code OutOfMemoryError}). This class does not permit
 * {@code null} elements.  A priority queue relying on {@linkplain
 * Comparable natural ordering} also does not permit insertion of
 * non-comparable objects (doing so results in
 * {@code ClassCastException}).
 * <p>
 * 迭代器的遍历不保证有序
 *
 * <p>This class and its iterator implement all of the <em>optional</em>
 * methods of the {@link Collection} and {@link Iterator} interfaces.
 * The Iterator provided in method {@link #iterator()} and the
 * Spliterator provided in method {@link #spliterator()} are <em>not</em>
 * guaranteed to traverse the elements of the PriorityBlockingQueue in
 * any particular order. If you need ordered traversal, consider using
 * {@code Arrays.sort(pq.toArray())}.  Also, method {@code drainTo} can
 * be used to <em>remove</em> some or all elements in priority order and
 * place them in another collection.
 *
 * <p>Operations on this class make no guarantees about the ordering
 * of elements with equal priority. If you need to enforce an
 * ordering, you can define custom classes or comparators that use a
 * secondary key to break ties in primary priority values.  For
 * example, here is a class that applies first-in-first-out
 * tie-breaking to comparable elements. To use it, you would insert a
 * {@code new FIFOEntry(anEntry)} instead of a plain entry object.
 *
 * <pre> {@code
 * class FIFOEntry<E extends Comparable<? super E>>
 *     implements Comparable<FIFOEntry<E>> {
 *   static final AtomicLong seq = new AtomicLong(0);
 *   final long seqNum;
 *   final E entry;
 *   public FIFOEntry(E entry) {
 *     seqNum = seq.getAndIncrement();
 *     this.entry = entry;
 *   }
 *   public E getEntry() { return entry; }
 *   public int compareTo(FIFOEntry<E> other) {
 *     int res = entry.compareTo(other.entry);
 *     if (res == 0 && other.entry != this.entry)
 *       res = (seqNum < other.seqNum ? -1 : 1);
 *     return res;
 *   }
 * }}</pre>
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
 * 1. 无界队列，底层是如何存储的？
 * 基于数组的平衡二分堆
 * 2. 无界与有界的区别？
 * 主要在于能否扩容，类似于 LinkedBlockingQueue 以及 ArrayBlockingQueue 在初始化的时候就已经确定了队列的长度。
 * PriorityBlockingQueue 的队列长度可以改变
 * 3. remove 为什么最后要根据 index 来移除？
 * 因为底层存储结构是一个平衡二叉堆的结构，堆的维护需要使用到 index
 * 4. PriorityBlockingQueue 是一个大顶堆还是小顶堆？
 * 小顶堆
 *
 * @param <E>
 */
@SuppressWarnings("unchecked")
public class PriorityBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = 5595510919245408276L;

    /*
     * The implementation uses an array-based binary heap, with public
     * operations protected with a single lock. However, allocation
     * during resizing uses a simple spinlock (used only while not
     * holding main lock) in order to allow takes to operate
     * concurrently with allocation.  This avoids repeated
     * postponement of waiting consumers and consequent element
     * build-up. The need to back away from lock during allocation
     * makes it impossible to simply wrap delegated
     * java.util.PriorityQueue operations within a lock, as was done
     * in a previous version of this class. To maintain
     * interoperability, a plain PriorityQueue is still used during
     * serialization, which maintains compatibility at the expense of
     * transiently doubling overhead.
     */

    /**
     * 初始化长度 = 11
     * <p>
     * Default array capacity.
     */
    private static final int DEFAULT_INITIAL_CAPACITY = 11;

    /**
     * 数组最大的大小
     * <p>
     * The maximum size of array to allocate.
     * Some VMs reserve some header words in an array.
     * Attempts to allocate larger arrays may result in
     * OutOfMemoryError: Requested array size exceeds VM limit
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * 存放元素的数组，长度可能会大于 size
     * <p>
     * Priority queue represented as a balanced binary heap: the two
     * children of queue[n] are queue[2*n+1] and queue[2*(n+1)].  The
     * priority queue is ordered by comparator, or by the elements'
     * natural ordering, if comparator is null: For each node n in the
     * heap and each descendant d of n, n <= d.  The element with the
     * lowest value is in queue[0], assuming the queue is nonempty.
     */
    private transient Object[] queue;

    /**
     * 队列的长度，数组中实际元素的个数
     * <p>
     * The number of elements in the priority queue.
     */
    private transient int size;

    /**
     * 比较器
     * <p>
     * The comparator, or null if priority queue uses elements'
     * natural ordering.
     */
    private transient Comparator<? super E> comparator;

    /**
     * 锁
     * <p>
     * Lock used for all public operations.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 非空条件
     * <p>
     * Condition for blocking when empty.
     */
    @SuppressWarnings("serial") // Classes implementing Condition may be serializable.
    private final Condition notEmpty = lock.newCondition();

    /**
     * 自旋锁
     * <p>
     * Spinlock for allocation, acquired via CAS.
     */
    private transient volatile int allocationSpinLock;

    /**
     * 一般的 PriorityQueue
     * <p>
     * A plain PriorityQueue used only for serialization,
     * to maintain compatibility with previous versions
     * of this class. Non-null only during serialization/deserialization.
     */
    private PriorityQueue<E> q;

    /**
     * 构造方法
     * <p>
     * Creates a {@code PriorityBlockingQueue} with the default
     * initial capacity (11) that orders its elements according to
     * their {@linkplain Comparable natural ordering}.
     */
    public PriorityBlockingQueue() {
        this(DEFAULT_INITIAL_CAPACITY, null);
    }

    /**
     * 构造方法
     * <p>
     * Creates a {@code PriorityBlockingQueue} with the specified
     * initial capacity that orders its elements according to their
     * {@linkplain Comparable natural ordering}.
     *
     * @param initialCapacity the initial capacity for this priority queue
     * @throws IllegalArgumentException if {@code initialCapacity} is less
     *                                  than 1
     */
    public PriorityBlockingQueue(int initialCapacity) {
        this(initialCapacity, null);
    }

    /**
     * 构造方法
     * <p>
     * Creates a {@code PriorityBlockingQueue} with the specified initial
     * capacity that orders its elements according to the specified
     * comparator.
     *
     * @param initialCapacity the initial capacity for this priority queue
     * @param comparator      the comparator that will be used to order this
     *                        priority queue.  If {@code null}, the {@linkplain Comparable
     *                        natural ordering} of the elements will be used.
     * @throws IllegalArgumentException if {@code initialCapacity} is less
     *                                  than 1
     */
    public PriorityBlockingQueue(int initialCapacity,
                                 Comparator<? super E> comparator) {
        if (initialCapacity < 1)
            throw new IllegalArgumentException();
        this.comparator = comparator;
        this.queue = new Object[Math.max(1, initialCapacity)];
    }

    /**
     * 构造方法，有序则保持有序，无序则按照自然顺序
     * <p>
     * Creates a {@code PriorityBlockingQueue} containing the elements
     * in the specified collection.  If the specified collection is a
     * {@link SortedSet} or a {@link PriorityQueue}, this
     * priority queue will be ordered according to the same ordering.
     * Otherwise, this priority queue will be ordered according to the
     * {@linkplain Comparable natural ordering} of its elements.
     *
     * @param c the collection whose elements are to be placed
     *          into this priority queue
     * @throws ClassCastException   if elements of the specified collection
     *                              cannot be compared to one another according to the priority
     *                              queue's ordering
     * @throws NullPointerException if the specified collection or any
     *                              of its elements are null
     */
    public PriorityBlockingQueue(Collection<? extends E> c) {
        // 是否已经堆化
        boolean heapify = true; // true if not known to be in heap order
        boolean screen = true;  // true if must screen for nulls
        if (c instanceof SortedSet<?>) {
            SortedSet<? extends E> ss = (SortedSet<? extends E>) c;
            // 初始化 comparator
            this.comparator = (Comparator<? super E>) ss.comparator();
            // heapify 设为为 false，表示不需要堆化了
            heapify = false;
        } else if (c instanceof PriorityBlockingQueue<?>) {
            PriorityBlockingQueue<? extends E> pq =
                    (PriorityBlockingQueue<? extends E>) c;
            // 初始化 comparator
            this.comparator = (Comparator<? super E>) pq.comparator();
            screen = false;
            // 精确匹配，如果就是 PriorityBlockingQueue 类型的
            if (pq.getClass() == PriorityBlockingQueue.class) // exact match
                // 则不需要 heapify，表示不需要堆化了
                heapify = false;
        }
        Object[] es = c.toArray();
        int n = es.length;
        // If c.toArray incorrectly doesn't return Object[], copy it.
        // 转成 Object 数组
        if (es.getClass() != Object[].class)
            es = Arrays.copyOf(es, n, Object[].class);
        if (screen && (n == 1 || this.comparator != null)) {
            for (Object e : es)
                if (e == null)
                    throw new NullPointerException();
        }
        this.queue = ensureNonEmpty(es);
        this.size = n;
        // 如果需要堆化则进行堆化
        if (heapify)
            heapify();
    }

    /**
     * 保证保存数据的数组非空
     * <p>
     * Ensures that queue[0] exists, helping peek() and poll().
     */
    private static Object[] ensureNonEmpty(Object[] es) {
        return (es.length > 0) ? es : new Object[1];
    }

    /**
     * 扩容数组，因为元素的个数已经超过了 queue 数组的长度
     * 扩容策略：如果当前 capacity < 64 ，则扩容为 *2 + 2
     * 如果 >= 64 ,则扩容为原来的 1.5 倍
     * <p>
     * Tries to grow array to accommodate at least one more element
     * (but normally expand by about 50%), giving up (allowing retry)
     * on contention (which we expect to be rare). Call only while
     * holding lock.
     *
     * @param array  the heap array
     * @param oldCap the length of the array
     */
    private void tryGrow(Object[] array, int oldCap) {
        // 释放锁
        lock.unlock(); // must release and then re-acquire main lock
        Object[] newArray = null;
        // 获取自旋锁
        if (allocationSpinLock == 0 &&
                ALLOCATIONSPINLOCK.compareAndSet(this, 0, 1)) {
            try {
                int newCap = oldCap + ((oldCap < 64) ?
                        (oldCap + 2) : // grow faster if small
                        (oldCap >> 1));
                // 超过 MAX_ARRAY_SIZE
                if (newCap - MAX_ARRAY_SIZE > 0) {    // possible overflow
                    int minCap = oldCap + 1;
                    // 越界抛异常
                    if (minCap < 0 || minCap > MAX_ARRAY_SIZE)
                        throw new OutOfMemoryError();
                    // 最大只能为 MAX_ARRAY_SIZE
                    newCap = MAX_ARRAY_SIZE;
                }
                //
                if (newCap > oldCap && queue == array)
                    // 保存数据到 newArray
                    newArray = new Object[newCap];
            } finally {
                // 释放自旋锁
                allocationSpinLock = 0;
            }
        }
        // 如果有其他线程在尝试扩容，则 放弃 cpu
        if (newArray == null) // back off if another thread is allocating
            Thread.yield();
        // 加锁
        lock.lock();
        if (newArray != null && queue == array) {
            queue = newArray;
            System.arraycopy(array, 0, newArray, 0, oldCap);
        }
    }

    /**
     * 元素出队
     * <p>
     * Mechanics for poll().  Call only while holding lock.
     */
    private E dequeue() {
        // assert lock.isHeldByCurrentThread();
        final Object[] es;
        final E result;
        // 获取结果，数组的第一个元素
        if ((result = (E) ((es = queue)[0])) != null) {
            final int n;
            // 保存最后一个元素
            final E x = (E) es[(n = --size)];
            // 将最后一个位置置为 null
            es[n] = null;
            // 如果数组中还有元素
            if (n > 0) {
                final Comparator<? super E> cmp;
                if ((cmp = comparator) == null)
                    // 将最后一个元素放到第 0 个位置，然后从 0 位置开始向下调整
                    siftDownComparable(0, x, es, n);
                else
                    siftDownUsingComparator(0, x, es, n, cmp);
            }
        }
        return result;
    }

    /**
     * 入队时堆的操作，此时堆已经基本有序
     * 向上调整
     * <p>
     * Inserts item x at position k, maintaining heap invariant by
     * promoting x up the tree until it is greater than or equal to
     * its parent, or is the root.
     * <p>
     * To simplify and speed up coercions and comparisons, the
     * Comparable and Comparator versions are separated into different
     * methods that are otherwise identical. (Similarly for siftDown.)
     *
     * @param k  the position to fill
     * @param x  the item to insert
     * @param es the heap array
     */
    private static <T> void siftUpComparable(int k, T x, Object[] es) {
        Comparable<? super T> key = (Comparable<? super T>) x;

        while (k > 0) {
            // 计算父节点的 index
            int parent = (k - 1) >>> 1;
            // 父节点元素
            Object e = es[parent];
            // 进行比较
            // 如果满足条件则直接 break
            if (key.compareTo((T) e) >= 0)
                break;
            // 更换
            es[k] = e;
            // 更新 k，继续往上比较
            k = parent;
        }
        // 更新最后一个值
        es[k] = key;
    }

    /**
     * 与 {@link #siftUpComparable(int, java.lang.Object, Object[])} 类似
     *
     * @param k
     * @param x
     * @param es
     * @param cmp
     * @param <T>
     */
    private static <T> void siftUpUsingComparator(
            int k, T x, Object[] es, Comparator<? super T> cmp) {
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object e = es[parent];
            if (cmp.compare(x, (T) e) >= 0)
                break;
            es[k] = e;
            k = parent;
        }
        es[k] = x;
    }

    /**
     * 堆排序的向下调整
     * <p>
     * Inserts item x at position k, maintaining heap invariant by
     * demoting x down the tree repeatedly until it is less than or
     * equal to its children or is a leaf.
     *
     * @param k  the position to fill
     * @param x  the item to insert
     * @param es the heap array
     * @param n  heap size
     */
    private static <T> void siftDownComparable(int k, T x, Object[] es, int n) {
        // assert n > 0;
        Comparable<? super T> key = (Comparable<? super T>) x;
        int half = n >>> 1;           // loop while a non-leaf
        // k < half 表示下标为 k 的节点为非叶子节点
        while (k < half) {
            // 左孩子节点
            int child = (k << 1) + 1; // assume left child is least
            // 左孩子节点值
            Object c = es[child];
            // 右孩子节点
            int right = child + 1;
            // 检查 right 是否越界
            // 得到左孩子和右孩子节点的较小值
            if (right < n &&
                    ((Comparable<? super T>) c).compareTo((T) es[right]) > 0)
                c = es[child = right];
            // 当前节点与 孩子节点中的较小节点进行比较
            if (key.compareTo((T) c) <= 0)
                // 不需要调整
                break;
            // 需要调整
            es[k] = c;
            // 更新 k，继续往下调整
            k = child;
        }
        es[k] = key;
    }

    /**
     * 与 {@link #siftDownComparable(int, java.lang.Object, Object[], int)} 类似，只不过用的是指定的Comparator
     *
     * @param k
     * @param x
     * @param es
     * @param n
     * @param cmp
     * @param <T>
     */
    private static <T> void siftDownUsingComparator(
            int k, T x, Object[] es, int n, Comparator<? super T> cmp) {
        // assert n > 0;
        int half = n >>> 1;
        while (k < half) {
            int child = (k << 1) + 1;
            Object c = es[child];
            int right = child + 1;
            if (right < n && cmp.compare((T) c, (T) es[right]) > 0)
                c = es[child = right];
            if (cmp.compare(x, (T) c) <= 0)
                break;
            es[k] = c;
            k = child;
        }
        es[k] = x;
    }

    /**
     * 建堆
     * <p>
     * Establishes the heap invariant (described above) in the entire tree,
     * assuming nothing about the order of the elements prior to the call.
     * This classic algorithm due to Floyd (1964) is known to be O(size).
     */
    private void heapify() {
        final Object[] es = queue;
        // i : 从第一个非叶子节点开始调整
        int n = size, i = (n >>> 1) - 1;
        final Comparator<? super E> cmp;
        if ((cmp = comparator) == null)
            // 自然排序
            for (; i >= 0; i--)
                siftDownComparable(i, (E) es[i], es, n);
        else
            // 根据 cmp 排序
            for (; i >= 0; i--)
                siftDownUsingComparator(i, (E) es[i], es, n, cmp);
    }

    /**
     * 向 priority queue 中添加元素
     * add 方法也是调用 offer 方法实现
     * 只会返回 true
     * <p>
     * Inserts the specified element into this priority queue.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws ClassCastException   if the specified element cannot be compared
     *                              with elements currently in the priority queue according to the
     *                              priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E e) {
        return offer(e);
    }

    /**
     * 队列是无界的，因此该方法永远不会返回 false，只会返回 true
     * <p>
     * Inserts the specified element into this priority queue.
     * As the queue is unbounded, this method will never return {@code false}.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Queue#offer})
     * @throws ClassCastException   if the specified element cannot be compared
     *                              with elements currently in the priority queue according to the
     *                              priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null)
            throw new NullPointerException();
        final ReentrantLock lock = this.lock;
        lock.lock();
        int n, cap;
        Object[] es;
        // 检查数组是否需要扩容
        while ((n = size) >= (cap = (es = queue).length))
            // 扩容数组
            tryGrow(es, cap);
        try {
            final Comparator<? super E> cmp;
            if ((cmp = comparator) == null)
                // 自然排序规则入队
                siftUpComparable(n, e, es);
            else
                // 指定 cmp 排序规则入队
                siftUpUsingComparator(n, e, es, cmp);
            size = n + 1;
            // 非空唤醒
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * 调用 offer 实现
     * <p>
     * Inserts the specified element into this priority queue.
     * As the queue is unbounded, this method will never block.
     *
     * @param e the element to add
     * @throws ClassCastException   if the specified element cannot be compared
     *                              with elements currently in the priority queue according to the
     *                              priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public void put(E e) {
        offer(e); // never need to block
    }

    /**
     * Inserts the specified element into this priority queue.
     * As the queue is unbounded, this method will never block or
     * return {@code false}.
     *
     * @param e       the element to add
     * @param timeout This parameter is ignored as the method never blocks
     * @param unit    This parameter is ignored as the method never blocks
     * @return {@code true} (as specified by
     * {@link BlockingQueue#offer(Object, long, TimeUnit) BlockingQueue.offer})
     * @throws ClassCastException   if the specified element cannot be compared
     *                              with elements currently in the priority queue according to the
     *                              priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        return offer(e); // never need to block
    }

    /**
     * 出队
     *
     * @return
     */
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 一定会返回一个元素
     *
     * @return
     * @throws InterruptedException
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        E result;
        try {
            while ((result = dequeue()) == null)
                // 一直阻塞等待
                notEmpty.await();
        } finally {
            lock.unlock();
        }
        return result;
    }

    /**
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        E result;
        try {
            // 不会阻塞，直接返回。
            while ((result = dequeue()) == null && nanos > 0)
                // 阻塞等待 nanos 时间，再去尝试出队
                nanos = notEmpty.awaitNanos(nanos);
        } finally {
            lock.unlock();
        }
        return result;
    }

    /**
     * 返回队首的元素，也就是最小的元素
     *
     * @return
     */
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return (E) queue[0];
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the comparator used to order the elements in this queue,
     * or {@code null} if this queue uses the {@linkplain Comparable
     * natural ordering} of its elements.
     *
     * @return the comparator used to order the elements in this queue,
     * or {@code null} if this queue uses the natural
     * ordering of its elements
     */
    public Comparator<? super E> comparator() {
        return comparator;
    }

    /**
     * 返回队列中实际持有的元素个数
     *
     * @return
     */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 无界队列，所以总是返回 Integer.MAX_VALUE
     * <p>
     * Always returns {@code Integer.MAX_VALUE} because
     * a {@code PriorityBlockingQueue} is not capacity constrained.
     *
     * @return {@code Integer.MAX_VALUE} always
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * 从头开始遍历寻找
     *
     * @param o
     * @return
     */
    private int indexOf(Object o) {
        if (o != null) {
            final Object[] es = queue;
            for (int i = 0, n = size; i < n; i++)
                if (o.equals(es[i]))
                    return i;
        }
        return -1;
    }

    /**
     * 移除指定位置的元素
     * <p>
     * Removes the ith element from queue.
     */
    private void removeAt(int i) {
        final Object[] es = queue;
        final int n = size - 1;
        // 如果是最后一个元素，那么直接置为 null 即可
        if (n == i) // removed last element
            es[i] = null;
            // 不是最后一个元素
        else {
            // 最后一个元素
            E moved = (E) es[n];
            //将最后一个位置置为 null
            es[n] = null;
            final Comparator<? super E> cmp;
            // 将最后一个元素插入到要删除的元素的位置上，进行堆调整。
            // 一般情况下，原来的最后一个元素，应该是比较大的
            // 需要向下调整
            if ((cmp = comparator) == null)
                siftDownComparable(i, moved, es, n);
            else
                siftDownUsingComparator(i, moved, es, n, cmp);
            // 如果满足条件，说明向下没有调整，需要向上进行调整
            if (es[i] == moved) {
                if (cmp == null)
                    siftUpComparable(i, moved, es);
                else
                    siftUpUsingComparator(i, moved, es, cmp);
            }
        }
        size = n;
    }

    /**
     * 移除单个元素
     * <p>
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.  Returns {@code true} if and only if this queue contained
     * the specified element (or equivalently, if this queue changed as a
     * result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int i = indexOf(o);
            if (i == -1)
                return false;
            // 主要逻辑也是 removeAt
            removeAt(i);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Identity-based version for use in Itr.remove.
     *
     * @param o element to be removed from this queue, if present
     */
    void removeEq(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] es = queue;
            // 遍历队列开始找
            for (int i = 0, n = size; i < n; i++) {
                if (o == es[i]) {
                    // 调用 removeAt 移除
                    removeAt(i);
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     */
    public boolean contains(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return indexOf(o) != -1;
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        return Helpers.collectionToString(this);
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
     * 将队列中的元素移除，保存到 Collection 中
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
            int n = Math.min(size, maxElements);
            for (int i = 0; i < n; i++) {
                // Collection 添加一个
                c.add((E) queue[0]); // In this order, in case add() throws.
                // 出队一个
                dequeue();
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 直接将 queue 数组中的元素置为 null
     * 并更新 size
     * <p>
     * Atomically removes all of the elements from this queue.
     * The queue will be empty after this call returns.
     */
    public void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] es = queue;
            for (int i = 0, n = size; i < n; i++)
                es[i] = null;
            size = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue.
     * The returned array elements are in no particular order.
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
            // 返回一个实际元素个数大小的数组
            return Arrays.copyOf(queue, size);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue; the
     * runtime type of the returned array is that of the specified array.
     * The returned array elements are in no particular order.
     * If the queue fits in the specified array, it is returned therein.
     * Otherwise, a new array is allocated with the runtime type of the
     * specified array and the size of this queue.
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
    public <T> T[] toArray(T[] a) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = size;
            if (a.length < n)
                // Make a new array of a's runtime type, but my contents:
                return (T[]) Arrays.copyOf(queue, size, a.getClass());
            System.arraycopy(queue, 0, a, 0, n);
            if (a.length > n)
                a[n] = null;
            return a;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回一个 Itr 实例
     * <p>
     * Returns an iterator over the elements in this queue. The
     * iterator does not return the elements in any particular order.
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue
     */
    public Iterator<E> iterator() {
        return new Itr(toArray());
    }

    /**
     * 实现 Iterator 接口
     * <p>
     * Snapshot iterator that works off copy of underlying q array.
     */
    final class Itr implements Iterator<E> {
        // 目标数组
        final Object[] array; // Array of all elements
        // 游标
        int cursor;           // index of next element to return
        // 上一次遍历的位置
        // 初始化为 -1
        int lastRet = -1;     // index of last element, or -1 if no such

        Itr(Object[] array) {
            this.array = array;
        }

        public boolean hasNext() {
            // 直接判断 cursor 是否小于数组的长度
            return cursor < array.length;
        }

        public E next() {
            if (cursor >= array.length)
                throw new NoSuchElementException();
            // 直接返回当前下标为 cursor 的元素，并移动下标和更新 lastRet
            return (E) array[lastRet = cursor++];
        }

        /**
         * 主要逻辑归结到 {@link #removeAt(int)} 方法
         */
        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            // 移除 lastRet 位置的元素
            // 移除操作加锁
            removeEq(array[lastRet]);
            // 更新 lastRet 为 -1
            lastRet = -1;
        }

        /**
         * 从目前遍历到的位置到结尾，执行 action
         *
         * @param action
         */
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final Object[] es = array;
            int i;
            if ((i = cursor) < es.length) {
                lastRet = -1;
                cursor = es.length;
                for (; i < es.length; i++)
                    action.accept((E) es[i]);
                lastRet = es.length - 1;
            }
        }
    }

    /**
     * Saves this queue to a stream (that is, serializes it).
     * <p>
     * For compatibility with previous version of this class, elements
     * are first copied to a java.util.PriorityQueue, which is then
     * serialized.
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
            throws java.io.IOException {
        lock.lock();
        try {
            // avoid zero capacity argument
            q = new PriorityQueue<E>(Math.max(size, 1), comparator);
            q.addAll(this);
            s.defaultWriteObject();
        } finally {
            q = null;
            lock.unlock();
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
        try {
            s.defaultReadObject();
            int sz = q.size();
            SharedSecrets.getJavaObjectInputStreamAccess().checkArray(s, Object[].class, sz);
            this.queue = new Object[Math.max(1, sz)];
            comparator = q.comparator();
            addAll(q);
        } finally {
            q = null;
        }
    }

    /**
     * Immutable snapshot spliterator that binds to elements "late".
     */
    final class PBQSpliterator implements Spliterator<E> {
        Object[] array;        // null until late-bound-initialized
        int index;
        int fence;

        PBQSpliterator() {
        }

        PBQSpliterator(Object[] array, int index, int fence) {
            this.array = array;
            this.index = index;
            this.fence = fence;
        }

        private int getFence() {
            if (array == null)
                fence = (array = toArray()).length;
            return fence;
        }

        public PBQSpliterator trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid) ? null :
                    new PBQSpliterator(array, lo, index = mid);
        }

        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final int hi = getFence(), lo = index;
            final Object[] es = array;
            index = hi;                 // ensure exhaustion
            for (int i = lo; i < hi; i++)
                action.accept((E) es[i]);
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            if (getFence() > index && index >= 0) {
                action.accept((E) array[index++]);
                return true;
            }
            return false;
        }

        public long estimateSize() {
            return getFence() - index;
        }

        public int characteristics() {
            return (Spliterator.NONNULL |
                    Spliterator.SIZED |
                    Spliterator.SUBSIZED);
        }
    }

    /**
     * Returns a {@link Spliterator} over the elements in this queue.
     * The spliterator does not traverse elements in any particular order
     * (the {@link Spliterator#ORDERED ORDERED} characteristic is not reported).
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#SIZED} and
     * {@link Spliterator#NONNULL}.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @implNote The {@code Spliterator} additionally reports {@link Spliterator#SUBSIZED}.
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return new PBQSpliterator();
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

    // A tiny bit set implementation

    private static long[] nBits(int n) {
        // 这里为什么要 ((n - 1) >> 6)
        // +1 使得 long 数组至少有一个元素
        // 2^6 = 64
        // 因为一个 long 类型的值由 64 bit 组成，所以数组中的一个元素最多只能保存 64 个元素
        // 所以当大于 64 个元素的时候，需要扩大 long 数组的个数，以记录更多的元素
        // n - 1 是因为第一位已经被置为 1 了
        return new long[((n - 1) >> 6) + 1];
    }

    private static void setBit(long[] bits, int i) {
        bits[i >> 6] |= 1L << i;
    }

    /**
     * 返回该位置上的元素是否需要被清理
     * 如果返回 true 说明不需要清理
     * 返回 false 说明需要清理
     *
     * @param bits
     * @param i
     * @return
     */
    private static boolean isClear(long[] bits, int i) {
        return (bits[i >> 6] & (1L << i)) == 0;
    }

    /**
     * 批量移除方法
     * <p>
     * Implementation of bulk remove methods.
     */
    private boolean bulkRemove(Predicate<? super E> filter) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] es = queue;
            final int end = size;
            int i;
            // Optimize for initial run of survivors
            // 快速跳过不满足条件的对象
            for (i = 0; i < end && !filter.test((E) es[i]); i++)
                ;
            // 如果都不满足，因为没有移除元素，直接返回 false。
            if (i >= end)
                return false;
            // Tolerate predicates that reentrantly access the
            // collection for read, so traverse once to find elements
            // to delete, a second pass to physically expunge.
            // beg 是第一个满足 filter 的元素下标
            final int beg = i;
            final long[] deathRow = nBits(end - beg);
            deathRow[0] = 1L;   // set bit 0
            // 继续遍历
            for (i = beg + 1; i < end; i++)
                // 如果满足 filter
                if (filter.test((E) es[i]))
                    // 将 i - beg 的位置为 1
                    setBit(deathRow, i - beg);
            int w = beg;
            for (i = beg; i < end; i++)
                // 返回 true 说明不需要清理
                if (isClear(deathRow, i - beg))
                    // 不需要清理，所以往前移
                    es[w++] = es[i];
            // 后续的位置置为 null
            // 并更新 size
            for (i = size = w; i < end; i++)
                es[i] = null;
            // 重新建堆
            heapify();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Object[] es = queue;
            // 遍历，执行 consumer
            for (int i = 0, n = size; i < n; i++)
                action.accept((E) es[i]);
        } finally {
            lock.unlock();
        }
    }

    // VarHandle mechanics
    private static final VarHandle ALLOCATIONSPINLOCK;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            ALLOCATIONSPINLOCK = l.findVarHandle(PriorityBlockingQueue.class,
                    "allocationSpinLock",
                    int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
