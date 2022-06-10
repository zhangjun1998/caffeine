/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.Caffeine.ceilingPowerOfTwo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.function.Consumer;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * 带状缓冲区的抽象类，提供了一些带状缓冲区的基本方法
 * <p>
 *
 * A base class providing the mechanics for supporting dynamic striping of bounded buffers. This
 * implementation is an adaption of the numeric 64-bit {@link java.util.concurrent.atomic.Striped64}
 * class, which is used by atomic counters. The approach was modified to lazily grow an array of
 * buffers in order to minimize memory usage for caches that are not heavily contended on.
 *
 * @author dl@cs.oswego.edu (Doug Lea)
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class StripedBuffer<E> implements Buffer<E> {
  /*
   * This class maintains a lazily-initialized table of atomically updated buffers. The table size
   * is a power of two. Indexing uses masked per-thread hash codes. Nearly all declarations in this
   * class are package-private, accessed directly by subclasses.
   *
   * Table entries are of class Buffer and should be padded to reduce cache contention. Padding is
   * overkill for most atomics because they are usually irregularly scattered in memory and thus
   * don't interfere much with each other. But Atomic objects residing in arrays will tend to be
   * placed adjacent to each other, and so will most often share cache lines (with a huge negative
   * performance impact) without this precaution.
   *
   * In part because Buffers are relatively large, we avoid creating them until they are needed.
   * When there is no contention, all updates are made to a single buffer. Upon contention (a failed
   * CAS inserting into the buffer), the table is expanded to size 2. The table size is doubled upon
   * further contention until reaching the nearest power of two greater than or equal to the number
   * of CPUS. Table slots remain empty (null) until they are needed.
   *
   * A single spinlock ("tableBusy") is used for initializing and resizing the table, as well as
   * populating slots with new Buffers. There is no need for a blocking lock; when the lock is not
   * available, threads try other slots. During these retries, there is increased contention and
   * reduced locality, which is still better than alternatives.
   *
   * Contention and/or table collisions are indicated by failed CASes when performing an update
   * operation. Upon a collision, if the table size is less than the capacity, it is doubled in size
   * unless some other thread holds the lock. If a hashed slot is empty, and lock is available, a
   * new Buffer is created. Otherwise, if the slot exists, a CAS is tried. The Thread id serves as
   * the base for per-thread hash codes. Retries proceed by "incremental hashing", using the top
   * half of the seed to increment the bottom half used as the probe to try to find a free slot.
   *
   * The table size is capped because, when there are more threads than CPUs, supposing that each
   * thread were bound to a CPU, there would exist a perfect hash function mapping threads to slots
   * that eliminates collisions. When we reach capacity, we search for this mapping by varying the
   * hash codes of colliding threads. Because search is random, and collisions only become known via
   * CAS failures, convergence can be slow, and because threads are typically not bound to CPUs
   * forever, may not occur at all. However, despite these limitations, observed contention rates
   * are typically low in these cases.
   *
   * It is possible for a Buffer to become unused when threads that once hashed to it terminate, as
   * well as in the case where doubling the table causes no thread to hash to it under expanded
   * mask. We do not try to detect or remove buffers, under the assumption that for long-running
   * instances, observed contention levels will recur, so the buffers will eventually be needed
   * again; and for short-lived ones, it does not matter.
   */

  static final VarHandle TABLE_BUSY;

  /**
   * 当前服务器的 CPU 数量
   */
  static final int NCPU = Runtime.getRuntime().availableProcessors();

  /**
   * 带状缓冲区的最大容量，取决于 CPU 数量
   */
  static final int MAXIMUM_TABLE_SIZE = 4 * ceilingPowerOfTwo(NCPU);

  /**
   * 扩容操作的最大重试次数
   */
  static final int ATTEMPTS = 3;

  /**
   * 带状缓冲区大小，2的n次幂
   */
  volatile Buffer<E> @Nullable[] table;

  /**
   * 操作 缓冲区的 CAS 标记，在初始化缓冲区和调节缓冲区大小时会使用其进行自旋
   */
  volatile int tableBusy;

  /** CASes the tableBusy field from 0 to 1 to acquire lock. */
  final boolean casTableBusy() {
    return TABLE_BUSY.compareAndSet(this, 0, 1);
  }

  /**
   * Creates a new buffer instance after resizing to accommodate a producer.
   *
   * @param e the producer's element
   * @return a newly created buffer populated with a single element
   */
  protected abstract Buffer<E> create(E e);

  /**
   * 向缓冲区中添加元素
   */
  @Override
  public int offer(E e) {
    long z = mix64(Thread.currentThread().getId());
    int increment = (int) (z >>> 32) | 1;
    int h = (int) z;

    int mask;
    int result;
    Buffer<E> buffer;
    boolean uncontended = true;
    Buffer<E>[] buffers = table;

    // buffer.offer(e) 调用的是 RingBuffer 的 offer() 方法，内部使用 CAS 来添加元素
    if ((buffers == null)
        || ((mask = buffers.length - 1) < 0)
        || ((buffer = buffers[h & mask]) == null)
        || !(uncontended = ((result = buffer.offer(e)) != Buffer.FAILED))) {

      // 添加到缓冲区失败，进行扩容避免线程竞争
      return expandOrRetry(e, h, increment, uncontended);
    }
    return result;
  }

  /**
   * Handles cases of updates involving initialization, resizing, creating new Buffers, and/or
   * contention. See above for explanation. This method suffers the usual non-modularity problems of
   * optimistic retry code, relying on rechecked sets of reads.
   *
   * @param e the element to add
   * @param h the thread's hash
   * @param increment the amount to increment by when rehashing
   * @param wasUncontended false if CAS failed before this call
   * @return {@code Buffer.SUCCESS}, {@code Buffer.FAILED}, or {@code Buffer.FULL}
   */
  final int expandOrRetry(E e, int h, int increment, boolean wasUncontended) {
    int result = Buffer.FAILED;
    boolean collide = false; // True if last slot nonempty
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      Buffer<E>[] buffers;
      Buffer<E> buffer;
      int n;
      if (((buffers = table) != null) && ((n = buffers.length) > 0)) {
        if ((buffer = buffers[(n - 1) & h]) == null) {
          if ((tableBusy == 0) && casTableBusy()) { // Try to attach new Buffer
            boolean created = false;
            try { // Recheck under lock
              Buffer<E>[] rs;
              int mask, j;
              if (((rs = table) != null) && ((mask = rs.length) > 0)
                  && (rs[j = (mask - 1) & h] == null)) {
                rs[j] = create(e);
                created = true;
              }
            } finally {
              tableBusy = 0;
            }
            if (created) {
              break;
            }
            continue; // Slot is now non-empty
          }
          collide = false;
        } else if (!wasUncontended) { // CAS already known to fail
          wasUncontended = true;      // Continue after rehash
        } else if ((result = buffer.offer(e)) != Buffer.FAILED) {
          break;
        } else if ((n >= MAXIMUM_TABLE_SIZE) || (table != buffers)) {
          collide = false; // At max size or stale
        } else if (!collide) {
          collide = true;
        } else if ((tableBusy == 0) && casTableBusy()) {
          try {
            if (table == buffers) { // Expand table unless stale
              table = Arrays.copyOf(buffers, n << 1);
            }
          } finally {
            tableBusy = 0;
          }
          collide = false;
          continue; // Retry with expanded table
        }
        h += increment;
      } else if ((tableBusy == 0) && (table == buffers) && casTableBusy()) {
        boolean init = false;
        try { // Initialize table
          if (table == buffers) {
            // 创建 readBuffer，桶的初始数量为1
            @SuppressWarnings({"unchecked", "rawtypes"})
            Buffer<E>[] rs = new Buffer[1];
            // 这里初始化第一个桶位，一个容量为16的环形缓冲区
            rs[0] = create(e);
            table = rs;
            init = true;
          }
        } finally {
          tableBusy = 0;
        }
        if (init) {
          break;
        }
      }
    }
    return result;
  }

  /**
   * 清理缓冲区，BoundedBuffer 继承了该方法，会遍历清理所有的环形缓冲区
   */
  @Override
  public void drainTo(Consumer<E> consumer) {
    // 带状缓冲区实例
    Buffer<E>[] buffers = table;
    if (buffers == null) {
      return;
    }
    // 遍历清理带状缓冲区中的所有环形缓冲区
    for (Buffer<E> buffer : buffers) {
      if (buffer != null) {
        // 这里调用的是 RingBuffer 的 drainTo() 方法
        buffer.drainTo(consumer);
      }
    }
  }

  @Override
  public long reads() {
    Buffer<E>[] buffers = table;
    if (buffers == null) {
      return 0;
    }
    long reads = 0;
    for (Buffer<E> buffer : buffers) {
      if (buffer != null) {
        reads += buffer.reads();
      }
    }
    return reads;
  }

  @Override
  public long writes() {
    Buffer<E>[] buffers = table;
    if (buffers == null) {
      return 0;
    }
    long writes = 0;
    for (Buffer<E> buffer : buffers) {
      if (buffer != null) {
        writes += buffer.writes();
      }
    }
    return writes;
  }

  /** Computes Stafford variant 13 of 64-bit mix function. */
  static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }

  static {
    try {
      TABLE_BUSY = MethodHandles.lookup()
          .findVarHandle(StripedBuffer.class, "tableBusy", int.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
