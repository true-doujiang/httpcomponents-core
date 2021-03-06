/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.pool;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

/**
 * Abstract synchronous (blocking) pool of connections.
 * <p>
 * Please note that this class does not maintain its own pool of execution {@link Thread}s.
 * Therefore, one <b>must</b> call {@link Future#get()} or {@link Future#get(long, TimeUnit)}
 * method on the {@link Future} object returned by the
 * {@link #lease(Object, Object, FutureCallback)} method in order for the lease operation
 * to complete.
 *
 * @param <T> the route type that represents the opposite endpoint of a pooled
 *   connection.
 * @param <C> the connection type.
 * @param <E> the type of the pool entry containing a pooled connection.
 * @since 4.2
 */
@Contract(threading = ThreadingBehavior.SAFE_CONDITIONAL)
public abstract class AbstractConnPool<T, C, E extends PoolEntry<T, C>>

                       implements ConnPool<T, E>, ConnPoolControl<T> {

    private final Lock lock;
    private final Condition condition;

    // org.apache.http.impl.conn.PoolingHttpClientConnectionManager.InternalConnectionFactory
    private final ConnFactory<T, C> connFactory;
    //
    private final Map<T, RouteSpecificPool<T, C, E>> routeToPool;

    // 已在使用的
    private final Set<E> leased;
    // 可以使用的
    private final LinkedList<E> available;
    // 阻塞住的
    private final LinkedList<Future<E>> pending;

    //
    private final Map<T, Integer> maxPerRoute;

    private volatile boolean isShutDown;
    //
    private volatile int defaultMaxPerRoute;
    //
    private volatile int maxTotal;
    private volatile int validateAfterInactivity;

    /**
     * default constructor
     *
     * PoolingHttpClientConnectionManager->CPool->本类
     */
    public AbstractConnPool(
            final ConnFactory<T, C> connFactory,
            final int defaultMaxPerRoute,
            final int maxTotal) {
        super();
        this.connFactory = Args.notNull(connFactory, "Connection factory");
        //
        this.defaultMaxPerRoute = Args.positive(defaultMaxPerRoute, "Max per route value");
        //
        this.maxTotal = Args.positive(maxTotal, "Max total value");

        // 创建可重入锁
        this.lock = new ReentrantLock();
        this.condition = this.lock.newCondition();

        //
        this.routeToPool = new HashMap<T, RouteSpecificPool<T, C, E>>();

        //
        this.leased = new HashSet<E>();
        this.available = new LinkedList<E>();
        this.pending = new LinkedList<Future<E>>();

        //
        this.maxPerRoute = new HashMap<T, Integer>();
    }

    /**
     * Creates a new entry for the given connection with the given route.
     */
    protected abstract E createEntry(T route, C conn);

    /**
     * @since 4.3
     */
    protected void onLease(final E entry) {
    }

    /**
     * @since 4.3
     */
    protected void onRelease(final E entry) {
    }

    /**
     * @since 4.4
     */
    protected void onReuse(final E entry) {
    }

    /**
     * @since 4.4
     */
    protected boolean validate(final E entry) {
        return true;
    }

    public boolean isShutdown() {
        return this.isShutDown;
    }

    /**
     * Shuts down the pool.
     */
    public void shutdown() throws IOException {
        if (this.isShutDown) {
            return ;
        }
        this.isShutDown = true;
        this.lock.lock();
        try {
            for (final E entry: this.available) {
                entry.close();
            }
            for (final E entry: this.leased) {
                entry.close();
            }
            for (final RouteSpecificPool<T, C, E> pool: this.routeToPool.values()) {
                pool.shutdown();
            }
            this.routeToPool.clear();
            this.leased.clear();
            this.available.clear();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     *
     */
    private RouteSpecificPool<T, C, E> getPool(final T route) {
        //
        RouteSpecificPool<T, C, E> pool = this.routeToPool.get(route);
        if (pool == null) {

            /*
             * 匿名内部类
             */
            pool = new RouteSpecificPool<T, C, E>(route) {
                @Override
                protected E createEntry(final C conn) {
                    // 调用外部类的子类方法 创建CPoolEntry
                    E entry = AbstractConnPool.this.createEntry(route, conn);
                    return entry;
                }
            };

            System.out.println(this + "--\r\n--getPool() 创建匿名内部类 RouteSpecificPool --\r\n--" + pool);
            // 缓存起来
            this.routeToPool.put(route, pool);
        }

        return pool;
    }

    private static Exception operationAborted() {
        return new CancellationException("Operation aborted");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Please note that this class does not maintain its own pool of execution
     * {@link Thread}s. Therefore, one <b>must</b> call {@link Future#get()}
     * or {@link Future#get(long, TimeUnit)} method on the {@link Future}
     * returned by this method in order for the lease operation to complete.
     *
     * org.apache.http.impl.conn.PoolingHttpClientConnectionManager#requestConnection() 调用
     *
     * 返回匿名内部类
     */
    @Override
    public Future<E> lease(final T route, final Object state, final FutureCallback<E> callback) {
        Args.notNull(route, "Route");
        Asserts.check(!this.isShutDown, "Connection pool shut down");

        /*
         * 匿名内部类
         * E: CPoolEntry
         */
        Future<E> future = new Future<E>() {

            private final AtomicBoolean cancelled = new AtomicBoolean(false);
            private final AtomicBoolean done = new AtomicBoolean(false);
            private final AtomicReference<E> entryRef = new AtomicReference<E>(null);

            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                if (done.compareAndSet(false, true)) {
                    cancelled.set(true);
                    lock.lock();
                    try {
                        condition.signalAll();
                    } finally {
                        lock.unlock();
                    }
                    if (callback != null) {
                        callback.cancelled();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public boolean isDone() {
                return done.get();
            }

            @Override
            public E get() throws InterruptedException, ExecutionException {
                try {
                    return get(0L, TimeUnit.MILLISECONDS);
                } catch (final TimeoutException ex) {
                    throw new ExecutionException(ex);
                }
            }

            /**
             * @param timeout
             * @param timeUnit
             */
            @Override
            public E get(final long timeout, final TimeUnit timeUnit)
                    throws InterruptedException, ExecutionException, TimeoutException {

                for (; ; ) {

                    System.out.println("AbstractConnPool.lease.Future.get = " + timeout);

                    synchronized (this) {

                        System.out.println("AbstractConnPool.lease.Future.this = " + this);

                        try {
                            final E entry = entryRef.get();
                            System.out.println("AbstractConnPool.lease.Future.entry = " + entry);
                            if (entry != null) {
                                return entry;
                            }

                            if (done.get()) {
                                throw new ExecutionException(operationAborted());
                            }

                            // E: CPoolEntry      外部类方法,下下面一个方法
                            final E leasedEntry = getPoolEntryBlocking(route, state, timeout, timeUnit, this);

                            if (validateAfterInactivity > 0) {
                                if (leasedEntry.getUpdated() + validateAfterInactivity <= System.currentTimeMillis()) {
                                    if (!validate(leasedEntry)) {
                                        leasedEntry.close();
                                        release(leasedEntry, false);
                                        continue;
                                    }
                                }
                            }

                            if (done.compareAndSet(false, true)) {
                                entryRef.set(leasedEntry);
                                done.set(true);
                                onLease(leasedEntry);
                                if (callback != null) {
                                    callback.completed(leasedEntry);
                                }

                                // CPoolEntry
                                System.out.println("AbstractConnPool leasedEntry = " + leasedEntry);

                                //
                                return leasedEntry;
                            } else {
                                //
                                release(leasedEntry, true);
                                throw new ExecutionException(operationAborted());
                            }
                        } catch (final IOException ex) {
                            if (done.compareAndSet(false, true)) {
                                if (callback != null) {
                                    callback.failed(ex);
                                }
                            }
                            throw new ExecutionException(ex);
                        }
                    }
                }
            }
        };

        System.out.println(this + "--\r\n--lease() 创建匿名内部类 future = " + future);

        return future;
    }

    /**
     * Attempts to lease a connection for the given route and with the given
     * state from the pool.
     * <p>
     * Please note that this class does not maintain its own pool of execution
     * {@link Thread}s. Therefore, one <b>must</b> call {@link Future#get()}
     * or {@link Future#get(long, TimeUnit)} method on the {@link Future}
     * returned by this method in order for the lease operation to complete.
     *
     * @param route route of the connection.
     * @param state arbitrary object that represents a particular state
     *  (usually a security principal or a unique token identifying
     *  the user whose credentials have been used while establishing the connection).
     *  May be {@code null}.
     * @return future for a leased pool entry.
     */
    public Future<E> lease(final T route, final Object state) {
        return lease(route, state, null);
    }

    /**
     * org.apache.http.pool.AbstractConnPool#lease()中的内部类调用
     * 看名字就知道是阻塞方法。即该route所对应的连接池中的连接不够用时，该方法就会阻塞，直到该 route所对应的连接池有连接释放，
     * 方法才会被唤醒；或者方法一直等待，直到连接超时抛出异常。
     */
    private E getPoolEntryBlocking(
            final T route,
            final Object state,
            final long timeout,
            final TimeUnit timeUnit,
            final Future<E> future)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {

        Date deadline = null;
        if (timeout > 0) {
            // 设置超时时间
            deadline = new Date (System.currentTimeMillis() + timeUnit.toMillis(timeout));
        }

        // 并发锁
        this.lock.lock();

        try {
            E entry;

            for (;;) {

                Asserts.check(!this.isShutDown, "Connection pool shut down");

                System.out.println("AbstractConnPool.getPoolEntryBlocking for1");

                if (future.isCancelled()) {
                    throw new ExecutionException(operationAborted());
                }

                /*
                 * 上面加锁应该是防止这里并发请求hashmap吧 ??
                 * 匿名内部类
                 *
                 * 从Map中获取该route对应的连接池，若Map中没有，则创建该route对应的连接池
                 */
                final RouteSpecificPool<T, C, E> routerPool = getPool(route);
                System.out.println(Thread.currentThread().getName() +
                        " AbstractConnPool.getPoolEntryBlocking routerPool--\r\n--" + routerPool);

                for (;;) {

                    System.out.println(Thread.currentThread().getName() + " AbstractConnPool.getPoolEntryBlocking for2");

                    // 获取 同一状态的 空闲连接，即从available链表的头部中移除，添加到leased集合中
                    entry = routerPool.getFree(state);
                    System.out.println(Thread.currentThread().getName() + " AbstractConnPool.getPoolEntryBlocking for2 entry = " + entry);
                    if (entry == null) {
                        break;
                    }
                    if (entry.isExpired(System.currentTimeMillis())) {
                        System.out.println(Thread.currentThread().getName() + " 超时关闭  entry = " + entry);
                        // 关闭底层的socket 还有对应的输入输出流
                        entry.close();
                    }

                    if (entry.isClosed()) {
                        System.out.println(Thread.currentThread().getName() + " 已关闭移除  entry = " + entry);
                        // 若该连接已关闭，则总的available链表中删除该连接
                        this.available.remove(entry);
                        // 从该route对应的连接池的leased集合中删除该连接，并且不回收到available链表中
                        routerPool.free(entry, false);
                    } else {
                        break;
                    }
                }
                // for end

                if (entry != null) {
                    // 若获取的连接不为空，将连接从总的available链表移除，并添加到leased集合中
                    // 获取连接成功，直接返回
                    this.available.remove(entry);
                    this.leased.add(entry);
                    onReuse(entry);
                    return entry;
                }

                // New connection is needed  计算该route的最大连接数
                final int maxPerRoute = getMax(route);

                // 计算该route连接池中的连接数 是否 大于等于 route最大连接数
                // Shrink the pool prior to allocating a new connection
                final int excess = Math.max(0, routerPool.getAllocatedCount() + 1 - maxPerRoute);
                if (excess > 0) {
                    for (int i = 0; i < excess; i++) {
                        final E lastUsed = routerPool.getLastUsed();
                        if (lastUsed == null) {
                            break;
                        }
                        lastUsed.close();
                        this.available.remove(lastUsed);
                        routerPool.remove(lastUsed);
                    }
                }

                // 该route的连接池大小 小于 route最大连接数
                if (routerPool.getAllocatedCount() < maxPerRoute) {

                    final int totalUsed = this.leased.size();
                    final int freeCapacity = Math.max(this.maxTotal - totalUsed, 0);
                    if (freeCapacity > 0) {
                        final int totalAvailable = this.available.size();

                        // 总的空闲连接数 大于等于 总的连接池剩余容量
                        if (totalAvailable > freeCapacity - 1) {
                            if (!this.available.isEmpty()) {
                                final E lastUsed = this.available.removeLast();
                                lastUsed.close();
                                final RouteSpecificPool<T, C, E> otherpool = getPool(lastUsed.getRoute());
                                otherpool.remove(lastUsed);
                            }
                        }

                        // 创建 LoggingManagedHttpClientConnection
                        // 创建新连接，并添加到总的leased集合以及route连接池的leased集合中，函数返回
                        final C conn = this.connFactory.create(route);
                        // 添加到entity中
                        entry = routerPool.add(conn);
                        this.leased.add(entry);
                        // 返回
                        return entry;
                    }
                }


                //route的连接池已满，无法分配连接
                boolean success = false;

                try {
                    // 将该获取连接的任务放入pending队列
                    routerPool.queue(future);
                    this.pending.add(future);

                    if (deadline != null) {
                        System.out.println(Thread.currentThread().getName() + " 开始休眠1 future = " + future);
                        // 阻塞等待，若在超时之前被唤醒，则返回true；若直到超时才返回，则返回false
                        success = this.condition.awaitUntil(deadline);
                        System.out.println(Thread.currentThread().getName() + " 结束休眠1 future = " + future);
                    } else {
                        System.out.println(Thread.currentThread().getName() + " 开始休眠2 future = " + future);
                        this.condition.await();
                        success = true;
                        System.out.println(Thread.currentThread().getName() + " 结束休眠2 future = " + future);
                    }

                    if (future.isCancelled()) {
                        throw new ExecutionException(operationAborted());
                    }
                } finally {
                    // In case of 'success', we were woken up by the
                    // connection pool and should now have a connection
                    // waiting for us, or else we're shutting down.
                    // Just continue in the loop, both cases are checked.
                    // 无论是 被唤醒返回、超时返回 还是被 中断异常返回，都会进入finally代码段
                    // 从pending队列中移除
                    routerPool.unqueue(future);
                    this.pending.remove(future);
                }

                // 判断是伪唤醒 还是 连接超时
                // 若是 连接超时，则跳出while循环，并抛出 连接超时的异常；
                // 若是 伪唤醒，则继续循环获取连接
                // check for spurious wakeup vs. timeout
                if (!success && (deadline != null && deadline.getTime() <= System.currentTimeMillis())) {
                    System.out.println(Thread.currentThread().getName() + " 唤醒超时抛异常  break future = " + future);
                    // break 就执行到 throw exception了
                    break;
                }

            } // for end

            throw new TimeoutException("Timeout waiting for connection");

        } finally {
            // 释放锁
            this.lock.unlock();
        }
    }

    /**
     *
     */
    @Override
    public void release(final E entry, final boolean reusable) {
        this.lock.lock();
        try {

            System.out.println(Thread.currentThread().getName() + " release entry = " + entry);
            // 从总的leased集合中移除连接
            if (this.leased.remove(entry)) {

                final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
                // 回收连接
                pool.free(entry, reusable);

                if (reusable && !this.isShutDown) {
                    this.available.addFirst(entry);
                } else {
                    entry.close();
                }

                onRelease(entry);

                // 获取pending队列队头的任务（先进先出原则），唤醒该阻塞的任务
                Future<E> future = pool.nextPending();
                if (future != null) {
                    this.pending.remove(future);
                } else {
                    future = this.pending.poll();
                }

                if (future != null) {
                    System.out.println(Thread.currentThread().getName() + " 唤醒 所有 future = " + future);
                    // 唤醒pending中的future
                    this.condition.signalAll();
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    private int getMax(final T route) {
        final Integer v = this.maxPerRoute.get(route);
        return v != null ? v.intValue() : this.defaultMaxPerRoute;
    }

    @Override
    public void setMaxTotal(final int max) {
        Args.positive(max, "Max value");
        this.lock.lock();
        try {
            this.maxTotal = max;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getMaxTotal() {
        this.lock.lock();
        try {
            return this.maxTotal;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setDefaultMaxPerRoute(final int max) {
        Args.positive(max, "Max per route value");
        this.lock.lock();
        try {
            this.defaultMaxPerRoute = max;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getDefaultMaxPerRoute() {
        this.lock.lock();
        try {
            return this.defaultMaxPerRoute;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setMaxPerRoute(final T route, final int max) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            if (max > -1) {
                this.maxPerRoute.put(route, Integer.valueOf(max));
            } else {
                this.maxPerRoute.remove(route);
            }
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getMaxPerRoute(final T route) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            return getMax(route);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PoolStats getTotalStats() {
        this.lock.lock();
        try {
            return new PoolStats(
                    this.leased.size(),
                    this.pending.size(),
                    this.available.size(),
                    this.maxTotal);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PoolStats getStats(final T route) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            return new PoolStats(
                    pool.getLeasedCount(),
                    pool.getPendingCount(),
                    pool.getAvailableCount(),
                    getMax(route));
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Returns snapshot of all knows routes
     * @return the set of routes
     *
     * @since 4.4
     */
    public Set<T> getRoutes() {
        this.lock.lock();
        try {
            return new HashSet<T>(routeToPool.keySet());
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Enumerates all available connections.
     *
     * @since 4.3
     */
    protected void enumAvailable(final PoolEntryCallback<T, C> callback) {
        this.lock.lock();
        try {
            final Iterator<E> it = this.available.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
                if (entry.isClosed()) {
                    final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
                    pool.remove(entry);
                    it.remove();
                }
            }
            purgePoolMap();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Enumerates all leased connections.
     *
     * @since 4.3
     */
    protected void enumLeased(final PoolEntryCallback<T, C> callback) {
        this.lock.lock();
        try {
            final Iterator<E> it = this.leased.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
            }
        } finally {
            this.lock.unlock();
        }
    }

    private void purgePoolMap() {
        final Iterator<Map.Entry<T, RouteSpecificPool<T, C, E>>> it = this.routeToPool.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<T, RouteSpecificPool<T, C, E>> entry = it.next();
            final RouteSpecificPool<T, C, E> pool = entry.getValue();
            if (pool.getPendingCount() + pool.getAllocatedCount() == 0) {
                it.remove();
            }
        }
    }

    /**
     * Closes connections that have been idle longer than the given period
     * of time and evicts them from the pool.
     *
     * @param idletime maximum idle time.
     * @param timeUnit time unit.
     */
    public void closeIdle(final long idletime, final TimeUnit timeUnit) {
        Args.notNull(timeUnit, "Time unit");
        long time = timeUnit.toMillis(idletime);
        if (time < 0) {
            time = 0;
        }
        final long deadline = System.currentTimeMillis() - time;
        enumAvailable(new PoolEntryCallback<T, C>() {

            @Override
            public void process(final PoolEntry<T, C> entry) {
                if (entry.getUpdated() <= deadline) {
                    entry.close();
                }
            }

        });
    }

    /**
     * Closes expired connections and evicts them from the pool.
     */
    public void closeExpired() {
        final long now = System.currentTimeMillis();
        enumAvailable(new PoolEntryCallback<T, C>() {

            @Override
            public void process(final PoolEntry<T, C> entry) {
                if (entry.isExpired(now)) {
                    entry.close();
                }
            }

        });
    }

    /**
     * @return the number of milliseconds
     * @since 4.4
     */
    public int getValidateAfterInactivity() {
        return this.validateAfterInactivity;
    }

    /**
     * @param ms the number of milliseconds
     * @since 4.4
     */
    public void setValidateAfterInactivity(final int ms) {
        this.validateAfterInactivity = ms;
    }

    @Override
    public String toString() {
        this.lock.lock();
        try {
            final StringBuilder buffer = new StringBuilder();

            buffer.append("{\r\n");
            buffer.append("   ");
            buffer.append(super.toString());
            buffer.append("\r\n");
            buffer.append("   ");
            buffer.append("[leased: ");
            buffer.append(this.leased.size());
            buffer.append(this.leased);
            buffer.append("]");
            buffer.append("\r\n   [available: ");
            buffer.append(this.available.size());
            buffer.append(this.available);
            buffer.append("] \r\n   [pending: ");
            buffer.append(this.pending.size());
            buffer.append(this.pending);
            buffer.append("]");
            buffer.append("\r\n}");
            return buffer.toString();
        } finally {
            this.lock.unlock();
        }
    }

}
