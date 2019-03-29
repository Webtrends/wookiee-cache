/*
 * Copyright 2015 Webtrends (http://www.webtrends.com)
 *
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
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
package com.webtrends.harness.component.cache.memory

import com.webtrends.harness.component.cache.{CacheConfig, Cache}
import com.webtrends.harness.health.{ComponentState, HealthComponent}
import org.jboss.netty.buffer.ChannelBuffer
import scala.concurrent._
import scala.collection.mutable

import scala.concurrent.duration.Duration

/**
 * In memory cache is exactly that, it stores values inside a standard HashMap. There are obviously
 * issues with this approach, you wouldn't use it if you wanted the cache to be distributed or if the
 * data became too large.
 *
 * TTL for cached items will default to 5 minutes if not specified
 */
case class TimedChannelBuffer(expirationTime:Long, buffer:Array[Byte])

object MemoryManager {
  val caches = mutable.Map[String, mutable.Map[String, TimedChannelBuffer]]()
}

class MemoryManager(name:String) extends Cache(name) {

  def caches = MemoryManager.caches

  import context.dispatcher

  protected val DEFAULT_TTL_SEC = if (config.hasPath(s"$name.ttl")) {
    config.getLong(s"$name.ttl")
  } else {
    300     // 5 minutes
  }

  private val expireInterval = Duration(DEFAULT_TTL_SEC, "seconds")

  private[this] val scheduler = context.system.scheduler
  private[this] var taskRunning: Boolean = false

  private[this] def scheduleTimer() : Unit = synchronized {
    require(!taskRunning)
    taskRunning = true
    scheduler.scheduleOnce(expireInterval)(() => timeout())
  }

  private[this] def timeout() = {
    synchronized {
      removeExpiredItems()
      taskRunning = false
      if (caches.nonEmpty) scheduleTimer()
    }
  }

  private[this] def removeExpiredItems() = synchronized {
    val currentTime = compat.Platform.currentTime
    val cache = caches
    val expired:mutable.Map[String, List[String]] = cache map {
      nc =>
        val expireList = mutable.MutableList[String]()
        nc._2 foreach { cacheItem =>
          if (currentTime > cacheItem._2.expirationTime) {
            expireList += cacheItem._1
          }
        }
        nc._1 -> expireList.toList
    }

    // we delete here so that we don't get any concurrent modification exceptions
    expired foreach {
      nc => nc._2 foreach { delete(nc._1, _) }
    }
  }

  /**
   * Any logic to stop the component
   */
  override def stop = {
    super.stop
    caches.clear()
  }

  override protected def get(namespace: String, key: String): Future[Option[Array[Byte]]] = {
    caches.get(namespace) match {
      case Some(c) => Future.successful(c.get(key).map(_.buffer))
      case None => Future.successful(None)
    }
  }

  override protected def clear(namespace: String): Future[Boolean] = {
    caches.get(namespace) match {
      case Some(c) =>
        c.clear()
        Future.successful(true)
      case None => Future.successful(false)
    }
  }

  // Not supported in in-memory cache
  override protected def increment(namespace: String, key: String, incrementBy: Long): Future[Option[Long]] = ???

  // Not supported in in-memory cache
  override protected def decrement(namespace: String, key: String, decrementBy: Long): Future[Option[Long]] = ???

  override protected def delete(namespace: String, key: String): Future[Boolean] = {
    caches.get(namespace) match {
      case Some(c) =>
        c.remove(key)
        Future { true }
      case None =>
        Future { false }
    }
  }

  override protected def contains(namespace: String, key: String): Future[Boolean] = {
    caches.get(namespace) match {
      case Some(c) => Future { c.contains(key) }
      case None => Future { false }
    }
  }

  override protected def getHealth: Future[HealthComponent] =
    Future { HealthComponent(self.path.name, ComponentState.NORMAL, "All Good, managing %d caches".format(caches.size)) }

  /**
   * Creates a cache based on the implementer of the function. So could be any cache type
   *
   * @param config The
   * @return true if created, false if already created
   */
  override protected def createCache(config: CacheConfig): Boolean = {
    caches.get(config.namespace) match {
      case Some(c) =>
        false
      case None =>
        val cache = mutable.Map[String, TimedChannelBuffer]()
        caches += (config.namespace -> cache)
        true
    }
  }

  /**
   * Deletes the cache from the in memory map
   *
   * @param namespace namespace for the cache you are deleting
   * @return
   */
  override protected def deleteCache(namespace:String): Boolean = {
    if (caches.contains(namespace)) {
      caches.remove(namespace)
      true
    } else {
      false
    }
  }

  override protected def add(namespace: String, key: String, value: Array[Byte], ttlSec: Option[Int]): Future[Boolean] = {
    caches.get(namespace) match {
      case Some(c) =>
        val expirationTime = compat.Platform.currentTime + ttlSec.map(_ * 1000L).getOrElse(DEFAULT_TTL_SEC * 1000L)
        c.put(key, TimedChannelBuffer(expirationTime, value))
        if (c.nonEmpty && !taskRunning) scheduleTimer()
        Future { true }
      case None => Future { false }
    }
  }
}
