/*
 * Copyright (c) 1997, 2019, Oracle and/or its affiliates. All rights reserved.
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

package java.util;

import jdk.internal.util.ArraysSupport;

/**
 * This class provides a skeletal implementation of the {@code Collection}
 * interface, to minimize the effort required to implement this interface. <p>
 * <p>
 * To implement an unmodifiable collection, the programmer needs only to
 * extend this class and provide implementations for the {@code iterator} and
 * {@code size} methods.  (The iterator returned by the {@code iterator}
 * method must implement {@code hasNext} and {@code next}.)<p>
 * <p>
 * To implement a modifiable collection, the programmer must additionally
 * override this class's {@code add} method (which otherwise throws an
 * {@code UnsupportedOperationException}), and the iterator returned by the
 * {@code iterator} method must additionally implement its {@code remove}
 * method.<p>
 * <p>
 * The programmer should generally provide a void (no argument) and
 * {@code Collection} constructor, as per the recommendation in the
 * {@code Collection} interface specification.<p>
 * <p>
 * The documentation for each non-abstract method in this class describes its
 * implementation in detail.  Each of these methods may be overridden if
 * the collection being implemented admits a more efficient implementation.<p>
 * <p>
 * This class is a member of the
 * <a href="{@docRoot}/java.base/java/util/package-summary.html#CollectionsFramework">
 * Java Collections Framework</a>.
 *
 * @author Josh Bloch
 * @author Neal Gafter
 * @see Collection
 * @since 1.2
 */

/**
 * 实现了 Collection 接口，提供了一些模板方法的实现
 *
 * @param <E>
 */
public abstract class AbstractCollection<E> implements Collection<E> {
    /**
     * 唯一的构造方法， protected 级别的，只允许子类调用
     * <p>
     * Sole constructor.  (For invocation by subclass constructors, typically
     * implicit.)
     */
    protected AbstractCollection() {
    }

    // Query Operations

    /**
     * 返回一个迭代器
     * <p>
     * Returns an iterator over the elements contained in this collection.
     *
     * @return an iterator over the elements contained in this collection
     */
    public abstract Iterator<E> iterator();

    /**
     * 用于记录集合中元素的个数
     *
     * @return
     */
    public abstract int size();

    /**
     * 返回集合元素个数是否为空
     * <p>
     * {@inheritDoc}
     *
     * @implSpec This implementation returns {@code size() == 0}.
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 返回集合中是否某个元素
     * <p>
     * {@inheritDoc}
     *
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     * @implSpec This implementation iterates over the elements in the collection,
     * checking each element in turn for equality with the specified element.
     */
    public boolean contains(Object o) {
        Iterator<E> it = iterator();
        if (o == null) {
            // 遍历
            while (it.hasNext())
                if (it.next() == null)
                    return true;
        } else {
            // 遍历
            while (it.hasNext())
                if (o.equals(it.next()))
                    return true;
        }
        // 不包含，返回 false
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @implSpec This implementation returns an array containing all the elements
     * returned by this collection's iterator, in the same order, stored in
     * consecutive elements of the array, starting with index {@code 0}.
     * The length of the returned array is equal to the number of elements
     * returned by the iterator, even if the size of this collection changes
     * during iteration, as might happen if the collection permits
     * concurrent modification during iteration.  The {@code size} method is
     * called only as an optimization hint; the correct result is returned
     * even if the iterator returns a different number of elements.
     *
     * <p>This method is equivalent to:
     *
     * <pre> {@code
     * List<E> list = new ArrayList<E>(size());
     * for (E e : this)
     *     list.add(e);
     * return list.toArray();
     * }</pre>
     */
    public Object[] toArray() {
        // Estimate size of array; be prepared to see more or fewer elements
        Object[] r = new Object[size()];
        Iterator<E> it = iterator();
        for (int i = 0; i < r.length; i++) {
            // 在遍历的时候 Collection 并发修改了
            // 该情况为 Collection 中的元素减少了
            if (!it.hasNext()) // fewer elements than expected
                // 返回一个新的数组
                return Arrays.copyOf(r, i);
            r[i] = it.next();
        }
        // 该情况为 Collection 中元素的个数增加了
        return it.hasNext() ? finishToArray(r, it) : r;
    }

    /**
     * {@inheritDoc}
     *
     * @throws ArrayStoreException  {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     * @implSpec This implementation returns an array containing all the elements
     * returned by this collection's iterator in the same order, stored in
     * consecutive elements of the array, starting with index {@code 0}.
     * If the number of elements returned by the iterator is too large to
     * fit into the specified array, then the elements are returned in a
     * newly allocated array with length equal to the number of elements
     * returned by the iterator, even if the size of this collection
     * changes during iteration, as might happen if the collection permits
     * concurrent modification during iteration.  The {@code size} method is
     * called only as an optimization hint; the correct result is returned
     * even if the iterator returns a different number of elements.
     *
     * <p>This method is equivalent to:
     *
     * <pre> {@code
     * List<E> list = new ArrayList<E>(size());
     * for (E e : this)
     *     list.add(e);
     * return list.toArray(a);
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        // Estimate size of array; be prepared to see more or fewer elements
        // 当前时间点的元素的个数
        int size = size();
        // 如果传入的数组大于 size ，则直接将引用指向传入的数组
        // 否则通过反射新创建一个数组
        T[] r = a.length >= size ? a :
                // 通过反射获取到 T 运行时的类型
                (T[]) java.lang.reflect.Array
                        .newInstance(a.getClass().getComponentType(), size);
        // 迭代器
        Iterator<E> it = iterator();
        // 遍历过程中同样需要检测 Collection 集合中的元素是否发生变化
        for (int i = 0; i < r.length; i++) {
            if (!it.hasNext()) { // fewer elements than expected
                if (a == r) {
                    r[i] = null; // null-terminate
                } else if (a.length < i) {
                    return Arrays.copyOf(r, i);
                } else {
                    System.arraycopy(r, 0, a, 0, i);
                    if (a.length > i) {
                        a[i] = null;
                    }
                }
                return a;
            }
            r[i] = (T) it.next();
        }
        // more elements than expected
        return it.hasNext() ? finishToArray(r, it) : r;
    }

    /**
     * 当调用到这个方法的时候说明在 ToArray 遍历过程中，Collection 中又加进了新的元素。
     * <p>
     * Reallocates the array being used within toArray when the iterator
     * returned more elements than expected, and finishes filling it from
     * the iterator.
     *
     * @param r  the array, replete with previously stored elements
     * @param it the in-progress iterator over this collection
     * @return array containing the elements in the given array, plus any
     * further elements returned by the iterator, trimmed to size
     */
    @SuppressWarnings("unchecked")
    private static <T> T[] finishToArray(T[] r, Iterator<?> it) {
        // 之前的数组的长度
        int len = r.length;
        int i = len;
        // 继续遍历
        while (it.hasNext()) {
            // 数组扩容
            if (i == len) {
                len = ArraysSupport.newLength(len,
                        1,             /* minimum growth */
                        (len >> 1) + 1 /* preferred growth */);
                // 元素拷贝到新数组
                r = Arrays.copyOf(r, len);
            }
            // 放进去
            r[i++] = (T) it.next();
        }
        // trim if overallocated
        // 数组是否有变动，无变动则返回之前传入的数组，有变动则返回一个新的数组
        return (i == len) ? r : Arrays.copyOf(r, i);
    }

    // Modification Operations

    /**
     * 抛出异常，说明子类必须得重写该方法，不能直接用。
     * <p>
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     * @throws IllegalStateException         {@inheritDoc}
     * @implSpec This implementation always throws an
     * {@code UnsupportedOperationException}.
     */
    public boolean add(E e) {
        throw new UnsupportedOperationException();
    }

    /**
     * 使用 Iterator 遍历
     * 提供默认的 remove 实现，从前往后遍历，找到则移除。
     * <p>
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @implSpec This implementation iterates over the collection looking for the
     * specified element.  If it finds the element, it removes the element
     * from the collection using the iterator's remove method.
     *
     * <p>Note that this implementation throws an
     * {@code UnsupportedOperationException} if the iterator returned by this
     * collection's iterator method does not implement the {@code remove}
     * method and this collection contains the specified object.
     */
    public boolean remove(Object o) {
        Iterator<E> it = iterator();
        if (o == null) {
            while (it.hasNext()) {
                if (it.next() == null) {
                    it.remove();
                    return true;
                }
            }
        } else {
            while (it.hasNext()) {
                if (o.equals(it.next())) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }


    // Bulk Operations

    /**
     * 提供默认的 containsAll 实现
     * 对传入的没个元素遍历调用 contains 方法判断是否包含
     * <p>
     * {@inheritDoc}
     *
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     * @implSpec This implementation iterates over the specified collection,
     * checking each element returned by the iterator in turn to see
     * if it's contained in this collection.  If all elements are so
     * contained {@code true} is returned, otherwise {@code false}.
     * @see #contains(Object)
     */
    public boolean containsAll(Collection<?> c) {
        for (Object e : c)
            if (!contains(e))
                return false;
        return true;
    }

    /**
     * 提供默认的 addAll 实现
     * 调用 add 方法进行添加
     * 返回 Collection 是否被修改过，如果被修改过，就返回 true
     * <p>
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     * @throws IllegalStateException         {@inheritDoc}
     * @implSpec This implementation iterates over the specified collection, and adds
     * each object returned by the iterator to this collection, in turn.
     *
     * <p>Note that this implementation will throw an
     * {@code UnsupportedOperationException} unless {@code add} is
     * overridden (assuming the specified collection is non-empty).
     * @see #add(Object)
     */
    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for (E e : c)
            if (add(e))
                modified = true;
        return modified;
    }

    /**
     * 将当前 Collection 中存在于传入 Collection 集合中的元素全部删除
     * 如果又发生过删除，则返回 true
     * <p>
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @implSpec This implementation iterates over this collection, checking each
     * element returned by the iterator in turn to see if it's contained
     * in the specified collection.  If it's so contained, it's removed from
     * this collection with the iterator's {@code remove} method.
     *
     * <p>Note that this implementation will throw an
     * {@code UnsupportedOperationException} if the iterator returned by the
     * {@code iterator} method does not implement the {@code remove} method
     * and this collection contains one or more elements in common with the
     * specified collection.
     * @see #remove(Object)
     * @see #contains(Object)
     */
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        Iterator<?> it = iterator();
        while (it.hasNext()) {
            // 调用传入 Collection 的 contains 方法
            if (c.contains(it.next())) {
                it.remove();
                modified = true;
            }
        }
        return modified;
    }

    /**
     * 保留存在于给定 Collection 中的元素
     * <p>
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @implSpec This implementation iterates over this collection, checking each
     * element returned by the iterator in turn to see if it's contained
     * in the specified collection.  If it's not so contained, it's removed
     * from this collection with the iterator's {@code remove} method.
     *
     * <p>Note that this implementation will throw an
     * {@code UnsupportedOperationException} if the iterator returned by the
     * {@code iterator} method does not implement the {@code remove} method
     * and this collection contains one or more elements not present in the
     * specified collection.
     * @see #remove(Object)
     * @see #contains(Object)
     */
    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        Iterator<E> it = iterator();
        while (it.hasNext()) {
            // 不在给定的 Collection 中，则移除
            if (!c.contains(it.next())) {
                it.remove();
                // 只要移除了，就标记为 true
                modified = true;
            }
        }
        return modified;
    }

    /**
     * 删除所有的元素
     * <p>
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException {@inheritDoc}
     * @implSpec This implementation iterates over this collection, removing each
     * element using the {@code Iterator.remove} operation.  Most
     * implementations will probably choose to override this method for
     * efficiency.
     *
     * <p>Note that this implementation will throw an
     * {@code UnsupportedOperationException} if the iterator returned by this
     * collection's {@code iterator} method does not implement the
     * {@code remove} method and this collection is non-empty.
     */
    public void clear() {
        Iterator<E> it = iterator();
        while (it.hasNext()) {
            it.next();
            it.remove();
        }
    }


    //  String conversion

    /**
     * 格式化输出 Collection 的元素
     * <p>
     * Returns a string representation of this collection.  The string
     * representation consists of a list of the collection's elements in the
     * order they are returned by its iterator, enclosed in square brackets
     * ({@code "[]"}).  Adjacent elements are separated by the characters
     * {@code ", "} (comma and space).  Elements are converted to strings as
     * by {@link String#valueOf(Object)}.
     *
     * @return a string representation of this collection
     */
    public String toString() {
        Iterator<E> it = iterator();
        if (!it.hasNext())
            return "[]";

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (; ; ) {
            E e = it.next();
            sb.append(e == this ? "(this Collection)" : e);
            if (!it.hasNext())
                return sb.append(']').toString();
            sb.append(',').append(' ');
        }
    }

}
