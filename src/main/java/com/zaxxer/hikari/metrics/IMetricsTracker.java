/*
 * Copyright (C) 2017 Brett Wooldridge
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

/**
 * @author Brett Wooldridge
 */
public interface IMetricsTracker extends AutoCloseable
{
   //创建实际的物理连接时 用于记录一个实际的物理连接创建所耗费的时间
   default void recordConnectionCreatedMillis(long connectionCreatedMillis) {}
   //getConnection时 用于记录获取一个连接时实际的耗时
   default void recordConnectionAcquiredNanos(final long elapsedAcquiredNanos) {}
   //回收连接时 用于记录一个连接从被获取到被回收时所消耗的时间
   default void recordConnectionUsageMillis(final long elapsedBorrowedMillis) {}
   //在getConnection时，用于记录获取连接超时的次数，每发生一次获取连接超时，就会触发一次该方法的调用
   default void recordConnectionTimeout() {}

   @Override
   default void close() {}
}
