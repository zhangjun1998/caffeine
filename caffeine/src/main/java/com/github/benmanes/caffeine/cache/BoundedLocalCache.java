/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.Async.ASYNC_EXPIRY;
import static com.github.benmanes.caffeine.cache.Caffeine.calculateHashMapCapacity;
import static com.github.benmanes.caffeine.cache.Caffeine.ceilingPowerOfTwo;
import static com.github.benmanes.caffeine.cache.Caffeine.requireArgument;
import static com.github.benmanes.caffeine.cache.Caffeine.saturatedToNanos;
import static com.github.benmanes.caffeine.cache.LocalLoadingCache.newBulkMappingFunction;
import static com.github.benmanes.caffeine.cache.LocalLoadingCache.newMappingFunction;
import static com.github.benmanes.caffeine.cache.Node.PROBATION;
import static com.github.benmanes.caffeine.cache.Node.PROTECTED;
import static com.github.benmanes.caffeine.cache.Node.WINDOW;
import static java.util.Objects.requireNonNull;
import static java.util.Spliterator.DISTINCT;
import static java.util.Spliterator.IMMUTABLE;
import static java.util.Spliterator.NONNULL;
import static java.util.Spliterator.ORDERED;
import static java.util.function.Function.identity;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.Async.AsyncExpiry;
import com.github.benmanes.caffeine.cache.LinkedDeque.PeekingIterator;
import com.github.benmanes.caffeine.cache.Policy.CacheEntry;
import com.github.benmanes.caffeine.cache.References.InternalReference;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.google.errorprone.annotations.concurrent.GuardedBy;

/**
 * Caffeine 不仅对 {@link Node} 做了代码生成，还对 LocalCache 也做了代码生成，debug 的时候发现使用的是一个动态生成名为『SSMSA』的类
 * <p>
 *
 * An in-memory cache implementation that supports full concurrency of retrievals, a high expected
 * concurrency for updates, and multiple ways to bound the cache.
 * <p>
 * This class is abstract and code generated subclasses provide the complete implementation for a
 * particular configuration. This is to ensure that only the fields and execution paths necessary
 * for a given configuration are used.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
@SuppressWarnings("serial")
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef
    implements LocalCache<K, V> {

  /*
   * This class performs a best-effort bounding of a ConcurrentHashMap using a page-replacement
   * algorithm to determine which entries to evict when the capacity is exceeded.
   *
   * Concurrency:
   * ------------
   * The page replacement algorithms are kept eventually consistent with the map. An update to the
   * map and recording of reads may not be immediately reflected on the policy's data structures.
   * These structures are guarded by a lock and operations are applied in batches to avoid lock
   * contention. The penalty of applying the batches is spread across threads so that the amortized
   * cost is slightly higher than performing just the ConcurrentHashMap operation [1].
   *
   * A memento of the reads and writes that were performed on the map are recorded in buffers. These
   * buffers are drained at the first opportunity after a write or when a read buffer is full. The
   * reads are offered to a buffer that will reject additions if contended on or if it is full. Due
   * to the concurrent nature of the read and write operations a strict policy ordering is not
   * possible, but may be observably strict when single threaded. The buffers are drained
   * asynchronously to minimize the request latency and uses a state machine to determine when to
   * schedule this work on an executor.
   *
   * Due to a lack of a strict ordering guarantee, a task can be executed out-of-order, such as a
   * removal followed by its addition. The state of the entry is encoded using the key field to
   * avoid additional memory. An entry is "alive" if it is in both the hash table and the page
   * replacement policy. It is "retired" if it is not in the hash table and is pending removal from
   * the page replacement policy. Finally, an entry transitions to the "dead" state when it is not
   * in the hash table nor the page replacement policy. Both the retired and dead states are
   * represented by a sentinel key that should not be used for map operations.
   *
   * Eviction:
   * ---------
   * Maximum size is implemented using the Window TinyLfu policy [2] due to its high hit rate, O(1)
   * time complexity, and small footprint. A new entry starts in the admission window and remains
   * there as long as it has high temporal locality (recency). Eventually an entry will slip from
   * the window into the main space. If the main space is already full, then a historic frequency
   * filter determines whether to evict the newly admitted entry or the victim entry chosen by the
   * eviction policy. This process ensures that the entries in the window were very recently used
   * and entries in the main space are accessed very frequently and are at least moderately recent.
   * The windowing allows the policy to have a high hit rate when entries exhibit a bursty access
   * pattern while the filter ensures that popular items are retained. The admission window uses
   * LRU and the main space uses Segmented LRU.
   *
   * The optimal size of the window vs main spaces is workload dependent [3]. A large admission
   * window is favored by recency-biased workloads while a small one favors frequency-biased
   * workloads. When the window is too small then recent arrivals are prematurely evicted, but when
   * too large then they pollute the cache and force the eviction of more popular entries. The
   * configuration is dynamically determined using hill climbing to walk the hit rate curve. This is
   * done by sampling the hit rate and adjusting the window size in the direction that is improving
   * (making positive or negative steps). At each interval the step size is decreased until the
   * climber converges at the optimal setting. The process is restarted when the hit rate changes
   * over a threshold, indicating that the workload altered and a new setting may be required.
   *
   * The historic usage is retained in a compact popularity sketch, which uses hashing to
   * probabilistically estimate an item's frequency. This exposes a flaw where an adversary could
   * use hash flooding [4] to artificially raise the frequency of the main space's victim and cause
   * all candidates to be rejected. In the worst case, by exploiting hash collisions an attacker
   * could cause the cache to never hit and hold only worthless items, resulting in a
   * denial-of-service attack against the underlying resource. This is protected against by
   * introducing jitter so that candidates which are at least moderately popular have a small,
   * random chance of being admitted. This causes the victim to be evicted, but in a way that
   * marginally impacts the hit rate.
   *
   * Expiration:
   * -----------
   * Expiration is implemented in O(1) time complexity. The time-to-idle policy uses an access-order
   * queue, the time-to-live policy uses a write-order queue, and variable expiration uses a
   * hierarchical timer wheel [5]. The queuing policies allow for peeking at the oldest entry to
   * see if it has expired and, if it has not, then the younger entries must not have too. If a
   * maximum size is set then expiration will share the queues so that the per-entry footprint is
   * minimized. The timer wheel based policy uses hashing and cascading in a manner that amortizes
   * the penalty of sorting to achieve a similar algorithmic cost.
   *
   * The expiration updates are applied in a best effort fashion. The reordering of variable or
   * access-order expiration may be discarded by the read buffer if full or contended on. Similarly,
   * the reordering of write expiration may be ignored for an entry if the last update was within a
   * short time window in order to avoid overwhelming the write buffer.
   *
   * [1] BP-Wrapper: A Framework Making Any Replacement Algorithms (Almost) Lock Contention Free
   * http://web.cse.ohio-state.edu/hpcs/WWW/HTML/publications/papers/TR-09-1.pdf
   * [2] TinyLFU: A Highly Efficient Cache Admission Policy
   * https://dl.acm.org/citation.cfm?id=3149371
   * [3] Adaptive Software Cache Management
   * https://dl.acm.org/citation.cfm?id=3274816
   * [4] Denial of Service via Algorithmic Complexity Attack
   * https://www.usenix.org/legacy/events/sec03/tech/full_papers/crosby/crosby.pdf
   * [5] Hashed and Hierarchical Timing Wheels
   * http://www.cs.columbia.edu/~nahum/w6998/papers/ton97-timing-wheels.pdf
   */

  static final Logger logger = System.getLogger(BoundedLocalCache.class.getName());

  // 缓冲区相关，最大容量与 CPU 核心数相关
  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();
  /** 写缓冲区的初始容量 */
  static final int WRITE_BUFFER_MIN = 4;
  /** 写缓冲区的最大容量 */
  static final int WRITE_BUFFER_MAX = 128 * ceilingPowerOfTwo(NCPU);
  /** The number of attempts to insert into the write buffer before yielding. */
  static final int WRITE_BUFFER_RETRIES = 100;

  /** The maximum weighted capacity of the map. */
  // 缓存最大容量阈值
  static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;
  /** The initial percent of the maximum weighted capacity dedicated to the main space. */
  // main 空间的初始百分比，剩下的 0.01 为 window 部分，也就是说 window 和 main 的占比为 1:99
  static final double PERCENT_MAIN = 0.99d;
  /** The percent of the maximum weighted capacity dedicated to the main's protected space. */
  // main 空间中 protect 部分的初始百分比，main 空间中 probation 与 protect的占比为 20:80
  static final double PERCENT_MAIN_PROTECTED = 0.80d;

  /** The difference in hit rates that restarts the climber. */
  static final double HILL_CLIMBER_RESTART_THRESHOLD = 0.05d;
  /** The percent of the total size to adapt the window by. */
  static final double HILL_CLIMBER_STEP_PERCENT = 0.0625d;
  /** The rate to decrease the step size to adapt by. */
  static final double HILL_CLIMBER_STEP_DECAY_RATE = 0.98d;
  /** The maximum number of entries that can be transferred between queues. */
  static final int QUEUE_TRANSFER_THRESHOLD = 1_000;
  /** The maximum time window between entry updates before the expiration must be reordered. */
  static final long EXPIRE_WRITE_TOLERANCE = TimeUnit.SECONDS.toNanos(1);

  /** The maximum duration before an entry expires. */
  // 最大过期时间
  static final long MAXIMUM_EXPIRY = (Long.MAX_VALUE >> 1); // 150 years
  /** The duration to wait on the eviction lock before warning that of a possible misuse. */
  // 获取 evictionLock 的最大等待时间
  static final long WARN_AFTER_LOCK_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
  /** The handle for the in-flight refresh operations. */
  // 指向 refreshes，用于提供原子操作
  static final VarHandle REFRESHES;

  // 移除事件监听器
  final @Nullable RemovalListener<K, V> evictionListener;
  // 缓存加载器
  final @Nullable AsyncCacheLoader<K, V> cacheLoader;

  // 写缓冲区，它是一个队列，允许无锁的高并发写入，但只允许一个消费者，用于重放写操作来执行一些写操作后需要的处理；它存储的是 Runnable 类型的任务；
  // 写缓冲区用来重放写操作来执行一些写操作后需要的处理，用来帮助 基于最后写入时间驱逐 的执行；
  // 它需要尽量保证无丢失和低延迟，如果丢失/延迟就会导致最后写入时间错误，进而导致即使本次写入缓存当时是成功的，但可能会因为最后写入时间还是上次的写入时间而把本次刚写进去的元素驱逐掉，造成写缓存丢失
  final MpscGrowableArrayQueue<Runnable> writeBuffer;
  // 缓存实例，起始就是 ConcurrentHashMap 对象，只是 key 和 value 都被包装了用来实现缓存的一些特性
  final ConcurrentHashMap<Object, Node<K, V>> data;
  // 缓冲区清理任务
  final PerformCleanupTask drainBuffersTask;
  // 访问(读|写)操作后需要执行的策略
  final Consumer<Node<K, V>> accessPolicy;
  // 读缓冲区，它是一个带状缓冲区，每个线程在带状缓冲区上有自己的桶位，每个桶位都是一个环形缓冲区，这样来减少线程竞争；它存储的是 Node 本身
  // 读缓冲区用来重放读操作来执行一些读操作后需要的处理，用来帮助 基于容量驱逐和基于最后读取时间驱逐 的执行；它可以接收一些丢失/延迟，这对业务没什么影响
  final Buffer<Node<K, V>> readBuffer;
  // 用于 Node 相关的操作，如创建、引用包装等
  final NodeFactory<K, V> nodeFactory;
  // 在清理缓冲区、维护缓存时候需要加的锁
  final ReentrantLock evictionLock;
  // 权重策略
  final Weigher<K, V> weigher;
  // 线程池，默认使用 ForkJoinPool.commonPool()
  final Executor executor;

  final boolean isWeighted;
  final boolean isAsync;

  @Nullable Set<K> keySet;
  @Nullable Collection<V> values;
  @Nullable Set<Entry<K, V>> entrySet;
  // 自动刷新任务
  @Nullable volatile ConcurrentMap<Object, CompletableFuture<?>> refreshes;

  /**
   * 这个构造函数会在动态生成的子类的构造器中调用
   * <p>
   *
   * Creates an instance based on the builder's configuration.
   */
  protected BoundedLocalCache(Caffeine<K, V> builder, @Nullable AsyncCacheLoader<K, V> cacheLoader, boolean isAsync) {
    // 是否异步
    this.isAsync = isAsync;
    // 自动加载器
    this.cacheLoader = cacheLoader;
    // 线程池，用来执行移除事件回调、缓存自动刷新、缓存维护等耗时操作
    executor = builder.getExecutor();
    isWeighted = builder.isWeighted();
    // 这把锁很多地方都用到，注意
    evictionLock = new ReentrantLock();
    weigher = builder.getWeigher(isAsync);
    // 用于驱逐缓存的任务
    drainBuffersTask = new PerformCleanupTask(this);
    // 节点工厂，用于包装 key-value，一个 Node 中包含 key、value 等属性
    nodeFactory = NodeFactory.newFactory(builder, isAsync);
    // 移除事件监听器
    evictionListener = builder.getEvictionListener(isAsync);
    // 实际的数据存储容器就是 ConcurrentHashMap，只是其中存储的value通过 Node<K, V> 包装了一遍
    data = new ConcurrentHashMap<>(builder.getInitialCapacity());
    // 读缓冲区
    readBuffer = evicts() || collectKeys() || collectValues() || expiresAfterAccess()
        ? new BoundedBuffer<>()
        : Buffer.disabled();
    // 访问策略，在缓存有过期/容量限制时使用到，会在清理 readBuffer/writeBuffer 时调用，用来更新 node 在所属队列中的顺序和访问频率，方便 W-TinyLFU 算法回收
    // 调用 onAccess() 方法
    accessPolicy = (evicts() || expiresAfterAccess()) ? this::onAccess : e -> {};
    // 写缓冲区
    writeBuffer = new MpscGrowableArrayQueue<>(WRITE_BUFFER_MIN, WRITE_BUFFER_MAX);

    if (evicts()) {
      setMaximumSize(builder.getMaximum());
    }
  }

  static {
    try {
      REFRESHES = MethodHandles.lookup()
          .findVarHandle(BoundedLocalCache.class, "refreshes", ConcurrentMap.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /* --------------- Shared --------------- */

  @Override
  public boolean isAsync() {
    return isAsync;
  }

  /** Returns if the node's value is currently being computed asynchronously. */
  final boolean isComputingAsync(Node<?, ?> node) {
    return isAsync && !Async.isReady((CompletableFuture<?>) node.getValue());
  }

  /**
   * Window-TinyLFU 算法
   * 在动态生成的『SSMS』类中体现的更直观
   *
   * 整个缓存区域被划分为 window 和 main，window 与 main 的空间占比为 1% : 99%；
   * main 区域又被划分为 probation 和 protect，它们的占比为 20% : 80%，probation 用于存储冷数据，protect 用于存储热数据；
   * window 区域主要用于应对突发流量，它使用 LRU 算法进行淘汰；
   * main 区域则使用 SLRU 和 TinyLFU 算法进行淘汰；
   *
   * 新加入的缓存首先进入到 window 区域，如果 window 满，则会通过 LRU 进行淘汰晋升到 probation 区域。
   * 元素的淘汰发生在 probation 区域，如果总容量超出，则会对 probation 队列的首尾元素(也就是从 window 晋升和从 probation 淘汰的)根据 TinyLFU 记录的频率进行PK，淘汰访问频率低的。
   * probation 队列元素被访问后，该元素会晋升到 protected 区域。
   * protected 区域的元素是比较稳定的，如果容量超出，则淘汰的元素会进入到 probation 队列，在 probation 队列中重新进行淘汰。
   */

  // ====== 顺序访问队列，LRU，尾部为最近被访问的元素， 头部为最久未访问的元素 =======

  /**
   * 顺序访问队列，用于存放 W-TinyLFU 算法中 window 区域的数据；
   * 属于 window 区，占据的空间为总缓存大小的1%；
   * 使用 LRU 算法进行淘汰；
   * 用于基于容量驱逐与基于空闲时间驱逐的策略
   */
  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderWindowDeque() {
    throw new UnsupportedOperationException();
  }

  /**
   * 顺序访问队列，用于存放 W-TinyLFU 算法中 probation 区域的数据；
   * 属于 main 区，占据 main 区域的20%；
   * 使用 LRU 算法进行淘汰；
   */
  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderProbationDeque() {
    throw new UnsupportedOperationException();
  }

  /**
   * 顺序访问队列，用于存放 W-TinyLFU 算法中 protect 区域的数据；
   * 属于 main 区，占据 main 区域的80%;
   * 使用 LRU 算法进行淘汰；
   */
  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderProtectedDeque() {
    throw new UnsupportedOperationException();
  }

  // ========= 顺序写队列 LRU，尾部为最近被写入的元素， 头部为写入时间最早的元素(存活时间最长) =========

  /**
   * 顺序写队列，用于基于存活时间(expireAfterWrite)的驱逐策略；
   * 它的入队出队操作被封装在 writeBuffer 中的任务中，AddTask、UpdateTask、RemoveTask 都会对该队列进行操作。
   */
  @GuardedBy("evictionLock")
  protected WriteOrderDeque<Node<K, V>> writeOrderDeque() {
    throw new UnsupportedOperationException();
  }

  @Override
  public final Executor executor() {
    return executor;
  }

  @Override
  @SuppressWarnings("NullAway")
  public ConcurrentMap<Object, CompletableFuture<?>> refreshes() {
    var pending = refreshes;
    if (pending == null) {
      pending = new ConcurrentHashMap<>();
      if (!REFRESHES.compareAndSet(this, null, pending)) {
        pending = refreshes;
      }
    }
    return pending;
  }

  /** Invalidate the in-flight refresh. */
  void discardRefresh(Object keyReference) {
    var pending = refreshes;
    if (pending != null) {
      pending.remove(keyReference);
    }
  }

  @Override
  public Object referenceKey(K key) {
    return nodeFactory.newLookupKey(key);
  }

  /* --------------- Stats Support --------------- */

  @Override
  public boolean isRecordingStats() {
    return false;
  }

  @Override
  public StatsCounter statsCounter() {
    return StatsCounter.disabledStatsCounter();
  }

  @Override
  public Ticker statsTicker() {
    return Ticker.disabledTicker();
  }

  /* --------------- Removal Listener Support --------------- */

  @SuppressWarnings("NullAway")
  protected RemovalListener<K, V> removalListener() {
    return null;
  }

  protected boolean hasRemovalListener() {
    return false;
  }

  /* 移除事件回调，丢到线程池处理 */
  @Override
  public void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
    if (!hasRemovalListener()) {
      return;
    }
    Runnable task = () -> {
      try {
        removalListener().onRemoval(key, value, cause);
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown by removal listener", t);
      }
    };
    try {
      executor.execute(task);
    } catch (Throwable t) {
      logger.log(Level.ERROR, "Exception thrown when submitting removal listener", t);
      task.run();
    }
  }

  /* --------------- Eviction Listener Support --------------- */

  /* 驱逐事件回调，同步执行 */
  void notifyEviction(@Nullable K key, @Nullable V value, RemovalCause cause) {
    if (evictionListener == null) {
      return;
    }
    try {
      evictionListener.onRemoval(key, value, cause);
    } catch (Throwable t) {
      logger.log(Level.WARNING, "Exception thrown by eviction listener", t);
    }
  }

  /* --------------- Reference Support --------------- */

  /**
   * key 是否被 弱引用包装，导致可能被GC收集
   * <p>
   *
   * Returns if the keys are weak reference garbage collected.
   */
  protected boolean collectKeys() {
    return false;
  }

  /**
   * key 是否被弱引用/软引用包装，导致可能被GC收集
   * <p>
   *
   * Returns if the values are weak or soft reference garbage collected.
   */
  protected boolean collectValues() {
    return false;
  }

  /**
   * 弱引用 key 关联的 ReferenceQueue，GC回收时会将 key 放到该队列中，起到通知作用。
   * 在新建 node 或 key 时会关联该 ReferenceQueue
   */
  @SuppressWarnings("NullAway")
  protected ReferenceQueue<K> keyReferenceQueue() {
    return null;
  }

  /**
   * 弱引用/软引用 value 关联的 ReferenceQueue，GC回收时会将 value 放到该队列中，起到通知作用。
   * 在新建 node 或 setValue 时会关联该 ReferenceQueue
   */
  @SuppressWarnings("NullAway")
  protected ReferenceQueue<V> valueReferenceQueue() {
    return null;
  }

  /* --------------- Expiration Support --------------- */

  /** Returns the {@link Pacer} used to schedule the maintenance task. */
  protected @Nullable Pacer pacer() {
    return null;
  }

  /** Returns if the cache expires entries after a variable time threshold. */
  protected boolean expiresVariable() {
    return false;
  }

  /** Returns if the cache expires entries after an access time threshold. */
  protected boolean expiresAfterAccess() {
    return false;
  }

  /** Returns how long after the last access to an entry the map will retain that entry. */
  protected long expiresAfterAccessNanos() {
    throw new UnsupportedOperationException();
  }

  protected void setExpiresAfterAccessNanos(long expireAfterAccessNanos) {
    throw new UnsupportedOperationException();
  }

  /** Returns if the cache expires entries after a write time threshold. */
  protected boolean expiresAfterWrite() {
    return false;
  }

  /** Returns how long after the last write to an entry the map will retain that entry. */
  protected long expiresAfterWriteNanos() {
    throw new UnsupportedOperationException();
  }

  protected void setExpiresAfterWriteNanos(long expireAfterWriteNanos) {
    throw new UnsupportedOperationException();
  }

  /** Returns if the cache refreshes entries after a write time threshold. */
  protected boolean refreshAfterWrite() {
    return false;
  }

  /** Returns how long after the last write an entry becomes a candidate for refresh. */
  protected long refreshAfterWriteNanos() {
    throw new UnsupportedOperationException();
  }

  protected void setRefreshAfterWriteNanos(long refreshAfterWriteNanos) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasWriteTime() {
    return expiresAfterWrite() || refreshAfterWrite();
  }

  @Override
  @SuppressWarnings("NullAway")
  public Expiry<K, V> expiry() {
    return null;
  }

  /** Returns the {@link Ticker} used by this cache for expiration. */
  public Ticker expirationTicker() {
    return Ticker.disabledTicker();
  }

  protected TimerWheel<K, V> timerWheel() {
    throw new UnsupportedOperationException();
  }

  /* --------------- Eviction Support --------------- */

  /** Returns if the cache evicts entries due to a maximum size or weight threshold. */
  protected boolean evicts() {
    return false;
  }

  /** Returns if entries may be assigned different weights. */
  protected boolean isWeighted() {
    return (weigher != Weigher.singletonWeigher());
  }

  protected FrequencySketch<K> frequencySketch() {
    throw new UnsupportedOperationException();
  }

  /** Returns if an access to an entry can skip notifying the eviction policy. */
  protected boolean fastpath() {
    return false;
  }

  /**
   * 缓存的最大元素数量限制
   * <p>
   *
   * Returns the maximum weighted size.
   */
  protected long maximum() {
    throw new UnsupportedOperationException();
  }

  /**
   * window 区域的最大元素数量限制
   * <p>
   *
   * Returns the maximum weighted size of the window space.
   */
  protected long windowMaximum() {
    throw new UnsupportedOperationException();
  }

  /**
   * protected 区域的最大元素数量限制
   * <p>
   *
   * Returns the maximum weighted size of the main's protected space.
   */
  protected long mainProtectedMaximum() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setWindowMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setMainProtectedMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  /** Returns the combined weight of the values in the cache (may be negative). */
  protected long weightedSize() {
    throw new UnsupportedOperationException();
  }

  /** Returns the uncorrected combined weight of the values in the window space. */
  protected long windowWeightedSize() {
    throw new UnsupportedOperationException();
  }

  /** Returns the uncorrected combined weight of the values in the main's protected space. */
  protected long mainProtectedWeightedSize() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setWindowWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setMainProtectedWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  protected int hitsInSample() {
    throw new UnsupportedOperationException();
  }

  protected int missesInSample() {
    throw new UnsupportedOperationException();
  }

  protected int sampleCount() {
    throw new UnsupportedOperationException();
  }

  protected double stepSize() {
    throw new UnsupportedOperationException();
  }

  protected double previousSampleHitRate() {
    throw new UnsupportedOperationException();
  }

  protected long adjustment() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setHitsInSample(int hitCount) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setMissesInSample(int missCount) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setSampleCount(int sampleCount) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setStepSize(double stepSize) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setPreviousSampleHitRate(double hitRate) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setAdjustment(long amount) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum weighted size of the cache. The caller may need to perform a maintenance cycle
   * to eagerly evicts entries until the cache shrinks to the appropriate size.
   */
  @GuardedBy("evictionLock")
  void setMaximumSize(long maximum) {
    requireArgument(maximum >= 0, "maximum must not be negative");
    if (maximum == maximum()) {
      return;
    }

    long max = Math.min(maximum, MAXIMUM_CAPACITY);
    long window = max - (long) (PERCENT_MAIN * max);
    long mainProtected = (long) (PERCENT_MAIN_PROTECTED * (max - window));

    setMaximum(max);
    setWindowMaximum(window);
    setMainProtectedMaximum(mainProtected);

    setHitsInSample(0);
    setMissesInSample(0);
    setStepSize(-HILL_CLIMBER_STEP_PERCENT * max);

    if ((frequencySketch() != null) && !isWeighted() && (weightedSize() >= (max >>> 1))) {
      // Lazily initialize when close to the maximum size
      frequencySketch().ensureCapacity(max);
    }
  }

  /**
   * 缓存容量达到上限，根据 W-TinyLFU 算法驱逐元素：
   * <ol>
   *     <li>新加入的缓存首先进入到 window 区域，如果 window 满，则会通过 LRU 将淘汰的缓存晋升到 probation 区域</li>
   *     <li>元素的淘汰发生在 probation 区域，如果总容量超出，则会对 probation 队列的首尾元素(也就是从 window 晋升和从 probation 淘汰的)根据 TinyLFU 记录的频率进行PK，淘汰访问频率低的</li>
   *     <li>probation 队列元素被访问后，该元素会晋升到 protected 区域</li>
   *     <li>protected 区域的元素是比较稳定的，如果容量超出，则淘汰的元素会重新进入到 probation 队列，在 probation 队列中重新进行淘汰</li>
   * </ol>
   * <p>
   *
   * Evicts entries if the cache exceeds the maximum.
   */
  @GuardedBy("evictionLock")
  void evictEntries() {
    // evicts() 方法会被动态生成的子类重写，决定是否需要进行缓存驱逐
    if (!evicts()) {
      return;
    }
    // 驱逐 window 区域的数据
    int candidates = evictFromWindow();
    // 驱逐 main 区域的数据
    evictFromMain(candidates);
  }

  /**
   * 驱逐 window 区域的数据：
   * <ol>
   *     <li>死循环清理，直到 window 区域元素数量低于阈值时终止</li>
   *     <li>根据 node 的权重判断，权重为0的 node 跳过清理</li>
   *     <li>先设置 node 所属的 queueType 为 probation</li>
   *     <li>将该 node 从 window 区域移除</li>
   *     <li>将该 node 加入到 probation 区域的尾部</li>
   *     <li>candidates 的数量+1</li>
   * </ol>
   * <p>
   *
   * Evicts entries from the window space into the main space while the window size exceeds a
   * maximum.
   *
   * @return the number of candidate entries evicted from the window space
   */
  @GuardedBy("evictionLock")
  int evictFromWindow() {
    // 从 window 区域淘汰的元素数量
    int candidates = 0;
    // 从 window 区域的头部开始移除
    Node<K, V> node = accessOrderWindowDeque().peek();
    // 死循环，直到 window 区域的元素数量低于阈值
    while (windowWeightedSize() > windowMaximum()) {
      // The pending operations will adjust the size to reflect the correct weight
      if (node == null) {
        break;
      }
      // 当前元素的下一个元素
      Node<K, V> next = node.getNextInAccessOrder();
      // 根据权重判断是否需要清理
      if (node.getPolicyWeight() != 0) {
        // 先设置 node 所属的 queueType 为 probation
        node.makeMainProbation();
        // 从 window 区域移除该 node
        accessOrderWindowDeque().remove(node);
        // 将该 node 加入到 probation 区域的尾部
        accessOrderProbationDeque().add(node);
        // 数量+1
        candidates++;

        // 更新 window 区域当前的元素数量
        setWindowWeightedSize(windowWeightedSize() - node.getPolicyWeight());
      }
      node = next;
    }

    return candidates;
  }

  /**
   * 驱逐 main 区域的缓存：
   * <ol>
   *     <li>死循环，直到总缓存的元素数量低于阈值</li>
   *     <li>(candidate == null) && (victim== null) 的情况，从 protected 区域和 window 区域驱逐</li>
   *     <li>(victim == null) || (candidate == null) 的情况，立即淘汰不为 null 的那个</li>
   *     <li>(victim.key == null) || (candidate.key == null) 的情况，立即淘汰为 null 的</li>
   *     <li>candidate 的权重大于最大容量限制的情况，立即淘汰 candidate</li>
   *     <li>以上条件都不符合，将 victim 和 candidate 根据访问频率进行PK</li>
   * </ol>
   *
   * Evicts entries from the main space if the cache exceeds the maximum capacity. The main space
   * determines whether admitting an entry (coming from the window space) is preferable to retaining
   * the eviction policy's victim. This decision is made using a frequency filter so that the
   * least frequently used entry is removed.
   *
   * The window space's candidates were previously placed in the MRU position and the eviction
   * policy's victim is at the LRU position. The two ends of the queue are evaluated while an
   * eviction is required. The number of remaining candidates is provided and decremented on
   * eviction, so that when there are no more candidates the victim is evicted.
   *
   * @param candidates the number of candidate entries evicted from the window space
   */
  @GuardedBy("evictionLock")
  void evictFromMain(int candidates) {

    // 选取出 victim 的队列
    int victimQueue = PROBATION;
    // probation 区域首部的元素
    Node<K, V> victim = accessOrderProbationDeque().peekFirst();
    // probation 区域尾部的元素，也就是从 window 区域淘汰晋升进来的
    Node<K, V> candidate = accessOrderProbationDeque().peekLast();

    // 死循环，直到总缓存的元素数量低于阈值
    while (weightedSize() > maximum()) {
      // 从 window 区域淘汰的数量为0，直接获取 window 尾部的元素作为 candidate
      if (candidates == 0) {
        candidate = accessOrderWindowDeque().peekLast();
      }

      // 1. (candidate == null) && (victim== null) 的情况，从 protected 区域和 window 区域驱逐
      if ((candidate == null) && (victim == null)) {
        if (victimQueue == PROBATION) {
          victim = accessOrderProtectedDeque().peekFirst();
          victimQueue = PROTECTED;
          continue;
        } else if (victimQueue == PROTECTED) {
          victim = accessOrderWindowDeque().peekFirst();
          victimQueue = WINDOW;
          continue;
        }

        // The pending operations will adjust the size to reflect the correct weight
        break;
      }

      // 跳过权重为0的node
      if ((victim != null) && (victim.getPolicyWeight() == 0)) {
        victim = victim.getNextInAccessOrder();
        continue;
      } else if ((candidate != null) && (candidate.getPolicyWeight() == 0)) {
        candidate = (candidates > 0)
            ? candidate.getPreviousInAccessOrder()
            : candidate.getNextInAccessOrder();
        candidates--;
        continue;
      }

      // 2. (victim == null) || (candidate == null) 的情况，立即淘汰不为 null 的那个

      // victim == null，立即淘汰 candidate
      if (victim == null) {
        @SuppressWarnings("NullAway")
        Node<K, V> previous = candidate.getPreviousInAccessOrder();
        Node<K, V> evict = candidate;
        candidate = previous;
        candidates--;
        evictEntry(evict, RemovalCause.SIZE, 0L);
        continue;
      } else if (candidate == null) {
        // candidate == null，立即淘汰 victim
        Node<K, V> evict = victim;
        victim = victim.getNextInAccessOrder();
        evictEntry(evict, RemovalCause.SIZE, 0L);
        continue;
      }

      // 3. (victim.key == null) || (candidate.key == null) 的情况，立即淘汰为 null 的
      K victimKey = victim.getKey();
      K candidateKey = candidate.getKey();
      if (victimKey == null) {
        Node<K, V> evict = victim;
        victim = victim.getNextInAccessOrder();
        evictEntry(evict, RemovalCause.COLLECTED, 0L);
        continue;
      } else if (candidateKey == null) {
        Node<K, V> evict = candidate;
        candidate = (candidates > 0)
            ? candidate.getPreviousInAccessOrder()
            : candidate.getNextInAccessOrder();
        candidates--;
        evictEntry(evict, RemovalCause.COLLECTED, 0L);
        continue;
      }

      // 4. candidate 的权重大于最大容量限制的情况，立即淘汰 candidate
      if (candidate.getPolicyWeight() > maximum()) {
        Node<K, V> evict = candidate;
        candidate = (candidates > 0)
            ? candidate.getPreviousInAccessOrder()
            : candidate.getNextInAccessOrder();
        candidates--;
        evictEntry(evict, RemovalCause.SIZE, 0L);
        continue;
      }

      // 5. 以上条件都不符合，将 victim 和 candidate 根据访问频率进行PK，淘汰访问频率低的
      // 这里的比较规则是，依次对所有 candidate 做PK，胜出的留下并挑选下一个继续，而 victim 则只在失败被淘汰才会更新为下一个 node
      candidates--;
      // 决定 candidate 和 victim 哪一个应该被淘汰
      if (admit(candidateKey, victimKey)) {
        // victimKey 的访问频率较低，淘汰 victim
        Node<K, V> evict = victim;
        victim = victim.getNextInAccessOrder();
        evictEntry(evict, RemovalCause.SIZE, 0L);
        // 这里PK过的 candidate 不会在下一轮继续，而是选取下一个 candidate 与 新的 victim 进行PK
        candidate = candidate.getPreviousInAccessOrder();
      } else {
        // candidate 的访问频率比较低，淘汰 candidate
        Node<K, V> evict = candidate;
        // 继续选择下一个 candidate 进行PK，但是 victim 还是当前的这个
        candidate = (candidates > 0)
            ? candidate.getPreviousInAccessOrder()
            : candidate.getNextInAccessOrder();
        evictEntry(evict, RemovalCause.SIZE, 0L);
      }
    }
  }

  /**
   * 决定 candidate 和 victim 哪一个应该被淘汰：
   * <ol>
   *     <li>candidate 的访问频率大于 victim，淘汰 victim</li>
   *     <li>candidate 的访问频率小于 victim 且 candidate 的访问频率 <= 5，淘汰 candidate</li>
   *     <li>candidate 的访问频率小于 victim 但是又大于5，随机淘汰</li>
   * </ol>
   *
   * Determines if the candidate should be accepted into the main space, as determined by its
   * frequency relative to the victim. A small amount of randomness is used to protect against hash
   * collision attacks, where the victim's frequency is artificially raised so that no new entries
   * are admitted.
   *
   * @param candidateKey the key for the entry being proposed for long term retention
   * @param victimKey the key for the entry chosen by the eviction policy for replacement
   * @return if the candidate should be admitted and the victim ejected
   */
  @GuardedBy("evictionLock")
  boolean admit(K candidateKey, K victimKey) {
    // victim 的访问频率
    int victimFreq = frequencySketch().frequency(victimKey);
    // candidate 的访问频率
    int candidateFreq = frequencySketch().frequency(candidateKey);

    // 1. candidate 的访问频率大于 victim，淘汰 victim
    if (candidateFreq > victimFreq) {
      return true;
    } else if (candidateFreq <= 5) {
      // 2. candidate 的访问频率小于 victim 且 candidate 的访问频率 <= 5，淘汰 candidate
      // 主要是因为 candidate 的访问频率 >5 时再次被访问的概率也很高，处于一个上升期
      return false;
    }
    // 3. candidate 的访问频率小于 victim 但是又大于5，随机淘汰
    int random = ThreadLocalRandom.current().nextInt();
    return ((random & 127) == 0);
  }

  /**
   * 清除所有过期数据；
   * 这里不是通过遍历 data 实现的，而是通过顺序写队列和顺序读队列，因为这些队列是有序的，因此在清理过程中第一次遇到未过期数据时就代表后面没有过期数据可以停止清理了
   * <p>
   *
   * Expires entries that have expired by access, write, or variable.
   */
  @GuardedBy("evictionLock")
  void expireEntries() {
    long now = expirationTicker().read();
    // 设置了 afterAccess，最后一次访问(读|写)后的指定时间失效，从三个顺序读队列不断取元素判断是否过期、驱逐
    expireAfterAccessEntries(now);
    // 设置了 afterWrite，最后一次写入后的指定时间失效，从顺序写队列不断取元素判断是否过期、驱逐
    expireAfterWriteEntries(now);
    // 设置了自定义过期策略，时间轮淘汰，自定义过期策略时会将 node 丢到时间轮中，因此需要遍历时间轮来找到所有过期数据
    expireVariableEntries(now);

    Pacer pacer = pacer();
    if (pacer != null) {
      long delay = getExpirationDelay(now);
      if (delay == Long.MAX_VALUE) {
        pacer.cancel();
      } else {
        pacer.schedule(executor, drainBuffersTask, now, delay);
      }
    }
  }

  /** Expires entries in the access-order queue. */
  @GuardedBy("evictionLock")
  void expireAfterAccessEntries(long now) {
    if (!expiresAfterAccess()) {
      return;
    }

    expireAfterAccessEntries(accessOrderWindowDeque(), now);
    if (evicts()) {
      expireAfterAccessEntries(accessOrderProbationDeque(), now);
      expireAfterAccessEntries(accessOrderProtectedDeque(), now);
    }
  }

  /** Expires entries in an access-order queue. */
  @GuardedBy("evictionLock")
  void expireAfterAccessEntries(AccessOrderDeque<Node<K, V>> accessOrderDeque, long now) {
    long duration = expiresAfterAccessNanos();
    for (;;) {
      Node<K, V> node = accessOrderDeque.peekFirst();
      if ((node == null) || ((now - node.getAccessTime()) < duration)
          || !evictEntry(node, RemovalCause.EXPIRED, now)) {
        return;
      }
    }
  }

  /** Expires entries on the write-order queue. */
  @GuardedBy("evictionLock")
  void expireAfterWriteEntries(long now) {
    if (!expiresAfterWrite()) {
      return;
    }
    long duration = expiresAfterWriteNanos();
    for (;;) {
      Node<K, V> node = writeOrderDeque().peekFirst();
      if ((node == null) || ((now - node.getWriteTime()) < duration)
          || !evictEntry(node, RemovalCause.EXPIRED, now)) {
        break;
      }
    }
  }

  /** Expires entries in the timer wheel. */
  @GuardedBy("evictionLock")
  void expireVariableEntries(long now) {
    if (expiresVariable()) {
      timerWheel().advance(now);
    }
  }

  /** Returns the duration until the next item expires, or {@link Long.MAX_VALUE} if none. */
  @GuardedBy("evictionLock")
  long getExpirationDelay(long now) {
    long delay = Long.MAX_VALUE;
    if (expiresAfterAccess()) {
      Node<K, V> node = accessOrderWindowDeque().peekFirst();
      if (node != null) {
        delay = Math.min(delay, expiresAfterAccessNanos() - (now - node.getAccessTime()));
      }
      if (evicts()) {
        node = accessOrderProbationDeque().peekFirst();
        if (node != null) {
          delay = Math.min(delay, expiresAfterAccessNanos() - (now - node.getAccessTime()));
        }
        node = accessOrderProtectedDeque().peekFirst();
        if (node != null) {
          delay = Math.min(delay, expiresAfterAccessNanos() - (now - node.getAccessTime()));
        }
      }
    }
    if (expiresAfterWrite()) {
      Node<K, V> node = writeOrderDeque().peekFirst();
      if (node != null) {
        delay = Math.min(delay, expiresAfterWriteNanos() - (now - node.getWriteTime()));
      }
    }
    if (expiresVariable()) {
      delay = Math.min(delay, timerWheel().getExpirationDelay());
    }
    return delay;
  }

  /** Returns if the entry has expired. */
  @SuppressWarnings("ShortCircuitBoolean")
  // node 是否过期
  boolean hasExpired(Node<K, V> node, long now) {
    if (isComputingAsync(node)) {
      return false;
    }
    // 这里会校验所有的过期策略，包括 基于最后访问时间(空闲时间)、基于最后写入时间(存活时间)、基于自定义过期策略，只要任一校验通过即可
    // 正是因为这些校验，因此 afterRead 操作才需要尽量立即清理缓冲区，因为如果不及时更新最后写入时间，这次的读操作读取到的就还是上次的写入时间，
    // 如果上次写入时间到期就会重新加载/驱逐进而导致最近的写操作丢失
    return (expiresAfterAccess() && (now - node.getAccessTime() >= expiresAfterAccessNanos()))
        | (expiresAfterWrite() && (now - node.getWriteTime() >= expiresAfterWriteNanos()))
        | (expiresVariable() && (now - node.getVariableTime() >= 0));
  }

  /**
   * 驱逐 node
   * <p>
   *
   * Attempts to evict the entry based on the given removal cause. A removal may be ignored if the
   * entry was updated and is no longer eligible for eviction.
   *
   * @param node the entry to evict
   * @param cause the reason to evict
   * @param now the current time, used only if expiring
   * @return if the entry was evicted
   */
  @GuardedBy("evictionLock")
  @SuppressWarnings({"PMD.CollapsibleIfStatements", "GuardedByChecker", "NullAway"})
  boolean evictEntry(Node<K, V> node, RemovalCause cause, long now) {
    K key = node.getKey();
    @SuppressWarnings("unchecked")
    V[] value = (V[]) new Object[1];
    // node 是否移除
    boolean[] removed = new boolean[1];
    // node 是否复活
    boolean[] resurrect = new boolean[1];
    // node 的key
    Object keyReference = node.getKeyReference();
    // 移除原因
    RemovalCause[] actualCause = new RemovalCause[1];

    // 这里主要用于移除的善后工作，比如移除原因、移除通知、
    data.computeIfPresent(keyReference, (k, n) -> {
      if (n != node) {
        return n;
      }

      // 这里主要在将 node 的状态置为 dead，并进行移除事件回调(节点、原因...)
      synchronized (n) {
        value[0] = n.getValue();

        // 找到移除原因
        if ((key == null) || (value[0] == null)) {
          actualCause[0] = RemovalCause.COLLECTED;
        } else if (cause == RemovalCause.COLLECTED) {
          // 如果是 GC 移除的直接复活原 node
          resurrect[0] = true;
          return n;
        } else {
          actualCause[0] = cause;
        }

        // 移除原因是过期
        if (actualCause[0] == RemovalCause.EXPIRED) {
          boolean expired = false;
          if (expiresAfterAccess()) {
            expired |= ((now - n.getAccessTime()) >= expiresAfterAccessNanos());
          }
          if (expiresAfterWrite()) {
            expired |= ((now - n.getWriteTime()) >= expiresAfterWriteNanos());
          }
          if (expiresVariable()) {
            expired |= (n.getVariableTime() <= now);
          }
          // 实际没有过期，复活原 node，不移除
          if (!expired) {
            resurrect[0] = true;
            return n;
          }
        }
        // 移除原因是空间不足
        else if (actualCause[0] == RemovalCause.SIZE) {
          int weight = node.getWeight();
          // 节点的权重为0，复活原 node，不移除
          if (weight == 0) {
            resurrect[0] = true;
            return n;
          }
        }

        // 到这里说明 node 是真的应该被移除，触发移除事件通知
        notifyEviction(key, value[0], actualCause[0]);
        // 设置 node 状态为 dead 并且减少所在区域的权重
        makeDead(n);
      }


      // 丢弃该key正在进行的刷新任务
      discardRefresh(keyReference);
      removed[0] = true;

      // 返回null，表示将该 value 从 ConcurrentHashMap 中移除
      return null;
    });

    // 节点被复活，没有驱逐
    if (resurrect[0]) {
      return false;
    }

    // If the eviction fails due to a concurrent removal of the victim, that removal may cancel out
    // the addition that triggered this eviction. The victim is eagerly unlinked before the removal
    // task so that if an eviction is still required then a new victim will be chosen for removal.
    // 判断是否配置了驱逐策略，将 node 从其所属的队列中移除
    if (node.inWindow() && (evicts() || expiresAfterAccess())) {
      // W-TinyLFU 算法，window 部分
      accessOrderWindowDeque().remove(node);
    } else if (evicts()) {
      if (node.inMainProbation()) {
        // W-TinyLFU 算法，probation 部分
        accessOrderProbationDeque().remove(node);
      } else {
        // W-TinyLFU 算法，protect 部分
        accessOrderProtectedDeque().remove(node);
      }
    }

    // 从 writeOrderDeque 中移除
    if (expiresAfterWrite()) {
      writeOrderDeque().remove(node);
    } else if (expiresVariable()) {
      // 从时间轮中移除
      timerWheel().deschedule(node);
    }

    // 节点移除成功
    if (removed[0]) {
      // 统计
      statsCounter().recordEviction(node.getWeight(), actualCause[0]);

      // Notify the listener only if the entry was evicted. This must be performed as the last
      // step during eviction to safeguard against the executor rejecting the notification task.
      // 移除事件通知
      notifyRemoval(key, value[0], actualCause[0]);
    } else {
      // Eagerly decrement the size to potentially avoid an additional eviction, rather than wait
      // for the removal task to do it on the next maintenance cycle.
      // 移除失败，将 node 设置为 dead 状态
      makeDead(node);
    }

    // 返回移除结果
    return true;
  }

  /** Adapts the eviction policy to towards the optimal recency / frequency configuration. */
  @GuardedBy("evictionLock")
  void climb() {
    if (!evicts()) {
      return;
    }

    // 确定 window 区域需要调整的大小
    determineAdjustment();
    // 如果 protected 区域的缓存有溢出，将溢出部分移动到 probation 区域
    demoteFromMainProtected();
    // 需要调整的大小
    long amount = adjustment();
    if (amount == 0) {
      // 调整大小 == 0，不调整
      return;
    } else if (amount > 0) {
      // 调整大小 > 0，增大 window 区域
      increaseWindow();
    } else {
      // 调整大小 < 0，减小 window 区域
      decreaseWindow();
    }
  }

  /** Calculates the amount to adapt the window by and sets {@link #adjustment()} accordingly. */
  @GuardedBy("evictionLock")
  void determineAdjustment() {
    if (frequencySketch().isNotInitialized()) {
      setPreviousSampleHitRate(0.0);
      setMissesInSample(0);
      setHitsInSample(0);
      return;
    }

    // 总的请求量 = 命中 + 错失
    int requestCount = hitsInSample() + missesInSample();
    if (requestCount < frequencySketch().sampleSize) {
      return;
    }

    // 命中率
    double hitRate = (double) hitsInSample() / requestCount;
    // 本次命中率减去上次命中率的差距
    double hitRateChange = hitRate - previousSampleHitRate();
    // 本次调整的大小，由命中率差值和上次的步长决定
    double amount = (hitRateChange >= 0) ? stepSize() : -stepSize();
    // 下次调整大小
    double nextStepSize = (Math.abs(hitRateChange) >= HILL_CLIMBER_RESTART_THRESHOLD)
        ? HILL_CLIMBER_STEP_PERCENT * maximum() * (amount >= 0 ? 1 : -1)
        : HILL_CLIMBER_STEP_DECAY_RATE * amount;

    setPreviousSampleHitRate(hitRate);
    setAdjustment((long) amount);
    setStepSize(nextStepSize);
    setMissesInSample(0);
    setHitsInSample(0);
  }

  /**
   * Increases the size of the admission window by shrinking the portion allocated to the main
   * space. As the main space is partitioned into probation and protected regions (80% / 20%), for
   * simplicity only the protected is reduced. If the regions exceed their maximums, this may cause
   * protected items to be demoted to the probation region and probation items to be demoted to the
   * admission window.
   */
  @GuardedBy("evictionLock")
  void increaseWindow() {
    if (mainProtectedMaximum() == 0) {
      return;
    }

    long quota = Math.min(adjustment(), mainProtectedMaximum());
    setMainProtectedMaximum(mainProtectedMaximum() - quota);
    setWindowMaximum(windowMaximum() + quota);
    demoteFromMainProtected();

    for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
      Node<K, V> candidate = accessOrderProbationDeque().peek();
      boolean probation = true;
      if ((candidate == null) || (quota < candidate.getPolicyWeight())) {
        candidate = accessOrderProtectedDeque().peek();
        probation = false;
      }
      if (candidate == null) {
        break;
      }

      int weight = candidate.getPolicyWeight();
      if (quota < weight) {
        break;
      }

      quota -= weight;
      if (probation) {
        accessOrderProbationDeque().remove(candidate);
      } else {
        setMainProtectedWeightedSize(mainProtectedWeightedSize() - weight);
        accessOrderProtectedDeque().remove(candidate);
      }
      setWindowWeightedSize(windowWeightedSize() + weight);
      accessOrderWindowDeque().add(candidate);
      candidate.makeWindow();
    }

    setMainProtectedMaximum(mainProtectedMaximum() + quota);
    setWindowMaximum(windowMaximum() - quota);
    setAdjustment(quota);
  }

  /** Decreases the size of the admission window and increases the main's protected region. */
  @GuardedBy("evictionLock")
  void decreaseWindow() {
    if (windowMaximum() <= 1) {
      return;
    }

    long quota = Math.min(-adjustment(), Math.max(0, windowMaximum() - 1));
    setMainProtectedMaximum(mainProtectedMaximum() + quota);
    setWindowMaximum(windowMaximum() - quota);

    for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
      Node<K, V> candidate = accessOrderWindowDeque().peek();
      if (candidate == null) {
        break;
      }

      int weight = candidate.getPolicyWeight();
      if (quota < weight) {
        break;
      }

      quota -= weight;
      setWindowWeightedSize(windowWeightedSize() - weight);
      accessOrderWindowDeque().remove(candidate);
      accessOrderProbationDeque().add(candidate);
      candidate.makeMainProbation();
    }

    setMainProtectedMaximum(mainProtectedMaximum() - quota);
    setWindowMaximum(windowMaximum() + quota);
    setAdjustment(-quota);
  }

  /** Transfers the nodes from the protected to the probation region if it exceeds the maximum. */
  @GuardedBy("evictionLock")
  void demoteFromMainProtected() {
    long mainProtectedMaximum = mainProtectedMaximum();
    long mainProtectedWeightedSize = mainProtectedWeightedSize();
    if (mainProtectedWeightedSize <= mainProtectedMaximum) {
      return;
    }

    for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
      if (mainProtectedWeightedSize <= mainProtectedMaximum) {
        break;
      }

      Node<K, V> demoted = accessOrderProtectedDeque().poll();
      if (demoted == null) {
        break;
      }
      demoted.makeMainProbation();
      accessOrderProbationDeque().add(demoted);
      mainProtectedWeightedSize -= demoted.getPolicyWeight();
    }
    setMainProtectedWeightedSize(mainProtectedWeightedSize);
  }

  /**
   * 访问操作的后置处理，将读到的 node 丢到 readBuffer 中，如果 readBuffer 满了就顺带清理一波
   * 注意：这个方法不是只有 get() 才会调用，而是在实际有读操作发生的时候就会调用，比如 putIfAbsent() 操作就会先读取旧值，这时候也会进行 afterRead()
   * <p>
   *
   * Performs the post-processing work required after a read.
   *
   * @param node the entry in the page replacement policy
   * @param now the current time, in nanoseconds
   * @param recordHit if the hit count should be incremented
   * @return the refreshed value if immediately loaded, else null
   */
  @Nullable V afterRead(Node<K, V> node, long now, boolean recordHit) {
    // 缓存情况统计
    if (recordHit) {
      statsCounter().recordHits(1);
    }

    // 注意这里和 afterWrite() 的区别，afterRead 中不要求立即执行 scheduleDrainBuffers，而是先尝试丢到读缓冲区中，只有读缓冲区满了才进行清理
    // 这是因为 read 操作并不需要保证不丢，延迟高点也可以接受，所以不需要每次 read 都清理缓冲区

    // 将本次读操作丢到读缓冲区，如果缓存区满了就清理缓冲区(读+写)
    // 注意这里没有进行任何的重试操作，也就是说写入读缓冲区失败了就直接丢弃
    boolean delayable = skipReadBuffer() || (readBuffer.offer(node) != Buffer.FULL);
    if (shouldDrainBuffers(delayable)) {
      // 线程池异步清理缓冲区，这里还会进行缓存维护操作
      scheduleDrainBuffers();
    }

    // 是否需要自动刷新，这里说明自动刷新操作是在读操作后才触发的，没有读操作则缓存不会自动刷新
    return refreshIfNeeded(node, now);
  }

  /** Returns if the cache should bypass the read buffer. */
  boolean skipReadBuffer() {
    return fastpath() && frequencySketch().isNotInitialized();
  }

  /**
   * Asynchronously refreshes the entry if eligible.
   *
   * @param node the entry in the cache to refresh
   * @param now the current time, in nanoseconds
   * @return the refreshed value if immediately loaded, else null
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  @Nullable V refreshIfNeeded(Node<K, V> node, long now) {
    if (!refreshAfterWrite()) {
      return null;
    }

    K key;
    V oldValue;
    long writeTime = node.getWriteTime();
    long refreshWriteTime = writeTime | 1L;
    Object keyReference = node.getKeyReference();
    ConcurrentMap<Object, CompletableFuture<?>> refreshes;
    if (((now - writeTime) > refreshAfterWriteNanos()) && (keyReference != null)
        && ((key = node.getKey()) != null) && ((oldValue = node.getValue()) != null)
        && ((writeTime & 1L) == 0L) && !(refreshes = refreshes()).containsKey(keyReference)
        && node.isAlive() && node.casWriteTime(writeTime, refreshWriteTime)) {
      long[] startTime = new long[1];
      @SuppressWarnings({"unchecked", "rawtypes"})
      CompletableFuture<? extends V>[] refreshFuture = new CompletableFuture[1];
      try {
        refreshes.computeIfAbsent(keyReference, k -> {
          try {
            startTime[0] = statsTicker().read();
            if (isAsync) {
              @SuppressWarnings("unchecked")
              CompletableFuture<V> future = (CompletableFuture<V>) oldValue;
              if (Async.isReady(future)) {
                @SuppressWarnings("NullAway")
                var refresh = cacheLoader.asyncReload(key, future.join(), executor);
                refreshFuture[0] = refresh;
              } else {
                // no-op if load is pending
                return future;
              }
            } else {
              @SuppressWarnings("NullAway")
              var refresh = cacheLoader.asyncReload(key, oldValue, executor);
              refreshFuture[0] = refresh;
            }
            return refreshFuture[0];
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Exception thrown when submitting refresh task", e);
            return null;
          } catch (Throwable e) {
            logger.log(Level.WARNING, "Exception thrown when submitting refresh task", e);
            return null;
          }
        });
      } finally {
        node.casWriteTime(refreshWriteTime, writeTime);
      }

      if (refreshFuture[0] == null) {
        return null;
      }

      var refreshed = refreshFuture[0].handle((newValue, error) -> {
        long loadTime = statsTicker().read() - startTime[0];
        if (error != null) {
          if (!(error instanceof CancellationException) && !(error instanceof TimeoutException)) {
            logger.log(Level.WARNING, "Exception thrown during refresh", error);
          }
          refreshes.remove(keyReference, refreshFuture[0]);
          statsCounter().recordLoadFailure(loadTime);
          return null;
        }

        @SuppressWarnings("unchecked")
        V value = (isAsync && (newValue != null)) ? (V) refreshFuture[0] : newValue;

        boolean[] discard = new boolean[1];
        V result = compute(key, (k, currentValue) -> {
          if (currentValue == null) {
            // If the entry is absent then discard the refresh and maybe notifying the listener
            discard[0] = (value != null);
            return null;
          } else if (currentValue == value) {
            // If the reloaded value is the same instance then no-op
            return currentValue;
          } else if (isAsync &&
              (newValue == Async.getIfReady((CompletableFuture<?>) currentValue))) {
            // If the completed futures hold the same value instance then no-op
            return currentValue;
          } else if ((currentValue == oldValue) && (node.getWriteTime() == writeTime)) {
            // If the entry was not modified while in-flight (no ABA) then replace
            return value;
          }
          // Otherwise, a write invalidated the refresh so discard it and notify the listener
          discard[0] = true;
          return currentValue;
        }, expiry(), /* recordLoad */ false, /* recordLoadFailure */ true);

        if (discard[0]) {
          notifyRemoval(key, value, RemovalCause.REPLACED);
        }
        if (newValue == null) {
          statsCounter().recordLoadFailure(loadTime);
        } else {
          statsCounter().recordLoadSuccess(loadTime);
        }

        refreshes.remove(keyReference, refreshFuture[0]);
        return result;
      });
      return Async.getIfReady(refreshed);
    }

    return null;
  }

  /**
   * Returns the expiration time for the entry after being created.
   *
   * @param key the key of the entry that was created
   * @param value the value of the entry that was created
   * @param expiry the calculator for the expiration time
   * @param now the current time, in nanoseconds
   * @return the expiration time
   */
  long expireAfterCreate(@Nullable K key, @Nullable V value,
      Expiry<? super K, ? super V> expiry, long now) {
    if (expiresVariable() && (key != null) && (value != null)) {
      long duration = expiry.expireAfterCreate(key, value, now);
      return isAsync ? (now + duration) : (now + Math.min(duration, MAXIMUM_EXPIRY));
    }
    return 0L;
  }

  /**
   * Returns the expiration time for the entry after being updated.
   *
   * @param node the entry in the page replacement policy
   * @param key the key of the entry that was updated
   * @param value the value of the entry that was updated
   * @param expiry the calculator for the expiration time
   * @param now the current time, in nanoseconds
   * @return the expiration time
   */
  long expireAfterUpdate(Node<K, V> node, @Nullable K key,
      @Nullable V value, Expiry<? super K, ? super V> expiry, long now) {
    if (expiresVariable() && (key != null) && (value != null)) {
      long currentDuration = Math.max(1, node.getVariableTime() - now);
      long duration = expiry.expireAfterUpdate(key, value, now, currentDuration);
      return isAsync ? (now + duration) : (now + Math.min(duration, MAXIMUM_EXPIRY));
    }
    return 0L;
  }

  /**
   * Returns the access time for the entry after a read.
   *
   * @param node the entry in the page replacement policy
   * @param key the key of the entry that was read
   * @param value the value of the entry that was read
   * @param expiry the calculator for the expiration time
   * @param now the current time, in nanoseconds
   * @return the expiration time
   */
  // 返回 read 操作后的下次失效时间
  long expireAfterRead(Node<K, V> node, @Nullable K key, @Nullable V value, Expiry<K, V> expiry, long now) {
    if (expiresVariable() && (key != null) && (value != null)) {
      long currentDuration = Math.max(1, node.getVariableTime() - now);
      long duration = expiry.expireAfterRead(key, value, now, currentDuration);
      return isAsync ? (now + duration) : (now + Math.min(duration, MAXIMUM_EXPIRY));
    }
    return 0L;
  }

  /**
   * 尝试在读取操作后更新过期时间
   * <p>
   *
   * Attempts to update the access time for the entry after a read.
   *
   * @param node the entry in the page replacement policy
   * @param key the key of the entry that was read
   * @param value the value of the entry that was read
   * @param expiry the calculator for the expiration time
   * @param now the current time, in nanoseconds
   */
  void tryExpireAfterRead(Node<K, V> node, @Nullable K key, @Nullable V value, Expiry<K, V> expiry, long now) {
    // 没有自定义缓存过期策略时不执行
    if (!expiresVariable() || (key == null) || (value == null)) {
      return;
    }

    long variableTime = node.getVariableTime();
    long currentDuration = Math.max(1, variableTime - now);
    if (isAsync && (currentDuration > MAXIMUM_EXPIRY)) {
      // expireAfterCreate has not yet set the duration after completion
      return;
    }

    long duration = expiry.expireAfterRead(key, value, now, currentDuration);
    if (duration != currentDuration) {
      long expirationTime = isAsync ? (now + duration) : (now + Math.min(duration, MAXIMUM_EXPIRY));
      node.casVariableTime(variableTime, expirationTime);
    }
  }

  /**
   * 设置过期时间，这用在自定义 Expire 过期策略中
   */
  void setVariableTime(Node<K, V> node, long expirationTime) {
    if (expiresVariable()) {
      node.setVariableTime(expirationTime);
    }
  }

  void setWriteTime(Node<K, V> node, long now) {
    if (expiresAfterWrite() || refreshAfterWrite()) {
      node.setWriteTime(now & ~1L);
    }
  }

  void setAccessTime(Node<K, V> node, long now) {
    if (expiresAfterAccess()) {
      node.setAccessTime(now);
    }
  }


  /**
   * 写操作的后置处理，主要是将写行为封装为对应的task 丢到 writeBuffer 中，如果写缓冲区满了就顺带进行一次缓存维护。
   * 写操作相关的 Task：{@link AddTask}、{@link UpdateTask}、{@link RemovalTask}。
   * 在这些 Task 中都会对 {@link #writeOrderDeque()} 进行操作。
   * 注意：这个方法不是只有 put() 才会调用，而是在实际有写操作发生的时候就会调用，比如 get() 失败执行缓存自动加载时就会进行写入，这时候也会进行 afterWrite()。
   * <p>
   *
   * Performs the post-processing work required after a write.
   *
   * @param task the pending operation to be applied
   */
  void afterWrite(Runnable task) {
    // 注意这里和 afterRead() 的区别，afterRead() 在缓冲区空闲的时候不会立即清理缓冲区，只有缓冲区满才会进行一次清理，而 afterWrite() 每次都会清理缓冲区；
    // 这里还做了重试保证 write 操作不会丢失，并且会在 offer 成功后尽量进行一次缓存清理任务的调度，用于降低延迟，防止发生写入丢失

    // 循环重试，最大重试100次
    for (int i = 0; i < WRITE_BUFFER_RETRIES; i++) {
      // 加入写缓冲区成功，会立即调度清理缓冲区的任务，即使缓冲区还是空闲的
      if (writeBuffer.offer(task)) {
        // 在加入写缓冲区成功后会立即调度清理缓冲区任务，所以写缓冲区不需要太大
        scheduleAfterWrite();
        return;
      }
      // 加入写缓冲区失败，清理读、写缓冲区
      scheduleDrainBuffers();
    }

    // 重试了100次都加入写缓冲区失败，这是天选倒霉蛋吧，那就直接同步调用缓存维护吧

    // In scenarios where the writing threads cannot make progress then they attempt to provide
    // assistance by performing the eviction work directly. This can resolve cases where the
    // maintenance task is scheduled but not running. That might occur due to all of the executor's
    // threads being busy (perhaps writing into this cache), the write rate greatly exceeds the
    // consuming rate, priority inversion, or if the executor silently discarded the maintenance
    // task. Unfortunately this cannot resolve when the eviction is blocked waiting on a long-
    // running computation due to an eviction listener, the victim being computed on by other write,
    // or the victim residing in the same hash bin as a computing entry. In those cases a warning is
    // logged to encourage the application to decouple these computations from the map operations.

    // 加锁
    lock();
    try {
      // 维护缓存，这里还把 afterWrite() 中的 task 作为参数传到了 maintenance() 方法中，来保证缓冲区满的情况下在缓存维护过程中将这个 task 执行掉，避免丢失
      maintenance(task);
    } catch (RuntimeException e) {
      logger.log(Level.ERROR, "Exception thrown when performing the maintenance task", e);
    } finally {
      evictionLock.unlock();
    }
  }

  /** Acquires the eviction lock. */
  void lock() {
    long remainingNanos = WARN_AFTER_LOCK_WAIT_NANOS;
    long end = System.nanoTime() + remainingNanos;
    boolean interrupted = false;
    try {
      for (;;) {
        try {
          if (evictionLock.tryLock(remainingNanos, TimeUnit.NANOSECONDS)) {
            return;
          }
          logger.log(Level.WARNING, "The cache is experiencing excessive wait times for acquiring "
              + "the eviction lock. This may indicate that a long-running computation has halted "
              + "eviction when trying to remove the victim entry. Consider using AsyncCache to "
              + "decouple the computation from the map operation.", new TimeoutException());
          evictionLock.lock();
          return;
        } catch (InterruptedException e) {
          remainingNanos = end - System.nanoTime();
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Conditionally schedules the asynchronous maintenance task after a write operation. If the
   * task status was IDLE or REQUIRED then the maintenance task is scheduled immediately. If it
   * is already processing then it is set to transition to REQUIRED upon completion so that a new
   * execution is triggered by the next operation.
   */
  void scheduleAfterWrite() {
    for (;;) {
      switch (drainStatus()) {
        case IDLE:
          casDrainStatus(IDLE, REQUIRED);
          scheduleDrainBuffers();
          return;
        case REQUIRED:
          scheduleDrainBuffers();
          return;
        case PROCESSING_TO_IDLE:
          if (casDrainStatus(PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED)) {
            return;
          }
          continue;
        case PROCESSING_TO_REQUIRED:
          return;
        default:
          throw new IllegalStateException();
      }
    }
  }

  /**
   * 清理读、写缓冲区，这里会触发缓存维护操作。
   * 注意这个操作在读写缓冲区任意一个满的时候都会执行。
   * <p>
   *
   * Attempts to schedule an asynchronous task to apply the pending operations to the page
   * replacement policy. If the executor rejects the task then it is run directly.
   */
  void scheduleDrainBuffers() {
    // 缓冲区正在清理中，直接返回
    if (drainStatus() >= PROCESSING_TO_IDLE) {
      return;
    }

    // 对缓冲区的清理过程加锁
    if (evictionLock.tryLock()) {
      try {
        int drainStatus = drainStatus();
        if (drainStatus >= PROCESSING_TO_IDLE) {
          return;
        }

        // 原子更新缓冲区状态为处理中
        setDrainStatusRelease(PROCESSING_TO_IDLE);
        // 在线程池执行 drainBuffersTask 任务，这个 task 在创建缓存的时候就已经初始化了，作用就是清理缓冲区
        executor.execute(drainBuffersTask);

      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown when submitting maintenance task", t);
        // 提交任务到线程池执行出错，直接同步执行缓存维护工作
        maintenance(/* ignored */ null);
      } finally {
        // 释放锁
        evictionLock.unlock();
      }
    }
  }

  /**
   * 主动维护缓存，常用于在读频率非常低的情况下提供主动清理缓存中已过期数据的方法
   */
  @Override
  public void cleanUp() {
    try {
      performCleanUp(/* ignored */ null);
    } catch (RuntimeException e) {
      logger.log(Level.ERROR, "Exception thrown when performing the maintenance task", e);
    }
  }

  /**
   * 清理缓存
   * <p>
   *
   * Performs the maintenance work, blocking until the lock is acquired.
   *
   * @param task an additional pending task to run, or {@code null} if not present
   */
  void performCleanUp(@Nullable Runnable task) {
    evictionLock.lock();
    try {
      // 维护缓存
      maintenance(task);
    } finally {
      evictionLock.unlock();
    }
    // 注意这里，缓存维护结束后会重新判断 drainStatus，如果缓冲区还需要清理那么还会走到 scheduleDrainBuffers() 方法进行清理缓存
    if ((drainStatus() == REQUIRED) && (executor == ForkJoinPool.commonPool())) {
      scheduleDrainBuffers();
    }
  }

  /**
   * 维护缓存，需要在加锁的情况下进行
   * <ol>
   *     <li>清理读缓冲区</li>
   *     <li>清理写缓冲区</li>
   *     <li>清理 key 的引用队列</li>
   *     <li>清理 value 的引用队列</li>
   *     <li>清理过期缓存</li>
   *     <li>缓存驱逐</li>
   *     <li>动态调整驱逐策略</li>
   * </ul>
   * <p>
   *
   * Performs the pending maintenance work and sets the state flags during processing to avoid
   * excess scheduling attempts. The read buffer, write buffer, and reference queues are drained,
   * followed by expiration, and size-based eviction.
   *
   * @param task an additional pending task to run, or {@code null} if not present
   */
  @GuardedBy("evictionLock")
  void maintenance(@Nullable Runnable task) {

    // 原子更新缓冲区状态，开始维护为 PROCESSING_TO_IDLE，维护结束为 IDLE
    setDrainStatusRelease(PROCESSING_TO_IDLE);

    try {
      // 清理读缓冲区，读缓冲区中存储的是 node 本身
      // 清理读缓冲区的过程主要就是将读缓冲区中的 node 进行重放，重放过程中会调用对应的策略来更新 node 在所属队列中的排序和访问频率，然后删除 node
      drainReadBuffer();

      // 清理写缓冲区，写缓冲区中存储的是根据当时写操作封装出的 task，这些 task 表示写操作后需要执行的策略
      // 清理写缓冲区的过程主要就是将写缓冲区中存储的 task 遍历执行
      drainWriteBuffer();

      // 这个 task 一般是在 afterWrite() 操作中将 task 丢到 writeBuffer 失败时带过来的任务，会在这里立即执行降低延迟
      if (task != null) {
        task.run();
      }

      // key、value 如果使用软引用/弱引用进行包装，key/value 被JVM回收时会将 对应的 key/value 放在 referenceQueue 中
      // Caffeine 通过监听key 和 value 的 referenceQueue 就可以得知被JVM回收的 key/value，然后移除相应的 node
      // 清理被GC收集的 key 关联的 node
      drainKeyReferences();
      // 清理被GC收集的 value 关联的 node
      drainValueReferences();

      // 清理过期的缓存，这里用到了上面维护的几个顺序读、写队列，会按照队列中的顺序进行过期处理，直到遇到不过期的元素停止
      expireEntries();
      // 缓存驱逐，这里体现了 W-TinyLFU 算法的思路
      evictEntries();

      // 根据最近访问/访问频率动态调整window区域缓存的大小，达到最佳命中率
      climb();

    } finally {
      // 缓存维护结束，更新缓冲状态为 IDLE
      if ((drainStatus() != PROCESSING_TO_IDLE) || !casDrainStatus(PROCESSING_TO_IDLE, IDLE)) {
        setDrainStatusOpaque(REQUIRED);
      }
    }
  }

  /**
   * 驱逐被GC回收的弱引用包装的 key 关联的 node
   * <p>
   *
   * Drains the weak key references queue.
   */
  @GuardedBy("evictionLock")
  void drainKeyReferences() {
    if (!collectKeys()) {
      return;
    }
    // keyReferenceQueue 包含了所有 weak key 被 GC 清理后的引用通知
    Reference<? extends K> keyRef;
    while ((keyRef = keyReferenceQueue().poll()) != null) {
      Node<K, V> node = data.get(keyRef);
      // 从缓存中驱逐
      if (node != null) {
        evictEntry(node, RemovalCause.COLLECTED, 0L);
      }
    }
  }

  /**
   * 驱逐被GC回收的弱引用/软引用包装的 value 关联的 node
   * <p>
   *
   * Drains the weak / soft value references queue.
   */
  @GuardedBy("evictionLock")
  void drainValueReferences() {
    if (!collectValues()) {
      return;
    }
    Reference<? extends V> valueRef;
    while ((valueRef = valueReferenceQueue().poll()) != null) {
      @SuppressWarnings("unchecked")
      InternalReference<V> ref = (InternalReference<V>) valueRef;
      Node<K, V> node = data.get(ref.getKeyReference());
      if ((node != null) && (valueRef == node.getValueReference())) {
        evictEntry(node, RemovalCause.COLLECTED, 0L);
      }
    }
  }

  /**
   * 清理 readBuffer。
   * 对有界缓存来说，readBuffer 使用的是 {@link BoundedBuffer}，它继承了 {@link StripedBuffer} 抽象类，是带状缓冲区的实现。
   * {@link BoundedBuffer} 内部定义了一个名为 {@link com.github.benmanes.caffeine.cache.BoundedBuffer.RingBuffer} 的内部类，
   * 该内部类表示一个环形缓冲区，带状缓冲区的每个桶位上都是一个环形缓冲区，每个线程根据自己的 threadId 在带状缓冲区中定位到属于自己的桶位，
   * 然后再对该桶位上的环形缓冲区进行操作，这样可以有效的减少线程竞争。
   * <p>
   *
   * Drains the read buffer.
   */
  @GuardedBy("evictionLock")
  void drainReadBuffer() {
    if (!skipReadBuffer()) {
      // 清理读缓冲区，这里清理的是带状缓冲区中的所有环形缓冲区，而不仅仅是当前线程占用的环形缓冲区
      // 这里将 accessPolicy 作为参数，清理 readBuffer 中每个 node 时都会调用一次 accessPolicy，用于调整 node 在对应队列中的排序
      readBuffer.drainTo(accessPolicy);
    }
  }

  /**
   * 访问策略，用来更新 node 在所属队列中的顺序和访问频率，方便 W-TinyLFU 算法进行驱逐
   * <p>
   *
   * Updates the node's location in the page replacement policy.
   */
  @GuardedBy("evictionLock")
  void onAccess(Node<K, V> node) {
    // 配置了缓存驱逐
    if (evicts()) {
      K key = node.getKey();
      if (key == null) {
        return;
      }
      // 增加 node 的访问频率
      frequencySketch().increment(key);

      // 更新 node 在所属顺序访问队列中的排序
      if (node.inWindow()) {
        // 访问的 node 在 window 区域，移动到 window 尾部
        reorder(accessOrderWindowDeque(), node);
      } else if (node.inMainProbation()) {
        // 访问的 node 在 probation 区域，移动到 protected 区域或移动到 probation 尾部
        reorderProbation(node);
      } else {
        // 访问的 node 在 protected 区域，移动到 protected 尾部
        reorder(accessOrderProtectedDeque(), node);
      }
      // 命中的缓存数量+1，用于后续计算缓存命中率，动态调整 window 区域大小
      setHitsInSample(hitsInSample() + 1);
    } // 配置了按最后访问时间过期
    else if (expiresAfterAccess()) {
      reorder(accessOrderWindowDeque(), node);
    }

    // 配置了自定义过期策略，丢到时间轮中调度
    if (expiresVariable()) {
      timerWheel().reschedule(node);
    }
  }

  /**
   * 处于 probation 区域的 node 被访问，更新排序或移动到 protected 区域
   * <p>
   *
   * Promote the node from probation to protected on an access.
   */
  @GuardedBy("evictionLock")
  void reorderProbation(Node<K, V> node) {
    if (!accessOrderProbationDeque().contains(node)) {
      // Ignore stale accesses for an entry that is no longer present
      return;
    } else if (node.getPolicyWeight() > mainProtectedMaximum()) {
      // node 的权重超过 protected 的元素数量限制，那么继续停留在 probation 中，只是移动到尾部
      reorder(accessOrderProbationDeque(), node);
      return;
    }

    // If the protected space exceeds its maximum, the LRU items are demoted to the probation space.
    // This is deferred to the adaption phase at the end of the maintenance cycle.

    // 更新 protected 区域的 weight
    setMainProtectedWeightedSize(mainProtectedWeightedSize() + node.getPolicyWeight());
    // 将 node 从 probation 区域中移除
    accessOrderProbationDeque().remove(node);
    // 将 node 加入到 protected 区域尾部
    accessOrderProtectedDeque().add(node);
    // 更新 node 所在的区域
    node.makeMainProtected();
  }

  /**
   * 更新 node 在指定队列中的排序(移动到末尾)
   * <p>
   *
   * Updates the node's location in the policy's deque.
   */
  static <K, V> void reorder(LinkedDeque<Node<K, V>> deque, Node<K, V> node) {
    // An entry may be scheduled for reordering despite having been removed. This can occur when the
    // entry was concurrently read while a writer was removing it. If the entry is no longer linked
    // then it does not need to be processed.
    if (deque.contains(node)) {
      deque.moveToBack(node);
    }
  }

  /**
   * 清理写缓冲区，依次执行写缓冲区中的所有 task
   * <p>
   *
   * Drains the write buffer.
   */
  @GuardedBy("evictionLock")
  void drainWriteBuffer() {
    // 遍历写缓冲区，执行缓冲区中的任务
    for (int i = 0; i <= WRITE_BUFFER_MAX; i++) {
      Runnable task = writeBuffer.poll();
      if (task == null) {
        return;
      }
      task.run();
    }
    setDrainStatusOpaque(PROCESSING_TO_REQUIRED);
  }

  /**
   * Atomically transitions the node to the <tt>dead</tt> state and decrements the
   * <tt>weightedSize</tt>.
   *
   * @param node the entry in the page replacement policy
   */
  @GuardedBy("evictionLock")
  void makeDead(Node<K, V> node) {
    synchronized (node) {
      if (node.isDead()) {
        return;
      }
      if (evicts()) {
        // The node's policy weight may be out of sync due to a pending update waiting to be
        // processed. At this point the node's weight is finalized, so the weight can be safely
        // taken from the node's perspective and the sizes will be adjusted correctly.
        if (node.inWindow()) {
          setWindowWeightedSize(windowWeightedSize() - node.getWeight());
        } else if (node.inMainProtected()) {
          setMainProtectedWeightedSize(mainProtectedWeightedSize() - node.getWeight());
        }
        setWeightedSize(weightedSize() - node.getWeight());
      }
      node.die();
    }
  }

  /**
   * 写操作之add的处理策略，包含以下几点：
   * <ol>
   *     <li>策略权重、访问频率的维护</li>
   *     <li>将 node 按权重排序丢到 window 区域，权重大的在队首，权重小的队尾，如果自定义了过期策略，还要将 node 丢到时间轮中</li>
   *     <li>若正在进行异步计算，更新 node 的最后访问时间、最后写入时间、自定义策略的可存活时间，避免在进行异步计算时 node 过期</li>
   * </ol>
   *
   * Adds the node to the page replacement policy.
   */
  final class AddTask implements Runnable {
    final Node<K, V> node;
    final int weight;

    AddTask(Node<K, V> node, int weight) {
      this.weight = weight;
      this.node = node;
    }

    @Override
    @GuardedBy("evictionLock")
    @SuppressWarnings("FutureReturnValueIgnored")
    public void run() {
      // 1. 配置了缓存驱逐时，更新 node 的访问频率和权重
      if (evicts()) {
        long weightedSize = weightedSize();
        setWeightedSize(weightedSize + weight);
        setWindowWeightedSize(windowWeightedSize() + weight);
        node.setPolicyWeight(node.getPolicyWeight() + weight);

        long maximum = maximum();
        if (weightedSize >= (maximum >>> 1)) {
          // Lazily initialize when close to the maximum
          long capacity = isWeighted() ? data.mappingCount() : maximum;
          frequencySketch().ensureCapacity(capacity);
        }

        // 频率计数
        K key = node.getKey();
        if (key != null) {
          frequencySketch().increment(key);
        }

        // 未命中的缓存数量+1
        setMissesInSample(missesInSample() + 1);
      }

      // 2. node 还存活时，将 node 入队到 window 区域，如果自定义了过期策略还需要丢到时间轮中
      boolean isAlive;
      synchronized (node) {
        isAlive = node.isAlive();
      }
      if (isAlive) {
        // 配置了 expiresAfterWrite()，添加到顺序写队列的尾部
        if (expiresAfterWrite()) {
          writeOrderDeque().add(node);
        }
        // 配置了驱逐且权重超过了 window 的总和，放在 window 区域的首部(尽快淘汰)
        if (evicts() && (weight > windowMaximum())) {
          accessOrderWindowDeque().offerFirst(node);
        } else if (evicts() || expiresAfterAccess()) {
          // 配置了驱逐或过期，加入到 window 区域的尾部
          accessOrderWindowDeque().offerLast(node);
        }
        // 配置了自定义过期策略，加入到时间轮中进行调度
        if (expiresVariable()) {
          timerWheel().schedule(node);
        }
      }

      // 3. 如果正在进行异步计算，更新 node 的可存活时间、最后访问时间、最后写入时间，避免在进行异步计算时 node 过期
      if (isComputingAsync(node)) {
        synchronized (node) {
          if (!Async.isReady((CompletableFuture<?>) node.getValue())) {
            long expirationTime = expirationTicker().read() + ASYNC_EXPIRY;
            setVariableTime(node, expirationTime);
            setAccessTime(node, expirationTime);
            setWriteTime(node, expirationTime);
          }
        }
      }

    }
  }

  /**
   * 写操作之remove的处理策略，包含以下几点：
   * <ol>
   *     <li>如果 node 在 window 区域，直接移除</li>
   *     <li>如果配置了驱逐缓存，将 node 从所属队列中移除</li>
   *     <li>如果配置了写过期策略，从顺序写队列或时间轮中移除</li>
   *     <li>设置 node 的状态为 dead</li>
   * </ol>
   * <p>
   *
   * Removes a node from the page replacement policy.
   */
  final class RemovalTask implements Runnable {
    final Node<K, V> node;

    RemovalTask(Node<K, V> node) {
      this.node = node;
    }

    @Override
    @GuardedBy("evictionLock")
    public void run() {
      // 1. node 在 window 区域，直接移除
      if (node.inWindow() && (evicts() || expiresAfterAccess())) {
        accessOrderWindowDeque().remove(node);
      }
      // 2. 配置了驱逐缓存，将 node 从所属队列中移除
      else if (evicts()) {
        if (node.inMainProbation()) {
          accessOrderProbationDeque().remove(node);
        } else {
          accessOrderProtectedDeque().remove(node);
        }
      }
      // 3. 配置了写过期策略
      // 配置了 expireAfterWrite()，从顺序写队列中移除
      if (expiresAfterWrite()) {
        writeOrderDeque().remove(node);
      } else if (expiresVariable()) {
        // 配置了自定义过期策略，从时间轮中移除
        timerWheel().deschedule(node);
      }

      // 设置 node 状态为 dead
      makeDead(node);
    }
  }

  /**
   * 写操作之update的处理策略，包含以下几点：
   * <ol>
   *     <li>配置了驱逐策略时，更新 node 所属的队列、排序、访问频率、权重等，执行访问操作的后置策略</li>
   *     <li>配置了读后过期策略时，执行访问后置策略</li>
   *     <li>配置了写后过期策略时，更新 node 在顺序写队列或时间轮中的顺序</li>
   * </ol>
   *
   * Updates the weighted size.
   */
  final class UpdateTask implements Runnable {
    final int weightDifference;
    final Node<K, V> node;

    public UpdateTask(Node<K, V> node, int weightDifference) {
      this.weightDifference = weightDifference;
      this.node = node;
    }

    @Override
    @GuardedBy("evictionLock")
    public void run() {

      // 配置了驱逐策略时
      if (evicts()) {
        int oldWeightedSize = node.getPolicyWeight();
        node.setPolicyWeight(oldWeightedSize + weightDifference);

        // 1. 更新 node 所属的队列、排序、访问频率、权重等，执行访问操作的后置策略
        if (node.inWindow()) { // node 在 window 区域中
          // node 在 window 区域中，调用 onAccess() 方法更新 node
          if (node.getPolicyWeight() <= windowMaximum()) {
            // 正常情况下，调用访问策略(相当于执行访问的后置操作)
            onAccess(node);
          } else if (accessOrderWindowDeque().contains(node)) {
            // node 的权重超出了 window 区域最大容量，丢到 window 区域的首部，尽快淘汰
            accessOrderWindowDeque().moveToFront(node);
          }
          setWindowWeightedSize(windowWeightedSize() + weightDifference);
        } else if (node.inMainProbation()) { // node 在 probation 区域中
            if (node.getPolicyWeight() <= maximum()) {
              // 正常情况下，调用访问策略(相当于执行访问的后置操作)
              onAccess(node);
            } else if (accessOrderProbationDeque().remove(node)) {
              // node 的权重超出了 probation 区域最大容量，从 probation 中移除并加入到 window 首部
              accessOrderWindowDeque().addFirst(node);
              setWindowWeightedSize(windowWeightedSize() + node.getPolicyWeight());
            }
        } else if (node.inMainProtected()) { // node 在 protected 区域中
          if (node.getPolicyWeight() <= maximum()) {
            // 正常情况下，调用访问策略(相当于执行访问的后置操作)
            onAccess(node);
            setMainProtectedWeightedSize(mainProtectedWeightedSize() + weightDifference);
          } else if (accessOrderProtectedDeque().remove(node)) {
            // node 的权重超出了 protected 区域最大容量，从 protected 中移除并加入到 window 首部
            accessOrderWindowDeque().addFirst(node);
            setWindowWeightedSize(windowWeightedSize() + node.getPolicyWeight());
            setMainProtectedWeightedSize(mainProtectedWeightedSize() - oldWeightedSize);
          } else {
            setMainProtectedWeightedSize(mainProtectedWeightedSize() - oldWeightedSize);
          }
        }
        setWeightedSize(weightedSize() + weightDifference);
      }
      // 配置了基于空闲时间的驱逐策略
      else if (expiresAfterAccess()) {
        // 执行访问后置操作
        onAccess(node);
      }

      // 2. 配置了基于存活时间/自定义的驱逐策略，更新 node 在顺序写队列或时间轮中的顺序
      // 配置了 expireAfterWrite()，更新 node 在顺序写队列中的排序
      if (expiresAfterWrite()) {
        reorder(writeOrderDeque(), node);
      } else if (expiresVariable()) {
        // 自定义了过期策略
        timerWheel().reschedule(node);
      }
    }
  }

  /* --------------- Concurrent Map Support --------------- */

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public long estimatedSize() {
    return data.mappingCount();
  }

  /**
   * 清空整个缓存，注意这是个同步操作，可能很慢
   * <ol>
   *     <li>执行 writeBuffer 中所有的 task</li>
   *     <li>移除所有 node</li>
   *     <li>丢弃 readBuffer 中所有数据</li>
   * </ol>
   */
  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  public void clear() {
    evictionLock.lock();
    try {
      long now = expirationTicker().read();

      // Apply all pending writes
      Runnable task;
      while ((task = writeBuffer.poll()) != null) {
        task.run();
      }

      // Discard all entries
      for (var entry : data.entrySet()) {
        removeNode(entry.getValue(), now);
      }

      // Cancel the scheduled cleanup
      Pacer pacer = pacer();
      if (pacer != null) {
        pacer.cancel();
      }

      // Discard all pending reads
      readBuffer.drainTo(e -> {});
    } finally {
      evictionLock.unlock();
    }
  }

  @GuardedBy("evictionLock")
  @SuppressWarnings("GuardedByChecker")
  void removeNode(Node<K, V> node, long now) {
    K key = node.getKey();
    @SuppressWarnings("unchecked")
    V[] value = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];

    data.computeIfPresent(node.getKeyReference(), (k, n) -> {
      if (n != node) {
        return n;
      }
      synchronized (n) {
        value[0] = n.getValue();

        if ((key == null) || (value[0] == null)) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, now)) {
          cause[0] = RemovalCause.EXPIRED;
        } else {
          cause[0] = RemovalCause.EXPLICIT;
        }

        if (cause[0].wasEvicted()) {
          notifyEviction(key, value[0], cause[0]);
        }

        discardRefresh(node.getKeyReference());
        makeDead(n);
        return null;
      }
    });

    if (node.inWindow() && (evicts() || expiresAfterAccess())) {
      accessOrderWindowDeque().remove(node);
    } else if (evicts()) {
      if (node.inMainProbation()) {
        accessOrderProbationDeque().remove(node);
      } else {
        accessOrderProtectedDeque().remove(node);
      }
    }
    if (expiresAfterWrite()) {
      writeOrderDeque().remove(node);
    } else if (expiresVariable()) {
      timerWheel().deschedule(node);
    }

    if (cause[0] != null) {
      notifyRemoval(key, value[0], cause[0]);
    }
  }

  @Override
  public boolean containsKey(Object key) {
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    return (node != null) && (node.getValue() != null)
        && !hasExpired(node, expirationTicker().read());
  }

  @Override
  public boolean containsValue(Object value) {
    requireNonNull(value);

    long now = expirationTicker().read();
    for (Node<K, V> node : data.values()) {
      if (node.containsValue(value) && !hasExpired(node, now) && (node.getKey() != null)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public @Nullable V get(Object key) {
    return getIfPresent(key, /* recordStats */ false);
  }

  @Override
  public @Nullable V getIfPresent(Object key, boolean recordStats) {
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if (node == null) {
      if (recordStats) {
        statsCounter().recordMisses(1);
      }
      if (drainStatus() == REQUIRED) {
        scheduleDrainBuffers();
      }
      return null;
    }

    V value = node.getValue();
    long now = expirationTicker().read();
    if (hasExpired(node, now) || (collectValues() && (value == null))) {
      if (recordStats) {
        statsCounter().recordMisses(1);
      }
      scheduleDrainBuffers();
      return null;
    }

    if (!isComputingAsync(node)) {
      @SuppressWarnings("unchecked")
      K castedKey = (K) key;
      setAccessTime(node, now);
      tryExpireAfterRead(node, castedKey, value, expiry(), now);
    }
    V refreshed = afterRead(node, now, recordStats);
    return (refreshed == null) ? value : refreshed;
  }

  @Override
  public @Nullable V getIfPresentQuietly(Object key) {
    V value;
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if ((node == null) || ((value = node.getValue()) == null)
        || hasExpired(node, expirationTicker().read())) {
      return null;
    }
    return value;
  }

  @Override
  public @Nullable V getIfPresentQuietly(K key, long[/* 1 */] writeTime) {
    V value;
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if ((node == null) || ((value = node.getValue()) == null)
        || hasExpired(node, expirationTicker().read())) {
      return null;
    }
    writeTime[0] = node.getWriteTime();
    return value;
  }

  /**
   * Returns the key associated with the mapping in this cache, or {@code null} if there is none.
   *
   * @param key the key whose canonical instance is to be returned
   * @return the key used by the mapping, or {@code null} if this cache does not contain a mapping
   *         for the key
   * @throws NullPointerException if the specified key is null
   */
  public @Nullable K getKey(K key) {
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if (node == null) {
      if (drainStatus() == REQUIRED) {
        scheduleDrainBuffers();
      }
      return null;
    }
    afterRead(node, /* now */ 0L, /* recordStats */ false);
    return node.getKey();
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<? extends K> keys) {
    var result = new LinkedHashMap<Object, Object>(calculateHashMapCapacity(keys));
    for (Object key : keys) {
      result.put(key, null);
    }

    int uniqueKeys = result.size();
    long now = expirationTicker().read();
    for (var iter = result.entrySet().iterator(); iter.hasNext();) {
      V value;
      var entry = iter.next();
      Node<K, V> node = data.get(nodeFactory.newLookupKey(entry.getKey()));
      if ((node == null) || ((value = node.getValue()) == null) || hasExpired(node, now)) {
        iter.remove();
      } else {
        if (!isComputingAsync(node)) {
          @SuppressWarnings("unchecked")
          K castedKey = (K) entry.getKey();
          tryExpireAfterRead(node, castedKey, value, expiry(), now);
          setAccessTime(node, now);
        }
        V refreshed = afterRead(node, now, /* recordHit */ false);
        if (refreshed == null) {
          entry.setValue(value);
        } else {
          entry.setValue(refreshed);
        }
      }
    }
    statsCounter().recordHits(result.size());
    statsCounter().recordMisses(uniqueKeys - result.size());

    @SuppressWarnings("unchecked")
    Map<K, V> castedResult = (Map<K, V>) result;
    return Collections.unmodifiableMap(castedResult);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    map.forEach(this::put);
  }

  /**
   * 手动 put()
   */
  @Override
  public @Nullable V put(K key, V value) {
    return put(key, value, expiry(), /* onlyIfAbsent */ false);
  }

  @Override
  public @Nullable V putIfAbsent(K key, V value) {
    return put(key, value, expiry(), /* onlyIfAbsent */ true);
  }

  /**
   * 手动 put()，expiry 表示过期策略，onlyIfAbsent 为 true 时表示当只有值为null时才put，不对原值强行覆盖
   * <p>
   *
   * Adds a node to the policy and the data store. If an existing node is found, then its value is
   * updated if allowed.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param expiry the calculator for the write expiration time
   * @param onlyIfAbsent a write is performed only if the key is not already associated with a value
   * @return the prior value in or null if no mapping was found
   */
  @Nullable V put(K key, V value, Expiry<K, V> expiry, boolean onlyIfAbsent) {
    requireNonNull(key);
    requireNonNull(value);

    // 本次需要 put 的 node
    Node<K, V> node = null;
    long now = expirationTicker().read();
    int newWeight = weigher.weigh(key, value);

    // ============== put 操作的核心部分开始 ===============

    // 自旋直到操作成功
    for (;;) {
      Node<K, V> prior = data.get(nodeFactory.newLookupKey(key));
      // 1. 当前 node 为null
      if (prior == null) {
        // 1.1 首先构建 node 并设置过期时间
        if (node == null) {
          node = nodeFactory.newNode(key, keyReferenceQueue(),
              value, valueReferenceQueue(), newWeight, now);
          setVariableTime(node, expireAfterCreate(key, value, expiry, now));
        }

        // 1.2 将 node put 到 data
        // 使用 putIfAbsent() 是考虑可能有其它线程也在put，避免将其它线程 put 的值强行覆盖掉
        prior = data.putIfAbsent(node.getKeyReference(), node);

        // 1.2.1 返回的 prior 为 null 代表 putIfAbsent 成功，当前线程成功将 node put 到 data。执行 afterWrite() 然后返回即可
        if (prior == null) {
          afterWrite(new AddTask(node, newWeight));
          return null;
        } else if (onlyIfAbsent) { // onlyIfAbsent 为 true 保证了当前操作不会覆盖已有值，只会在 null 时 put
          // 1.2.2 返回的 prior 不为 null，说明有其它线程先于当前线程 put 成功了，当前线程 put 失败
          V currentValue = prior.getValue();
          // 如果旧值不为null且没过期则直接返回即可
          if ((currentValue != null) && !hasExpired(prior, now)) {
            // 更新过期时间和最后访问时间
            if (!isComputingAsync(prior)) {
              tryExpireAfterRead(prior, key, currentValue, expiry(), now);
              setAccessTime(prior, now);
            }
            // 这里因为实际没有写入数据，但是读取并返回了旧值，所以需要 afterRead()，不需要 afterWrite()
            afterRead(prior, now, /* recordHit */ false);
            return currentValue;
          }
        }
      }

      // 2. 当前 node 不为null，且指定 onlyIfAbsent 为 true，表示不对旧值进行覆盖，那么返回旧值即可，和上面 1.2.2 逻辑差不多
      else if (onlyIfAbsent) {
        V currentValue = prior.getValue();
        if ((currentValue != null) && !hasExpired(prior, now)) {
          if (!isComputingAsync(prior)) {
            // 更新过期时间(只有在定义了自定义过期策略时才会执行)
            tryExpireAfterRead(prior, key, currentValue, expiry(), now);
            // 更新最后访问时间
            setAccessTime(prior, now);
          }
          // 本次操作对旧值进行了读取，且没有进行强行覆盖，所以不需要调用 afterWrite()，只需要调用 afterRead()
          afterRead(prior, now, /* recordHit */ false);
          return currentValue;
        }
      }

      // 走到这里说明当前已经存在值并且需要强行覆盖，那就需要加锁了；上面的步骤都是不需要加锁的，自旋重试搭配原子操作就可以了，只需要操作 data

      // 3. 当前 node 不为null，且指定 onlyIfAbsent 为 false，表示强行覆盖旧值
      else {
        // 3.1 先丢掉当前key正在进行的自动刷新任务
        discardRefresh(prior.getKeyReference());
      }

      // 3.2 强行覆盖旧值
      // 旧值
      V oldValue;
      // 本次操作后 node 的过期时间
      long varTime;
      int oldWeight;
      // 旧值是否过期
      boolean expired = false;
      // 本次操作是否会对旧值进行修改(强行覆盖旧值)
      boolean mayUpdate = true;
      // 代表两次写操作之间的间隔是否超过了指定阈值，如果大于这个阈值就认为第二次的操作是一次单独的写操作需要执行 afterWrite()，否则第二次操作只需要执行 afterRead()
      boolean exceedsTolerance = false;
      // 这里就必须加锁了，因为这里操作的不是 data 而是 node，ConcurrentHashMap 不能保证 node 粒度的并发安全
      synchronized (prior) {
        if (!prior.isAlive()) {
          continue;
        }
        // 3.2.1 准备阶段，对上面定义的那些需要用到的数据进行赋值
        oldValue = prior.getValue();
        oldWeight = prior.getWeight();
        // 这里主要就是进行驱逐通知 和 计算下次失效时间
        if (oldValue == null) {
          varTime = expireAfterCreate(key, value, expiry, now);
          notifyEviction(key, null, RemovalCause.COLLECTED);
        } else if (hasExpired(prior, now)) {
          expired = true;
          varTime = expireAfterCreate(key, value, expiry, now);
          notifyEviction(key, oldValue, RemovalCause.EXPIRED);
        } else if (onlyIfAbsent) {
          // 这里根据 onlyIfAbsent 的值来判读这次操作会不会对旧值进行修改
          mayUpdate = false;
          varTime = expireAfterRead(prior, key, value, expiry, now);
        } else {
          varTime = expireAfterUpdate(prior, key, value, expiry, now);
        }

        // 3.2.2 执行强行覆盖
        if (mayUpdate) {
          // 这里调用
          exceedsTolerance =
              (expiresAfterWrite() && (now - prior.getWriteTime()) > EXPIRE_WRITE_TOLERANCE)
              || (expiresVariable() && Math.abs(varTime - prior.getVariableTime()) > EXPIRE_WRITE_TOLERANCE);

          // 更新最后写入时间和权重
          setWriteTime(prior, now);
          prior.setWeight(newWeight);
          // 更新 node 的value
          prior.setValue(value, valueReferenceQueue());
        }

        // 更新可存活时间和最后访问时间，主要用于自定义过期策略和基于最后访问时间过期中
        setVariableTime(prior, varTime);
        setAccessTime(prior, now);
      }

      // ============== put 操作的核心部分结束 ===============

      // 到这里说明 put 操作对 node 的写入或更新结束了，接下来就是 put 的收尾工作

      // 4. 收尾工作，事件通知、执行 afterWrite() 或 afterRead()、

      // 4.1 旧值过期或者为null或者被强行覆盖，触发相应的事件通知
      if (expired) {
        notifyRemoval(key, oldValue, RemovalCause.EXPIRED);
      } else if (oldValue == null) {
        notifyRemoval(key, /* oldValue */ null, RemovalCause.COLLECTED);
      } else if (mayUpdate) {
        notifyOnReplace(key, oldValue, value);
      }

      // 4.2 执行 afterWrite() 或 afterRead()
      int weightedDifference = mayUpdate ? (newWeight - oldWeight) : 0;
      // oldValue 为 null 或 旧值过期 或 两次写间隔超过阈值，都需要执行 afterWrite()
      if ((oldValue == null) || (weightedDifference != 0) || expired) {
        afterWrite(new UpdateTask(prior, weightedDifference));
      } else if (!onlyIfAbsent && exceedsTolerance) {
        afterWrite(new UpdateTask(prior, weightedDifference));
      } else {
        // 执行了强行覆盖，更新最后写入时间
        if (mayUpdate) {
          setWriteTime(prior, now);
        }
        // 到这说明两次写间隔没有超过阈值，不需要进行 afterWrite()，只要 afterRead() 即可
        afterRead(prior, now, /* recordHit */ false);
      }

      // 返回旧值，如果旧值过期了就返回 null
      return expired ? null : oldValue;
    }
  }

  @Override
  public @Nullable V remove(Object key) {
    @SuppressWarnings("unchecked")
    K castKey = (K) key;
    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V>[] node = new Node[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];
    Object lookupKey = nodeFactory.newLookupKey(key);

    data.computeIfPresent(lookupKey, (k, n) -> {
      synchronized (n) {
        oldValue[0] = n.getValue();
        if (oldValue[0] == null) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, expirationTicker().read())) {
          cause[0] = RemovalCause.EXPIRED;
        } else {
          cause[0] = RemovalCause.EXPLICIT;
        }
        if (cause[0].wasEvicted()) {
          notifyEviction(castKey, oldValue[0], cause[0]);
        }
        n.retire();
      }
      discardRefresh(lookupKey);
      node[0] = n;
      return null;
    });

    if (cause[0] != null) {
      afterWrite(new RemovalTask(node[0]));
      notifyRemoval(castKey, oldValue[0], cause[0]);
    }
    return (cause[0] == RemovalCause.EXPLICIT) ? oldValue[0] : null;
  }

  @Override
  public boolean remove(Object key, Object value) {
    requireNonNull(key);
    if (value == null) {
      return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V>[] removed = new Node[1];
    @SuppressWarnings("unchecked")
    K[] oldKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];
    Object lookupKey = nodeFactory.newLookupKey(key);

    data.computeIfPresent(lookupKey, (kR, node) -> {
      synchronized (node) {
        oldKey[0] = node.getKey();
        oldValue[0] = node.getValue();
        if ((oldKey[0] == null) || (oldValue[0] == null)) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(node, expirationTicker().read())) {
          cause[0] = RemovalCause.EXPIRED;
        } else if (node.containsValue(value)) {
          cause[0] = RemovalCause.EXPLICIT;
        } else {
          return node;
        }
        if (cause[0].wasEvicted()) {
          notifyEviction(oldKey[0], oldValue[0], cause[0]);
        }
        discardRefresh(lookupKey);
        removed[0] = node;
        node.retire();
        return null;
      }
    });

    if (removed[0] == null) {
      return false;
    }
    afterWrite(new RemovalTask(removed[0]));
    notifyRemoval(oldKey[0], oldValue[0], cause[0]);

    return (cause[0] == RemovalCause.EXPLICIT);
  }

  @Override
  public @Nullable V replace(K key, V value) {
    requireNonNull(key);
    requireNonNull(value);

    int[] oldWeight = new int[1];
    @SuppressWarnings("unchecked")
    K[] nodeKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    long[] now = new long[1];
    int weight = weigher.weigh(key, value);
    Node<K, V> node = data.computeIfPresent(nodeFactory.newLookupKey(key), (k, n) -> {
      synchronized (n) {
        nodeKey[0] = n.getKey();
        oldValue[0] = n.getValue();
        oldWeight[0] = n.getWeight();
        if ((nodeKey[0] == null) || (oldValue[0] == null)
            || hasExpired(n, now[0] = expirationTicker().read())) {
          oldValue[0] = null;
          return n;
        }

        long varTime = expireAfterUpdate(n, key, value, expiry(), now[0]);
        n.setValue(value, valueReferenceQueue());
        n.setWeight(weight);

        setVariableTime(n, varTime);
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        discardRefresh(k);
        return n;
      }
    });

    if (oldValue[0] == null) {
      return null;
    }

    int weightedDifference = (weight - oldWeight[0]);
    if (expiresAfterWrite() || (weightedDifference != 0)) {
      afterWrite(new UpdateTask(node, weightedDifference));
    } else {
      afterRead(node, now[0], /* recordHit */ false);
    }

    notifyOnReplace(nodeKey[0], oldValue[0], value);
    return oldValue[0];
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNonNull(key);
    requireNonNull(oldValue);
    requireNonNull(newValue);

    int weight = weigher.weigh(key, newValue);
    boolean[] replaced = new boolean[1];
    @SuppressWarnings("unchecked")
    K[] nodeKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] prevValue = (V[]) new Object[1];
    int[] oldWeight = new int[1];
    long[] now = new long[1];
    Node<K, V> node = data.computeIfPresent(nodeFactory.newLookupKey(key), (k, n) -> {
      synchronized (n) {
        nodeKey[0] = n.getKey();
        prevValue[0] = n.getValue();
        oldWeight[0] = n.getWeight();
        if ((nodeKey[0] == null) || (prevValue[0] == null) || !n.containsValue(oldValue)
            || hasExpired(n, now[0] = expirationTicker().read())) {
          return n;
        }

        long varTime = expireAfterUpdate(n, key, newValue, expiry(), now[0]);
        n.setValue(newValue, valueReferenceQueue());
        n.setWeight(weight);

        setVariableTime(n, varTime);
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        replaced[0] = true;
        discardRefresh(k);
      }
      return n;
    });

    if (!replaced[0]) {
      return false;
    }

    int weightedDifference = (weight - oldWeight[0]);
    if (expiresAfterWrite() || (weightedDifference != 0)) {
      afterWrite(new UpdateTask(node, weightedDifference));
    } else {
      afterRead(node, now[0], /* recordHit */ false);
    }

    notifyOnReplace(nodeKey[0], prevValue[0], newValue);
    return true;
  }

  @Override
  public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
    requireNonNull(function);

    BiFunction<K, V, V> remappingFunction = (key, oldValue)
        -> requireNonNull(function.apply(key, oldValue));
    for (K key : keySet()) {
      long[] now = { expirationTicker().read() };
      Object lookupKey = nodeFactory.newLookupKey(key);
      remap(key, lookupKey, remappingFunction, expiry(), now, /* computeIfAbsent */ false);
    }
  }

  /**
   * 重写了父接口 LocalCache 的方法，LocalCache 规定了缓存的基本操作方法，子类只是实现者。
   * 缓存的获取和自动加载的核心就在这里，主要步骤如下：
   * <ul>
   *     <li>尝试直接从 data 中获取 node，尽量避免加锁</li>
   *     <li>从 data 成功获取到 node 且没有过期，更新最后访问时间、缓存过期时间(自定义了过期策略才会执行)、自动刷新缓存，返回 node.value</li>
   *     <li>从 data 中获取 node 失败，调用自动加载函数进行缓存自动加载</li>
   *     <li>缓存自动加载完毕，返回 node.value</li>
   * </ul>
   */
  @Override
  public @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction, boolean recordStats, boolean recordLoad) {
    requireNonNull(key);
    requireNonNull(mappingFunction);

    // 当前基准时间，ticker 默认采用 System.nanoTime() 获取基准时间，也可以在构建缓存时自定义
    long now = expirationTicker().read();

    // 1. 尝试直接从 data 中快速取值，尽量避免加锁
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if (node != null) {
      V value = node.getValue();
      // data 中取到的值不为 null 且没有过期，可以直接返回 value
      if ((value != null) && !hasExpired(node, now)) {
        // 是否异步缓存，若是同步缓存则直接同步设置过期时间和最后读取时间
        if (!isComputingAsync(node)) {
          // 尝试更新过期时间
          tryExpireAfterRead(node, key, value, expiry(), now);
          // 更新最后访问时间
          setAccessTime(node, now);
        }
        // 读取操作后执行 afterRead()，并根据 afterRead() 返回的结果判断是否进行了异步刷新
        var refreshed = afterRead(node, now, /* recordHit */ recordStats);
        // 这里根据是否异步来决定返回值，同步缓存 refreshed 表示当前值，异步缓存则 refreshed 是 future
        return (refreshed == null) ? value : refreshed;
      }
    }

    // 2. data 中不存在或已过期，需要通过缓存加载器加载；这时候读操作就转换为了写操作
    if (recordStats) {
      mappingFunction = statsAware(mappingFunction, recordLoad);
    }
    // 通过 nodeFactory 获取key，根据指定的配置这里可能会将 key 包装为软引用/弱引用
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    // 加载缓存
    return doComputeIfAbsent(key, keyRef, mappingFunction, new long[] { now }, recordStats);
  }

  /**
   * get(key) 失败，加载缓存。
   * 这里主要做了以下几件事：
   * <ol>
   *     <li>加载缓存到 data 中</li>
   *     <li>执行 afterRead() 或 afterWrite() 操作，将读/写事件丢到缓冲区，用于后续回放来调整 node 在各个区域的位置方便 Window-TinyLFU 算法回收</li>
   *     <li>如果缓冲区满了，就进行一次缓存维护操作</li>
   * </ol>
   * <p>
   *
   * Returns the current value from a computeIfAbsent invocation.
   */
  @Nullable V doComputeIfAbsent(K key, Object keyRef, Function<? super K, ? extends V> mappingFunction, long[/* 1 */] now, boolean recordStats) {
    @SuppressWarnings("unchecked")
    // 旧值
    V[] oldValue = (V[]) new Object[1];
    @SuppressWarnings("unchecked")
    // 新值
    V[] newValue = (V[]) new Object[1];
    @SuppressWarnings("unchecked")
    // key
    K[] nodeKey = (K[]) new Object[1];
    @SuppressWarnings({"unchecked", "rawtypes"})
    // 被移除的node
    Node<K, V>[] removed = new Node[1];

    int[] weight = new int[2]; // old, new
    // 移除原因
    RemovalCause[] cause = new RemovalCause[1];


    // 1. 加载缓存
    // 这里是借助 ConcurrentHashMap 的 compute() 方法来将加载到的 node 存入 data 中并返回 value 的，compute() 会对 key 加锁
    Node<K, V> node = data.compute(keyRef, (k, n) -> {
      // 1.1 不存在旧值的情况，直接通过加载函数加载新的 value 并构建为新的 node 返回
      if (n == null) {
        newValue[0] = mappingFunction.apply(key);
        // 加载函数获取的值为null，直接返回null
        if (newValue[0] == null) {
          return null;
        }
        // 加载函数获取的值不为null，构造为 node 后返回
        now[0] = expirationTicker().read();
        weight[1] = weigher.weigh(key, newValue[0]);
        // 使用 nodeFactory 构建 key-value 为 node 节点
        n = nodeFactory.newNode(key, keyReferenceQueue(), newValue[0], valueReferenceQueue(), weight[1], now[0]);
        // 设置可存活时间，主要用在自定义过期策略中
        setVariableTime(n, expireAfterCreate(key, newValue[0], expiry(), now[0]));
        return n;
      }

      // 1.2 旧值过期的情况，除了重新加载之外，还需要进行一些其它操作
      // todo 这里对 node 进行加锁，原因？防止 put、evict 等操作出现并发问题吗？
      synchronized (n) {
        nodeKey[0] = n.getKey();
        weight[0] = n.getWeight();
        oldValue[0] = n.getValue();

        // 1.2.1 找到移除原因并根据移除原因做一些处理
        if ((nodeKey[0] == null) || (oldValue[0] == null)) {
          // 移除原因：垃圾收集，导致 key 和 value 都被回收了
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, now[0])) {
          // 移除原因：数据过期
          cause[0] = RemovalCause.EXPIRED;
        } else {
          // 数据既没被回收也没过期，直接返回
          return n;
        }

        // 数据由于 GC、过期、容量淘汰 这几种原因之一被驱逐了，需要进行驱逐通知
        if (cause[0].wasEvicted()) {
          // todo 这里是同步调用的，为啥，不考虑下性能么？
          notifyEviction(nodeKey[0], oldValue[0], cause[0]);
        }

        // 1.2.2 重新加载，并更新 node 的一些属性
        newValue[0] = mappingFunction.apply(key);
        // 加载出的value为null，标记 node 为淘汰状态并返回 null
        if (newValue[0] == null) {
          removed[0] = n;
          n.retire();
          return null;
        }
        // 设置 node 的值和权重
        weight[1] = weigher.weigh(key, newValue[0]);
        n.setValue(newValue[0], valueReferenceQueue());
        n.setWeight(weight[1]);
        // 更新 可存活时间、最后访问时间、最后写入时间
        now[0] = expirationTicker().read();
        setVariableTime(n, expireAfterCreate(key, newValue[0], expiry(), now[0]));
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        // 取消该 node 的自动刷新操作，刚加载的不需要刷新了
        discardRefresh(k);

        return n;
      }
    });

    // 到这里代表缓存加载完成，已经获取到最终需要返回的 node 了，当然这个 node 可能是null

    // 2. 缓存加载后的一些处理，因为不是单纯的 get(key) 了，缓存加载包含了多种行为
    // 2.1 移除事件通知
    if (cause[0] != null) {
      if (cause[0].wasEvicted()) {
        statsCounter().recordEviction(weight[0], cause[0]);
      }
      // 这个方法会把移除事件通知封装为 task 丢到线程池中执行
      notifyRemoval(nodeKey[0], oldValue[0], cause[0]);
    }

    // 2.2 compute() 返回的 node 为null，直接返回。因为这里 node 返回的是null 表示会被删除，所以 afterRead() 也就没有意义不需要了
    if (node == null) {
      // 原节点被移除，触发 afterWrite() 操作，注意这里将是移除节点的事件封装为了 task 丢到 writeBuffer 里
      if (removed[0] != null) {
        afterWrite(new RemovalTask(removed[0]));
      }
      return null;
    }

    // 2.3 加载函数加载到的 value 为 null，更新过期时间和最后访问时间
    // todo 什么时候会走到这里呢，按理说 newValue[0] == null 则 node == null，应该在上一个判断中就 return了
    if (newValue[0] == null) {
      if (!isComputingAsync(node)) {
        tryExpireAfterRead(node, key, oldValue[0], expiry(), now[0]);
        setAccessTime(node, now[0]);
      }
      // 这里返回了 oldValue，所以对于 oldValue 来说有读取操作，需要触发 afterRead()
      afterRead(node, now[0], /* recordHit */ recordStats);
      return oldValue[0];
    }

    // 2.4 到这里说明加载到了值，执行 afterWrite()
    if ((oldValue[0] == null) && (cause[0] == null)) {
      afterWrite(new AddTask(node, weight[1]));
    } else {
      int weightedDifference = (weight[1] - weight[0]);
      afterWrite(new UpdateTask(node, weightedDifference));
    }

    // 加载过程结束，返回 value
    return newValue[0];
  }

  @Override
  public @Nullable V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(key);
    requireNonNull(remappingFunction);

    // An optimistic fast path to avoid unnecessary locking
    Object lookupKey = nodeFactory.newLookupKey(key);
    @Nullable Node<K, V> node = data.get(lookupKey);
    long now;
    if (node == null) {
      return null;
    } else if ((node.getValue() == null) || hasExpired(node, (now = expirationTicker().read()))) {
      scheduleDrainBuffers();
      return null;
    }

    BiFunction<? super K, ? super V, ? extends V> statsAwareRemappingFunction =
        statsAware(remappingFunction, /* recordLoad */ true, /* recordLoadFailure */ true);
    return remap(key, lookupKey, statsAwareRemappingFunction,
        expiry(), new long[] { now }, /* computeIfAbsent */ false);
  }

  @Override
  @SuppressWarnings("NullAway")
  public @Nullable V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      @Nullable Expiry<? super K, ? super V> expiry, boolean recordLoad,
      boolean recordLoadFailure) {
    requireNonNull(key);
    requireNonNull(remappingFunction);

    long[] now = { expirationTicker().read() };
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    BiFunction<? super K, ? super V, ? extends V> statsAwareRemappingFunction =
        statsAware(remappingFunction, recordLoad, recordLoadFailure);
    return remap(key, keyRef, statsAwareRemappingFunction,
        expiry, now, /* computeIfAbsent */ true);
  }

  @Override
  public @Nullable V merge(K key, V value,
      BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(key);
    requireNonNull(value);
    requireNonNull(remappingFunction);

    long[] now = { expirationTicker().read() };
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    BiFunction<? super K, ? super V, ? extends V> mergeFunction = (k, oldValue) ->
        (oldValue == null) ? value : statsAware(remappingFunction).apply(oldValue, value);
    return remap(key, keyRef, mergeFunction, expiry(), now, /* computeIfAbsent */ true);
  }

  /**
   * Attempts to compute a mapping for the specified key and its current mapped value (or
   * {@code null} if there is no current mapping).
   * <p>
   * An entry that has expired or been reference collected is evicted and the computation continues
   * as if the entry had not been present. This method does not pre-screen and does not wrap the
   * remappingFunction to be statistics aware.
   *
   * @param key key with which the specified value is to be associated
   * @param keyRef the key to associate with or a lookup only key if not <tt>computeIfAbsent</tt>
   * @param remappingFunction the function to compute a value
   * @param expiry the calculator for the expiration time
   * @param now the current time, according to the ticker
   * @param computeIfAbsent if an absent entry can be computed
   * @return the new value associated with the specified key, or null if none
   */
  @SuppressWarnings("PMD.EmptyIfStmt")
  @Nullable V remap(K key, Object keyRef,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      Expiry<? super K, ? super V> expiry, long[/* 1 */] now, boolean computeIfAbsent) {
    @SuppressWarnings("unchecked")
    K[] nodeKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] newValue = (V[]) new Object[1];
    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V>[] removed = new Node[1];

    int[] weight = new int[2]; // old, new
    RemovalCause[] cause = new RemovalCause[1];

    Node<K, V> node = data.compute(keyRef, (kr, n) -> {
      if (n == null) {
        if (!computeIfAbsent) {
          return null;
        }
        newValue[0] = remappingFunction.apply(key, null);
        if (newValue[0] == null) {
          return null;
        }
        now[0] = expirationTicker().read();
        weight[1] = weigher.weigh(key, newValue[0]);
        n = nodeFactory.newNode(keyRef, newValue[0],
            valueReferenceQueue(), weight[1], now[0]);
        setVariableTime(n, expireAfterCreate(key, newValue[0], expiry, now[0]));
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        discardRefresh(key);
        return n;
      }

      synchronized (n) {
        nodeKey[0] = n.getKey();
        oldValue[0] = n.getValue();
        if ((nodeKey[0] == null) || (oldValue[0] == null)) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, expirationTicker().read())) {
          cause[0] = RemovalCause.EXPIRED;
        }
        if (cause[0] != null) {
          notifyEviction(nodeKey[0], oldValue[0], cause[0]);
          if (!computeIfAbsent) {
            removed[0] = n;
            n.retire();
            return null;
          }
        }

        newValue[0] = remappingFunction.apply(nodeKey[0],
            (cause[0] == null) ? oldValue[0] : null);
        if (newValue[0] == null) {
          if (cause[0] == null) {
            cause[0] = RemovalCause.EXPLICIT;
            discardRefresh(kr);
          }
          removed[0] = n;
          n.retire();
          return null;
        }

        weight[0] = n.getWeight();
        weight[1] = weigher.weigh(key, newValue[0]);
        now[0] = expirationTicker().read();
        if (cause[0] == null) {
          if (newValue[0] != oldValue[0]) {
            cause[0] = RemovalCause.REPLACED;
          }
          setVariableTime(n, expireAfterUpdate(n, key, newValue[0], expiry, now[0]));
        } else {
          setVariableTime(n, expireAfterCreate(key, newValue[0], expiry, now[0]));
        }
        n.setValue(newValue[0], valueReferenceQueue());
        n.setWeight(weight[1]);
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        discardRefresh(kr);
        return n;
      }
    });

    if (cause[0] != null) {
      if (cause[0] == RemovalCause.REPLACED) {
        notifyOnReplace(key, oldValue[0], newValue[0]);
      } else {
        if (cause[0].wasEvicted()) {
          statsCounter().recordEviction(weight[0], cause[0]);
        }
        notifyRemoval(nodeKey[0], oldValue[0], cause[0]);
      }
    }

    if (removed[0] != null) {
      afterWrite(new RemovalTask(removed[0]));
    } else if (node == null) {
      // absent and not computable
    } else if ((oldValue[0] == null) && (cause[0] == null)) {
      afterWrite(new AddTask(node, weight[1]));
    } else {
      int weightedDifference = weight[1] - weight[0];
      if (expiresAfterWrite() || (weightedDifference != 0)) {
        afterWrite(new UpdateTask(node, weightedDifference));
      } else {
        afterRead(node, now[0], /* recordHit */ false);
        if ((cause[0] != null) && cause[0].wasEvicted()) {
          scheduleDrainBuffers();
        }
      }
    }

    return newValue[0];
  }

  @Override
  public Set<K> keySet() {
    final Set<K> ks = keySet;
    return (ks == null) ? (keySet = new KeySetView<>(this)) : ks;
  }

  @Override
  public Collection<V> values() {
    final Collection<V> vs = values;
    return (vs == null) ? (values = new ValuesView<>(this)) : vs;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    final Set<Entry<K, V>> es = entrySet;
    return (es == null) ? (entrySet = new EntrySetView<>(this)) : es;
  }

  /**
   * Object equality requires reflexive, symmetric, transitive, and consistency properties. Of
   * these, symmetry and consistency require further clarification for how they are upheld.
   * <p>
   * The <i>consistency</i> property between invocations requires that the results are the same if
   * there are no modifications to the information used. Therefore, usages should expect that this
   * operation may return misleading results if either map or the data held by them is modified
   * during the execution of this method. This characteristic allows for comparing the map sizes and
   * assuming stable mappings, as done by {@link AbstractMap}-based maps.
   * <p>
   * The <i>symmetric</i> property requires that the result is the same for all implementations of
   * {@link Map#equals(Object)}. That contract is defined in terms of the stable mappings provided
   * by {@link #entrySet()}, meaning that the {@link #size()} optimization forces that the count is
   * consistent with the mappings when used for an equality check.
   * <p>
   * The cache's {@link #size()} method may include entries that have expired or have been reference
   * collected, but have not yet been removed from the backing map. An iteration over the map may
   * trigger the removal of these dead entries when skipped over during traversal. To ensure
   * consistency and symmetry, usages should call {@link #cleanUp()} before {@link #equals(Object)}.
   * This is not done implicitly by {@link #size()} as many usages assume it to be instantaneous and
   * lock-free.
   */
  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof Map)) {
      return false;
    }

    var map = (Map<?, ?>) o;
    if (size() != map.size()) {
      return false;
    }

    long now = expirationTicker().read();
    for (var node : data.values()) {
      K key = node.getKey();
      V value = node.getValue();
      if ((key == null) || (value == null)
          || !node.isAlive() || hasExpired(node, now)) {
        scheduleDrainBuffers();
        return false;
      } else {
        var val = map.get(key);
        if ((val == null) || ((val != value) && !val.equals(value))) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  @SuppressWarnings("NullAway")
  public int hashCode() {
    int hash = 0;
    long now = expirationTicker().read();
    for (var node : data.values()) {
      K key = node.getKey();
      V value = node.getValue();
      if ((key == null) || (value == null)
          || !node.isAlive() || hasExpired(node, now)) {
        scheduleDrainBuffers();
      } else {
        hash += key.hashCode() ^ value.hashCode();
      }
    }
    return hash;
  }

  @Override
  public String toString() {
    var result = new StringBuilder().append('{');
    long now = expirationTicker().read();
    for (var node : data.values()) {
      K key = node.getKey();
      V value = node.getValue();
      if ((key == null) || (value == null)
          || !node.isAlive() || hasExpired(node, now)) {
        scheduleDrainBuffers();
      } else {
        if (result.length() != 1) {
          result.append(',').append(' ');
        }
        result.append((key == this) ? "(this Map)" : key);
        result.append('=');
        result.append((value == this) ? "(this Map)" : value);
      }
    }
    return result.append('}').toString();
  }

  /**
   * Returns the computed result from the ordered traversal of the cache entries.
   *
   * @param hottest the coldest or hottest iteration order
   * @param transformer a function that unwraps the value
   * @param mappingFunction the mapping function to compute a value
   * @return the computed value
   */
  @SuppressWarnings("GuardedByChecker")
  <T> T evictionOrder(boolean hottest, Function<V, V> transformer,
      Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
    Comparator<Node<K, V>> comparator = Comparator.comparingInt(node -> {
      K key = node.getKey();
      return (key == null) ? 0 : frequencySketch().frequency(key);
    });
    Iterable<Node<K, V>> iterable;
    if (hottest) {
      iterable = () -> {
        var secondary = PeekingIterator.comparing(
            accessOrderProbationDeque().descendingIterator(),
            accessOrderWindowDeque().descendingIterator(), comparator);
        return PeekingIterator.concat(
            accessOrderProtectedDeque().descendingIterator(), secondary);
      };
    } else {
      iterable = () -> {
        var primary = PeekingIterator.comparing(
            accessOrderWindowDeque().iterator(), accessOrderProbationDeque().iterator(),
            comparator.reversed());
        return PeekingIterator.concat(primary, accessOrderProtectedDeque().iterator());
      };
    }
    return snapshot(iterable, transformer, mappingFunction);
  }

  /**
   * Returns the computed result from the ordered traversal of the cache entries.
   *
   * @param oldest the youngest or oldest iteration order
   * @param transformer a function that unwraps the value
   * @param mappingFunction the mapping function to compute a value
   * @return the computed value
   */
  @SuppressWarnings("GuardedByChecker")
  <T> T expireAfterAccessOrder(boolean oldest, Function<V, V> transformer,
      Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
    Iterable<Node<K, V>> iterable;
    if (evicts()) {
      iterable = () -> {
        Comparator<Node<K, V>> comparator = Comparator.comparingLong(Node::getAccessTime);
        PeekingIterator<Node<K, V>> first, second, third;
        if (oldest) {
          first = accessOrderWindowDeque().iterator();
          second = accessOrderProbationDeque().iterator();
          third = accessOrderProtectedDeque().iterator();
        } else {
          comparator = comparator.reversed();
          first = accessOrderWindowDeque().descendingIterator();
          second = accessOrderProbationDeque().descendingIterator();
          third = accessOrderProtectedDeque().descendingIterator();
        }
        return PeekingIterator.comparing(
            PeekingIterator.comparing(first, second, comparator), third, comparator);
      };
    } else {
      iterable = oldest
          ? accessOrderWindowDeque()
          : accessOrderWindowDeque()::descendingIterator;
    }
    return snapshot(iterable, transformer, mappingFunction);
  }

  /**
   * Returns the computed result from the ordered traversal of the cache entries.
   *
   * @param iterable the supplier of the entries in the cache
   * @param transformer a function that unwraps the value
   * @param mappingFunction the mapping function to compute a value
   * @return the computed value
   */
  <T> T snapshot(Iterable<Node<K, V>> iterable, Function<V, V> transformer,
      Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
    requireNonNull(mappingFunction);
    requireNonNull(transformer);
    requireNonNull(iterable);

    evictionLock.lock();
    try {
      maintenance(/* ignored */ null);

      // Obtain the iterator as late as possible for modification count checking
      try (var stream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(
           iterable.iterator(), DISTINCT | ORDERED | NONNULL | IMMUTABLE), /* parallel */ false)) {
        return mappingFunction.apply(stream
            .map(node -> nodeToCacheEntry(node, transformer))
            .filter(Objects::nonNull));
      }
    } finally {
      evictionLock.unlock();
    }
  }

  /** Returns an entry for the given node if it can be used externally, else null. */
  @Nullable CacheEntry<K, V> nodeToCacheEntry(Node<K, V> node, Function<V, V> transformer) {
    V value = transformer.apply(node.getValue());
    K key = node.getKey();
    long now;
    if ((key == null) || (value == null) || !node.isAlive()
        || hasExpired(node, (now = expirationTicker().read()))) {
      return null;
    }

    long expiresAfter = Long.MAX_VALUE;
    if (expiresAfterAccess()) {
      expiresAfter = Math.min(expiresAfter, now - node.getAccessTime() + expiresAfterAccessNanos());
    }
    if (expiresAfterWrite()) {
      expiresAfter = Math.min(expiresAfter,
          (now & ~1L) - (node.getWriteTime() & ~1L) + expiresAfterWriteNanos());
    }
    if (expiresVariable()) {
      expiresAfter = node.getVariableTime() - now;
    }

    long refreshableAt = refreshAfterWrite()
        ? node.getWriteTime() + refreshAfterWriteNanos()
        : now + Long.MAX_VALUE;
    int weight = node.getPolicyWeight();
    return SnapshotEntry.forEntry(key, value, now, weight, now + expiresAfter, refreshableAt);
  }

  /** A function that produces an unmodifiable map up to the limit in stream order. */
  static final class SizeLimiter<K, V> implements Function<Stream<CacheEntry<K, V>>, Map<K, V>> {
    private final int expectedSize;
    private final long limit;

    SizeLimiter(int expectedSize, long limit) {
      requireArgument(limit >= 0);
      this.expectedSize = expectedSize;
      this.limit = limit;
    }

    @Override
    public Map<K, V> apply(Stream<CacheEntry<K, V>> stream) {
      var map = new LinkedHashMap<K, V>(calculateHashMapCapacity(expectedSize));
      stream.limit(limit).forEach(entry -> map.put(entry.getKey(), entry.getValue()));
      return Collections.unmodifiableMap(map);
    }
  }

  /** A function that produces an unmodifiable map up to the weighted limit in stream order. */
  static final class WeightLimiter<K, V> implements Function<Stream<CacheEntry<K, V>>, Map<K, V>> {
    private final long weightLimit;

    private long weightedSize;

    WeightLimiter(long weightLimit) {
      requireArgument(weightLimit >= 0);
      this.weightLimit = weightLimit;
    }

    @Override
    public Map<K, V> apply(Stream<CacheEntry<K, V>> stream) {
      var map = new LinkedHashMap<K, V>();
      stream.takeWhile(entry -> {
        weightedSize = Math.addExact(weightedSize, entry.weight());
        return (weightedSize <= weightLimit);
      }).forEach(entry -> map.put(entry.getKey(), entry.getValue()));
      return Collections.unmodifiableMap(map);
    }
  }

  /** An adapter to safely externalize the keys. */
  static final class KeySetView<K, V> extends AbstractSet<K> {
    final BoundedLocalCache<K, V> cache;

    KeySetView(BoundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    public boolean contains(Object obj) {
      return cache.containsKey(obj);
    }

    @Override
    public boolean remove(Object obj) {
      return (cache.remove(obj) != null);
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator<>(cache);
    }

    @Override
    public Spliterator<K> spliterator() {
      return new KeySpliterator<>(cache);
    }
  }

  /** An adapter to safely externalize the key iterator. */
  static final class KeyIterator<K, V> implements Iterator<K> {
    final EntryIterator<K, V> iterator;

    KeyIterator(BoundedLocalCache<K, V> cache) {
      this.iterator = new EntryIterator<>(cache);
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public K next() {
      return iterator.nextKey();
    }

    @Override
    public void remove() {
      iterator.remove();
    }
  }

  /** An adapter to safely externalize the key spliterator. */
  static final class KeySpliterator<K, V> implements Spliterator<K> {
    final Spliterator<Node<K, V>> spliterator;
    final BoundedLocalCache<K, V> cache;

    KeySpliterator(BoundedLocalCache<K, V> cache) {
      this(cache, cache.data.values().spliterator());
    }

    KeySpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
      this.spliterator = requireNonNull(spliterator);
      this.cache = requireNonNull(cache);
    }

    @Override
    public void forEachRemaining(Consumer<? super K> action) {
      requireNonNull(action);
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(key);
        }
      };
      spliterator.forEachRemaining(consumer);
    }

    @Override
    public boolean tryAdvance(Consumer<? super K> action) {
      requireNonNull(action);
      boolean[] advanced = { false };
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(key);
          advanced[0] = true;
        }
      };
      for (;;) {
        if (spliterator.tryAdvance(consumer)) {
          if (advanced[0]) {
            return true;
          }
          continue;
        }
        return false;
      }
    }

    @Override
    public @Nullable Spliterator<K> trySplit() {
      Spliterator<Node<K, V>> split = spliterator.trySplit();
      return (split == null) ? null : new KeySpliterator<>(cache, split);
    }

    @Override
    public long estimateSize() {
      return spliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return DISTINCT | CONCURRENT | NONNULL;
    }
  }

  /** An adapter to safely externalize the values. */
  static final class ValuesView<K, V> extends AbstractCollection<V> {
    final BoundedLocalCache<K, V> cache;

    ValuesView(BoundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    public boolean contains(Object o) {
      return cache.containsValue(o);
    }

    @Override
    public boolean removeIf(Predicate<? super V> filter) {
      requireNonNull(filter);
      boolean removed = false;
      for (Entry<K, V> entry : cache.entrySet()) {
        if (filter.test(entry.getValue())) {
          removed |= cache.remove(entry.getKey(), entry.getValue());
        }
      }
      return removed;
    }

    @Override
    public Iterator<V> iterator() {
      return new ValueIterator<>(cache);
    }

    @Override
    public Spliterator<V> spliterator() {
      return new ValueSpliterator<>(cache);
    }
  }

  /** An adapter to safely externalize the value iterator. */
  static final class ValueIterator<K, V> implements Iterator<V> {
    final EntryIterator<K, V> iterator;

    ValueIterator(BoundedLocalCache<K, V> cache) {
      this.iterator = new EntryIterator<>(cache);
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public V next() {
      return iterator.nextValue();
    }

    @Override
    public void remove() {
      iterator.remove();
    }
  }

  /** An adapter to safely externalize the value spliterator. */
  static final class ValueSpliterator<K, V> implements Spliterator<V> {
    final Spliterator<Node<K, V>> spliterator;
    final BoundedLocalCache<K, V> cache;

    ValueSpliterator(BoundedLocalCache<K, V> cache) {
      this(cache, cache.data.values().spliterator());
    }

    ValueSpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
      this.spliterator = requireNonNull(spliterator);
      this.cache = requireNonNull(cache);
    }

    @Override
    public void forEachRemaining(Consumer<? super V> action) {
      requireNonNull(action);
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(value);
        }
      };
      spliterator.forEachRemaining(consumer);
    }

    @Override
    public boolean tryAdvance(Consumer<? super V> action) {
      requireNonNull(action);
      boolean[] advanced = { false };
      long now = cache.expirationTicker().read();
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        if ((key != null) && (value != null) && !cache.hasExpired(node, now) && node.isAlive()) {
          action.accept(value);
          advanced[0] = true;
        }
      };
      for (;;) {
        if (spliterator.tryAdvance(consumer)) {
          if (advanced[0]) {
            return true;
          }
          continue;
        }
        return false;
      }
    }

    @Override
    public @Nullable Spliterator<V> trySplit() {
      Spliterator<Node<K, V>> split = spliterator.trySplit();
      return (split == null) ? null : new ValueSpliterator<>(cache, split);
    }

    @Override
    public long estimateSize() {
      return spliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return CONCURRENT | NONNULL;
    }
  }

  /** An adapter to safely externalize the entries. */
  static final class EntrySetView<K, V> extends AbstractSet<Entry<K, V>> {
    final BoundedLocalCache<K, V> cache;

    EntrySetView(BoundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    public boolean contains(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      var entry = (Entry<?, ?>) obj;
      var key = entry.getKey();
      var value = entry.getValue();
      if ((key == null) || (value == null)) {
        return false;
      }
      Node<K, V> node = cache.data.get(cache.nodeFactory.newLookupKey(key));
      return (node != null) && node.containsValue(value);
    }

    @Override
    public boolean remove(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      var entry = (Entry<?, ?>) obj;
      return cache.remove(entry.getKey(), entry.getValue());
    }

    @Override
    public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
      requireNonNull(filter);
      boolean removed = false;
      for (Entry<K, V> entry : this) {
        if (filter.test(entry)) {
          removed |= cache.remove(entry.getKey(), entry.getValue());
        }
      }
      return removed;
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator<>(cache);
    }

    @Override
    public Spliterator<Entry<K, V>> spliterator() {
      return new EntrySpliterator<>(cache);
    }
  }

  /** An adapter to safely externalize the entry iterator. */
  static final class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
    final BoundedLocalCache<K, V> cache;
    final Iterator<Node<K, V>> iterator;

    @Nullable K key;
    @Nullable V value;
    @Nullable K removalKey;
    @Nullable Node<K, V> next;

    EntryIterator(BoundedLocalCache<K, V> cache) {
      this.iterator = cache.data.values().iterator();
      this.cache = cache;
    }

    @Override
    public boolean hasNext() {
      if (next != null) {
        return true;
      }

      long now = cache.expirationTicker().read();
      for (;;) {
        if (iterator.hasNext()) {
          next = iterator.next();
          value = next.getValue();
          key = next.getKey();

          boolean evictable = (key == null) || (value == null) || cache.hasExpired(next, now);
          if (evictable || !next.isAlive()) {
            if (evictable) {
              cache.scheduleDrainBuffers();
            }
            value = null;
            next = null;
            key = null;
            continue;
          }
          return true;
        }
        return false;
      }
    }

    @SuppressWarnings("NullAway")
    K nextKey() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      removalKey = key;
      value = null;
      next = null;
      key = null;
      return removalKey;
    }

    @SuppressWarnings("NullAway")
    V nextValue() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      removalKey = key;
      V val = value;
      value = null;
      next = null;
      key = null;
      return val;
    }

    @Override
    public Entry<K, V> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      @SuppressWarnings("NullAway")
      var entry = new WriteThroughEntry<>(cache, key, value);
      removalKey = key;
      value = null;
      next = null;
      key = null;
      return entry;
    }

    @Override
    public void remove() {
      if (removalKey == null) {
        throw new IllegalStateException();
      }
      cache.remove(removalKey);
      removalKey = null;
    }
  }

  /** An adapter to safely externalize the entry spliterator. */
  static final class EntrySpliterator<K, V> implements Spliterator<Entry<K, V>> {
    final Spliterator<Node<K, V>> spliterator;
    final BoundedLocalCache<K, V> cache;

    EntrySpliterator(BoundedLocalCache<K, V> cache) {
      this(cache, cache.data.values().spliterator());
    }

    EntrySpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
      this.spliterator = requireNonNull(spliterator);
      this.cache = requireNonNull(cache);
    }

    @Override
    public void forEachRemaining(Consumer<? super Entry<K, V>> action) {
      requireNonNull(action);
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(new WriteThroughEntry<>(cache, key, value));
        }
      };
      spliterator.forEachRemaining(consumer);
    }

    @Override
    public boolean tryAdvance(Consumer<? super Entry<K, V>> action) {
      requireNonNull(action);
      boolean[] advanced = { false };
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(new WriteThroughEntry<>(cache, key, value));
          advanced[0] = true;
        }
      };
      for (;;) {
        if (spliterator.tryAdvance(consumer)) {
          if (advanced[0]) {
            return true;
          }
          continue;
        }
        return false;
      }
    }

    @Override
    public @Nullable Spliterator<Entry<K, V>> trySplit() {
      Spliterator<Node<K, V>> split = spliterator.trySplit();
      return (split == null) ? null : new EntrySpliterator<>(cache, split);
    }

    @Override
    public long estimateSize() {
      return spliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return DISTINCT | CONCURRENT | NONNULL;
    }
  }

  /**
   * 这个内部类继承自 Runnable 接口，用于执行清理缓存的任务
   * <p>
   *
   * A reusable task that performs the maintenance work; used to avoid wrapping by ForkJoinPool.
   */
  static final class PerformCleanupTask extends ForkJoinTask<Void> implements Runnable {
    private static final long serialVersionUID = 1L;

    final WeakReference<BoundedLocalCache<?, ?>> reference;

    PerformCleanupTask(BoundedLocalCache<?, ?> cache) {
      reference = new WeakReference<BoundedLocalCache<?,?>>(cache);
    }

    @Override
    public boolean exec() {
      try {
        run();
      } catch (Throwable t) {
        logger.log(Level.ERROR, "Exception thrown when performing the maintenance task", t);
      }

      // Indicates that the task has not completed to allow subsequent submissions to execute
      return false;
    }

    @Override
    public void run() {
      BoundedLocalCache<?, ?> cache = reference.get();
      if (cache != null) {
        cache.performCleanUp(/* ignored */ null);
      }
    }

    /**
     * This method cannot be ignored due to being final, so a hostile user supplied Executor could
     * forcibly complete the task and halt future executions. There are easier ways to intentionally
     * harm a system, so this is assumed to not happen in practice.
     */
    // public final void quietlyComplete() {}

    @Override public Void getRawResult() { return null; }
    @Override public void setRawResult(Void v) {}
    @Override public void complete(Void value) {}
    @Override public void completeExceptionally(Throwable ex) {}
    @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
  }

  /** Creates a serialization proxy based on the common configuration shared by all cache types. */
  static <K, V> SerializationProxy<K, V> makeSerializationProxy(BoundedLocalCache<?, ?> cache) {
    SerializationProxy<K, V> proxy = new SerializationProxy<>();
    proxy.weakKeys = cache.collectKeys();
    proxy.weakValues = cache.nodeFactory.weakValues();
    proxy.softValues = cache.nodeFactory.softValues();
    proxy.isRecordingStats = cache.isRecordingStats();
    proxy.evictionListener = cache.evictionListener;
    proxy.removalListener = cache.removalListener();
    proxy.ticker = cache.expirationTicker();
    if (cache.expiresAfterAccess()) {
      proxy.expiresAfterAccessNanos = cache.expiresAfterAccessNanos();
    }
    if (cache.expiresAfterWrite()) {
      proxy.expiresAfterWriteNanos = cache.expiresAfterWriteNanos();
    }
    if (cache.expiresVariable()) {
      proxy.expiry = cache.expiry();
    }
    if (cache.refreshAfterWrite()) {
      proxy.refreshAfterWriteNanos = cache.refreshAfterWriteNanos();
    }
    if (cache.evicts()) {
      if (cache.isWeighted) {
        proxy.weigher = cache.weigher;
        proxy.maximumWeight = cache.maximum();
      } else {
        proxy.maximumSize = cache.maximum();
      }
    }
    proxy.cacheLoader = cache.cacheLoader;
    proxy.async = cache.isAsync;
    return proxy;
  }

  /* --------------- Manual Cache --------------- */

  /**
   * 有界手动缓存
   */
  static class BoundedLocalManualCache<K, V> implements LocalManualCache<K, V>, Serializable {
    private static final long serialVersionUID = 1;

    final BoundedLocalCache<K, V> cache;

    @Nullable Policy<K, V> policy;

    BoundedLocalManualCache(Caffeine<K, V> builder) {
      this(builder, null);
    }

    /**
     * 初始化缓存实例
     * <p>
     *
     * LocalCacheFactory 作为缓存工厂，用来按需创建缓存实例，LocalCacheFactory 会根据指定的不同条件调用那些自动生成的类来创建缓存实例。
     * Cache 有许多不同的配置，只有使用特定功能的子集的时候，相关字段才有意义，如果默认情况下所有字段都被存在，将会导致缓存和每个缓存中的元素的内存开销的浪费。
     * 而通过代码生成的最合理实现，将会减少运行时的内存开销但是会需要磁盘上更大的二进制文件。
     */
    BoundedLocalManualCache(Caffeine<K, V> builder, @Nullable CacheLoader<? super K, V> loader) {
      // 使用 LocalCacheFactory 根据配置情况按需创建缓存实例，节约内存开销
      cache = LocalCacheFactory.newBoundedLocalCache(builder, loader, /* async */ false);
    }

    /**
     * 继承自 LocalManualCache 接口，返回 cache 实例，也就是 BoundedLocalCache 动态生成的子类本身
     */
    @Override
    public BoundedLocalCache<K, V> cache() {
      return cache;
    }

    @Override
    public Policy<K, V> policy() {
      var p = policy;
      return (p == null) ? (policy = new BoundedPolicy<>(cache, identity(), cache.isWeighted)) : p;
    }

    @SuppressWarnings("UnusedVariable")
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    private Object writeReplace() {
      return makeSerializationProxy(cache);
    }
  }

  static final class BoundedPolicy<K, V> implements Policy<K, V> {
    final BoundedLocalCache<K, V> cache;
    final Function<V, V> transformer;
    final boolean isWeighted;

    @Nullable Optional<Eviction<K, V>> eviction;
    @Nullable Optional<FixedRefresh<K, V>> refreshes;
    @Nullable Optional<FixedExpiration<K, V>> afterWrite;
    @Nullable Optional<FixedExpiration<K, V>> afterAccess;
    @Nullable Optional<VarExpiration<K, V>> variable;

    BoundedPolicy(BoundedLocalCache<K, V> cache, Function<V, V> transformer, boolean isWeighted) {
      this.transformer = transformer;
      this.isWeighted = isWeighted;
      this.cache = cache;
    }

    @Override public boolean isRecordingStats() {
      return cache.isRecordingStats();
    }
    @Override public @Nullable V getIfPresentQuietly(K key) {
      return transformer.apply(cache.getIfPresentQuietly(key));
    }
    @Override public @Nullable CacheEntry<K, V> getEntryIfPresentQuietly(K key) {
      Node<K, V> node = cache.data.get(cache.nodeFactory.newLookupKey(key));
      return (node == null) ? null : cache.nodeToCacheEntry(node, transformer);
    }
    @Override public Map<K, CompletableFuture<V>> refreshes() {
      var refreshes = cache.refreshes;
      if ((refreshes == null) || refreshes.isEmpty()) {
        return Map.of();
      } else if (cache.collectKeys()) {
        var inFlight = new IdentityHashMap<K, CompletableFuture<V>>(refreshes.size());
        for (var entry : refreshes.entrySet()) {
          @SuppressWarnings("unchecked")
          var key = ((InternalReference<K>) entry.getKey()).get();
          @SuppressWarnings("unchecked")
          var future = (CompletableFuture<V>) entry.getValue();
          if (key != null) {
            inFlight.put(key, future);
          }
        }
        return Collections.unmodifiableMap(inFlight);
      }
      @SuppressWarnings("unchecked")
      var castedRefreshes = (Map<K, CompletableFuture<V>>) (Object) refreshes;
      return Map.copyOf(castedRefreshes);
    }
    @Override public Optional<Eviction<K, V>> eviction() {
      return cache.evicts()
          ? (eviction == null) ? (eviction = Optional.of(new BoundedEviction())) : eviction
          : Optional.empty();
    }
    @Override public Optional<FixedExpiration<K, V>> expireAfterAccess() {
      if (!cache.expiresAfterAccess()) {
        return Optional.empty();
      }
      return (afterAccess == null)
          ? (afterAccess = Optional.of(new BoundedExpireAfterAccess()))
          : afterAccess;
    }
    @Override public Optional<FixedExpiration<K, V>> expireAfterWrite() {
      if (!cache.expiresAfterWrite()) {
        return Optional.empty();
      }
      return (afterWrite == null)
          ? (afterWrite = Optional.of(new BoundedExpireAfterWrite()))
          : afterWrite;
    }
    @Override public Optional<VarExpiration<K, V>> expireVariably() {
      if (!cache.expiresVariable()) {
        return Optional.empty();
      }
      return (variable == null)
          ? (variable = Optional.of(new BoundedVarExpiration()))
          : variable;
    }
    @Override public Optional<FixedRefresh<K, V>> refreshAfterWrite() {
      if (!cache.refreshAfterWrite()) {
        return Optional.empty();
      }
      return (refreshes == null)
          ? (refreshes = Optional.of(new BoundedRefreshAfterWrite()))
          : refreshes;
    }

    final class BoundedEviction implements Eviction<K, V> {
      @Override public boolean isWeighted() {
        return isWeighted;
      }
      @Override public OptionalInt weightOf(K key) {
        requireNonNull(key);
        if (!isWeighted) {
          return OptionalInt.empty();
        }
        Node<K, V> node = cache.data.get(cache.nodeFactory.newLookupKey(key));
        if ((node == null) || cache.hasExpired(node, cache.expirationTicker().read())) {
          return OptionalInt.empty();
        }
        synchronized (node) {
          return OptionalInt.of(node.getWeight());
        }
      }
      @Override public OptionalLong weightedSize() {
        if (cache.evicts() && isWeighted()) {
          cache.evictionLock.lock();
          try {
            return OptionalLong.of(Math.max(0, cache.weightedSize()));
          } finally {
            cache.evictionLock.unlock();
          }
        }
        return OptionalLong.empty();
      }
      @Override public long getMaximum() {
        cache.evictionLock.lock();
        try {
          return cache.maximum();
        } finally {
          cache.evictionLock.unlock();
        }
      }
      @Override public void setMaximum(long maximum) {
        cache.evictionLock.lock();
        try {
          cache.setMaximumSize(maximum);
          cache.maintenance(/* ignored */ null);
        } finally {
          cache.evictionLock.unlock();
        }
      }
      @Override public Map<K, V> coldest(int limit) {
        int expectedSize = Math.min(limit, cache.size());
        var limiter = new SizeLimiter<K, V>(expectedSize, limit);
        return cache.evictionOrder(/* hottest */ false, transformer, limiter);
      }
      @Override public Map<K, V> coldestWeighted(long weightLimit) {
        var limiter = new WeightLimiter<K, V>(weightLimit);
        return cache.evictionOrder(/* hottest */ false, transformer, limiter);
      }
      @Override
      public <T> T coldest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
        requireNonNull(mappingFunction);
        return cache.evictionOrder(/* hottest */ false, transformer, mappingFunction);
      }
      @Override public Map<K, V> hottest(int limit) {
        int expectedSize = Math.min(limit, cache.size());
        var limiter = new SizeLimiter<K, V>(expectedSize, limit);
        return cache.evictionOrder(/* hottest */ true, transformer, limiter);
      }
      @Override public Map<K, V> hottestWeighted(long weightLimit) {
        var limiter = isWeighted()
            ? new WeightLimiter<K, V>(weightLimit)
            : new SizeLimiter<K, V>((int) Math.min(weightLimit, cache.size()), weightLimit);
        return cache.evictionOrder(/* hottest */ true, transformer, limiter);
      }
      @Override
      public <T> T hottest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
        requireNonNull(mappingFunction);
        return cache.evictionOrder(/* hottest */ true, transformer, mappingFunction);
      }
    }

    @SuppressWarnings("PreferJavaTimeOverload")
    final class BoundedExpireAfterAccess implements FixedExpiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<K, V> node = cache.data.get(lookupKey);
        if (node == null) {
          return OptionalLong.empty();
        }
        long now = cache.expirationTicker().read();
        return cache.hasExpired(node, now)
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(now - node.getAccessTime(), TimeUnit.NANOSECONDS));
      }
      @Override public long getExpiresAfter(TimeUnit unit) {
        return unit.convert(cache.expiresAfterAccessNanos(), TimeUnit.NANOSECONDS);
      }
      @Override public void setExpiresAfter(long duration, TimeUnit unit) {
        requireArgument(duration >= 0);
        cache.setExpiresAfterAccessNanos(unit.toNanos(duration));
        cache.scheduleAfterWrite();
      }
      @Override public Map<K, V> oldest(int limit) {
        return oldest(new SizeLimiter<>(Math.min(limit, cache.size()), limit));
      }
      @Override public <T> T oldest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
        return cache.expireAfterAccessOrder(/* oldest */ true, transformer, mappingFunction);
      }
      @Override public Map<K, V> youngest(int limit) {
        return youngest(new SizeLimiter<>(Math.min(limit, cache.size()), limit));
      }
      @Override public <T> T youngest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
        return cache.expireAfterAccessOrder(/* oldest */ false, transformer, mappingFunction);
      }
    }

    @SuppressWarnings("PreferJavaTimeOverload")
    final class BoundedExpireAfterWrite implements FixedExpiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<K, V> node = cache.data.get(lookupKey);
        if (node == null) {
          return OptionalLong.empty();
        }
        long now = cache.expirationTicker().read();
        return cache.hasExpired(node, now)
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(now - node.getWriteTime(), TimeUnit.NANOSECONDS));
      }
      @Override public long getExpiresAfter(TimeUnit unit) {
        return unit.convert(cache.expiresAfterWriteNanos(), TimeUnit.NANOSECONDS);
      }
      @Override public void setExpiresAfter(long duration, TimeUnit unit) {
        requireArgument(duration >= 0);
        cache.setExpiresAfterWriteNanos(unit.toNanos(duration));
        cache.scheduleAfterWrite();
      }
      @Override public Map<K, V> oldest(int limit) {
        return oldest(new SizeLimiter<>(Math.min(limit, cache.size()), limit));
      }
      @SuppressWarnings("GuardedByChecker")
      @Override public <T> T oldest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
        return cache.snapshot(cache.writeOrderDeque(), transformer, mappingFunction);
      }
      @Override public Map<K, V> youngest(int limit) {
        return youngest(new SizeLimiter<>(Math.min(limit, cache.size()), limit));
      }
      @SuppressWarnings("GuardedByChecker")
      @Override public <T> T youngest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
        return cache.snapshot(cache.writeOrderDeque()::descendingIterator,
            transformer, mappingFunction);
      }
    }

    @SuppressWarnings("PreferJavaTimeOverload")
    final class BoundedVarExpiration implements VarExpiration<K, V> {
      @Override public OptionalLong getExpiresAfter(K key, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<K, V> node = cache.data.get(lookupKey);
        if (node == null) {
          return OptionalLong.empty();
        }
        long now = cache.expirationTicker().read();
        return cache.hasExpired(node, now)
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(node.getVariableTime() - now, TimeUnit.NANOSECONDS));
      }
      @Override public void setExpiresAfter(K key, long duration, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        requireArgument(duration >= 0);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<K, V> node = cache.data.get(lookupKey);
        if (node != null) {
          long now;
          long durationNanos = TimeUnit.NANOSECONDS.convert(duration, unit);
          synchronized (node) {
            now = cache.expirationTicker().read();
            if (cache.hasExpired(node, now)) {
              return;
            }
            node.setVariableTime(now + Math.min(durationNanos, MAXIMUM_EXPIRY));
          }
          cache.afterRead(node, now, /* recordHit */ false);
        }
      }
      @Override public @Nullable V put(K key, V value, long duration, TimeUnit unit) {
        requireNonNull(unit);
        requireNonNull(value);
        requireArgument(duration >= 0);
        return cache.isAsync
            ? putAsync(key, value, duration, unit)
            : putSync(key, value, duration, unit, /* onlyIfAbsent */ false);
      }
      @Override public @Nullable V putIfAbsent(K key, V value, long duration, TimeUnit unit) {
        requireNonNull(unit);
        requireNonNull(value);
        requireArgument(duration >= 0);
        return cache.isAsync
            ? putIfAbsentAsync(key, value, duration, unit)
            : putSync(key, value, duration, unit, /* onlyIfAbsent */ true);
      }
      @Nullable V putSync(K key, V value, long duration, TimeUnit unit, boolean onlyIfAbsent) {
        var expiry = new FixedExpiry<K, V>(duration, unit);
        return cache.put(key, value, expiry, onlyIfAbsent);
      }
      @SuppressWarnings("unchecked")
      @Nullable V putIfAbsentAsync(K key, V value, long duration, TimeUnit unit) {
        // Keep in sync with LocalAsyncCache.AsMapView#putIfAbsent(key, value)
        var expiry = (Expiry<K, V>) new AsyncExpiry<>(new FixedExpiry<>(duration, unit));
        V asyncValue = (V) CompletableFuture.completedFuture(value);

        for (;;) {
          var priorFuture = (CompletableFuture<V>) cache.getIfPresent(key, /* recordStats */ false);
          if (priorFuture != null) {
            if (!priorFuture.isDone()) {
              Async.getWhenSuccessful(priorFuture);
              continue;
            }

            V prior = Async.getWhenSuccessful(priorFuture);
            if (prior != null) {
              return prior;
            }
          }

          boolean[] added = { false };
          var computed = (CompletableFuture<V>) cache.compute(key, (k, oldValue) -> {
            var oldValueFuture = (CompletableFuture<V>) oldValue;
            added[0] = (oldValueFuture == null)
                || (oldValueFuture.isDone() && (Async.getIfReady(oldValueFuture) == null));
            return added[0] ? asyncValue : oldValue;
          }, expiry, /* recordLoad */ false, /* recordLoadFailure */ false);

          if (added[0]) {
            return null;
          } else {
            V prior = Async.getWhenSuccessful(computed);
            if (prior != null) {
              return prior;
            }
          }
        }
      }
      @SuppressWarnings("unchecked")
      @Nullable V putAsync(K key, V value, long duration, TimeUnit unit) {
        var expiry = (Expiry<K, V>) new AsyncExpiry<>(new FixedExpiry<>(duration, unit));
        V asyncValue = (V) CompletableFuture.completedFuture(value);

        var oldValueFuture = (CompletableFuture<V>) cache.put(
            key, asyncValue, expiry, /* onlyIfAbsent */ false);
        return Async.getWhenSuccessful(oldValueFuture);
      }
      @SuppressWarnings("NullAway")
      @Override public V compute(K key,
          BiFunction<? super K, ? super V, ? extends V> remappingFunction,
          Duration duration) {
        requireNonNull(key);
        requireNonNull(duration);
        requireNonNull(remappingFunction);
        requireArgument(!duration.isNegative(), "duration cannot be negative: %s", duration);
        var expiry = new FixedExpiry<K, V>(saturatedToNanos(duration), TimeUnit.NANOSECONDS);

        return cache.isAsync
            ? computeAsync(key, remappingFunction, expiry)
            : cache.compute(key, remappingFunction, expiry,
                /* recordLoad */ true, /* recordLoadFailure */ true);
      }
      @Nullable V computeAsync(K key,
          BiFunction<? super K, ? super V, ? extends V> remappingFunction,
          Expiry<? super K, ? super V> expiry) {
        // Keep in sync with LocalAsyncCache.AsMapView#compute(key, remappingFunction)
        @SuppressWarnings("unchecked")
        var delegate = (LocalCache<K, CompletableFuture<V>>) cache;

        @SuppressWarnings({"unchecked", "rawtypes"})
        V[] newValue = (V[]) new Object[1];
        for (;;) {
          Async.getWhenSuccessful(delegate.getIfPresentQuietly(key));

          CompletableFuture<V> valueFuture = delegate.compute(key, (k, oldValueFuture) -> {
            if ((oldValueFuture != null) && !oldValueFuture.isDone()) {
              return oldValueFuture;
            }

            V oldValue = Async.getIfReady(oldValueFuture);
            BiFunction<? super K, ? super V, ? extends V> function = delegate.statsAware(
                remappingFunction, /* recordLoad */ true, /* recordLoadFailure */ true);
            newValue[0] = function.apply(key, oldValue);
            return (newValue[0] == null) ? null : CompletableFuture.completedFuture(newValue[0]);
          }, new AsyncExpiry<>(expiry), /* recordLoad */ false, /* recordLoadFailure */ false);

          if (newValue[0] != null) {
            return newValue[0];
          } else if (valueFuture == null) {
            return null;
          }
        }
      }
      @Override public Map<K, V> oldest(int limit) {
        return oldest(new SizeLimiter<>(Math.min(limit, cache.size()), limit));
      }
      @Override public <T> T oldest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
        return cache.snapshot(cache.timerWheel(), transformer, mappingFunction);
      }
      @Override public Map<K, V> youngest(int limit) {
        return youngest(new SizeLimiter<>(Math.min(limit, cache.size()), limit));
      }
      @Override public <T> T youngest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
        return cache.snapshot(cache.timerWheel()::descendingIterator, transformer, mappingFunction);
      }
    }

    static final class FixedExpiry<K, V> implements Expiry<K, V> {
      final long duration;
      final TimeUnit unit;

      FixedExpiry(long duration, TimeUnit unit) {
        this.duration = duration;
        this.unit = unit;
      }
      @Override public long expireAfterCreate(K key, V value, long currentTime) {
        return unit.toNanos(duration);
      }
      @Override public long expireAfterUpdate(
          K key, V value, long currentTime, long currentDuration) {
        return unit.toNanos(duration);
      }
      @Override public long expireAfterRead(
          K key, V value, long currentTime, long currentDuration) {
        return currentDuration;
      }
    }

    @SuppressWarnings("PreferJavaTimeOverload")
    final class BoundedRefreshAfterWrite implements FixedRefresh<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<K, V> node = cache.data.get(lookupKey);
        if (node == null) {
          return OptionalLong.empty();
        }
        long now = cache.expirationTicker().read();
        return cache.hasExpired(node, now)
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(now - node.getWriteTime(), TimeUnit.NANOSECONDS));
      }
      @Override public long getRefreshesAfter(TimeUnit unit) {
        return unit.convert(cache.refreshAfterWriteNanos(), TimeUnit.NANOSECONDS);
      }
      @Override public void setRefreshesAfter(long duration, TimeUnit unit) {
        requireArgument(duration >= 0);
        cache.setRefreshAfterWriteNanos(unit.toNanos(duration));
        cache.scheduleAfterWrite();
      }
    }
  }

  /* --------------- Loading Cache --------------- */

  /**
   * 这是个有界自动加载缓存，通过继承 有界手动缓存内部类 和 自动加载缓存接口 实现，
   * 所以自动缓存实际上最终还是通过 手动缓存 来实现的
   */
  static final class BoundedLocalLoadingCache<K, V> extends BoundedLocalManualCache<K, V> implements LocalLoadingCache<K, V> {
    private static final long serialVersionUID = 1;

    final Function<K, V> mappingFunction;
    @Nullable final Function<Set<? extends K>, Map<K, V>> bulkMappingFunction;

    /**
     * 传进来的是 Caffeine 实例和 自动加载器，
     * 这里主要是根据 Caffeine 实例 和自动加载器 创建出一个真正的缓存实例。
     */
    BoundedLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      // 1. 创建 cache 实例
      // 这里调用了 BoundedLocalManualCache 内部类的构造函数，实例化了 cache 实例
      super(builder, loader);

      requireNonNull(loader);

      // 2. 把缓存加载器里加载函数提取出来赋值
      // cacheLoader.load()
      mappingFunction = newMappingFunction(loader);
      // cacheLoader.loadAll()
      bulkMappingFunction = newBulkMappingFunction(loader);
    }

    @Override
    @SuppressWarnings("NullAway")
    public AsyncCacheLoader<? super K, V> cacheLoader() {
      return cache.cacheLoader;
    }

    /* 这个类主要的就是这个方法了，给缓存实例返回 自动加载器 */
    @Override
    public Function<K, V> mappingFunction() {
      return mappingFunction;
    }

    @Override
    public @Nullable Function<Set<? extends K>, Map<K, V>> bulkMappingFunction() {
      return bulkMappingFunction;
    }

    @SuppressWarnings("UnusedVariable")
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    private Object writeReplace() {
      return makeSerializationProxy(cache);
    }
  }

  /* --------------- Async Cache --------------- */

  static final class BoundedLocalAsyncCache<K, V> implements LocalAsyncCache<K, V>, Serializable {
    private static final long serialVersionUID = 1;

    final BoundedLocalCache<K, CompletableFuture<V>> cache;
    final boolean isWeighted;

    @Nullable ConcurrentMap<K, CompletableFuture<V>> mapView;
    @Nullable CacheView<K, V> cacheView;
    @Nullable Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    BoundedLocalAsyncCache(Caffeine<K, V> builder) {
      cache = (BoundedLocalCache<K, CompletableFuture<V>>) LocalCacheFactory
          .newBoundedLocalCache(builder, /* loader */ null, /* async */ true);
      isWeighted = builder.isWeighted();
    }

    @Override
    public BoundedLocalCache<K, CompletableFuture<V>> cache() {
      return cache;
    }

    @Override
    public ConcurrentMap<K, CompletableFuture<V>> asMap() {
      return (mapView == null) ? (mapView = new AsyncAsMapView<>(this)) : mapView;
    }

    @Override
    public Cache<K, V> synchronous() {
      return (cacheView == null) ? (cacheView = new CacheView<>(this)) : cacheView;
    }

    @Override
    public Policy<K, V> policy() {
      if (policy == null) {
        @SuppressWarnings("unchecked")
        BoundedLocalCache<K, V> castCache = (BoundedLocalCache<K, V>) cache;
        Function<CompletableFuture<V>, V> transformer = Async::getIfReady;
        @SuppressWarnings("unchecked")
        Function<V, V> castTransformer = (Function<V, V>) transformer;
        policy = new BoundedPolicy<>(castCache, castTransformer, isWeighted);
      }
      return policy;
    }

    @SuppressWarnings("UnusedVariable")
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    private Object writeReplace() {
      return makeSerializationProxy(cache);
    }
  }

  /* --------------- Async Loading Cache --------------- */

  static final class BoundedLocalAsyncLoadingCache<K, V>
      extends LocalAsyncLoadingCache<K, V> implements Serializable {
    private static final long serialVersionUID = 1;

    final BoundedLocalCache<K, CompletableFuture<V>> cache;
    final boolean isWeighted;

    @Nullable ConcurrentMap<K, CompletableFuture<V>> mapView;
    @Nullable Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    BoundedLocalAsyncLoadingCache(Caffeine<K, V> builder, AsyncCacheLoader<? super K, V> loader) {
      super(loader);
      isWeighted = builder.isWeighted();
      cache = (BoundedLocalCache<K, CompletableFuture<V>>) LocalCacheFactory
          .newBoundedLocalCache(builder, loader, /* async */ true);
    }

    @Override
    public BoundedLocalCache<K, CompletableFuture<V>> cache() {
      return cache;
    }

    @Override
    public ConcurrentMap<K, CompletableFuture<V>> asMap() {
      return (mapView == null) ? (mapView = new AsyncAsMapView<>(this)) : mapView;
    }

    @Override
    public Policy<K, V> policy() {
      if (policy == null) {
        @SuppressWarnings("unchecked")
        BoundedLocalCache<K, V> castCache = (BoundedLocalCache<K, V>) cache;
        Function<CompletableFuture<V>, V> transformer = Async::getIfReady;
        @SuppressWarnings("unchecked")
        Function<V, V> castTransformer = (Function<V, V>) transformer;
        policy = new BoundedPolicy<>(castCache, castTransformer, isWeighted);
      }
      return policy;
    }

    @SuppressWarnings("UnusedVariable")
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    private Object writeReplace() {
      return makeSerializationProxy(cache);
    }
  }
}

/** The namespace for field padding through inheritance. */
final class BLCHeader {

  static class PadDrainStatus {
    byte p000, p001, p002, p003, p004, p005, p006, p007;
    byte p008, p009, p010, p011, p012, p013, p014, p015;
    byte p016, p017, p018, p019, p020, p021, p022, p023;
    byte p024, p025, p026, p027, p028, p029, p030, p031;
    byte p032, p033, p034, p035, p036, p037, p038, p039;
    byte p040, p041, p042, p043, p044, p045, p046, p047;
    byte p048, p049, p050, p051, p052, p053, p054, p055;
    byte p056, p057, p058, p059, p060, p061, p062, p063;
    byte p064, p065, p066, p067, p068, p069, p070, p071;
    byte p072, p073, p074, p075, p076, p077, p078, p079;
    byte p080, p081, p082, p083, p084, p085, p086, p087;
    byte p088, p089, p090, p091, p092, p093, p094, p095;
    byte p096, p097, p098, p099, p100, p101, p102, p103;
    byte p104, p105, p106, p107, p108, p109, p110, p111;
    byte p112, p113, p114, p115, p116, p117, p118, p119;
  }

  /** Enforces a memory layout to avoid false sharing by padding the drain status. */
  abstract static class DrainStatusRef extends PadDrainStatus {
    // drainStatus 属性的变量句柄，方便进行原子操作
    static final VarHandle DRAIN_STATUS;

    // 缓冲区状态枚举
    /** A drain is not taking place. */
    // 空闲
    static final int IDLE = 0;
    /** A drain is required due to a pending write modification. */
    // 需要清理
    static final int REQUIRED = 1;
    // 正在清理中，清理完毕后会转为 IDLE 状态
    /** A drain is in progress and will transition to idle. */
    static final int PROCESSING_TO_IDLE = 2;
    /** A drain is in progress and will transition to required. */
    // 正在清理中，清理完毕后会转为 REQUIRED 状态
    static final int PROCESSING_TO_REQUIRED = 3;

    /** The draining status of the buffers. */
    // 缓冲区状态，这里不是单指读缓冲区或写缓冲区，而是整体的状态，任意一个缓冲区满都会触发状态变更并触发缓存维护，看 scheduleDrainBuffers() 方法就知道了
    volatile int drainStatus = IDLE;

    /**
     * Returns whether maintenance work is needed.
     *
     * @param delayable if draining the read buffer can be delayed
     */
    boolean shouldDrainBuffers(boolean delayable) {
      switch (drainStatus()) {
        case IDLE:
          return !delayable;
        case REQUIRED:
          return true;
        case PROCESSING_TO_IDLE:
        case PROCESSING_TO_REQUIRED:
          return false;
        default:
          throw new IllegalStateException();
      }
    }

    int drainStatus() {
      return (int) DRAIN_STATUS.getOpaque(this);
    }

    void setDrainStatusOpaque(int drainStatus) {
      DRAIN_STATUS.setOpaque(this, drainStatus);
    }

    void setDrainStatusRelease(int drainStatus) {
      DRAIN_STATUS.setRelease(this, drainStatus);
    }

    boolean casDrainStatus(int expect, int update) {
      return DRAIN_STATUS.compareAndSet(this, expect, update);
    }

    static {
      try {
        DRAIN_STATUS = MethodHandles.lookup()
            .findVarHandle(DrainStatusRef.class, "drainStatus", int.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
