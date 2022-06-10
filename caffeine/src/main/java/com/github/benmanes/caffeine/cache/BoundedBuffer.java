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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;

/**
 * 带状非阻塞的有界缓冲区实现。
 * 继承了{@link com.github.benmanes.caffeine.cache.StripedBuffer}，内部有一个 {@link RingBuffer} 类实现了环形缓冲区
 *
 * A striped, non-blocking, bounded buffer.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <E> the type of elements maintained by this buffer
 */
final class BoundedBuffer<E> extends StripedBuffer<E> {
  /*
   * A circular ring buffer stores the elements being transferred by the producers to the consumer.
   * The monotonically increasing count of reads and writes allow indexing sequentially to the next
   * element location based upon a power-of-two sizing.
   *
   * The producers race to read the counts, check if there is available capacity, and if so then try
   * once to CAS to the next write count. If the increment is successful then the producer lazily
   * publishes the element. The producer does not retry or block when unsuccessful due to a failed
   * CAS or the buffer being full.
   *
   * The consumer reads the counts and takes the available elements. The clearing of the elements
   * and the next read count are lazily set.
   *
   * This implementation is striped to further increase concurrency by rehashing and dynamically
   * adding new buffers when contention is detected, up to an internal maximum. When rehashing in
   * order to discover an available buffer, the producer may retry adding its element to determine
   * whether it found a satisfactory buffer or if resizing is necessary.
   */

  /** The maximum number of elements per buffer. */
  // 每个环形缓冲区的大小，16
  static final int BUFFER_SIZE = 16;
  // 掩码，方便hash进行取余操作，(hash & MASK) 即可算出该 hash 在环形缓冲区中的位置
  static final int MASK = BUFFER_SIZE - 1;

  @Override
  protected Buffer<E> create(E e) {
    return new RingBuffer<>(e);
  }

  /**
   * 内部类，环形缓冲区
   */
  static final class RingBuffer<E> extends BBHeader.ReadAndWriteCounterRef implements Buffer<E> {
    static final VarHandle BUFFER = MethodHandles.arrayElementVarHandle(Object[].class);

    /**
     * 环形缓冲区底层就是对象数组
     */
    final Object[] buffer;

    public RingBuffer(E e) {
      buffer = new Object[BUFFER_SIZE];
      BUFFER.set(buffer, 0, e);
    }

    /**
     * 将元素加入到缓冲区
     */
    @Override
    public int offer(E e) {
      // 读计数
      long head = readCounter;
      // 写计数
      long tail = writeCounterOpaque();
      // 读写计数之间的差值就是缓冲区中的元素数量，可以用来判断缓冲区是否已满
      long size = (tail - head);
      if (size >= BUFFER_SIZE) {
        return Buffer.FULL;
      }
      // CAS 操作更新 writeCounter 的值来保证并发安全，CAS 操作成功才允许操作
      if (casWriteCounter(tail, tail + 1)) {
        int index = (int) (tail & MASK);
        BUFFER.setRelease(buffer, index, e);
        return Buffer.SUCCESS;
      }
      // CAS 操作失败说明有其它线程在竞争，那么需要对带状缓冲区进行扩容避免线程竞争
      return Buffer.FAILED;
    }

    /**
     * 清理环形缓冲区，有一个 consumer 参数，每清除一个元素都会调用一次 consumer。
     * 由调用方加锁保证清理操作的并发安全
     */
    @Override
    public void drainTo(Consumer<E> consumer) {
      // 判断缓冲区中是否有数据
      long head = readCounter;
      long tail = writeCounterOpaque();
      long size = (tail - head);
      if (size == 0) {
        return;
      }

      // 清理缓冲区
      do {
        // 根据读计数器定位出 index
        int index = (int) (head & MASK);
        E e = (E) BUFFER.getAcquire(buffer, index);
        // buffer[index] 为 null 表示后续缓冲区还未使用，结束清理
        if (e == null) {
          break;
        }
        // 将 buffer[index] 设为 null
        BUFFER.setRelease(buffer, index, null);
        // 每清理一个元素调用一次 consumer
        consumer.accept(e);
        // 读计数器+1
        head++;
      } while (head != tail);
      setReadCounterOpaque(head);
    }

    @Override
    public long reads() {
      return readCounter;
    }

    @Override
    public long writes() {
      return writeCounter;
    }
  }
}

/** The namespace for field padding through inheritance. */
final class BBHeader {

  @SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
  abstract static class PadReadCounter {
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

  /** Enforces a memory layout to avoid false sharing by padding the read count. */
  abstract static class ReadCounterRef extends PadReadCounter {
    volatile long readCounter;
  }

  abstract static class PadWriteCounter extends ReadCounterRef {
    byte p120, p121, p122, p123, p124, p125, p126, p127;
    byte p128, p129, p130, p131, p132, p133, p134, p135;
    byte p136, p137, p138, p139, p140, p141, p142, p143;
    byte p144, p145, p146, p147, p148, p149, p150, p151;
    byte p152, p153, p154, p155, p156, p157, p158, p159;
    byte p160, p161, p162, p163, p164, p165, p166, p167;
    byte p168, p169, p170, p171, p172, p173, p174, p175;
    byte p176, p177, p178, p179, p180, p181, p182, p183;
    byte p184, p185, p186, p187, p188, p189, p190, p191;
    byte p192, p193, p194, p195, p196, p197, p198, p199;
    byte p200, p201, p202, p203, p204, p205, p206, p207;
    byte p208, p209, p210, p211, p212, p213, p214, p215;
    byte p216, p217, p218, p219, p220, p221, p222, p223;
    byte p224, p225, p226, p227, p228, p229, p230, p231;
    byte p232, p233, p234, p235, p236, p237, p238, p239;
  }

  /**
   * 缓冲区的读写计数器，通过读写计数器可以判断当前缓冲区的空闲状态。
   * 在向缓冲区加入元素时可以通过 CAS 原子更新 counter 的形式来保证并发安全，只有原子更新成功才允许加入缓冲区
   */
  abstract static class ReadAndWriteCounterRef extends PadWriteCounter {

    /**
     * VarHandle 是 JDK9 提供的一个快捷进行原子操作的类
     */
    static final VarHandle READ, WRITE;

    volatile long writeCounter;

    ReadAndWriteCounterRef() {
      WRITE.setOpaque(this, 1);
    }

    void setReadCounterOpaque(long count) {
      READ.setOpaque(this, count);
    }

    long writeCounterOpaque() {
      return (long) WRITE.getOpaque(this);
    }

    boolean casWriteCounter(long expect, long update) {
      return WRITE.weakCompareAndSet(this, expect, update);
    }

    static {
      var lookup = MethodHandles.lookup();
      try {
        READ = lookup.findVarHandle(ReadCounterRef.class, "readCounter", long.class);
        WRITE = lookup.findVarHandle(ReadAndWriteCounterRef.class, "writeCounter", long.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
