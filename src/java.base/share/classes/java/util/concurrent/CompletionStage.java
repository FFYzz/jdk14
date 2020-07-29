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

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 描述 "完成阶段" 的接口定义
 * <p>
 * A stage of a possibly asynchronous computation, that performs an
 * action or computes a value when another CompletionStage completes.
 * A stage completes upon termination of its computation, but this may
 * in turn trigger other dependent stages.  The functionality defined
 * in this interface takes only a few basic forms, which expand out to
 * a larger set of methods to capture a range of usage styles:
 *
 * <ul>
 * 按阶段执行，先1后2然后3。
 * Function/Consumer/Runnable 用于处理过程，compose 用于得到结果。
 *
 * <li>The computation performed by a stage may be expressed as a
 * Function, Consumer, or Runnable (using methods with names including
 * <em>apply</em>, <em>accept</em>, or <em>run</em>, respectively)
 * depending on whether it requires arguments and/or produces results.
 * For example:
 * <pre> {@code
 * stage.thenApply(x -> square(x))
 *      .thenAccept(x -> System.out.print(x))
 *      .thenRun(() -> System.out.println());}</pre>
 * <p>
 * An additional form (<em>compose</em>) allows the construction of
 * computation pipelines from functions returning completion stages.
 * <p>
 * 一个 stage 的输出是下一个 stage 的输入参数
 *
 * <p>Any argument to a stage's computation is the outcome of a
 * triggering stage's computation.
 * <p>
 * 一个 stage 的执行可能是由于前面一个或者几个 stage 完成触发的。
 * 命名规范上。
 * 1. 由一个 stage 触发的以前缀 then 命名。
 * 2. 如果由两个 stage 触发，则带上 combine。
 *
 * <li>One stage's execution may be triggered by completion of a
 * single stage, or both of two stages, or either of two stages.
 * Dependencies on a single stage are arranged using methods with
 * prefix <em>then</em>. Those triggered by completion of
 * <em>both</em> of two stages may <em>combine</em> their results or
 * effects, using correspondingly named methods. Those triggered by
 * <em>either</em> of two stages make no guarantees about which of the
 * results or effects are used for the dependent stage's computation.
 * <p>
 * 多个 stage 之间的依赖控制计算的触发
 * 新 stage 的执行有以下三种方式组织
 * 1. default execution
 * 2. default asynchronous execution 方法名以后缀 async 结尾。
 * 3. custom 自定义
 *
 * <li>Dependencies among stages control the triggering of
 * computations, but do not otherwise guarantee any particular
 * ordering. Additionally, execution of a new stage's computations may
 * be arranged in any of three ways: default execution, default
 * asynchronous execution (using methods with suffix <em>async</em>
 * that employ the stage's default asynchronous execution facility),
 * or custom (via a supplied {@link Executor}).  The execution
 * properties of default and async modes are specified by
 * CompletionStage implementations, not this interface. Methods with
 * explicit Executor arguments may have arbitrary execution
 * properties, and might not even support concurrent execution, but
 * are arranged for processing in a way that accommodates asynchrony.
 * <p>
 * handle 和 whenComplete 方法不管前置 stage 正常完成或者出现异常，
 * 都无条件支持后续的计算。
 * exceptionally 方法只有当前置 stage 出现异常时才会计算，会计算得到一个
 * replacement result，与 catch 关键字的功能类似。
 * 其他情况下，如果前置 stage 发生了 unchecked exception 或者 error，
 * 那么所有后续相关的 stage 会以 exceptionally complete，抛出 CompletionException。
 *
 * <li>Two method forms ({@link #handle handle} and {@link
 * #whenComplete whenComplete}) support unconditional computation
 * whether the triggering stage completed normally or exceptionally.
 * Method {@link #exceptionally exceptionally} supports computation
 * only when the triggering stage completes exceptionally, computing a
 * replacement result, similarly to the java {@code catch} keyword.
 * In all other cases, if a stage's computation terminates abruptly
 * with an (unchecked) exception or error, then all dependent stages
 * requiring its completion complete exceptionally as well, with a
 * {@link CompletionException} holding the exception as its cause.  If
 * a stage is dependent on <em>both</em> of two stages, and both
 * complete exceptionally, then the CompletionException may correspond
 * to either one of these exceptions.  If a stage is dependent on
 * <em>either</em> of two others, and only one of them completes
 * exceptionally, no guarantees are made about whether the dependent
 * stage completes normally or exceptionally. In the case of method
 * {@code whenComplete}, when the supplied action itself encounters an
 * exception, then the stage completes exceptionally with this
 * exception unless the source stage also completed exceptionally, in
 * which case the exceptional completion from the source stage is
 * given preference and propagated to the dependent stage.
 *
 * </ul>
 *
 * <p>All methods adhere to the above triggering, execution, and
 * exceptional completion specifications (which are not repeated in
 * individual method specifications). Additionally, while arguments
 * used to pass a completion result (that is, for parameters of type
 * {@code T}) for methods accepting them may be null, passing a null
 * value for any other parameter will result in a {@link
 * NullPointerException} being thrown.
 * <p>
 * handle 是最常用的处理方法，计算一个 arbitrary result
 * whenComplete 类似，也比较常用，只是他会保留前一个 stage 计算得到的结果，而不是新计算一个。
 *
 * <p>Method form {@link #handle handle} is the most general way of
 * creating a continuation stage, unconditionally performing a
 * computation that is given both the result and exception (if any) of
 * the triggering CompletionStage, and computing an arbitrary result.
 * Method {@link #whenComplete whenComplete} is similar, but preserves
 * the result of the triggering stage instead of computing a new one.
 * Because a stage's normal result may be {@code null}, both methods
 * should have a computation structured thus:
 * <p>
 * 因为 stage 的计算结果可能为 null，所以最佳实践应该加上 null 判断。
 *
 * <pre>{@code (result, exception) -> {
 *   if (exception == null) {
 *     // triggering stage completed normally
 *   } else {
 *     // triggering stage completed exceptionally
 *   }
 * }}</pre>
 *
 * <p>This interface does not define methods for initially creating,
 * forcibly completing normally or exceptionally, probing completion
 * status or results, or awaiting completion of a stage.
 * Implementations of CompletionStage may provide means of achieving
 * such effects, as appropriate.  Method {@link #toCompletableFuture}
 * enables interoperability among different implementations of this
 * interface by providing a common conversion type.
 *
 * @author Doug Lea
 * @since 1.8
 */
public interface CompletionStage<T> {

    /**
     * 传入一个 Function，一个输入，一个输出。
     * 将当前 stage 的 function 的输出作为下一个 CompletionStage 的输入
     * 与调用线程同步执行同步
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, is executed with this stage's result as the argument
     * to the supplied function.
     *
     * <p>This method is analogous to
     * {@link java.util.Optional#map Optional.map} and
     * {@link java.util.stream.Stream#map Stream.map}.
     *
     * <p>See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param fn  the function to use to compute the value of the
     *            returned CompletionStage
     * @param <U> the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> fn);

    /**
     * 传入一个 Function，fn 的输出作为 下一个 CompletionStage 的输入
     * 与调用线程异步执行
     * 使用默认的 Exector
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, is executed using this stage's default asynchronous
     * execution facility, with this stage's result as the argument to
     * the supplied function.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param fn  the function to use to compute the value of the
     *            returned CompletionStage
     * @param <U> the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> thenApplyAsync
    (Function<? super T, ? extends U> fn);

    /**
     * 传入一个 Function，fn 的输出作为 下一个 CompletionStage 的输入
     * 与调用线程异步执行
     * 可以指定 Executor
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, is executed using the supplied Executor, with this
     * stage's result as the argument to the supplied function.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param fn       the function to use to compute the value of the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @param <U>      the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> thenApplyAsync
    (Function<? super T, ? extends U> fn,
     Executor executor);

    /**
     * 返回一个新的 CompletionStage
     * 与调用线程同步执行，执行一个消费动作
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, is executed with this stage's result as the argument
     * to the supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> thenAccept(Consumer<? super T> action);

    /**
     * 返回一个新的 CompletionStage
     * 与调用线程异步执行，执行一个消费动作
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, is executed using this stage's default asynchronous
     * execution facility, with this stage's result as the argument to
     * the supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action);

    /**
     * 返回一个新的 CompletionStage
     * 与调用线程异步执行，执行一个消费动作
     * 以给定的 supplied Executor 来执行任务
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, is executed using the supplied Executor, with this
     * stage's result as the argument to the supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param action   the action to perform before completing the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletionStage
     */
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action,
                                                 Executor executor);

    /**
     * 返回一个新的 CompletionStage
     * 与调用线程同步执行，执行一个 Runnable 任务
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, executes the given action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> thenRun(Runnable action);

    /**
     * 返回一个新的 CompletionStage
     * 与调用线程异步执行，执行一个 Runnable 任务
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, executes the given action using this stage's default
     * asynchronous execution facility.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> thenRunAsync(Runnable action);

    /**
     * 返回一个新的 CompletionStage
     * 与调用线程异步执行，执行一个 Runnable 任务
     * 可以指定 Executor
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * normally, executes the given action using the supplied Executor.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param action   the action to perform before completing the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletionStage
     */
    public CompletionStage<Void> thenRunAsync(Runnable action,
                                              Executor executor);

    /**
     * 将两个 CompletionStage 的执行结果作为参数传入到 BiFunction 进行执行
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, is executed with the two
     * results as arguments to the supplied function.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other the other CompletionStage
     * @param fn    the function to use to compute the value of the
     *              returned CompletionStage
     * @param <U>   the type of the other CompletionStage's result
     * @param <V>   the function's return type
     * @return the new CompletionStage
     */
    public <U, V> CompletionStage<V> thenCombine
    (CompletionStage<? extends U> other,
     BiFunction<? super T, ? super U, ? extends V> fn);

    /**
     * 将两个 CompletionStage 的执行结果作为参数传入到 BiFunction 进行执行
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, is executed using this
     * stage's default asynchronous execution facility, with the two
     * results as arguments to the supplied function.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other the other CompletionStage
     * @param fn    the function to use to compute the value of the
     *              returned CompletionStage
     * @param <U>   the type of the other CompletionStage's result
     * @param <V>   the function's return type
     * @return the new CompletionStage
     */
    public <U, V> CompletionStage<V> thenCombineAsync
    (CompletionStage<? extends U> other,
     BiFunction<? super T, ? super U, ? extends V> fn);

    /**
     * 将两个 CompletionStage 的执行结果作为参数传入到 BiFunction 进行执行
     * 与调用线程异步执行
     * 以给定的 Executor 执行
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, is executed using the
     * supplied executor, with the two results as arguments to the
     * supplied function.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other    the other CompletionStage
     * @param fn       the function to use to compute the value of the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @param <U>      the type of the other CompletionStage's result
     * @param <V>      the function's return type
     * @return the new CompletionStage
     */
    public <U, V> CompletionStage<V> thenCombineAsync
    (CompletionStage<? extends U> other,
     BiFunction<? super T, ? super U, ? extends V> fn,
     Executor executor);

    /**
     * 将两个 CompletionStage 的执行结果作为参数传入到 BiConsumer 进行消费
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, is executed with the two
     * results as arguments to the supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other  the other CompletionStage
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @param <U>    the type of the other CompletionStage's result
     * @return the new CompletionStage
     */
    public <U> CompletionStage<Void> thenAcceptBoth
    (CompletionStage<? extends U> other,
     BiConsumer<? super T, ? super U> action);

    /**
     * 将两个 CompletionStage 的执行结果作为参数传入到 BiConsumer 进行消费
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, is executed using this
     * stage's default asynchronous execution facility, with the two
     * results as arguments to the supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other  the other CompletionStage
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @param <U>    the type of the other CompletionStage's result
     * @return the new CompletionStage
     */
    public <U> CompletionStage<Void> thenAcceptBothAsync
    (CompletionStage<? extends U> other,
     BiConsumer<? super T, ? super U> action);

    /**
     * 将两个 CompletionStage 的执行结果作为参数传入到 BiConsumer 进行消费
     * 与调用线程异步执行
     * 以给定的 Executor 执行
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, is executed using the
     * supplied executor, with the two results as arguments to the
     * supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other    the other CompletionStage
     * @param action   the action to perform before completing the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @param <U>      the type of the other CompletionStage's result
     * @return the new CompletionStage
     */
    public <U> CompletionStage<Void> thenAcceptBothAsync
    (CompletionStage<? extends U> other,
     BiConsumer<? super T, ? super U> action,
     Executor executor);

    /**
     * 当给定的两个 CompletionStage 都执行完毕之后会执行 Runnable
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, executes the given action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other  the other CompletionStage
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> runAfterBoth(CompletionStage<?> other,
                                              Runnable action);

    /**
     * 当给定的两个 CompletionStage 都执行完毕之后会执行 Runnable
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, executes the given action
     * using this stage's default asynchronous execution facility.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other  the other CompletionStage
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other,
                                                   Runnable action);

    /**
     * 当给定的两个 CompletionStage 都执行完毕之后会执行 Runnable
     * 与调用线程异步执行
     * 可以指定线程池
     * <p>
     * Returns a new CompletionStage that, when this and the other
     * given stage both complete normally, executes the given action
     * using the supplied executor.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other    the other CompletionStage
     * @param action   the action to perform before completing the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletionStage
     */
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other,
                                                   Runnable action,
                                                   Executor executor);

    /**
     * 两个 CompletionStage 无论哪个先完成，就将哪个 CompletionStage 的结果作为
     * Function 的输入进行计算
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, is executed with the
     * corresponding result as argument to the supplied function.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other the other CompletionStage
     * @param fn    the function to use to compute the value of the
     *              returned CompletionStage
     * @param <U>   the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> applyToEither
    (CompletionStage<? extends T> other,
     Function<? super T, U> fn);

    /**
     * 两个 CompletionStage 无论哪个先完成，就将哪个 CompletionStage 的结果作为
     * Function 的输入进行计算
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, is executed using this
     * stage's default asynchronous execution facility, with the
     * corresponding result as argument to the supplied function.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other the other CompletionStage
     * @param fn    the function to use to compute the value of the
     *              returned CompletionStage
     * @param <U>   the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> applyToEitherAsync
    (CompletionStage<? extends T> other,
     Function<? super T, U> fn);

    /**
     * 两个 CompletionStage 无论哪个先完成，就将哪个 CompletionStage 的结果作为
     * Function 的输入进行计算
     * 与调用线程异步执行
     * 支持指定 Executor
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, is executed using the
     * supplied executor, with the corresponding result as argument to
     * the supplied function.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other    the other CompletionStage
     * @param fn       the function to use to compute the value of the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @param <U>      the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> applyToEitherAsync
    (CompletionStage<? extends T> other,
     Function<? super T, U> fn,
     Executor executor);

    /**
     * 两个 CompletionStage 只要任意一个执行完成，执行的结果作为 Consumer 的输入进行消费
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, is executed with the
     * corresponding result as argument to the supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other  the other CompletionStage
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> acceptEither
    (CompletionStage<? extends T> other,
     Consumer<? super T> action);

    /**
     * 两个 CompletionStage 只要任意一个执行完成，执行的结果作为 Consumer 的输入进行消费
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, is executed using this
     * stage's default asynchronous execution facility, with the
     * corresponding result as argument to the supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other  the other CompletionStage
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> acceptEitherAsync
    (CompletionStage<? extends T> other,
     Consumer<? super T> action);

    /**
     * 两个 CompletionStage 只要任意一个执行完成，执行的结果作为 Consumer 的输入进行消费
     * 与调用线程异步执行
     * 支持指定 Executor
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, is executed using the
     * supplied executor, with the corresponding result as argument to
     * the supplied action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other    the other CompletionStage
     * @param action   the action to perform before completing the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletionStage
     */
    public CompletionStage<Void> acceptEitherAsync
    (CompletionStage<? extends T> other,
     Consumer<? super T> action,
     Executor executor);

    /**
     * 任意一个 CompletionStage 执行完毕之后就会执行 action
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, executes the given action.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other  the other CompletionStage
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> runAfterEither(CompletionStage<?> other,
                                                Runnable action);

    /**
     * 任意一个 CompletionStage 执行完毕之后就会执行 action
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, executes the given action
     * using this stage's default asynchronous execution facility.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other  the other CompletionStage
     * @param action the action to perform before completing the
     *               returned CompletionStage
     * @return the new CompletionStage
     */
    public CompletionStage<Void> runAfterEitherAsync
    (CompletionStage<?> other,
     Runnable action);

    /**
     * 任意一个 CompletionStage 执行完毕之后就会执行 action
     * 与调用线程异步执行
     * 支持指定 Executor
     * <p>
     * Returns a new CompletionStage that, when either this or the
     * other given stage complete normally, executes the given action
     * using the supplied executor.
     * <p>
     * See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param other    the other CompletionStage
     * @param action   the action to perform before completing the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletionStage
     */
    public CompletionStage<Void> runAfterEitherAsync
    (CompletionStage<?> other,
     Runnable action,
     Executor executor);

    /**
     * 实现流水线操作，第一个 CompletionStage 执行的结果作为第二个 CompletionStage 的输入
     * 与调用线程同步
     * <p>
     * Returns a new CompletionStage that is completed with the same
     * value as the CompletionStage returned by the given function.
     *
     * <p>When this stage completes normally, the given function is
     * invoked with this stage's result as the argument, returning
     * another CompletionStage.  When that stage completes normally,
     * the CompletionStage returned by this method is completed with
     * the same value.
     *
     * <p>To ensure progress, the supplied function must arrange
     * eventual completion of its result.
     *
     * <p>This method is analogous to
     * {@link java.util.Optional#flatMap Optional.flatMap} and
     * {@link java.util.stream.Stream#flatMap Stream.flatMap}.
     *
     * <p>See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param fn  the function to use to compute another CompletionStage
     * @param <U> the type of the returned CompletionStage's result
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> thenCompose
    (Function<? super T, ? extends CompletionStage<U>> fn);

    /**
     * 实现流水线操作，第一个 CompletionStage 执行的结果作为第二个 CompletionStage 的输入
     * 与调用线程异步
     * <p>
     * Returns a new CompletionStage that is completed with the same
     * value as the CompletionStage returned by the given function,
     * executed using this stage's default asynchronous execution
     * facility.
     *
     * <p>When this stage completes normally, the given function is
     * invoked with this stage's result as the argument, returning
     * another CompletionStage.  When that stage completes normally,
     * the CompletionStage returned by this method is completed with
     * the same value.
     *
     * <p>To ensure progress, the supplied function must arrange
     * eventual completion of its result.
     *
     * <p>See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param fn  the function to use to compute another CompletionStage
     * @param <U> the type of the returned CompletionStage's result
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> thenComposeAsync
    (Function<? super T, ? extends CompletionStage<U>> fn);

    /**
     * 实现流水线操作，第一个 CompletionStage 执行的结果作为第二个 CompletionStage 的输入
     * 与调用线程异步
     * 支持指定 Executor
     * <p>
     * Returns a new CompletionStage that is completed with the same
     * value as the CompletionStage returned by the given function,
     * executed using the supplied Executor.
     *
     * <p>When this stage completes normally, the given function is
     * invoked with this stage's result as the argument, returning
     * another CompletionStage.  When that stage completes normally,
     * the CompletionStage returned by this method is completed with
     * the same value.
     *
     * <p>To ensure progress, the supplied function must arrange
     * eventual completion of its result.
     *
     * <p>See the {@link CompletionStage} documentation for rules
     * covering exceptional completion.
     *
     * @param fn       the function to use to compute another CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @param <U>      the type of the returned CompletionStage's result
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> thenComposeAsync
    (Function<? super T, ? extends CompletionStage<U>> fn,
     Executor executor);

    /**
     * handle 方法与 thenApply 方法类似，不过 handle 还能处理 exception
     * 将当前 CompletionStage 执行完毕的结果作为输入输入到 下一个 CompletionStage 中
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * either normally or exceptionally, is executed with this stage's
     * result and exception as arguments to the supplied function.
     *
     * <p>When this stage is complete, the given function is invoked
     * with the result (or {@code null} if none) and the exception (or
     * {@code null} if none) of this stage as arguments, and the
     * function's result is used to complete the returned stage.
     *
     * @param fn  the function to use to compute the value of the
     *            returned CompletionStage
     * @param <U> the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> handle
    (BiFunction<? super T, Throwable, ? extends U> fn);

    /**
     * handle 方法与 thenApply 方法类似，不过 handle 还能处理 exception
     * 将当前 CompletionStage 执行完毕的结果作为输入输入到 下一个 CompletionStage 中
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * either normally or exceptionally, is executed using this stage's
     * default asynchronous execution facility, with this stage's
     * result and exception as arguments to the supplied function.
     *
     * <p>When this stage is complete, the given function is invoked
     * with the result (or {@code null} if none) and the exception (or
     * {@code null} if none) of this stage as arguments, and the
     * function's result is used to complete the returned stage.
     *
     * @param fn  the function to use to compute the value of the
     *            returned CompletionStage
     * @param <U> the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> handleAsync
    (BiFunction<? super T, Throwable, ? extends U> fn);

    /**
     * handle 方法与 thenApply 方法类似，不过 handle 还能处理 exception
     * 将当前 CompletionStage 执行完毕的结果作为输入输入到 下一个 CompletionStage 中
     * 与调用线程异步执行
     * 支持指定 Executor
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * either normally or exceptionally, is executed using the
     * supplied executor, with this stage's result and exception as
     * arguments to the supplied function.
     *
     * <p>When this stage is complete, the given function is invoked
     * with the result (or {@code null} if none) and the exception (or
     * {@code null} if none) of this stage as arguments, and the
     * function's result is used to complete the returned stage.
     *
     * @param fn       the function to use to compute the value of the
     *                 returned CompletionStage
     * @param executor the executor to use for asynchronous execution
     * @param <U>      the function's return type
     * @return the new CompletionStage
     */
    public <U> CompletionStage<U> handleAsync
    (BiFunction<? super T, Throwable, ? extends U> fn,
     Executor executor);

    /**
     * 返回一个 CompletionStage
     * 当前 CompletionStage 完成的时候会执行 BiConsumer，BiConsumer 中可以处理异常情况
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage with the same result or exception as
     * this stage, that executes the given action when this stage completes.
     *
     * <p>When this stage is complete, the given action is invoked
     * with the result (or {@code null} if none) and the exception (or
     * {@code null} if none) of this stage as arguments.  The returned
     * stage is completed when the action returns.
     *
     * <p>Unlike method {@link #handle handle},
     * this method is not designed to translate completion outcomes,
     * so the supplied action should not throw an exception. However,
     * if it does, the following rules apply: if this stage completed
     * normally but the supplied action throws an exception, then the
     * returned stage completes exceptionally with the supplied
     * action's exception. Or, if this stage completed exceptionally
     * and the supplied action throws an exception, then the returned
     * stage completes exceptionally with this stage's exception.
     *
     * @param action the action to perform
     * @return the new CompletionStage
     */
    public CompletionStage<T> whenComplete
    (BiConsumer<? super T, ? super Throwable> action);

    /**
     * 返回一个 CompletionStage
     * 当前 CompletionStage 完成的时候会执行 BiConsumer，BiConsumer 中可以处理异常情况
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage with the same result or exception as
     * this stage, that executes the given action using this stage's
     * default asynchronous execution facility when this stage completes.
     *
     * <p>When this stage is complete, the given action is invoked with the
     * result (or {@code null} if none) and the exception (or {@code null}
     * if none) of this stage as arguments.  The returned stage is completed
     * when the action returns.
     *
     * <p>Unlike method {@link #handleAsync(BiFunction) handleAsync},
     * this method is not designed to translate completion outcomes,
     * so the supplied action should not throw an exception. However,
     * if it does, the following rules apply: If this stage completed
     * normally but the supplied action throws an exception, then the
     * returned stage completes exceptionally with the supplied
     * action's exception. Or, if this stage completed exceptionally
     * and the supplied action throws an exception, then the returned
     * stage completes exceptionally with this stage's exception.
     *
     * @param action the action to perform
     * @return the new CompletionStage
     */
    public CompletionStage<T> whenCompleteAsync
    (BiConsumer<? super T, ? super Throwable> action);

    /**
     * 返回一个 CompletionStage
     * 当前 CompletionStage 完成的时候会执行 BiConsumer，BiConsumer 中可以处理异常情况
     * 与调用线程异步执行
     * 可以指定 Executor
     * <p>
     * Returns a new CompletionStage with the same result or exception as
     * this stage, that executes the given action using the supplied
     * Executor when this stage completes.
     *
     * <p>When this stage is complete, the given action is invoked with the
     * result (or {@code null} if none) and the exception (or {@code null}
     * if none) of this stage as arguments.  The returned stage is completed
     * when the action returns.
     *
     * <p>Unlike method {@link #handleAsync(BiFunction, Executor) handleAsync},
     * this method is not designed to translate completion outcomes,
     * so the supplied action should not throw an exception. However,
     * if it does, the following rules apply: If this stage completed
     * normally but the supplied action throws an exception, then the
     * returned stage completes exceptionally with the supplied
     * action's exception. Or, if this stage completed exceptionally
     * and the supplied action throws an exception, then the returned
     * stage completes exceptionally with this stage's exception.
     *
     * @param action   the action to perform
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletionStage
     */
    public CompletionStage<T> whenCompleteAsync
    (BiConsumer<? super T, ? super Throwable> action,
     Executor executor);

    /**
     * 当前 CompletionStage 执行抛异常的时候会返回一个处理异常的 CompletionStage
     * 与调用线程同步执行
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * exceptionally, is executed with this stage's exception as the
     * argument to the supplied function.  Otherwise, if this stage
     * completes normally, then the returned stage also completes
     * normally with the same value.
     *
     * @param fn the function to use to compute the value of the
     *           returned CompletionStage if this CompletionStage completed
     *           exceptionally
     * @return the new CompletionStage
     */
    public CompletionStage<T> exceptionally
    (Function<Throwable, ? extends T> fn);

    /**
     * 当前 CompletionStage 执行抛异常的时候会返回一个处理异常的 CompletionStage
     * 与调用线程异步执行
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * exceptionally, is executed with this stage's exception as the
     * argument to the supplied function, using this stage's default
     * asynchronous execution facility.  Otherwise, if this stage
     * completes normally, then the returned stage also completes
     * normally with the same value.
     *
     * @param fn the function to use to compute the value of the
     *           returned CompletionStage if this CompletionStage completed
     *           exceptionally
     * @return the new CompletionStage
     * @implSpec The default implementation invokes {@link #handle},
     * relaying to {@link #handleAsync} on exception, then {@link
     * #thenCompose} for result.
     * @since 12
     */
    public default CompletionStage<T> exceptionallyAsync
    (Function<Throwable, ? extends T> fn) {
        return handle((r, ex) -> (ex == null)
                ? this
                : this.<T>handleAsync((r1, ex1) -> fn.apply(ex1)))
                .thenCompose(Function.identity());
    }

    /**
     * default 方法。
     * 当前 CompletionStage 执行抛异常的时候会返回一个处理异常的 CompletionStage
     * 与调用线程异步执行
     * 支持指定 Executor
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * exceptionally, is executed with this stage's exception as the
     * argument to the supplied function, using the supplied Executor.
     * Otherwise, if this stage completes normally, then the returned
     * stage also completes normally with the same value.
     *
     * @param fn       the function to use to compute the value of the
     *                 returned CompletionStage if this CompletionStage completed
     *                 exceptionally
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletionStage
     * @implSpec The default implementation invokes {@link #handle},
     * relaying to {@link #handleAsync} on exception, then {@link
     * #thenCompose} for result.
     * @since 12
     */
    public default CompletionStage<T> exceptionallyAsync
    (Function<Throwable, ? extends T> fn, Executor executor) {
        return handle((r, ex) -> (ex == null)
                ? this
                : this.<T>handleAsync((r1, ex1) -> fn.apply(ex1), executor))
                .thenCompose(Function.identity());
    }

    /**
     * Returns a new CompletionStage that, when this stage completes
     * exceptionally, is composed using the results of the supplied
     * function applied to this stage's exception.
     *
     * @param fn the function to use to compute the returned
     *           CompletionStage if this CompletionStage completed exceptionally
     * @return the new CompletionStage
     * @implSpec The default implementation invokes {@link #handle},
     * invoking the given function on exception, then {@link
     * #thenCompose} for result.
     * @since 12
     */
    public default CompletionStage<T> exceptionallyCompose
    (Function<Throwable, ? extends CompletionStage<T>> fn) {
        return handle((r, ex) -> (ex == null)
                ? this
                : fn.apply(ex))
                .thenCompose(Function.identity());
    }

    /**
     * Returns a new CompletionStage that, when this stage completes
     * exceptionally, is composed using the results of the supplied
     * function applied to this stage's exception, using this stage's
     * default asynchronous execution facility.
     *
     * @param fn the function to use to compute the returned
     *           CompletionStage if this CompletionStage completed exceptionally
     * @return the new CompletionStage
     * @implSpec The default implementation invokes {@link #handle},
     * relaying to {@link #handleAsync} on exception, then {@link
     * #thenCompose} for result.
     * @since 12
     */
    public default CompletionStage<T> exceptionallyComposeAsync
    (Function<Throwable, ? extends CompletionStage<T>> fn) {
        return handle((r, ex) -> (ex == null)
                ? this
                : this.handleAsync((r1, ex1) -> fn.apply(ex1))
                .thenCompose(Function.identity()))
                .thenCompose(Function.identity());
    }

    /**
     * 当前 CompletionStage 异常结束，返回一个 处理异常的 CompletionStage
     * 可以指定 Executor
     * <p>
     * Returns a new CompletionStage that, when this stage completes
     * exceptionally, is composed using the results of the supplied
     * function applied to this stage's exception, using the
     * supplied Executor.
     *
     * @param fn       the function to use to compute the returned
     *                 CompletionStage if this CompletionStage completed exceptionally
     * @param executor the executor to use for asynchronous execution
     * @return the new CompletionStage
     * @implSpec The default implementation invokes {@link #handle},
     * relaying to {@link #handleAsync} on exception, then {@link
     * #thenCompose} for result.
     * @since 12
     */
    public default CompletionStage<T> exceptionallyComposeAsync
    (Function<Throwable, ? extends CompletionStage<T>> fn,
     Executor executor) {
        return handle((r, ex) -> (ex == null)
                ? this
                : this.handleAsync((r1, ex1) -> fn.apply(ex1), executor)
                .thenCompose(Function.identity()))
                .thenCompose(Function.identity());
    }

    /**
     * 返回一个具有与当前 CompletableFuture 相同属性的 CompletableFuture
     * <p>
     * Returns a {@link CompletableFuture} maintaining the same
     * completion properties as this stage. If this stage is already a
     * CompletableFuture, this method may return this stage itself.
     * Otherwise, invocation of this method may be equivalent in
     * effect to {@code thenApply(x -> x)}, but returning an instance
     * of type {@code CompletableFuture}.
     *
     * @return the CompletableFuture
     */
    public CompletableFuture<T> toCompletableFuture();

}
