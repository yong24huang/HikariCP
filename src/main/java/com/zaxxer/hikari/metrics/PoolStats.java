/*
 * Copyright (C) 2015 Brett Wooldridge
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

package com.zaxxer.hikari.metrics;

import static com.zaxxer.hikari.util.ClockSource.currentTime;
import static com.zaxxer.hikari.util.ClockSource.plusMillis;

import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * @author Brett Wooldridge
 */
public abstract class PoolStats
{
   private final AtomicLong reloadAt; //触发下次刷新的时间（时间戳）
   private final long timeoutMs; //刷新下面的各项属性值的频率，默认1s，无法改变

   // 总连接数
   protected volatile int totalConnections;
   // 闲置连接数
   protected volatile int idleConnections;
   // 活动连接数
   protected volatile int activeConnections;
   // 由于无法获取到可用连接而阻塞的业务线程数
   protected volatile int pendingThreads;
   // 最大连接数
   protected volatile int maxConnections;
   // 最小连接数
   protected volatile int minConnections;

   public PoolStats(final long timeoutMs)
   {
      this.timeoutMs = timeoutMs;
      this.reloadAt = new AtomicLong();
   }

   public int getTotalConnections()
   {
      if (shouldLoad()) {
         update();
      }

      return totalConnections;
   }

   public int getIdleConnections()
   {
      if (shouldLoad()) {
         update();
      }

      return idleConnections;
   }

   public int getActiveConnections()
   {
      if (shouldLoad()) {
         update();
      }

      return activeConnections;
   }

   public int getPendingThreads()
   {
      if (shouldLoad()) {
         update();
      }

      return pendingThreads;
   }

   public int getMaxConnections() {
      if (shouldLoad()) {
         update();
      }

      return maxConnections;
   }

   public int getMinConnections() {
      if (shouldLoad()) {//是否应该刷新
         update(); //刷新属性值，注意这个update的实现在HikariPool里，因为这些属性值的直接或间接来源都是HikariPool
      }

      return minConnections;
   }

   protected abstract void update();

   /**
    * //按照更新频率来决定是否刷新属性值
    * @return
    */
   private boolean shouldLoad()
   {
      for (; ; ) {
          final long now = currentTime();
          final long reloadTime = reloadAt.get();
          if (reloadTime > now) {
              return false;
          }
          else if (reloadAt.compareAndSet(reloadTime, plusMillis(now, timeoutMs))) {
              return true;
          }
      }
  }
}
