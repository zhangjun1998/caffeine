/*
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;
import java.util.Iterator;

/**
 * 写缓冲区。
 * 它是一个可自动扩容的数组，扩展时它不会像 hashmap 那样把旧数组的元素复制到新的数组，而是用一个 link 来连接到新的数组，
 * 它融合了链表和数组，既可以动态变化长度，又不会像链表一样需要频繁分配 Node，并且吞吐量优于传统的链表。
 * <p>
 *
 * An MPSC array queue which starts at <i>initialCapacity</i> and grows to <i>maxCapacity</i> in
 * linked chunks of the initial size. The queue grows only when the current buffer is full and
 * elements are not copied on resize, instead a link to the new buffer is stored in the old buffer
 * for the consumer to follow.<br>
 * <p>
 * This is a shaded copy of <tt>MpscGrowableArrayQueue</tt> provided by
 * <a href="https://github.com/JCTools/JCTools">JCTools</a> from version 2.0.
 *
 * @author nitsanw@yahoo.com (Nitsan Wakart)
 */
class MpscGrowableArrayQueue<E> extends MpscChunkedArrayQueue<E> {

  /**
   * 构造函数，传入的参数在 {@link BoundedLocalCache} 中写死了。
   * 初始容量为{@link BoundedLocalCache#WRITE_BUFFER_MIN}，最大容量为{@link BoundedLocalCache#WRITE_BUFFER_MAX}
   *
   * @param initialCapacity the queue initial capacity. If chunk size is fixed this will be the
   *        chunk size. Must be 2 or more.
   * @param maxCapacity the maximum capacity will be rounded up to the closest power of 2 and will
   *        be the upper limit of number of elements in this queue. Must be 4 or more and round up
   *        to a larger power of 2 than initialCapacity.
   */
  MpscGrowableArrayQueue(int initialCapacity, int maxCapacity) {
    super(initialCapacity, maxCapacity);
  }

  @Override
  protected int getNextBufferSize(E[] buffer) {
    long maxSize = maxQueueCapacity / 2;
    if (buffer.length > maxSize) {
      throw new IllegalStateException();
    }
    final int newSize = 2 * (buffer.length - 1);
    return newSize + 1;
  }

  @Override
  protected long getCurrentBufferCapacity(long mask) {
    return (mask + 2 == maxQueueCapacity) ? maxQueueCapacity : mask;
  }
}

@SuppressWarnings("OvershadowingSubclassFields")
abstract class MpscChunkedArrayQueue<E> extends MpscChunkedArrayQueueColdProducerFields<E> {
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

  MpscChunkedArrayQueue(int initialCapacity, int maxCapacity) {
    super(initialCapacity, maxCapacity);
  }

  @Override
  protected long availableInQueue(long pIndex, long cIndex) {
    return maxQueueCapacity - (pIndex - cIndex);
  }

  @Override
  public int capacity() {
    return (int) (maxQueueCapacity / 2);
  }

  @Override
  protected int getNextBufferSize(E[] buffer) {
    return buffer.length;
  }

  @Override
  protected long getCurrentBufferCapacity(long mask) {
    return mask;
  }
}

abstract class MpscChunkedArrayQueueColdProducerFields<E> extends BaseMpscLinkedArrayQueue<E> {
  protected final long maxQueueCapacity;

  // 子类中会链式调用父类构造函数
  MpscChunkedArrayQueueColdProducerFields(int initialCapacity, int maxCapacity) {
    // 调用父类构造函数，初始化 buffer
    super(initialCapacity);
    if (maxCapacity < 4) {
      throw new IllegalArgumentException("Max capacity must be 4 or more");
    }
    if (ceilingPowerOfTwo(initialCapacity) >= ceilingPowerOfTwo(maxCapacity)) {
      throw new IllegalArgumentException(
          "Initial capacity cannot exceed maximum capacity(both rounded up to a power of 2)");
    }
    // 设置最大容量为2^n * 2，其中2^n是大于 maxCapacity 的最小值
    maxQueueCapacity = ((long) ceilingPowerOfTwo(maxCapacity)) << 1;
  }
}

abstract class BaseMpscLinkedArrayQueuePad1<E> extends AbstractQueue<E> {
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

abstract class BaseMpscLinkedArrayQueueProducerFields<E> extends BaseMpscLinkedArrayQueuePad1<E> {
  protected long producerIndex;
}

@SuppressWarnings("OvershadowingSubclassFields")
abstract class BaseMpscLinkedArrayQueuePad2<E> extends BaseMpscLinkedArrayQueueProducerFields<E> {
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

@SuppressWarnings("NullAway")
abstract class BaseMpscLinkedArrayQueueConsumerFields<E> extends BaseMpscLinkedArrayQueuePad2<E> {
  // 消费者 buffer 的掩码，用于快速根据 offset 确定消费者的下标
  protected long consumerMask;
  // 消费者 buffer，实际指向的是和 producerBuffer 共同的数组
  protected E[] consumerBuffer;
  // 消费者当前下标
  protected long consumerIndex;
}

@SuppressWarnings("OvershadowingSubclassFields")
abstract class BaseMpscLinkedArrayQueuePad3<E> extends BaseMpscLinkedArrayQueueConsumerFields<E> {
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

@SuppressWarnings("NullAway")
abstract class BaseMpscLinkedArrayQueueColdProducerFields<E>
    extends BaseMpscLinkedArrayQueuePad3<E> {
  // 生产者的最大下标
  protected volatile long producerLimit;
  // 数组掩码，用于快速确定下标，index = offset & mask
  protected long producerMask;
  // 生产者的 buffer，实际指向的是和 consumerBuffer 共同的数组
  protected E[] producerBuffer;
}

@SuppressWarnings({"PMD", "NullAway"})
abstract class BaseMpscLinkedArrayQueue<E> extends BaseMpscLinkedArrayQueueColdProducerFields<E> {
  static final VarHandle REF_ARRAY;
  static final VarHandle P_INDEX;
  static final VarHandle C_INDEX;
  static final VarHandle P_LIMIT;

  // No post padding here, subclasses must add

  private static final Object JUMP = new Object();

  /**
   * @param initialCapacity the queue initial capacity. If chunk size is fixed this will be the
   *        chunk size. Must be 2 or more.
   */
  BaseMpscLinkedArrayQueue(final int initialCapacity) {
    if (initialCapacity < 2) {
      throw new IllegalArgumentException("Initial capacity must be 2 or more");
    }

    int p2capacity = ceilingPowerOfTwo(initialCapacity);
    // leave lower bit of mask clear
    long mask = (p2capacity - 1L) << 1;
    // need extra element to point at next array
    E[] buffer = allocate(p2capacity + 1);
    producerBuffer = buffer;
    producerMask = mask;
    consumerBuffer = buffer;
    consumerMask = mask;
    soProducerLimit(this, mask); // we know it's all empty to start with
  }

  @Override
  public final Iterator<E> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return getClass().getName() + "@" + Integer.toHexString(hashCode());
  }

  /**
   * <ol>
   *     <li>死循环，直到写入成功</li>
   *     <li>生产者的位置 > 限制值，需要进行扩容或者重试等</li>
   *     <li>生产者的位置 < 限制值，允许添加元素，更新生产者的位置</li>
   *     <li>若更新生产者位置成功，在数组指定位置设置元素值</li>
   * </ol>
   */
  @Override
  @SuppressWarnings("MissingDefault")
  public boolean offer(final E e) {
    if (null == e) {
      throw new NullPointerException();
    }

    long mask;
    E[] buffer;
    long pIndex;

    // 死循环，直到写入成功
    while (true) {
      long producerLimit = lvProducerLimit();
      // 获取 producerIndex，保证可见性
      pIndex = lvProducerIndex(this);
      // producerIndex 的最低位等于1，表示在进行扩容，空转等待扩容结束
      if ((pIndex & 1) == 1) {
        continue;
      }
      // pIndex is even (lower bit is 0) -> actual index is (pIndex >> 1)

      // mask/buffer may get changed by resizing -> only use for array access after successful CAS.
      mask = this.producerMask;
      buffer = this.producerBuffer;
      // a successful CAS ties the ordering, lv(pIndex)-[mask/buffer]->cas(pIndex)

      // assumption behind this optimization is that queue is almost always empty or near empty

      // 1. 生产者的位置 > 限制值，需要进行扩容或者重试等
      if (producerLimit <= pIndex) {
        // 通过 offerSlowPath() 判断应进行何种操作，0-，1-重试，2-，3-扩容
        int result = offerSlowPath(mask, pIndex, producerLimit);
        switch (result) {
          case 0:
            break;
          case 1:
            continue;
          case 2:
            return false;
          case 3:
            resize(mask, buffer, pIndex, e);
            return true;
        }
      }

      // 2. 生产者的位置 < 限制值，允许添加元素，更新生产者的位置
      // CAS 原子更新 producerIndex，若成功说明可以该线程可以对 producerIndex 在数组中对应的下标元素进行操作，这样还不阻塞下一个线程执行
      if (casProducerIndex(this, pIndex, pIndex + 2)) {
        break;
      }
    }

    // 3. 若更新生产者位置成功，在数组指定位置设置元素值
    // 这里没有通过追加的方式添加，而是通过操作指定下标的元素，以此配合CAS来避免多线程竞争数组的相同下标位置

    // 根据 producerIndex 定位当前线程可以操作的数组下标位置
    final long offset = modifiedCalcElementOffset(pIndex, mask);
    // 设置指定数组下标位置上的元素为 e
    soElement(buffer, offset, e);

    return true;
  }

  /**
   * We do not inline resize into this method because we do not resize on fill.
   */
  private int offerSlowPath(long mask, long pIndex, long producerLimit) {
    int result;
    final long cIndex = lvConsumerIndex(this);
    long bufferCapacity = getCurrentBufferCapacity(mask);
    result = 0;// 0 - goto pIndex CAS
    if (cIndex + bufferCapacity > pIndex) {
      if (!casProducerLimit(this, producerLimit, cIndex + bufferCapacity)) {
        result = 1;// retry from top
      }
    }
    // full and cannot grow
    else if (availableInQueue(pIndex, cIndex) <= 0) {
      result = 2;// -> return false;
    }
    // grab index for resize -> set lower bit
    else if (casProducerIndex(this, pIndex, pIndex + 1)) {
      result = 3;// -> resize
    } else {
      result = 1;// failed resize attempt, retry from top
    }
    return result;
  }

  /**
   * @return available elements in queue * 2
   */
  protected abstract long availableInQueue(long pIndex, final long cIndex);


  /**
   * <ol>
   *     <li>根据 consumerIndex 计算 offset，加载指定位置的元素e</li>
   *     <li>若 e ==null，判断是继续读取还是终止</li>
   *     <li>若 e == JUMP，跳到下一个数组继续读</li>
   *     <li>释放空间并更新消费者的位置</li>
   * </ol>
   */
  @Override
  @SuppressWarnings("unchecked")
  public E poll() {
    final E[] buffer = consumerBuffer;
    final long index = consumerIndex;
    final long mask = consumerMask;

    // 1. 根据 consumerIndex 计算 offset，加载指定位置的元素
    final long offset = modifiedCalcElementOffset(index, mask);
    Object e = lvElement(buffer, offset);// LoadLoad

    // 2. e ==null，一般不会发生
    if (e == null) {
      // 还没有读到 producerIndex 的位置，说明是延迟写入，继续读取即可
      if (index != lvProducerIndex(this)) {
        // poll() == null iff queue is empty, null element is not strong enough indicator, so we
        // must check the producer index. If the queue is indeed not empty we spin until element is
        // visible.
        do {
          e = lvElement(buffer, offset);
        } while (e == null);
      }
      // 已经读到了 producerIndex 的位置，说明数据读完了，停止
      else {
        return null;
      }
    }

    // 3. e == JUMP，跳到下一个数组继续读
    if (e == JUMP) {
      final E[] nextBuffer = getNextBuffer(buffer, mask);
      return newBufferPoll(nextBuffer, index);
    }

    // 4. 释放空间并更新消费者位置
    // 将读过的下标置为null，释放空间
    soElement(buffer, offset, null);
    // 更新 consumerIndex
    soConsumerIndex(this, index + 2);

    // 返回元素
    return (E) e;
  }

  /**
   * {@inheritDoc}
   * <p>
   * This implementation is correct for single consumer thread use only.
   */
  @SuppressWarnings("unchecked")
  @Override
  public E peek() {
    final E[] buffer = consumerBuffer;
    final long index = consumerIndex;
    final long mask = consumerMask;

    final long offset = modifiedCalcElementOffset(index, mask);
    Object e = lvElement(buffer, offset);// LoadLoad
    if (e == null && index != lvProducerIndex(this)) {
      // peek() == null iff queue is empty, null element is not strong enough indicator, so we must
      // check the producer index. If the queue is indeed not empty we spin until element is
      // visible.
      while ((e = lvElement(buffer, offset)) == null) {
        ;
      }
    }
    if (e == JUMP) {
      return newBufferPeek(getNextBuffer(buffer, mask), index);
    }
    return (E) e;
  }

  @SuppressWarnings("unchecked")
  private E[] getNextBuffer(final E[] buffer, final long mask) {
    final long nextArrayOffset = nextArrayOffset(mask);
    final E[] nextBuffer = (E[]) lvElement(buffer, nextArrayOffset);
    soElement(buffer, nextArrayOffset, null);
    return nextBuffer;
  }

  private long nextArrayOffset(final long mask) {
    return modifiedCalcElementOffset(mask + 2, Long.MAX_VALUE);
  }

  private E newBufferPoll(E[] nextBuffer, final long index) {
    final long offsetInNew = newBufferAndOffset(nextBuffer, index);
    final E n = lvElement(nextBuffer, offsetInNew);// LoadLoad
    if (n == null) {
      throw new IllegalStateException("new buffer must have at least one element");
    }
    soElement(nextBuffer, offsetInNew, null);// StoreStore
    soConsumerIndex(this, index + 2);
    return n;
  }

  private E newBufferPeek(E[] nextBuffer, final long index) {
    final long offsetInNew = newBufferAndOffset(nextBuffer, index);
    final E n = lvElement(nextBuffer, offsetInNew);// LoadLoad
    if (null == n) {
      throw new IllegalStateException("new buffer must have at least one element");
    }
    return n;
  }

  private long newBufferAndOffset(E[] nextBuffer, final long index) {
    consumerBuffer = nextBuffer;
    consumerMask = (nextBuffer.length - 2L) << 1;
    final long offsetInNew = modifiedCalcElementOffset(index, consumerMask);
    return offsetInNew;
  }

  @Override
  public final int size() {
    // NOTE: because indices are on even numbers we cannot use the size util.

    /*
     * It is possible for a thread to be interrupted or reschedule between the read of the producer
     * and consumer indices, therefore protection is required to ensure size is within valid range.
     * In the event of concurrent polls/offers to this method the size is OVER estimated as we read
     * consumer index BEFORE the producer index.
     */
    long after = lvConsumerIndex(this);
    long size;
    while (true) {
      final long before = after;
      final long currentProducerIndex = lvProducerIndex(this);
      after = lvConsumerIndex(this);
      if (before == after) {
        size = ((currentProducerIndex - after) >> 1);
        break;
      }
    }
    // Long overflow is impossible, so size is always positive. Integer overflow is possible for the
    // unbounded indexed queues.
    if (size > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    } else {
      return (int) size;
    }
  }

  @Override
  public final boolean isEmpty() {
    // Order matters!
    // Loading consumer before producer allows for producer increments after consumer index is read.
    // This ensures this method is conservative in its estimate. Note that as this is an MPMC there
    // is nothing we can do to make this an exact method.
    return (lvConsumerIndex(this) == lvProducerIndex(this));
  }

  private long lvProducerLimit() {
    return producerLimit;
  }

  public long currentProducerIndex() {
    return lvProducerIndex(this) / 2;
  }

  public long currentConsumerIndex() {
    return lvConsumerIndex(this) / 2;
  }

  public abstract int capacity();

  public boolean relaxedOffer(E e) {
    return offer(e);
  }

  @SuppressWarnings("unchecked")
  public E relaxedPoll() {
    final E[] buffer = consumerBuffer;
    final long index = consumerIndex;
    final long mask = consumerMask;

    final long offset = modifiedCalcElementOffset(index, mask);
    Object e = lvElement(buffer, offset);// LoadLoad
    if (e == null) {
      return null;
    }
    if (e == JUMP) {
      final E[] nextBuffer = getNextBuffer(buffer, mask);
      return newBufferPoll(nextBuffer, index);
    }
    soElement(buffer, offset, null);
    soConsumerIndex(this, index + 2);
    return (E) e;
  }

  @SuppressWarnings("unchecked")
  public E relaxedPeek() {
    final E[] buffer = consumerBuffer;
    final long index = consumerIndex;
    final long mask = consumerMask;

    final long offset = modifiedCalcElementOffset(index, mask);
    Object e = lvElement(buffer, offset);// LoadLoad
    if (e == JUMP) {
      return newBufferPeek(getNextBuffer(buffer, mask), index);
    }
    return (E) e;
  }

  private void resize(long oldMask, E[] oldBuffer, long pIndex, final E e) {
    int newBufferLength = getNextBufferSize(oldBuffer);
    final E[] newBuffer = allocate(newBufferLength);

    producerBuffer = newBuffer;
    final int newMask = (newBufferLength - 2) << 1;
    producerMask = newMask;

    final long offsetInOld = modifiedCalcElementOffset(pIndex, oldMask);
    final long offsetInNew = modifiedCalcElementOffset(pIndex, newMask);

    soElement(newBuffer, offsetInNew, e);// element in new array
    soElement(oldBuffer, nextArrayOffset(oldMask), newBuffer);// buffer linked

    // ASSERT code
    final long cIndex = lvConsumerIndex(this);
    final long availableInQueue = availableInQueue(pIndex, cIndex);
    if (availableInQueue <= 0) {
      throw new IllegalStateException();
    }

    // Invalidate racing CASs
    // We never set the limit beyond the bounds of a buffer
    soProducerLimit(this, pIndex + Math.min(newMask, availableInQueue));

    // make resize visible to the other producers
    soProducerIndex(this, pIndex + 2);

    // INDEX visible before ELEMENT, consistent with consumer expectation

    // make resize visible to consumer
    soElement(oldBuffer, offsetInOld, JUMP);
  }

  @SuppressWarnings("unchecked")
  public static <E> E[] allocate(int capacity) {
    return (E[]) new Object[capacity];
  }

  /**
   * @return next buffer size(inclusive of next array pointer)
   */
  protected abstract int getNextBufferSize(E[] buffer);

  /**
   * @return current buffer capacity for elements (excluding next pointer and jump entry) * 2
   */
  protected abstract long getCurrentBufferCapacity(long mask);

  static long lvProducerIndex(BaseMpscLinkedArrayQueue<?> self) {
    return (long) P_INDEX.getVolatile(self);
  }
  static long lvConsumerIndex(BaseMpscLinkedArrayQueue<?> self) {
    return (long) C_INDEX.getVolatile(self);
  }
  static void soProducerIndex(BaseMpscLinkedArrayQueue<?> self, long v) {
    P_INDEX.setRelease(self, v);
  }
  static boolean casProducerIndex(BaseMpscLinkedArrayQueue<?> self, long expect, long newValue) {
    return P_INDEX.compareAndSet(self, expect, newValue);
  }
  static void soConsumerIndex(BaseMpscLinkedArrayQueue<?> self, long v) {
    C_INDEX.setRelease(self, v);
  }
  static boolean casProducerLimit(BaseMpscLinkedArrayQueue<?> self, long expect, long newValue) {
    return P_LIMIT.compareAndSet(self, expect, newValue);
  }
  static void soProducerLimit(BaseMpscLinkedArrayQueue<?> self, long v) {
    P_LIMIT.setRelease(self, v);
  }

  /**
   * A concurrent access enabling class used by circular array based queues this class exposes an
   * offset computation method along with differently memory fenced load/store methods into the
   * underlying array. The class is pre-padded and the array is padded on either side to help with
   * False sharing prevention. It is expected that subclasses handle post padding.
   * <p>
   * Offset calculation is separate from access to enable the reuse of a give compute offset.
   * <p>
   * Load/Store methods using a <i>buffer</i> parameter are provided to allow the prevention of
   * final field reload after a LoadLoad barrier.
   * <p>
   */

  /**
   * A plain store (no ordering/fences) of an element to a given offset
   *
   * @param buffer this.buffer
   * @param offset computed via {@link org.jctools.util.UnsafeRefArrayAccess#calcElementOffset(long)}
   * @param e an orderly kitty
   */
  static <E> void spElement(E[] buffer, long offset, E e) {
    REF_ARRAY.set(buffer, (int) offset, e);
  }

  /**
   * An ordered store(store + StoreStore barrier) of an element to a given offset
   *
   * @param buffer this.buffer
   * @param offset computed via {@link org.jctools.util.UnsafeRefArrayAccess#calcElementOffset}
   * @param e an orderly kitty
   */
  static <E> void soElement(E[] buffer, long offset, E e) {
    REF_ARRAY.setRelease(buffer, (int) offset, e);
  }

  /**
   * A plain load (no ordering/fences) of an element from a given offset.
   *
   * @param buffer this.buffer
   * @param offset computed via {@link org.jctools.util.UnsafeRefArrayAccess#calcElementOffset(long)}
   * @return the element at the offset
   */
  static <E> E lpElement(E[] buffer, long offset) {
    return (E) REF_ARRAY.get(buffer, (int) offset);
  }

  /**
   * A volatile load (load + LoadLoad barrier) of an element from a given offset.
   *
   * @param buffer this.buffer
   * @param offset computed via {@link org.jctools.util.UnsafeRefArrayAccess#calcElementOffset(long)}
   * @return the element at the offset
   */
  static <E> E lvElement(E[] buffer, long offset) {
    return (E) REF_ARRAY.getVolatile(buffer, (int) offset);
  }

  /**
   * @param index desirable element index
   * @return the offset in bytes within the array for a given index.
   */
  static long calcElementOffset(long index) {
    return (index >> 1);
  }

  /**
   * This method assumes index is actually (index << 1) because lower bit is used for resize. This
   * is compensated for by reducing the element shift. The computation is constant folded, so
   * there's no cost.
   */
  static long modifiedCalcElementOffset(long index, long mask) {
    return (index & mask) >> 1;
  }

  static {
    try {
      Lookup pIndexLookup = MethodHandles.privateLookupIn(
          BaseMpscLinkedArrayQueueProducerFields.class, MethodHandles.lookup());
      Lookup cIndexLookup = MethodHandles.privateLookupIn(
          BaseMpscLinkedArrayQueueConsumerFields.class, MethodHandles.lookup());
      Lookup pLimitLookup = MethodHandles.privateLookupIn(
          BaseMpscLinkedArrayQueueColdProducerFields.class, MethodHandles.lookup());

      P_INDEX = pIndexLookup.findVarHandle(
          BaseMpscLinkedArrayQueueProducerFields.class, "producerIndex", long.class);
      C_INDEX = cIndexLookup.findVarHandle(
          BaseMpscLinkedArrayQueueConsumerFields.class, "consumerIndex", long.class);
      P_LIMIT = pLimitLookup.findVarHandle(
          BaseMpscLinkedArrayQueueColdProducerFields.class, "producerLimit", long.class);
      REF_ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
