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
package com.github.benmanes.caffeine.cache;

import java.lang.ref.ReferenceQueue;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.AccessOrderDeque.AccessOrder;
import com.github.benmanes.caffeine.cache.WriteOrderDeque.WriteOrder;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.github.benmanes.caffeine.cache.TimerWheel.Sentinel;

/**
 * 用于包装 value，是实际存储到 ConcurrentHashMap 中的元素，
 * 它的主要作用就是给 value 附加一些额外的属性，方便实现 Caffeine 的功能特性，类似于装饰器(Wrapper) 模式的作用。
 * <p>
 * 注意：Node 中没有定义任何属性，因为不同缓存类型拥有的属性也不完全相同，如果建立一个包含所有属性的 Node 类无疑会导致资源浪费，
 * 因此实际运行中 Caffeine 会根据缓存配置区按需动态生成代码，不同缓存类型中存储的实际上是 Node 的不同子类实现，
 * 这些子类只包含当前缓存所需的属性，以最大化节省 node 占据的空间
 * 可以在 debug 的时候看到，如名为『FSA』 的类
 * <p>
 *
 * An entry in the cache containing the key, value, weight, access, and write metadata. The key
 * or value may be held weakly or softly requiring identity comparison.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class Node<K, V> implements AccessOrder<Node<K, V>>, WriteOrder<Node<K, V>> {

  /* --------------- 下面部分是 key-value 及其引用类型相关方法，主要是 getter/setter --------------- */

  /** Return the key or {@code null} if it has been reclaimed by the garbage collector. */
  @Nullable
  public abstract K getKey();

  /**
   * Returns the reference that the cache is holding the entry by. This is either the key if
   * strongly held or a {@link java.lang.ref.WeakReference} to that key.
   */
  public abstract Object getKeyReference();

  // 这里没定义 keyReference 的 setter 方法，但它的子实现类

  /** Return the value or {@code null} if it has been reclaimed by the garbage collector. */
  @Nullable
  public abstract V getValue();

  /**
   * Returns the reference to the value. This is either the value if strongly held or a
   * {@link java.lang.ref.Reference} to that value.
   */
  public abstract Object getValueReference();

  /**
   * Sets the value, which may be held strongly, weakly, or softly. This update may be set lazily
   * and rely on the memory fence when the lock is released.
   */
  @GuardedBy("this")
  public abstract void setValue(V value, @Nullable ReferenceQueue<V> referenceQueue);

  /**
   * Returns {@code true} if the given objects are considered equivalent. A strongly held value is
   * compared by equality and a weakly or softly held value is compared by identity.
   */
  public abstract boolean containsValue(Object value);

  // 权重相关
  /** Returns the weight of this entry from the entry's perspective. */
  @NonNegative
  @GuardedBy("this")
  public int getWeight() {
    return 1;
  }

  /** Sets the weight from the entry's perspective. */
  @GuardedBy("this")
  public void setWeight(@NonNegative int weight) {}

  /** returns the weight of this entry from the policy's perspective. */
  @NonNegative
  // @GuardedBy("evictionLock")
  public int getPolicyWeight() {
    return 1;
  }

  /** Sets the weight from the policy's perspective. */
  // @GuardedBy("evictionLock")
  public void setPolicyWeight(@NonNegative int weight) {}

  /* --------------- 下面这部分是 node 的生命周期 --------------- */

  /** If the entry is available in the hash-table and page replacement policy. */
  public abstract boolean isAlive();

  /**
   * If the entry was removed from the hash-table and is awaiting removal from the page
   * replacement policy.
   */
  @GuardedBy("this")
  public abstract boolean isRetired();

  /** If the entry was removed from the hash-table and the page replacement policy. */
  @GuardedBy("this")
  public abstract boolean isDead();

  /** Sets the node to the <tt>retired</tt> state. */
  @GuardedBy("this")
  public abstract void retire();

  /** Sets the node to the <tt>dead</tt> state. */
  @GuardedBy("this")
  public abstract void die();

  /* --------------- 下面这部分是 node 的最后读时间相关 --------------- */

  /** Returns the variable expiration time, in nanoseconds. */
  public long getVariableTime() {
    return 0L;
  }

  /**
   * Sets the variable expiration time in nanoseconds. This update may be set lazily and rely on the
   * memory fence when the lock is released.
   */
  public void setVariableTime(long time) {}

  /**
   * Atomically sets the variable time to the given updated value if the current value equals the
   * expected value and returns if the update was successful.
   */
  public boolean casVariableTime(long expect, long update) {
    throw new UnsupportedOperationException();
  }

  // node 在读队列中的排序相关操作
  // @GuardedBy("evictionLock")
  public Node<K, V> getPreviousInVariableOrder() {
    throw new UnsupportedOperationException();
  }

  // @GuardedBy("evictionLock")
  public void setPreviousInVariableOrder(@Nullable Node<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  // @GuardedBy("evictionLock")
  public Node<K, V> getNextInVariableOrder() {
    throw new UnsupportedOperationException();
  }

  // @GuardedBy("evictionLock")
  public void setNextInVariableOrder(@Nullable Node<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  /* --------------- 下面这部分是 node 的最后写时间相关 --------------- */

  /**
   * node 所在的区域，和 W-TinyLFU 算法结合理解
   * {@link BoundedLocalCache#accessOrderWindowDeque()}、{@link BoundedLocalCache#accessOrderProbationDeque()}、{@link BoundedLocalCache#accessOrderProtectedDeque()}
   */
  public static final int WINDOW = 0;
  public static final  int PROBATION = 1;
  public static final  int PROTECTED = 2;

  /** Returns if the entry is in the Window or Main space. */
  public boolean inWindow() {
    return getQueueType() == WINDOW;
  }

  /** Returns if the entry is in the Main space's probation queue. */
  public boolean inMainProbation() {
    return getQueueType() == PROBATION;
  }

  /** Returns if the entry is in the Main space's protected queue. */
  public boolean inMainProtected() {
    return getQueueType() == PROTECTED;
  }

  /** Sets the status to the Window queue. */
  public void makeWindow() {
    setQueueType(WINDOW);
  }

  /** Sets the status to the Main space's probation queue. */
  public void makeMainProbation() {
    setQueueType(PROBATION);
  }

  /** Sets the status to the Main space's protected queue. */
  public void makeMainProtected() {
    setQueueType(PROTECTED);
  }

  /** Returns the queue that the entry's resides in (window, probation, or protected). */
  public int getQueueType() {
    return WINDOW;
  }

  /** Set queue that the entry resides in (window, probation, or protected). */
  public void setQueueType(int queueType) {
    throw new UnsupportedOperationException();
  }

  /** Returns the time that this entry was last accessed, in ns. */
  public long getAccessTime() {
    return 0L;
  }

  /**
   * Sets the access time in nanoseconds. This update may be set lazily and rely on the memory fence
   * when the lock is released.
   */
  public void setAccessTime(long time) {}

  @Override
  // @GuardedBy("evictionLock")
  public @Nullable Node<K, V> getPreviousInAccessOrder() {
    return null;
  }

  @Override
  // @GuardedBy("evictionLock")
  public void setPreviousInAccessOrder(@Nullable Node<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @Override
  // @GuardedBy("evictionLock")
  public @Nullable Node<K, V> getNextInAccessOrder() {
    return null;
  }

  @Override
  // @GuardedBy("evictionLock")
  public void setNextInAccessOrder(@Nullable Node<K, V> next) {
    throw new UnsupportedOperationException();
  }

  /* --------------- 下面这部分是 node 的最后写时间相关 --------------- */

  /** Returns the time that this entry was last written, in ns. */
  public long getWriteTime() {
    return 0L;
  }

  /**
   * Sets the write-time in nanoseconds. This update may be set lazily and rely on the memory fence
   * when the lock is released.
   */
  public void setWriteTime(long time) {}

  /**
   * Atomically sets the write-time to the given updated value if the current value equals the
   * expected value and returns if the update was successful.
   */
  public boolean casWriteTime(long expect, long update) {
    throw new UnsupportedOperationException();
  }

  @Override
  // @GuardedBy("evictionLock")
  public @Nullable Node<K, V> getPreviousInWriteOrder() {
    return null;
  }

  @Override
  // @GuardedBy("evictionLock")
  public void setPreviousInWriteOrder(@Nullable Node<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @Override
  // @GuardedBy("evictionLock")
  public @Nullable Node<K, V> getNextInWriteOrder() {
    return null;
  }

  @Override
  // @GuardedBy("evictionLock")
  public void setNextInWriteOrder(@Nullable Node<K, V> next) {
    throw new UnsupportedOperationException();
  }

  /**
   * 还重写了 toString() 方法
   */
  @Override
  @SuppressWarnings("GuardedBy")
  public final String toString() {
    return String.format("%s=[key=%s, value=%s, weight=%d, queueType=%,d, accessTimeNS=%,d, "
        + "writeTimeNS=%,d, varTimeNs=%,d, prevInAccess=%s, nextInAccess=%s, prevInWrite=%s, "
        + "nextInWrite=%s]", getClass().getSimpleName(), getKey(), getValue(), getWeight(),
        getQueueType(), getAccessTime(), getWriteTime(), getVariableTime(),
        getPreviousInAccessOrder() != null, getNextInAccessOrder() != null,
        getPreviousInWriteOrder() != null, getNextInWriteOrder() != null);
  }
}
