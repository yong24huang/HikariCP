/*
 * Copyright (C) 2013,2014 Brett Wooldridge
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

//用于创建IMetricsTracker实例，并且按需记录PoolStats对象里的属性（这个对象里的属性就是类似连接池当前闲置连接数之类的线程池状态类指标）
public interface MetricsTrackerFactory
{
   /**
    * Create an instance of an IMetricsTracker.
    *
    * @param poolName the name of the pool
    * @param poolStats a PoolStats instance to use
    * @return a IMetricsTracker implementation instance
    */
   //返回一个IMetricsTracker对象，并且把PoolStats传了过去
   IMetricsTracker create(String poolName, PoolStats poolStats);
}
