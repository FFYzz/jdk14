/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.lang;

import jdk.internal.misc.TerminatingThreadLocal;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 * <p>
 * 该类提供了一个 线程本地 变量。
 *
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * <p>
 * 下面例子为每个线程创建了 独一无二的标识符。
 * <p>
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit 含蓄的/不直接言明的 reference to its copy 副本
 * of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 *
 * @author Josh Bloch and Doug Lea
 * @since 1.2
 */
public class ThreadLocal<T> {
    /**
     * ThreadLocals rely on 依靠 per-thread 每个线程 linear-probe hash maps attached
     * to each thread (Thread.threadLocals and
     * inheritableThreadLocals).  The ThreadLocal objects act as keys,
     * searched via threadLocalHashCode.  This is a custom hash code
     * (useful only within ThreadLocalMaps) that eliminates 消除 collisions
     * in the common case where consecutively constructed ThreadLocals
     * are used by the same threads, while remaining well-behaved in
     * less common cases.
     */
    private final int threadLocalHashCode = nextHashCode();

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     * <p>
     * 用于计算 nextHashCode，从 0 开始。
     */
    private static AtomicInteger nextHashCode =
            new AtomicInteger();

    /**
     * The difference between successively generated hash codes - turns
     * implicit sequential thread-local IDs into near-optimally spread
     * multiplicative hash values for power-of-two-sized tables.
     * <p>
     * HASH 常量
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * Returns the next hash code.
     * <p>
     * 返回下一个 HashCode
     */
    private static int nextHashCode() {
        // 原先的值 + HASH_INCREMENT
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     * <p>
     * 返回当前线程的 thread-local 变量的 initial value。该方法会在第一次调用
     * threadLocal 的 {@link #get} 方法的时候执行，除非该线程之前已经调用过了
     * {@link #set} 方法，调用了 {@link #set} 方法后不会再次被调用。
     * 正常情况下该方法只能被调用一次。但是如果如果 {@link #get} 之后又调用了一次
     * {@link #remove} 方法，那么该方法会被重复调用。
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     * <p>
     * 默认实现简单的返回 null。如果希望默认不为 null，则子类需要重写该方法。
     * 子类一般作为匿名内部类存在中。
     *
     * @return the initial value for this thread-local
     */
    protected T initialValue() {
        return null;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     * <p>
     * 创建一个线程局部变量。
     *
     * @param <S>      the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new SuppliedThreadLocal<>(supplier);
    }

    /**
     * Creates a thread local variable.
     * <p>
     * 构造方法
     *
     * @see #withInitial(java.util.function.Supplier)
     */
    public ThreadLocal() {
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     * <p>
     * 返回 thread-local 变量在当前线程副本的值
     *
     * @return the current thread's value of this thread-local
     */
    public T get() {
        // 获取当前线程
        Thread t = Thread.currentThread();
        // 获取当前线程的 ThreadLocalMap
        ThreadLocalMap map = getMap(t);
        // 如果 map 不为 null
        if (map != null) {
            // 从 map 中获取 entry
            ThreadLocalMap.Entry e = map.getEntry(this);
            // 如果不为空，说明没有被 GC
            if (e != null) {
                // 获取 value
                @SuppressWarnings("unchecked")
                T result = (T) e.value;
                // 返回 value
                return result;
            }
        }
        // 如果 map 未初始化
        return setInitialValue();
    }

    /**
     * Returns {@code true} if there is a value in the current thread's copy of
     * this thread-local variable, even if that values is {@code null}.
     * <p>
     * 返回当前线程的 threadlocal 副本中是否存在元素
     *
     * @return {@code true} if current thread has associated value in this
     * thread-local variable; {@code false} if not
     */
    boolean isPresent() {
        // 当前线程
        Thread t = Thread.currentThread();
        // 当前线程持有的 ThreadLocalMap
        ThreadLocalMap map = getMap(t);
        // 查询 entry
        // 在查询的时候顺便会清理 stale entry
        return map != null && map.getEntry(this) != null;
    }

    /**
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     * <p>
     * 设置初始值
     *
     * @return the initial value
     */
    private T setInitialValue() {
        // 调用 initialValue 获得初始值
        T value = initialValue();
        // 当前线程
        Thread t = Thread.currentThread();
        // 获取当前线程持有的 ThreadLocalMap
        ThreadLocalMap map = getMap(t);
        // 如果 map 不为 null
        if (map != null) {
            // 设置进 map 中
            map.set(this, value);
            // 如果 map 为 null
        } else {
            // 创建 map
            // 并将当前的 key value 作为第一个元素
            createMap(t, value);
        }
        // 如果是 TerminatingThreadLocal 类型的
        if (this instanceof TerminatingThreadLocal) {
            // 则进行注册
            TerminatingThreadLocal.register((TerminatingThreadLocal<?>) this);
        }
        // 返回放入的值
        return value;
    }

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     * <p>
     * 将当前线程的副本变量的值设为 value
     *
     * @param value the value to be stored in the current thread's copy of
     *              this thread-local.
     */
    public void set(T value) {
        // 当前线程
        Thread t = Thread.currentThread();
        // 获取当前线程的 ThreadLocalMap
        ThreadLocalMap map = getMap(t);
        // 如果 map 已经初始化
        if (map != null) {
            // 设置值
            map.set(this, value);
        } else {
            // 初始化 map
            createMap(t, value);
        }
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * @since 1.5
     */
    public void remove() {
        // 当前线程的 ThreadLocalMap
        ThreadLocalMap m = getMap(Thread.currentThread());
        if (m != null) {
            // 移除
            m.remove(this);
        }
    }

    /**
     * Get the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     * <p>
     * 获取线程实例中的 ThreadLocalMap 属性
     *
     * @param t the current thread
     * @return the map
     */
    ThreadLocalMap getMap(Thread t) {
        return t.threadLocals;
    }

    /**
     * Create the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t          the current thread
     * @param firstValue value for the initial entry of the map
     */
    void createMap(Thread t, T firstValue) {
        // 实例化 thread 持有的 threadLocals
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    /**
     * Factory method to create map of inherited thread locals.
     * Designed to be called only from Thread constructor.
     * <p>
     * 工厂方法，创建 继承的 ThreadLocal 的 map
     *
     * @param parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     * <p>
     * 该方法定义在子类 InheritableThreadLocal 中。
     */
    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * An extension of ThreadLocal that obtains its initial value from
     * the specified {@code Supplier}.
     * <p>
     * 内部类，应该是 JDK 1.8 引入
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap is a customized hash map suitable only for
     * maintaining thread local values. No operations are exported
     * outside of the ThreadLocal class. The class is package private to
     * allow declaration of fields in class Thread.  To help deal with
     * very large and long-lived usages, the hash table entries use
     * WeakReferences for keys. However, since reference queues are not
     * used, stale entries are guaranteed to be removed only when
     * the table starts running out of space.
     * <p>
     * 没有向外导出任何方法。Hash table 使用 WeakReferences 作为 key。
     * 因为引用队列（reference queues）没有被使用。所以 stale entry 只有在 table 没有足够空间
     * 的情况下才会被移除。
     * 内部静态类
     * 每个线程都持有一个 ThreadLocalMap 的实例
     */
    static class ThreadLocalMap {

        /**
         * The entries in this hash map extend WeakReference, using
         * its main ref field as the key (which is always a
         * ThreadLocal object).  Note that null keys (i.e. entry.get()
         * == null) mean that the key is no longer referenced, so the
         * entry can be expunged from table.  Such entries are referred to
         * as "stale entries" in the code that follows.
         * <p>
         * 继承自 WeakReference，stale entries 指的是 entry.get() == null 的情况
         */
        static class Entry extends WeakReference<ThreadLocal<?>> {
            /**
             * The value associated with this ThreadLocal.
             * <p>
             * 存储的 value
             */
            Object value;

            /**
             * @param k ThreadLocal
             * @param v value
             */
            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }

        /**
         * The initial capacity -- MUST be a power of two.
         * <p>
         * ThreadLocalMap 的初始大小，必须是 2 的 n 次幂
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * The table, resized as necessary.
         * table.length MUST always be a power of two.
         * <p>
         * table，用于存放 Entry
         */
        private Entry[] table;

        /**
         * The number of entries in the table.
         * <p>
         * table 中 entries 的个数
         */
        private int size = 0;

        /**
         * The next size value at which to resize.
         * <p>
         * 下一次 table 扩容的阈值
         * 默认为 0
         */
        private int threshold; // Default to 0

        /**
         * Set the resize threshold to maintain at worst a 2/3 load factor.
         * <p>
         * 设置阈值为 table.length() * 2 / 3
         */
        private void setThreshold(int len) {
            threshold = len * 2 / 3;
        }

        /**
         * Increment i modulo len.
         * <p>
         * 计算下一个索引
         */
        private static int nextIndex(int i, int len) {
            return ((i + 1 < len) ? i + 1 : 0);
        }

        /**
         * Decrement i modulo len.
         * <p>
         * 计算前一个索引
         */
        private static int prevIndex(int i, int len) {
            return ((i - 1 >= 0) ? i - 1 : len - 1);
        }

        /**
         * Construct a new map initially containing (firstKey, firstValue).
         * ThreadLocalMaps are constructed lazily, so we only create
         * one when we have at least one entry to put in it.
         * <p>
         * 构造方法，创建一个新的 map。
         */
        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            // 初始化 table
            table = new Entry[INITIAL_CAPACITY];
            // 计算第一个元素的 index
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            // 初始化 index，并初始化 index 位置上的 entry
            table[i] = new Entry(firstKey, firstValue);
            // 设置 table 的size
            size = 1;
            // 设置 ThreadLocalMap 的 Threshold
            setThreshold(INITIAL_CAPACITY);
        }

        /**
         * Construct a new map including all Inheritable ThreadLocals
         * from given parent map. Called only by createInheritedMap.
         * <p>
         * 私有构造方法，从 ThreadLocalMap 继承其中的值
         * 只有在需要 继承 ThreadLocalMap 的时候才会使用该方法
         *
         * @param parentMap the map associated with parent thread.
         */
        private ThreadLocalMap(ThreadLocalMap parentMap) {
            // 指向传入的 table
            Entry[] parentTable = parentMap.table;
            // 记录长度，由于 parent 的 len 一定满足 2 的 n 次幂的规则
            // 所以此处的 len 也一定满足
            int len = parentTable.length;
            // 设置阈值
            setThreshold(len);
            // 新建一个 table
            table = new Entry[len];
            // 循环存放值
            for (Entry e : parentTable) {
                if (e != null) {
                    // 获取当前的 key
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
                    // key 不为 null，说明不是 stale entry
                    if (key != null) {
                        // childValue 一般由子类实现
                        Object value = key.childValue(e.value);
                        // 根据 key value 创建一个 Entry
                        Entry c = new Entry(key, value);
                        // 计算再 table 中的位置
                        int h = key.threadLocalHashCode & (len - 1);
                        // 如果被占用了，使用线性探测法，尝试往后放
                        while (table[h] != null)
                            h = nextIndex(h, len);
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        /**
         * Get the entry associated with key.  This method
         * itself handles only the fast path: a direct hit of existing
         * key. It otherwise relays to getEntryAfterMiss.  This is
         * designed to maximize performance for direct hits, in part
         * by making this method readily inlinable.
         * <p>
         * 根据 key 获取 entry。
         * 如果直接命中，则返回，如果未命中，则调用 {@link #getEntryAfterMiss(ThreadLocal, int, Entry)}
         *
         * @param key the thread local object
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntry(ThreadLocal<?> key) {
            // 计算 key 的 hashcode
            int i = key.threadLocalHashCode & (table.length - 1);
            // 拿到对应位置上的 entry
            Entry e = table[i];
            // 如果 entry 不为 null && key == entry 中保存的 key
            if (e != null && e.get() == key)
                // 返回该 entry
                return e;
            else
                // 另外查找
                return getEntryAfterMiss(key, i, e);
        }

        /**
         * Version of getEntry method for use when key is not found in
         * its direct hash slot.
         * <p>
         * 直接查找找不到会调用该方法
         *
         * @param key the thread local object
         * @param i   the table index for key's hash code
         * @param e   the entry at table[i]
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            Entry[] tab = table;
            int len = tab.length;
            // 从 e 开始遍历查找
            while (e != null) {
                ThreadLocal<?> k = e.get();
                // 满足，则返回
                if (k == key)
                    return e;
                // key 为 null，处理 stale entry
                if (k == null)
                    // 处理 stale entry
                    expungeStaleEntry(i);
                    // 往后找
                else
                    i = nextIndex(i, len);
                // e 后移
                e = tab[i];
            }
            return null;
        }

        /**
         * Set the value associated with key.
         * <p>
         * 设置与 key 关联的 value
         *
         * @param key   the thread local object
         * @param value the value to be set
         */
        private void set(ThreadLocal<?> key, Object value) {

            // We don't use a fast path as with get() because it is at
            // least as common to use set() to create new entries as
            // it is to replace existing ones, in which case, a fast
            // path would fail more often than not.

            Entry[] tab = table;
            int len = tab.length;
            // 根据 key 的 hashcode 得到 index
            int i = key.threadLocalHashCode & (len - 1);
            // 遍历
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                // 根据 index 找到 key
                ThreadLocal<?> k = e.get();
                // 如果 key 不为 null 且与传入的 key 相等
                if (k == key) {
                    // 更新 value
                    e.value = value;
                    return;
                }
                // 如果 key 为 null
                if (k == null) {
                    // 更新 value
                    // 不是简单的设置新值，具体看 replaceStaleEntry 的实现
                    replaceStaleEntry(key, value, i);
                    return;
                }
                // 不为 null 且不等于 key
                // 否则线性探测法继续往后找
            }
            // 为 null 说明可以直接放置一个新值
            tab[i] = new Entry(key, value);
            int sz = ++size;
            // 如果没有清除，说明 size 没变，且 size > 阈值，需要 rehash
            if (!cleanSomeSlots(i, sz) && sz >= threshold)
                rehash();
        }

        /**
         * Remove the entry for key.
         * <p>
         * 根据 key，将 entry 从 table 中移除
         */
        private void remove(ThreadLocal<?> key) {
            // 存储 entry 的 table
            Entry[] tab = table;
            // table 的长度
            int len = tab.length;
            // 计算 key 在 table 中的下标
            int i = key.threadLocalHashCode & (len - 1);
            // 开始遍历，因为存在 hash 碰撞冲突
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                // 如果对应到
                if (e.get() == key) {
                    // 因为 entry 继承自 WeakReference
                    // 清理引用对象
                    e.clear();
                    // 删去 key 为 null 的 entry
                    expungeStaleEntry(i);
                    return;
                }
            }
        }

        /**
         * Replace a stale entry encountered during a set operation
         * with an entry for the specified key.  The value passed in
         * the value parameter is stored in the entry, whether or not
         * an entry already exists for the specified key.
         * <p>
         * 在调用 set 方法的时候用新的 entry 替换一个 stale entry。
         * <p>
         * As a side effect, this method expunges all stale entries in the
         * "run" containing the stale entry.  (A run is a sequence of entries
         * between two null slots.)
         *
         * @param key       the key
         * @param value     the value to be associated with key
         * @param staleSlot index of the first stale entry encountered while
         *                  searching for key. 传进来的位置(staleSlot)上的 key 为 null
         */
        private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                                       int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;
            Entry e;

            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).
            int slotToExpunge = staleSlot;
            // 往前找，尝试找到一个 stale entry
            for (int i = prevIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = prevIndex(i, len))
                // 直到找到一个 key(ThreadLocal) 为 null
                if (e.get() == null)
                    // 记录前一个 stale entry 的位置
                    slotToExpunge = i;

            // Find either the key or trailing null slot of run, whichever
            // occurs first
            // 往后找，尝试找到一个 stale entry
            for (int i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();

                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.
                // 如果不是 stale entry， key 与 传入的 key 相等
                if (k == key) {
                    // 替换
                    e.value = value;
                    // 暂存，将原来要 expunge 的 entry 暂存到 i 位置
                    tab[i] = tab[staleSlot];
                    // 将 e 设置到 staleSlot 位置
                    tab[staleSlot] = e;

                    // Start expunge at preceding stale entry if it exists
                    // 如果往前没有找到 stale entry
                    if (slotToExpunge == staleSlot)
                        // 设置准备清除的位置为 i
                        slotToExpunge = i;
                    // 开始清理某一些 stale entry
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }

                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.
                // 如果是 stale entry && 往前没有找到 stale entry
                if (k == null && slotToExpunge == staleSlot)
                    // 更新需要处理的 index
                    slotToExpunge = i;
            }

            // If key not found, put new entry in stale slot
            // 如果没有找到 key，那么就放一个新的 entry 进去
            tab[staleSlot].value = null;
            tab[staleSlot] = new Entry(key, value);

            // If there are any other stale entries in run, expunge them
            // 如果有找到 stale entry，则清理。
            if (slotToExpunge != staleSlot)
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }

        /**
         * Expunge a stale entry by rehashing any possibly colliding entries
         * lying between staleSlot and the next null slot.  This also expunges
         * any other stale entries encountered before the trailing null.  See
         * Knuth, Section 6.4
         * <p>
         * 可以解决内存泄露的问题。
         * 如果 ThreadLocal 被置为了 null（引用被销毁），那么只有 entry 这个引用会指向
         * entry 在堆上分配的内存。但是 entry 是一个 WeakReference。因此 ThreadLocal 实例很有可能被 GC。
         * 因此，entry 中的 key 变为了 null，expungeStaleEntry 可以对这种情况进行处理，将 value 置为 null。
         *
         * @param staleSlot index of slot known to have null key
         * @return the index of the next null slot after staleSlot
         * (all between staleSlot and this slot will have been checked
         * for expunging).
         */
        private int expungeStaleEntry(int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;

            // expunge entry at staleSlot
            // 指定位置的 key 和 value 置为 null
            // 这里将 staleSlot 位置已经处理完毕
            tab[staleSlot].value = null;
            tab[staleSlot] = null;
            // size - 1
            size--;

            // Rehash until we encounter null
            // 再 Hash 直到遇到 tab[i] == null
            // 清理从 staleSlot 到 tab[i] == null 中间可能遇到的 stale entry
            Entry e;
            int i;
            // 防止内存泄露
            for (i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                // 获取 key
                ThreadLocal<?> k = e.get();
                // 如果又遇到 stale entry
                // 则进行处理
                if (k == null) {
                    // 将 value 置为 null
                    // 再次处理
                    e.value = null;
                    tab[i] = null;
                    // 长度 - 1
                    size--;
                    // key 不为 null
                } else {
                    // 计算其位置
                    int h = k.threadLocalHashCode & (len - 1);
                    // 如果计算出来的位置与当前位置不同
                    // 正常情况下计算出来的 h 和 i 应该相等
                    // 如果不相等，说明是之前的哈希冲突导致的往后放了，所以需要往前移动。
                    if (h != i) {
                        // 将当前位置置为空
                        tab[i] = null;

                        // Unlike Knuth 6.4 Algorithm R, we must scan until
                        // null because multiple entries could have been stale.
                        // 放到计算出来的 null 的位置
                        // 最前面也只能到传入的 staleSlot 位置
                        while (tab[h] != null)
                            h = nextIndex(h, len);
                        tab[h] = e;
                    }
                }
            }
            return i;
        }

        /**
         * Heuristically 试探性的 scan some 一些 cells looking for stale entries.
         * This is invoked when either a new element is added, or
         * another stale one has been expunged. It performs a
         * logarithmic number of scans, as a balance between no
         * scanning (fast but retains garbage) and a number of scans
         * proportional to number of elements, that would find all
         * garbage but would cause some insertions to take O(n) time.
         * <p>
         * 会调用该方法的情况：
         * 1. 添加了新元素
         * 2. 其他 stale entry 被 expunged
         * <p>
         * 会执行对数次数的 scan，是一种折中的方案。{@code log2(n)} 个 cell 会被扫描到
         * 从传入的 i 开始清理
         *
         * @param i a position known NOT to hold a stale entry. The
         *          scan starts at the element after i.
         * @param n scan control: {@code log2(n)} cells are scanned,
         *          unless a stale entry is found, in which case
         *          {@code log2(table.length)-1} additional cells are scanned.
         *          When called from insertions, this parameter is the number
         *          of elements, but when from replaceStaleEntry, it is the
         *          table length. (Note: all this could be changed to be either
         *          more or less aggressive by weighting n instead of just
         *          using straight log n. But this version is simple, fast, and
         *          seems to work well.)
         *          扫描控制，将会扫描 {@code log2(n)} 个cell。如果一个 stale entry 被找到，
         *          那么将会有额外的 {@code log2(table.length)-1} 个 cell 会被扫描。
         *          如果从 set 方法调用，那么 n 为当前 table 中元素的个数。
         *          如果从 replaceStaleEntry，那么 n 为当前 table 的 length。
         * @return true if any stale entries have been removed. 返回是否有 stale entry 被清除
         */
        private boolean cleanSomeSlots(int i, int n) {
            boolean removed = false;
            Entry[] tab = table;
            int len = tab.length;
            do {
                // 计算 i 的下一个位置
                i = nextIndex(i, len);
                Entry e = tab[i];
                // 如果满足 stale entry 的条件
                if (e != null && e.get() == null) {
                    n = len;
                    removed = true;
                    // 额外的次数在这里计算得出
                    i = expungeStaleEntry(i);
                }
                // 最多循环 log2(n) 次
            } while ((n >>>= 1) != 0);
            // 返回是否清除
            return removed;
        }

        /**
         * Re-pack and/or re-size the table. First scan the entire
         * table removing stale entries. If this doesn't sufficiently
         * shrink the size of the table, double the table size.
         * <p>
         * table size 扩容 * 2
         */
        private void rehash() {
            // 尝试处理所有的 stale entries
            expungeStaleEntries();
            // Use lower threshold for doubling to avoid hysteresis 滞后
            // 如果当前的 size 超过了 threshold 的 3/4，则需要 resize
            if (size >= threshold - threshold / 4)
                resize();
        }

        /**
         * Double the capacity of the table.
         * <p>
         * 双倍 table 的大小
         */
        private void resize() {
            // 原来的 table
            Entry[] oldTab = table;
            // 原来的长度
            int oldLen = oldTab.length;
            // 新的长度
            int newLen = oldLen * 2;
            // 新的 table
            Entry[] newTab = new Entry[newLen];
            // 计数
            int count = 0;
            for (Entry e : oldTab) {
                if (e != null) {
                    // 获取原来的 key
                    ThreadLocal<?> k = e.get();
                    // 如果为 null，说明是个 stale entry，处理掉
                    if (k == null) {
                        e.value = null; // Help the GC
                        // 如果不为 null，则放入新的 table
                    } else {
                        // 计算再新的 table 中的位置
                        int h = k.threadLocalHashCode & (newLen - 1);
                        // 找到有个适合的位置
                        // 使用 while 是因为可能存在 hash 碰撞
                        while (newTab[h] != null)
                            h = nextIndex(h, newLen);
                        // 放进去
                        newTab[h] = e;
                        // 计数 + 1
                        count++;
                    }
                }
            }
            // 设置新的 threshold
            setThreshold(newLen);
            // 设置最新的 size
            size = count;
            // 指向新的 table
            table = newTab;
        }

        /**
         * Expunge all stale entries in the table.
         * <p>
         * 删除 table 中所有的 stale entry
         */
        private void expungeStaleEntries() {
            // 获取 table
            Entry[] tab = table;
            // tab 的长度
            int len = tab.length;
            // 遍历
            for (int j = 0; j < len; j++) {
                Entry e = tab[j];
                if (e != null && e.get() == null)
                    // 处理 stale entry
                    expungeStaleEntry(j);
            }
        }
    }
}
