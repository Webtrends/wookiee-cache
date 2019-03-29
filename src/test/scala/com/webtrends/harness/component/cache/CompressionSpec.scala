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
package com.webtrends.harness.component.cache

import java.util.zip.Deflater

import com.webtrends.harness.component.cache.BaseSpecCache.ns
import com.webtrends.harness.component.cache.memory.MemoryManager

import scala.util.Random

case class CompressedData(d:String = "") extends Cacheable[CompressedData] with Compression[CompressedData] {
  override def level = Deflater.BEST_COMPRESSION
  override def namespace = ns
}

case class StandardData(d:String = "") extends Cacheable[CompressedData] {
  override def namespace = ns
}

class CompressionSpec extends BaseSpecCache {
  "A cacheable object with Compression" should {

    "be cacheable" in {
      val obj = CompressedData("Some data")
      val key = new CacheKey(1, "basic", false)
      obj.writeInCache(cacheRef, Some(key))

      val found = CompressedData().readFromCache(cacheRef, Some(key))
      found must beEqualTo(Some(CompressedData("Some data"))).await()
    }

    "be compressed when cached" in {
      val cacheActor = cacheRef.underlyingActor.asInstanceOf[MemoryManager]

      val bigData = new Random(System.currentTimeMillis()).nextString(2048)
      val uKey = new CacheKey(1,"uncompressed", false)
      val uObj = StandardData(bigData)
      uObj.writeInCache(cacheRef, Some(uKey))
      val uSize = cacheActor.caches(ns)(uKey.toString()).buffer.length

      val cKey = new CacheKey(1,"compressed", false)
      val cObj = CompressedData(bigData)
      cObj.writeInCache(cacheRef, Some(cKey))
      val cSize = cacheActor.caches(ns)(cKey.toString()).buffer.length

      cSize must beLessThan(uSize)
      cObj.compressionRatio.get must beGreaterThan(1.0d)
      cObj.compressedSize.get must beLessThan(uSize.toLong)
    }
  }
}
