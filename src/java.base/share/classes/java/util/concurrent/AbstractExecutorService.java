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

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * 实现 ExecutorService 的抽象类，提供了一些基础实现。
 * 通过使用 RunnableFuture 实现了 submit invokeAny invokeAll 这些方法。
 * <p>
 * Provides default implementations of {@link ExecutorService}
 * execution methods. This class implements the {@code submit},
 * {@code invokeAny} and {@code invokeAll} methods using a
 * {@link RunnableFuture} returned by {@code newTaskFor}, which defaults
 * to the {@link FutureTask} class provided in this package.  For example,
 * the implementation of {@code submit(Runnable)} creates an
 * associated {@code RunnableFuture} that is executed and
 * returned. Subclasses may override the {@code newTaskFor} methods
 * to return {@code RunnableFuture} implementations other than
 * {@code FutureTask}.
 *
 * <p><b>Extension example</b>. Here is a sketch of a class
 * that customizes {@link ThreadPoolExecutor} to use
 * a {@code CustomTask} class instead of the default {@code FutureTask}:
 * <pre> {@code
 * public class CustomThreadPoolExecutor extends ThreadPoolExecutor {
 *
 *   static class CustomTask<V> implements RunnableFuture<V> {...}
 *
 *   protected <V> RunnableFuture<V> newTaskFor(Callable<V> c) {
 *       return new CustomTask<V>(c);
 *   }
 *   protected <V> RunnableFuture<V> newTaskFor(Runnable r, V v) {
 *       return new CustomTask<V>(r, v);
 *   }
 *   // ... add constructors, etc.
 * }}</pre>
 *
 * @author Doug Lea
 * @since 1.5
 */
public abstract class AbstractExecutorService implements ExecutorService {

    /**
     * 将 Runnable 封装成 RunnableFuture
     * 返回一个默认的 RunnableFuture 实现，具体实现是一个 FutureTask 类型。
     * 带结果
     * <p>
     * Returns a {@code RunnableFuture} for the given runnable and default
     * value.
     *
     * @param runnable the runnable task being wrapped
     * @param value    the default value for the returned future
     * @param <T>      the type of the given value
     * @return a {@code RunnableFuture} which, when run, will run the
     * underlying runnable and which, as a {@code Future}, will yield
     * the given value as its result and provide for cancellation of
     * the underlying task
     * @since 1.6
     */
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new FutureTask<T>(runnable, value);
    }

    /**
     * 将 Callable 封装成 RunnableFuture
     * 返回一个默认的 RunnableFuture 实现，具体实现是一个 FutureTask 类型。
     * 不带结果
     * <p>
     * Returns a {@code RunnableFuture} for the given callable task.
     *
     * @param callable the callable task being wrapped
     * @param <T>      the type of the callable's result
     * @return a {@code RunnableFuture} which, when run, will call the
     * underlying callable and which, as a {@code Future}, will yield
     * the callable's result as its result and provide for
     * cancellation of the underlying task
     * @since 1.6
     */
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTask<T>(callable);
    }

    /**
     * 提交 Runnable 任务
     * 不带结果的提交
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public Future<?> submit(Runnable task) {
        if (task == null) throw new NullPointerException();
        // 将 task 封装到 RunnableFuture 中
        RunnableFuture<Void> ftask = newTaskFor(task, null);
        // 执行，具体实现由实现类提供
        execute(ftask);
        // 返回 future
        return ftask;
    }

    /**
     * 提交 Runnable 任务
     * 带结果的提交
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Runnable task, T result) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<T> ftask = newTaskFor(task, result);
        execute(ftask);
        return ftask;
    }

    /**
     * 提交 Callable 任务
     * 不带结果返回，因为 Callable 能返回执行结果
     *
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Callable<T> task) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<T> ftask = newTaskFor(task);
        execute(ftask);
        return ftask;
    }

    /**
     * 主要逻辑就是每次启动一个，如果都没有获取到，则继续启动知道所有任务都被启动。
     * <p>
     * the main mechanics of invokeAny.
     */
    private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks,
                              boolean timed, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        // 检查任务列表是否为空
        if (tasks == null)
            throw new NullPointerException();
        // 任务数量
        int ntasks = tasks.size();
        // 如果任务数量为 0，则抛异常
        if (ntasks == 0)
            throw new IllegalArgumentException();
        // Collection 转成 ArrayList
        ArrayList<Future<T>> futures = new ArrayList<>(ntasks);
        // ExecutorCompletionService，队列 + 线程池
        ExecutorCompletionService<T> ecs =
                new ExecutorCompletionService<T>(this);

        // For efficiency, especially in executors with limited
        // parallelism, check to see if previously submitted tasks are
        // done before submitting more of them. This interleaving
        // plus the exception mechanics account for messiness of main
        // loop.

        try {
            // Record exceptions so that if we fail to obtain any
            // result, we can throw the last exception we got.
            ExecutionException ee = null;
            // 如果设置了超时调用，则记录时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            // 任务迭代器
            Iterator<? extends Callable<T>> it = tasks.iterator();

            // Start one task for sure; the rest incrementally
            // 提交一个任务
            futures.add(ecs.submit(it.next()));
            // 任务数--
            --ntasks;
            // 当前活跃的任务数
            int active = 1;

            for (; ; ) {
                // 尝试获取 normal 完成的任务
                // 如果没有 normal 完成的任务，返回 null
                Future<T> f = ecs.poll();
                // 任务暂时还没有完成或者任务抛异常了
                if (f == null) {
                    // 有剩余未启动的任务
                    if (ntasks > 0) {
                        --ntasks;
                        // 继续启动任务
                        futures.add(ecs.submit(it.next()));
                        ++active;
                        // 没有活跃中的任务，说明所有任务都没有成功执行
                    } else if (active == 0)
                        break;
                        // 如果设置了超时
                    else if (timed) {
                        // 再次尝试超时获取
                        f = ecs.poll(nanos, NANOSECONDS);
                        // 如果获取不到
                        if (f == null)
                            // 抛出超时异常
                            throw new TimeoutException();
                        // 从队列中获取到了执行成功的任务
                        // 计算剩余时间
                        nanos = deadline - System.nanoTime();
                    } else
                        // 尝试获取，获取不到则一直阻塞
                        f = ecs.take();
                }
                // 有任务已经完成
                if (f != null) {
                    --active;5
                    try {
                        // 直接返回
                        return f.get();
                    } catch (ExecutionException eex) {
                        ee = eex;
                    } catch (RuntimeException rex) {
                        ee = new ExecutionException(rex);
                    }
                }
            }

            if (ee == null)
                ee = new ExecutionException();
            throw ee;

        } finally {
            // 无论如何都会走到这一步
            cancelAll(futures);
        }
    }

    /**
     * 任意一个任务完成即返回，不带超时时间
     *
     * @param tasks
     * @param <T>
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        try {
            return doInvokeAny(tasks, false, 0);
        } catch (TimeoutException cannotHappen) {
            assert false;
            return null;
        }
    }

    /**
     * 任意一个任务完成即返回，不带超时时间
     *
     * @param tasks
     * @param timeout
     * @param unit
     * @param <T>
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return doInvokeAny(tasks, true, unit.toNanos(timeout));
    }

    /**
     * 所有的任务都需要返回
     * 不带超时时间
     *
     * @param tasks
     * @param <T>
     * @return
     * @throws InterruptedException
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        if (tasks == null)
            throw new NullPointerException();
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<T> t : tasks) {
                // 封装成 RunnableFuture
                RunnableFuture<T> f = newTaskFor(t);
                // 加入到 list 中
                futures.add(f);
                // 全部执行
                execute(f);
            }
            for (int i = 0, size = futures.size(); i < size; i++) {
                // 获取执行结果
                Future<T> f = futures.get(i);
                // 检查 future 是否已经 Done
                if (!f.isDone()) {
                    try {
                        // 如果没有 Done，则尝试 get
                        // 阻塞在当前 future
                        f.get();
                    } catch (CancellationException | ExecutionException ignore) {
                        // 异常不处理
                    }
                }
            }
            // 返回所有 future 的执行结果
            return futures;
        } catch (Throwable t) {
            // 如果出异常了，则全部取消
            cancelAll(futures);
            throw t;
        }
    }

    /**
     * 所有的任务都需要返回
     * 不带超时时间
     *
     * @param tasks
     * @param timeout
     * @param unit
     * @param <T>
     * @return
     * @throws InterruptedException
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit)
            throws InterruptedException {
        if (tasks == null)
            throw new NullPointerException();
        // 超时时长 nanos
        final long nanos = unit.toNanos(timeout);
        // 计算 deadline 时间
        final long deadline = System.nanoTime() + nanos;
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        int j = 0;
        // 超时则直接走到后面的 cancel 逻辑
        timedOut:
        try {
            // 将所有的 task 加入到 list 中
            for (Callable<T> t : tasks)
                futures.add(newTaskFor(t));

            final int size = futures.size();

            // Interleave time checks and calls to execute in case
            // executor doesn't have any/much parallelism.
            for (int i = 0; i < size; i++) {
                // 检查是否超时
                if (((i == 0) ? nanos : deadline - System.nanoTime()) <= 0L)
                    break timedOut;
                // 执行所有的 task
                execute((Runnable) futures.get(i));
            }

            for (; j < size; j++) {
                Future<T> f = futures.get(j);
                // 如果 isDone，就不管了，直接看下一个 future
                // 如果还没有 Done，需要 get 一下，因为每一个 future 都必须有个结果，不管正常完成或者抛异常或者取消
                if (!f.isDone()) {
                    try {
                        // 带超时的 get
                        f.get(deadline - System.nanoTime(), NANOSECONDS);
                    } catch (CancellationException | ExecutionException ignore) {
                    } catch (TimeoutException timedOut) {
                        break timedOut;
                    }
                }
            }
            return futures;
        } catch (Throwable t) {
            cancelAll(futures);
            throw t;
        }
        // Timed out before all the tasks could be completed; cancel remaining
        cancelAll(futures, j);
        return futures;
    }

    private static <T> void cancelAll(ArrayList<Future<T>> futures) {
        cancelAll(futures, 0);
    }

    /**
     * 取消所有的任务
     * <p>
     * Cancels all futures with index at least j.
     */
    private static <T> void cancelAll(ArrayList<Future<T>> futures, int j) {
        for (int size = futures.size(); j < size; j++)
            futures.get(j).cancel(true);
    }
}
