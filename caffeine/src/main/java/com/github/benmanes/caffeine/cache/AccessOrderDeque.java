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

import java.util.Deque;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.AccessOrderDeque.AccessOrder;

/**
 * 顺序访问队列。
 * 对顺序访问队列的进行操作的时机主要在缓存维护中，而缓存维护是单线程加锁执行的，因此不需要考虑并发问题。
 * 顺序访问队列是根据 LRU 算法进行排序的，最近访问的在队首，最久未访问的在队尾，因此元素被访问时只需要移动到队首即可，时间复杂度是O(1)。
 * 因为是双向链表，因此元素被删除时很容易从队列中移除。
 * <p>
 * 需要注意泛型 E 继承了 AccessOrder 接口，该接口规定了 node 操作其相邻节点的方法。
 *
 * A linked deque implementation used to represent an access-order queue.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <E> the type of elements held in this collection
 */
final class AccessOrderDeque<E extends AccessOrder<E>> extends AbstractLinkedDeque<E> {

  @Override
  public boolean contains(Object o) {
    return (o instanceof AccessOrder<?>) && contains((AccessOrder<?>) o);
  }

  /**
   * 快速判断元素是否在顺序读队列中。
   * 只要元素有相邻节点或元素是头节点即认为元素在队列中
   */
  boolean contains(AccessOrder<?> e) {
    return (e.getPreviousInAccessOrder() != null)
        || (e.getNextInAccessOrder() != null)
        || (e == first);
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean remove(Object o) {
    return (o instanceof AccessOrder<?>) && remove((E) o);
  }

  // A fast-path removal
  boolean remove(E e) {
    if (contains(e)) {
      unlink(e);
      return true;
    }
    return false;
  }

  @Override
  public @Nullable E getPrevious(E e) {
    return e.getPreviousInAccessOrder();
  }

  @Override
  public void setPrevious(E e, @Nullable E prev) {
    e.setPreviousInAccessOrder(prev);
  }

  @Override
  public @Nullable E getNext(E e) {
    return e.getNextInAccessOrder();
  }

  @Override
  public void setNext(E e, @Nullable E next) {
    e.setNextInAccessOrder(next);
  }

  /**
   * 规定了在队列中的元素应该实现的操作
   * <p>
   * An element that is linked on the {@link Deque}.
   */
  interface AccessOrder<T extends AccessOrder<T>> {

    /**
     * Retrieves the previous element or <tt>null</tt> if either the element is unlinked or the
     * first element on the deque.
     */
    @Nullable T getPreviousInAccessOrder();

    /** Sets the previous element or <tt>null</tt> if there is no link. */
    void setPreviousInAccessOrder(@Nullable T prev);

    /**
     * Retrieves the next element or <tt>null</tt> if either the element is unlinked or the last
     * element on the deque.
     */
    @Nullable T getNextInAccessOrder();

    /** Sets the next element or <tt>null</tt> if there is no link. */
    void setNextInAccessOrder(@Nullable T next);
  }
}
