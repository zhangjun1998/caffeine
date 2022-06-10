/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.Caffeine.ceilingPowerOfTwo;
import static java.util.Objects.requireNonNull;

import java.lang.ref.ReferenceQueue;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * 分层时间轮。
 * <p>
 *
 * Caffeine 的分层时间轮分为2层，第一层的5个时间刻度从 1.07s ~ 6.5d，这5个刻度每个都关联了一个第二层时间轮，
 * 按照时间粒度的大小，每个刻度关联的第二层时间轮的范围也不一样，时间粒度越细则关联的时间轮范围越大，反之时间粒度越粗则关联的时间轮范围越小。
 * 其中最粗的时间粒度6.5d所关联的第二层时间轮的范围是1，因此这个时间轮上只有一个 bucket，也就是说过期时间 >= 6.5d 的所有元素都在这个 bucket 上。
 * Caffeine 之所以这样设计，主要是考虑到本地缓存在实际使用中缓存的都是一些热点数据，缓存的时间一般比较短，因此 >= 6.5d 的元素很少，
 * 放在一个 bucket 上对时间复杂度的影响基本为0。
 * <p>
 *
 * 第二层时间轮在其时间范围内设置了 n(n==时间范围) 个 bucket，每个 bucket 都关联了一个双向链表，在时间轮初始化的时候会设置一个哨兵节点作为链表的头节点。
 * 定位时间轮中元素则是先根据时间定位到对应粒度时间轮中的 bucket，然后遍历 bucket 关联的双向链表找到该元素。
 * <p>
 *
 * A hierarchical timer wheel to add, remove, and fire expiration events in amortized O(1) time. The
 * expiration events are deferred until the timer is advanced, which is performed as part of the
 * cache's maintenance cycle.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("GuardedBy")
final class TimerWheel<K, V> implements Iterable<Node<K, V>> {

  /*
   * A timer wheel [1] stores timer events in buckets on a circular buffer. A bucket represents a
   * coarse time span, e.g. one minute, and holds a doubly-linked list of events. The wheels are
   * structured in a hierarchy (seconds, minutes, hours, days) so that events scheduled in the
   * distant future are cascaded to lower buckets when the wheels rotate. This allows for events
   * to be added, removed, and expired in O(1) time, where expiration occurs for the entire bucket,
   * and the penalty of cascading is amortized by the rotations.
   *
   * [1] Hashed and Hierarchical Timing Wheels
   * http://www.cs.columbia.edu/~nahum/w6998/papers/ton97-timing-wheels.pdf
   */

  /**
   * 第二层各个时间轮的范围，64 ~ 1。
   * 第一层时间轮中按照不同的时间粒度关联了对应的第二层时间轮。
   * 第二层每个时间轮的范围都是 2^n，方便根据掩码快速定位 index。
   */
  static final int[] BUCKETS = { 64, 64, 32, 4, 1 };

  /**
   * 第一层时间轮的几个刻度对应能够覆盖的时间范围，1.07s ~ 6.5d。
   * 第一层时间轮的每个刻度都关联了一个第二层的时间轮。
   * 每个刻度覆盖的范围都是 2^n，方便根据掩码快速定位 index。
   */
  static final long[] SPANS = {
      ceilingPowerOfTwo(TimeUnit.SECONDS.toNanos(1)), // 1.07s
      ceilingPowerOfTwo(TimeUnit.MINUTES.toNanos(1)), // 1.14m
      ceilingPowerOfTwo(TimeUnit.HOURS.toNanos(1)),   // 1.22h
      ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)),    // 1.63d
      BUCKETS[3] * ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)), // 6.5d
      BUCKETS[3] * ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)), // 6.5d
  };

  /**
   * 用于计算指定时间在第二层时间轮中的刻度。
   * 不同粒度的时间轮 SHIFT 不同
   *
   * {@link Long#numberOfTrailingZeros(long)} 用于返回以二进制表时从低位到高位共有多少个连续的0
   */
  static final long[] SHIFT = {
      Long.numberOfTrailingZeros(SPANS[0]),
      Long.numberOfTrailingZeros(SPANS[1]),
      Long.numberOfTrailingZeros(SPANS[2]),
      Long.numberOfTrailingZeros(SPANS[3]),
      Long.numberOfTrailingZeros(SPANS[4]),
  };

  // 该时间轮关联的缓存
  final BoundedLocalCache<K, V> cache;

  /**
   * 分层时间轮实例，这里是两层时间轮。
   * <p>
   *
   * 第一层时间轮的刻度范围是固定，从 1.07s ~ 6.5d。
   * 第二层各个时间轮的刻度范围则根据粒度划分了不同的范围，
   * 1.07s 刻度关联的是一个范围为64的时间轮，
   * 1.14m 刻度关联的是一个范围为64的时间轮，
   * 1.22h 刻度关联的是一个范围为32的时间轮，
   * 1.63d 刻度关联的是一个范围为4的时间轮，
   * 6.5d 刻度关联的是一个范围为1的时间轮。
   * 最终形成的时间轮实例如下：
   * wheel = Node[5][] = { Node[64], Node[64], Node[32], Node[4], Node[1] }
   * <p>
   *
   * 第一层时间轮中越小的时间粒度，其关联的第二层时间轮范围越大，因为针对本地缓存的实际使用来说大多数都是秒级和分钟级过期，
   * 因此它们的时间轮范围比较大，时间轮范围越大，那么按时间定位到的刻度范围越小，因此需要遍历的时间刻度越少，时间复杂度越低。
   */
  final Node<K, V>[][] wheel;
  /**
   * 上次调度时间，纳秒
   */
  long nanos;

  /**
   * 时间轮的构造函数
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  TimerWheel(BoundedLocalCache<K, V> cache) {
    this.cache = requireNonNull(cache);

    // 创建分层时间轮实例
    wheel = new Node[BUCKETS.length][];
    // 初始化分层时间轮
    for (int i = 0; i < wheel.length; i++) {
      // 初始化第一层时间轮
      wheel[i] = new Node[BUCKETS[i]];
      // 初始化第二层时间轮
      for (int j = 0; j < wheel[i].length; j++) {
        // 初始化第二层时间轮上的每个 bucket 所关联双向链表的哨兵节点
        wheel[i][j] = new Sentinel<>();
      }
    }
  }

  /**
   * 进行时间轮调度
   * 触发时机：缓存维护期间
   * <p>
   *
   * Advances the timer and evicts entries that have expired.
   *
   * @param currentTimeNanos the current time, in nanoseconds
   */
  public void advance(long currentTimeNanos) {
    long previousTimeNanos = nanos;
    try {
      nanos = currentTimeNanos;

      // If wrapping, temporarily shift the clock for a positive comparison
      if ((previousTimeNanos < 0) && (currentTimeNanos > 0)) {
        previousTimeNanos += Long.MAX_VALUE;
        currentTimeNanos += Long.MAX_VALUE;
      }

      // 遍历第一层时间轮
      for (int i = 0; i < SHIFT.length; i++) {
        // 上次调度时间对应的刻度
        long previousTicks = (previousTimeNanos >>> SHIFT[i]);
        // 本次调度时间对应的刻度
        long currentTicks = (currentTimeNanos >>> SHIFT[i]);
        if ((currentTicks - previousTicks) <= 0L) {
          break;
        }
        // 遍历该刻度关联的第二层时间轮
        expire(i, previousTicks, currentTicks);
      }
    } catch (Throwable t) {
      nanos = previousTimeNanos;
      throw t;
    }
  }

  /**
   * 驱逐过期缓存。
   * 若缓存过期则驱逐，否则重新加入时间轮等待调度。
   * <p>
   *
   * Expires entries or reschedules into the proper bucket if still active.
   *
   * @param index the wheel being operated on
   * @param previousTicks the previous number of ticks
   * @param currentTicks the current number of ticks
   */
  void expire(int index, long previousTicks, long currentTicks) {
    Node<K, V>[] timerWheel = wheel[index];
    // timerWheel 的掩码，法改变快速取余定位到 index
    int mask = timerWheel.length - 1;

    // 根据第一层时间轮中的刻度差值计算出第二层时间轮需要遍历的范围
    int steps = Math.min(1 + Math.abs((int) (currentTicks - previousTicks)), timerWheel.length);
    int start = (int) (previousTicks & mask);
    int end = start + steps;

    // 遍历第二层时间轮
    for (int i = start; i < end; i++) {
      Node<K, V> sentinel = timerWheel[i & mask];
      Node<K, V> prev = sentinel.getPreviousInVariableOrder();
      Node<K, V> node = sentinel.getNextInVariableOrder();
      sentinel.setPreviousInVariableOrder(sentinel);
      sentinel.setNextInVariableOrder(sentinel);

      // 遍历该 bucket 中的所有节点
      while (node != sentinel) {
        Node<K, V> next = node.getNextInVariableOrder();
        // 这里解除该 node 在 bucket 双向链表中的节点关联关系
        node.setPreviousInVariableOrder(null);
        node.setNextInVariableOrder(null);

        try {
          // 如果 node 过期则驱逐，没有过期或驱逐失败则重新加入时间轮等待调度
          if (((node.getVariableTime() - nanos) > 0) || !cache.evictEntry(node, RemovalCause.EXPIRED, nanos)) {
            schedule(node);
          }
          // 继续遍历当前双向链表的下一个节点
          node = next;
        } catch (Throwable t) {
          node.setPreviousInVariableOrder(sentinel.getPreviousInVariableOrder());
          node.setNextInVariableOrder(next);
          sentinel.getPreviousInVariableOrder().setNextInVariableOrder(node);
          sentinel.setPreviousInVariableOrder(prev);
          throw t;
        }
      }
    }
  }

  /**
   * 将 node 加入到时间轮中。
   * 触发时机：缓存写入
   * <p>
   *
   * Schedules a timer event for the node.
   *
   * @param node the entry in the cache
   */
  public void schedule(Node<K, V> node) {
    Node<K, V> sentinel = findBucket(node.getVariableTime());
    link(sentinel, node);
  }

  /**
   * 对 node 重新进行调度。
   * 触发时机：缓存更新、访问缓存
   * <p>
   *
   * Reschedules an active timer event for the node.
   *
   * @param node the entry in the cache
   */
  public void reschedule(Node<K, V> node) {
    if (node.getNextInVariableOrder() != null) {
      unlink(node);
      schedule(node);
    }
  }

  /**
   * 从时间轮中移除 node。
   * 触发时机：加载到的缓存为null、移除缓存
   * <p>
   *
   * Removes a timer event for this entry if present.
   *
   * @param node the entry in the cache
   */
  public void deschedule(Node<K, V> node) {
    unlink(node);
    node.setNextInVariableOrder(null);
    node.setPreviousInVariableOrder(null);
  }

  /**
   * 根据时间定位到时间轮上对应的桶位
   * <p>
   *
   * Determines the bucket that the timer event should be added to.
   *
   * @param time the time when the event fires
   * @return the sentinel at the head of the bucket
   */
  Node<K, V> findBucket(long time) {
    // 根据当前时间减去上次调度时间计算出存活时间
    long duration = time - nanos;
    int length = wheel.length - 1;
    for (int i = 0; i < length; i++) {
      // 按照存活时间判断元素应该在哪个粒度的第二层时间轮及在其中所处的位置
      if (duration < SPANS[i + 1]) {
        // 计算出在第二层时间轮的刻度
        long ticks = (time >>> SHIFT[i]);
        // 计算出在第二层时间轮中的index
        int index = (int) (ticks & (wheel[i].length - 1));
        // 返回第二层时间轮该 index 所关联双向链表的哨兵节点(头节点)
        return wheel[i][index];
      }
    }
    // 存活时间大于最大粒度(6.5d)，放在最大粒度的时间轮中(6.5d)
    return wheel[length][0];
  }

  /**
   * 建立节点在时间轮中的关联关系
   * <p>
   *
   * Adds the entry at the tail of the bucket's list.
   */
  void link(Node<K, V> sentinel, Node<K, V> node) {
    node.setPreviousInVariableOrder(sentinel.getPreviousInVariableOrder());
    node.setNextInVariableOrder(sentinel);

    sentinel.getPreviousInVariableOrder().setNextInVariableOrder(node);
    sentinel.setPreviousInVariableOrder(node);
  }

  /**
   * 取消节点在时间轮中的关联关系
   * <p>
   *
   * Removes the entry from its bucket, if scheduled.
   */
  void unlink(Node<K, V> node) {
    Node<K, V> next = node.getNextInVariableOrder();
    if (next != null) {
      Node<K, V> prev = node.getPreviousInVariableOrder();
      next.setPreviousInVariableOrder(prev);
      prev.setNextInVariableOrder(next);
    }
  }

  /** Returns the duration until the next bucket expires, or {@link Long.MAX_VALUE} if none. */
  @SuppressWarnings("IntLongMath")
  public long getExpirationDelay() {
    for (int i = 0; i < SHIFT.length; i++) {
      Node<K, V>[] timerWheel = wheel[i];
      long ticks = (nanos >>> SHIFT[i]);

      long spanMask = SPANS[i] - 1;
      int start = (int) (ticks & spanMask);
      int end = start + timerWheel.length;
      int mask = timerWheel.length - 1;
      for (int j = start; j < end; j++) {
        Node<K, V> sentinel = timerWheel[(j & mask)];
        Node<K, V> next = sentinel.getNextInVariableOrder();
        if (next == sentinel) {
          continue;
        }
        long buckets = (j - start);
        long delay = (buckets << SHIFT[i]) - (nanos & spanMask);
        delay = (delay > 0) ? delay : SPANS[i];

        for (int k = i + 1; k < SHIFT.length; k++) {
          long nextDelay = peekAhead(k);
          delay = Math.min(delay, nextDelay);
        }

        return delay;
      }
    }
    return Long.MAX_VALUE;
  }

  /**
   * Returns the duration when the wheel's next bucket expires, or {@link Long.MAX_VALUE} if empty.
   */
  long peekAhead(int i) {
    long ticks = (nanos >>> SHIFT[i]);
    Node<K, V>[] timerWheel = wheel[i];

    long spanMask = SPANS[i] - 1;
    int mask = timerWheel.length - 1;
    int probe = (int) ((ticks  + 1) & mask);
    Node<K, V> sentinel = timerWheel[probe];
    Node<K, V> next = sentinel.getNextInVariableOrder();
    return (next == sentinel) ? Long.MAX_VALUE : (SPANS[i] - (nanos & spanMask));
  }

  /**
   * Returns an iterator roughly ordered by the expiration time from the entries most likely to
   * expire (oldest) to the entries least likely to expire (youngest). The wheels are evaluated in
   * order, but the timers that fall within the bucket's range are not sorted.
   */
  @Override
  public Iterator<Node<K, V>> iterator() {
    return new AscendingIterator();
  }

  /**
   * Returns an iterator roughly ordered by the expiration time from the entries least likely to
   * expire (youngest) to the entries most likely to expire (oldest). The wheels are evaluated in
   * order, but the timers that fall within the bucket's range are not sorted.
   */
  public Iterator<Node<K, V>> descendingIterator() {
    return new DescendingIterator();
  }

  /** An iterator with rough ordering that can be specialized for either direction. */
  abstract class Traverser implements Iterator<Node<K, V>> {
    final long expectedNanos;

    @Nullable Node<K, V> previous;
    @Nullable Node<K, V> current;

    Traverser() {
      expectedNanos = nanos;
    }

    @Override
    public boolean hasNext() {
      if (nanos != expectedNanos) {
        throw new ConcurrentModificationException();
      } else if (current != null) {
        return true;
      } else if (isDone()) {
        return false;
      }
      current = computeNext();
      return (current != null);
    }

    @Override
    @SuppressWarnings("NullAway")
    public Node<K, V> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      previous = current;
      current = null;
      return previous;
    }

    @Nullable Node<K, V> computeNext() {
      var node = (previous == null) ? sentinel() : previous;
      for (;;) {
        node = traverse(node);
        if (node != sentinel()) {
          return node;
        } else if ((node = goToNextBucket()) != null) {
          continue;
        } else if ((node = goToNextWheel()) != null) {
          continue;
        }
        return null;
      }
    }

    /** Returns if the iteration has completed. */
    abstract boolean isDone();

    /** Returns the sentinel at the current wheel and bucket position. */
    abstract Node<K, V> sentinel();

    /** Returns the node's successor, or the bucket's sentinel if at the end. */
    abstract Node<K, V> traverse(Node<K, V> node);

    /** Returns the sentinel for the wheel's next bucket, or null if the wheel is exhausted. */
    abstract @Nullable Node<K, V> goToNextBucket();

    /** Returns the sentinel for the next wheel's bucket position, or null if no more wheels. */
    abstract @Nullable Node<K, V> goToNextWheel();
  }

  final class AscendingIterator extends Traverser {
    int wheelIndex;
    int steps;

    @Override boolean isDone() {
      return (wheelIndex == wheel.length);
    }
    @Override Node<K, V> sentinel() {
      return wheel[wheelIndex][bucketIndex()];
    }
    @Override Node<K, V> traverse(Node<K, V> node) {
      return node.getNextInVariableOrder();
    }
    @Override @Nullable Node<K, V> goToNextBucket() {
      return (++steps < wheel[wheelIndex].length)
          ? wheel[wheelIndex][bucketIndex()]
          : null;
    }
    @Override @Nullable Node<K, V> goToNextWheel() {
      if (++wheelIndex == wheel.length) {
        return null;
      }
      steps = 0;
      return wheel[wheelIndex][bucketIndex()];
    }
    int bucketIndex() {
      int ticks = (int) (nanos >>> SHIFT[wheelIndex]);
      int bucketMask = wheel[wheelIndex].length - 1;
      int bucketOffset = (ticks & bucketMask) + 1;
      return (bucketOffset + steps) & bucketMask;
    }
  }

  final class DescendingIterator extends Traverser {
    int wheelIndex;
    int steps;

    DescendingIterator() {
      wheelIndex = wheel.length - 1;
    }
    @Override boolean isDone() {
      return (wheelIndex == -1);
    }
    @Override Node<K, V> sentinel() {
      return wheel[wheelIndex][bucketIndex()];
    }
    @Override @Nullable Node<K, V> goToNextBucket() {
      return (++steps < wheel[wheelIndex].length)
          ? wheel[wheelIndex][bucketIndex()]
          : null;
    }
    @Override @Nullable Node<K, V> goToNextWheel() {
      if (--wheelIndex < 0) {
        return null;
      }
      steps = 0;
      return wheel[wheelIndex][bucketIndex()];
    }
    @Override Node<K, V> traverse(Node<K, V> node) {
      return node.getPreviousInVariableOrder();
    }
    int bucketIndex() {
      int ticks = (int) (nanos >>> SHIFT[wheelIndex]);
      int bucketMask = wheel[wheelIndex].length - 1;
      int bucketOffset = (ticks & bucketMask);
      return (bucketOffset - steps) & bucketMask;
    }
  }

  /**
   * 第二层时间轮上每个 bucket 所关联双向链表的哨兵节点。
   * 哨兵节点继承了 {@link Node}
   * <p>
   *
   * A sentinel for the doubly-linked list in the bucket.
   */
  static final class Sentinel<K, V> extends Node<K, V> {
    Node<K, V> prev;
    Node<K, V> next;

    Sentinel() {
      prev = next = this;
    }

    @Override public Node<K, V> getPreviousInVariableOrder() {
      return prev;
    }
    @SuppressWarnings("NullAway")
    @Override public void setPreviousInVariableOrder(@Nullable Node<K, V> prev) {
      this.prev = prev;
    }
    @Override public Node<K, V> getNextInVariableOrder() {
      return next;
    }
    @SuppressWarnings("NullAway")
    @Override public void setNextInVariableOrder(@Nullable Node<K, V> next) {
      this.next = next;
    }

    @Override public @Nullable K getKey() { return null; }
    @Override public Object getKeyReference() { throw new UnsupportedOperationException(); }
    @Override public @Nullable V getValue() { return null; }
    @Override public Object getValueReference() { throw new UnsupportedOperationException(); }
    @Override public void setValue(V value, @Nullable ReferenceQueue<V> referenceQueue) {}
    @Override public boolean containsValue(Object value) { return false; }
    @Override public boolean isAlive() { return false; }
    @Override public boolean isRetired() { return false; }
    @Override public boolean isDead() { return false; }
    @Override public void retire() {}
    @Override public void die() {}
  }
}
