/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zaxxer.hikari.util;

import static java.lang.Thread.yield;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.locks.LockSupport.parkNanos;

import static com.zaxxer.hikari.util.ClockSource.currentTime;
import static com.zaxxer.hikari.util.ClockSource.elapsedNanos;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_REMOVED;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_RESERVED;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;

/**
 * This is a specialized concurrent bag that achieves superior performance
 * to LinkedBlockingQueue and LinkedTransferQueue for the purposes of a
 * connection pool.  It uses ThreadLocal storage when possible to avoid
 * locks, but resorts to scanning a common collection if there are no
 * available items in the ThreadLocal list.  Not-in-use items in the
 * ThreadLocal lists can be "stolen" when the borrowing thread has none
 * of its own.  It is a "lock-less" implementation using a specialized
 * AbstractQueuedLongSynchronizer to manage cross-thread signaling.
 *
 * Note that items that are "borrowed" from the bag are not actually
 * removed from any collection, so garbage collection will not occur
 * even if the reference is abandoned.  Thus care must be taken to
 * "requite" borrowed objects otherwise a memory leak will result.  Only
 * the "remove" method can completely remove an object from the bag.
 *
 * @author Brett Wooldridge
 *
 * @param <T> the templated type to store in the bag
 */
public class ConcurrentBag<T extends IConcurrentBagEntry> implements AutoCloseable
{
   private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);

   private final CopyOnWriteArrayList<T> sharedList; //最终存放PoolEntry对象的地方，它是一个CopyOnWriteArrayList
   private final boolean weakThreadLocals; //默认false，为true时可以让一个连接对象在下方threadList里的list内处于弱引用状态，防止内存泄漏

   private final ThreadLocal<List<Object>> threadList;//线程级的缓存，从sharedList拿到的连接对象，会被缓存进当前线程内，borrow时会先从缓存中拿，从而达到池内无锁实现
   private final IBagStateListener listener; //内部接口，HikariPool实现了该接口
   private final AtomicInteger waiters; //当前因为获取不到连接而发生阻塞的业务线程数,addBagItem根据该指标进行判断是否需要新增连接
   private volatile boolean closed; //标记当前ConcurrentBag是否已被关闭

   private final SynchronousQueue<T> handoffQueue; //这是个即产即销的队列，用于在连接不够用时，及时获取到add方法里新创建的连接对象，详情可以参考下面borrow和add的代码

   //内部接口，PoolEntry类实现了该接口
   public interface IConcurrentBagEntry
   {
      //连接对象的状态
      int STATE_NOT_IN_USE = 0; //闲置
      int STATE_IN_USE = 1; //使用中
      int STATE_REMOVED = -1; //已废弃
      int STATE_RESERVED = -2; //标记保留，介于闲置和废弃之间的中间状态，主要由缩容那里触发修改

      boolean compareAndSet(int expectState, int newState); //尝试利用cas修改连接对象的状态值
      void setState(int newState);  //设置状态值
      int getState();  //获取状态值
   }
   //主要用于ConcurrentBag主动通知HikariPool触发添加连接对象的异步操作
   public interface IBagStateListener
   {
      void addBagItem(int waiting);
   }

   /**
    * Construct a ConcurrentBag with the specified listener.
    *
    * @param listener the IBagStateListener to attach to this bag
    */
   public ConcurrentBag(final IBagStateListener listener)
   {
      this.listener = listener;
      this.weakThreadLocals = useWeakThreadLocals();

      this.handoffQueue = new SynchronousQueue<>(true);
      this.waiters = new AtomicInteger();
      this.sharedList = new CopyOnWriteArrayList<>();
      if (weakThreadLocals) {
         this.threadList = ThreadLocal.withInitial(() -> new ArrayList<>(16));
      }
      else {
         this.threadList = ThreadLocal.withInitial(() -> new FastList<>(IConcurrentBagEntry.class, 16));
      }
   }

   /**
    * The method will borrow a BagEntry from the bag, blocking for the
    * specified timeout if none are available.
    *
    * @param timeout how long to wait before giving up, in units of unit
    * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
    * @return a borrowed instance from the bag or null if a timeout occurs
    * @throws InterruptedException if interrupted while waiting
    */

   //获取连接方法
   public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException
   {
      // Try the thread-local list first
      final List<Object> list = threadList.get(); //首先从当前线程的缓存里拿到之前被缓存进来的连接对象集合
      for (int i = list.size() - 1; i >= 0; i--) {
         final Object entry = list.remove(i); //先移除，回收方法那里会再次add进来
         @SuppressWarnings("unchecked")
         final T bagEntry = weakThreadLocals ? ((WeakReference<T>) entry).get() : (T) entry; //默认不启用弱引用
         //获取到对象后，通过cas尝试把其状态从STATE_NOT_IN_USE 变为 STATE_IN_USE，注意，这里如果其他线程也在使用这个连接对象，
         //并且成功修改属性，那么当前线程的cas会失败，那么就会继续循环尝试获取下一个连接对象
         if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;
         }
      }

      // Otherwise, scan the shared list ... then poll the handoff queue
      final int waiting = waiters.incrementAndGet();  //如果缓存内找不到一个可用的连接对象，则认为需要“回源”，waiters+1
      try {
         for (T bagEntry : sharedList) {
            //循环sharedList，尝试把连接状态值从STATE_NOT_IN_USE 变为 STATE_IN_USE
            if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
               // If we may have stolen another waiter's connection, request another bag add.
               if (waiting > 1) {
                  listener.addBagItem(waiting - 1); //阻塞线程数大于1时，需要触发HikariPool的addBagItem方法来进行添加连接入池
               }
               return bagEntry;
            }
         }
         //走到这里说明不光线程缓存里的列表竞争不到连接对象，连sharedList里也找不到可用的连接，这时则认为需要通知HikariPool，该触发添加连接操作了
         listener.addBagItem(waiting);

         timeout = timeUnit.toNanos(timeout); //这时候开始利用timeout控制获取时间
         do {
            final long start = currentTime();
            //尝试从handoffQueue队列里获取最新被加进来的连接对象（一般新入的连接对象除了加进sharedList之外，还会被放进进该队列）
            final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
            //如果超出指定时间后仍然没有获取到可用的连接对象，或者获取到对象后通过cas设置成功，这两种情况都不需要重试，直接返回对象
            if (bagEntry == null || bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
               return bagEntry;
            }

            timeout -= elapsedNanos(start); //timeout减去消耗的时间，表示下次循环可用时间
         } while (timeout > 10_000);

         return null; //超时，仍然返回null
      }
      finally {
         waiters.decrementAndGet(); //这一步出去后，HikariPool收到borrow的结果，算是走出阻塞，所以waiters-1
      }
   }

   /**
    * This method will return a borrowed object to the bag.  Objects
    * that are borrowed from the bag but never "requited" will result
    * in a memory leak.
    *
    * @param bagEntry the value to return to the bag
    * @throws NullPointerException if value is null
    * @throws IllegalStateException if the bagEntry was not borrowed from the bag
    */
   //回收连接
   public void requite(final T bagEntry)
   {
      bagEntry.setState(STATE_NOT_IN_USE);//回收意味着使用完毕，更改state为STATE_NOT_IN_USE状态

      for (int i = 0; waiters.get() > 0; i++) { //如果存在等待线程的话，尝试传给队列，让borrow获取
         if (bagEntry.getState() != STATE_NOT_IN_USE || handoffQueue.offer(bagEntry)) {
            return;
         }
         else if ((i & 0xff) == 0xff) {
            parkNanos(MICROSECONDS.toNanos(10));
         }
         else {
            yield();
         }
      }

      final List<Object> threadLocalList = threadList.get();
      if (threadLocalList.size() < 50) { //线程内连接集合的缓存最多50个，这里回收连接时会再次加进当前线程的缓存里，方便下次borrow获取
         threadLocalList.add(weakThreadLocals ? new WeakReference<>(bagEntry) : bagEntry); //默认不启用弱引用，若启用的话，则缓存集合里的连接对象没有内存泄露的风险
      }
   }

   /**
    * Add a new object to the bag for others to borrow.
    *
    * @param bagEntry an object to add to the bag
    */
   public void add(final T bagEntry)
   {
      if (closed) {
         LOGGER.info("ConcurrentBag has been closed, ignoring add()");
         throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
      }

      sharedList.add(bagEntry);  //直接加到sharedList里去

      // spin until a thread takes it or none are waiting
      //当存在线程等待获取可用连接，并且当前新入的这个连接状态仍然是闲置状态，且队列里无消费者等待获取时，发起一次线程调度
      while (waiters.get() > 0 && bagEntry.getState() == STATE_NOT_IN_USE && !handoffQueue.offer(bagEntry)) {
         yield();
      }
   }

   /**
    * Remove a value from the bag.  This method should only be called
    * with objects obtained by <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
    *
    * @param bagEntry the value to remove
    * @return true if the entry was removed, false otherwise
    * @throws IllegalStateException if an attempt is made to remove an object
    *         from the bag that was not borrowed or reserved first
    */
   public boolean remove(final T bagEntry)
   {   // 下面两个cas操作，都是从其他状态变为移除状态，任意一个成功，都不会走到下面的warn
      if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.compareAndSet(STATE_RESERVED, STATE_REMOVED) && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", bagEntry);
         return false;
      }
      // 直接从sharedList移除掉
      final boolean removed = sharedList.remove(bagEntry);
      if (!removed && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry);
      }

      return removed;
   }

   /**
    * Close the bag to further adds.
    */
   @Override
   public void close()
   {
      closed = true;
   }

   /**
    * This method provides a "snapshot" in time of the BagEntry
    * items in the bag in the specified state.  It does not "lock"
    * or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in list before performing any action on them.
    *
    * @param state one of the {@link IConcurrentBagEntry} states
    * @return a possibly empty list of objects having the state specified
    */
   public List<T> values(final int state)
   {
      final List<T> list = sharedList.stream().filter(e -> e.getState() == state).collect(Collectors.toList());
      Collections.reverse(list);
      return list;
   }

   /**
    * This method provides a "snapshot" in time of the bag items.  It
    * does not "lock" or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in the list, or understand the concurrency implications of
    * modifying items, before performing any action on them.
    *
    * @return a possibly empty list of (all) bag items
    */
   @SuppressWarnings("unchecked")
   public List<T> values()
   {
      return (List<T>) sharedList.clone();
   }

   /**
    * The method is used to make an item in the bag "unavailable" for
    * borrowing.  It is primarily used when wanting to operate on items
    * returned by the <code>values(int)</code> method.  Items that are
    * reserved can be removed from the bag via <code>remove(T)</code>
    * without the need to unreserve them.  Items that are not removed
    * from the bag can be make available for borrowing again by calling
    * the <code>unreserve(T)</code> method.
    *
    * @param bagEntry the item to reserve
    * @return true if the item was able to be reserved, false otherwise
    */
   public boolean reserve(final T bagEntry)
   {
      return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
   }

   /**
    * This method is used to make an item reserved via <code>reserve(T)</code>
    * available again for borrowing.
    *
    * @param bagEntry the item to unreserve
    */
   public void unreserve(final T bagEntry)
   {
      if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
         // spin until a thread takes it or none are waiting
         while (waiters.get() > 0 && !handoffQueue.offer(bagEntry)) {
            yield();
         }
      }
      else {
         LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry);
      }
   }

   /**
    * Get the number of threads pending (waiting) for an item from the
    * bag to become available.
    *
    * @return the number of threads waiting for items from the bag
    */
   public int getWaitingThreadCount()
   {
      return waiters.get();
   }

   /**
    * Get a count of the number of items in the specified state at the time of this call.
    *
    * @param state the state of the items to count
    * @return a count of how many items in the bag are in the specified state
    */
   public int getCount(final int state)
   {
      int count = 0;
      for (IConcurrentBagEntry e : sharedList) {
         if (e.getState() == state) {
            count++;
         }
      }
      return count;
   }

   public int[] getStateCounts()
   {
      final int[] states = new int[6];
      for (IConcurrentBagEntry e : sharedList) {
         ++states[e.getState()];
      }
      states[4] = sharedList.size();
      states[5] = waiters.get();

      return states;
   }

   /**
    * Get the total number of items in the bag.
    *
    * @return the number of items in the bag
    */
   public int size()
   {
      return sharedList.size();
   }

   public void dumpState()
   {
      sharedList.forEach(entry -> LOGGER.info(entry.toString()));
   }

   /**
    * Determine whether to use WeakReferences based on whether there is a
    * custom ClassLoader implementation sitting between this class and the
    * System ClassLoader.
    *
    * @return true if we should use WeakReferences in our ThreadLocals, false otherwise
    */
   private boolean useWeakThreadLocals()
   {
      try {
         if (System.getProperty("com.zaxxer.hikari.useWeakReferences") != null) {   // undocumented manual override of WeakReference behavior
            return Boolean.getBoolean("com.zaxxer.hikari.useWeakReferences");
         }

         return getClass().getClassLoader() != ClassLoader.getSystemClassLoader();
      }
      catch (SecurityException se) {
         return true;
      }
   }
}
