/*
 *  Copyright 2017-2018 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.twosigma.flint.timeseries

import java.lang.{ Long => JLong }
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.TimeUnit

import com.twosigma.flint.FlintConf
import com.twosigma.flint.rdd.{ KeyPartitioningType, OrderedRDD }
import com.twosigma.flint.timeseries.TimeSeriesRDD.timeColumnName
import com.twosigma.flint.timeseries.row.Schema
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.{ GenericRow, GenericRowWithSchema => ExternalRow }
import org.apache.spark.sql.functions.{ col, udf }
import org.apache.spark.sql.types._
import org.scalatest.tagobjects.Slow

import scala.collection.mutable
import scala.concurrent.duration._

class TimeSeriesRDDSpec extends TimeSeriesSuite with TimeTypeSuite {
  import testImplicits._

  val priceSchema = Schema("time" -> LongType, "id" -> IntegerType, "price" -> DoubleType)
  val forecastSchema = Schema("time" -> LongType, "id" -> IntegerType, "forecast" -> DoubleType)
  val forecastSwitchColumnSchema = Schema("time" -> LongType, "forecast" -> DoubleType, "id" -> IntegerType)
  val volSchema = Schema("time" -> LongType, "id" -> IntegerType, "volume" -> LongType)
  val vol4Schema = Schema("time" -> LongType, "id" -> IntegerType, "volume" -> LongType, "volume.1" -> LongType)
  val clockSchema = Schema("time" -> LongType)

  val clockData = Array[(Long, Row)](
    (1000000000000L, new ExternalRow(Array(1000000000000L), clockSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L), clockSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L), clockSchema)),
    (1300000000000L, new ExternalRow(Array(1300000000000L), clockSchema))
  )

  val forecastData = Array[(Long, Row)](
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 3.0), forecastSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 5.0), forecastSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, -1.5), forecastSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 2.0), forecastSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, -2.4), forecastSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 6.4), forecastSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 1.5), forecastSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, -7.9), forecastSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 4.6), forecastSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1.4), forecastSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, -9.6), forecastSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 6.0), forecastSchema))
  )

  val forecastSwitchColumnData = forecastData.map {
    case (time, row) =>
      val data = row.toSeq.toArray
      val newData = Array(data(0), data(2), data(1))

      (time, new ExternalRow(newData, forecastSwitchColumnSchema): Row)
  }

  val priceData = Array[(Long, Row)](
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 0.5), priceSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 1.0), priceSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 1.5), priceSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 2.0), priceSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 2.5), priceSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 3.0), priceSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 3.5), priceSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 4.0), priceSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 4.5), priceSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 5.0), priceSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 5.5), priceSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 6.0), priceSchema))
  )

  val volData = Array[(Long, Row)](
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100L), volSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 300L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 400L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 500L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 600L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 700L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 800L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 900L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1000L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1100L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1200L), volSchema))
  )

  val vol2Data = Array[(Long, Row)](
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100L), volSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100L), volSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200L), volSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 300L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 300L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 400L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 400L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 500L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 600L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 500L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 600L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 700L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 800L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 700L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 800L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 900L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1000L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 900L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1000L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1100L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1200L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1100L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1200L), volSchema))
  )

  val vol3Data = Array[(Long, Row)](
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100L), volSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 101L), volSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200L), volSchema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 201L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 300L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 301L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 400L), volSchema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 401L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 500L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 600L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 501L), volSchema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 601L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 700L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 800L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 701L), volSchema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 801L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 900L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1000L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 901L), volSchema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1001L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1100L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1200L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1101L), volSchema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1201L), volSchema))
  )

  val vol4Data = Array[(Long, Row)](
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100L, 100L), vol4Schema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 101L, 102L), vol4Schema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200L, 200L), vol4Schema)),
    (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 201L, 202L), vol4Schema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 300L, 300L), vol4Schema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 301L, 302L), vol4Schema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 400L, 400L), vol4Schema)),
    (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 401L, 402L), vol4Schema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 500L, 500L), vol4Schema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 600L, 602L), vol4Schema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 501L, 501L), vol4Schema)),
    (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 601L, 601L), vol4Schema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 700L, 700L), vol4Schema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 800L, 802L), vol4Schema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 701L, 701L), vol4Schema)),
    (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 801L, 801L), vol4Schema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 900L, 902L), vol4Schema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1000L, 1000L), vol4Schema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 901L, 901L), vol4Schema)),
    (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1001L, 1002L), vol4Schema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1100L, 1100L), vol4Schema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1200L, 1202L), vol4Schema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1101L, 1101L), vol4Schema)),
    (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1201L, 1201L), vol4Schema))
  )

  val defaultNumPartitions = 5

  var priceTSRdd: TimeSeriesRDD = _
  var priceTSRdd2: TimeSeriesRDD = _
  var forecastTSRdd: TimeSeriesRDD = _
  var forecastSwitchColumnTSRdd: TimeSeriesRDD = _
  var volTSRdd: TimeSeriesRDD = _
  var clockTSRdd: TimeSeriesRDD = _
  var vol2TSRdd: TimeSeriesRDD = _
  var vol3TSRdd: TimeSeriesRDD = _
  var vol4TSRdd: TimeSeriesRDD = _

  override def beforeAll() {
    super.beforeAll()
    priceTSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(priceData, defaultNumPartitions), KeyPartitioningType.Sorted),
      priceSchema
    )
    forecastTSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(forecastData, defaultNumPartitions), KeyPartitioningType.Sorted),
      forecastSchema
    )
    forecastSwitchColumnTSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(forecastSwitchColumnData, defaultNumPartitions), KeyPartitioningType.Sorted),
      forecastSwitchColumnSchema
    )
    volTSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(volData, defaultNumPartitions), KeyPartitioningType.Sorted),
      volSchema
    )
    clockTSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(clockData, defaultNumPartitions), KeyPartitioningType.Sorted),
      clockSchema
    )
    vol2TSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(vol2Data, defaultNumPartitions), KeyPartitioningType.Sorted),
      volSchema
    )
    vol3TSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(vol3Data, defaultNumPartitions), KeyPartitioningType.Sorted),
      volSchema
    )
    vol4TSRdd = TimeSeriesRDD.fromOrderedRDD(
      OrderedRDD.fromRDD(sc.parallelize(vol4Data, defaultNumPartitions), KeyPartitioningType.Sorted),
      vol4Schema
    )
  }

  "TimeSeriesRDD" should "`select data between correctly`" in {
    val df = priceTSRdd.toDF
    val df2 = df.filter(df("time") >= 1000000000000L && df("time") < 1100000000000L)
    val df3 = TimeSeriesRDD.DFBetween(df, Some(1000000000000L), Some(1100000000000L), "time")

    assert(df2.collect().deep == df3.collect().deep)
  }

  "TimeSeriesRDD" should "`addColumns` correctly" in {
    val expectedSchema = Schema(
      "id" -> IntegerType, "price" -> DoubleType, "price2" -> DoubleType, "price3" -> DoubleType
    )
    val expectedData = Array[Row](
      new ExternalRow(Array(1000000000000L, 7, 0.5, 0.5, 1.0), expectedSchema),
      new ExternalRow(Array(1000000000000L, 3, 1.0, 1.0, 2.0), expectedSchema),
      new ExternalRow(Array(1050000000000L, 3, 1.5, 1.5, 3.0), expectedSchema),
      new ExternalRow(Array(1050000000000L, 7, 2.0, 2.0, 4.0), expectedSchema),
      new ExternalRow(Array(1100000000000L, 3, 2.5, 2.5, 5.0), expectedSchema),
      new ExternalRow(Array(1100000000000L, 7, 3.0, 3.0, 6.0), expectedSchema),
      new ExternalRow(Array(1150000000000L, 3, 3.5, 3.5, 7.0), expectedSchema),
      new ExternalRow(Array(1150000000000L, 7, 4.0, 4.0, 8.0), expectedSchema),
      new ExternalRow(Array(1200000000000L, 3, 4.5, 4.5, 9.0), expectedSchema),
      new ExternalRow(Array(1200000000000L, 7, 5.0, 5.0, 10.0), expectedSchema),
      new ExternalRow(Array(1250000000000L, 3, 5.5, 5.5, 11.0), expectedSchema),
      new ExternalRow(Array(1250000000000L, 7, 6.0, 6.0, 12.0), expectedSchema)
    )

    val expectedSchema2 = Schema(
      "id" -> IntegerType, "price" -> DoubleType, "price2" -> DoubleType
    )

    val expectedData2 = Array[Row](
      new ExternalRow(Array(1000000000000L, 14, 1.0, 0.5), expectedSchema2),
      new ExternalRow(Array(1000000000000L, 6, 2.0, 1.0), expectedSchema2),
      new ExternalRow(Array(1050000000000L, 6, 3.0, 1.5), expectedSchema2),
      new ExternalRow(Array(1050000000000L, 14, 4.0, 2.0), expectedSchema2),
      new ExternalRow(Array(1100000000000L, 6, 5.0, 2.5), expectedSchema2),
      new ExternalRow(Array(1100000000000L, 14, 6.0, 3.0), expectedSchema2),
      new ExternalRow(Array(1150000000000L, 6, 7.0, 3.5), expectedSchema2),
      new ExternalRow(Array(1150000000000L, 14, 8.0, 4.0), expectedSchema2),
      new ExternalRow(Array(1200000000000L, 6, 9.0, 4.5), expectedSchema2),
      new ExternalRow(Array(1200000000000L, 14, 10.0, 5.0), expectedSchema2),
      new ExternalRow(Array(1250000000000L, 6, 11.0, 5.5), expectedSchema2),
      new ExternalRow(Array(1250000000000L, 14, 12.0, 6.0), expectedSchema2)
    )

    val result = priceTSRdd.addColumns(
      "price2" -> DoubleType ->
        { r: Row => r.getAs[Double]("price") },

      "price3" -> DoubleType ->
        { r: Row => r.getAs[Double]("price") * 2 }
    ).collect()

    val result2 = priceTSRdd.addColumns(
      "price" -> DoubleType ->
        { r: Row => r.getAs[Double]("price") * 2 },

      "price2" -> DoubleType ->
        { r: Row => r.getAs[Double]("price") },

      "id" -> IntegerType ->
        { r: Row => r.getAs[Integer]("id") * 2 }
    ).collect()

    assert(result.deep == expectedData.deep)
    assert(result2.deep == expectedData2.deep)
  }

  it should "`toDF` correctly" in {
    val df = priceTSRdd.toDF
    assert(df.schema == priceTSRdd.schema)
    assert(df.collect().deep == priceTSRdd.collect().deep)
  }

  it should "`repartition` correctly" in {
    assert(vol2TSRdd.repartition(1).collect().deep == vol2TSRdd.collect().deep)
    assert(vol2TSRdd.repartition(defaultNumPartitions * 2).collect().deep == vol2TSRdd.collect().deep)
  }

  it should "`coalesce` correctly" in {
    assert(vol2TSRdd.coalesce(1).collect().deep == vol2TSRdd.collect().deep)
    assert(vol2TSRdd.coalesce(defaultNumPartitions / 2).collect().deep == vol2TSRdd.collect().deep)
  }

  it should "`groupByCycle` correctly" in {
    val innerRowSchema = Schema("id" -> IntegerType, "volume" -> LongType)
    val expectedSchema = Schema("rows" -> ArrayType(innerRowSchema))

    val rows = volData.map(_._2)

    val expectedData = Array[(Long, Row)](
      (1000000000000L, new ExternalRow(Array(1000000000000L, Array(rows(0), rows(1))), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, Array(rows(2), rows(3))), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, Array(rows(4), rows(5))), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, Array(rows(6), rows(7))), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, Array(rows(8), rows(9))), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, Array(rows(10), rows(11))), expectedSchema))
    )

    val result = volTSRdd.groupByCycle().collect()
    val expectedResult = expectedData.map(_._2)

    expectedResult.indices.foreach {
      index =>
        assert(result(index).getAs[mutable.WrappedArray[Row]]("rows").deep ==
          expectedResult(index).getAs[Array[Row]]("rows").deep)
    }
    assert(result.map(_.schema).deep == expectedResult.map(_.schema).deep)
  }

  it should "`addSummaryColumns` correctly" in {
    val expectedSchema = Schema("id" -> IntegerType, "volume" -> LongType, "volume_sum" -> DoubleType)
    val expectedData = Array[(Long, Row)](
      (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100, 100.0), expectedSchema)),
      (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200, 300.0), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 300, 600.0), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 400, 1000.0), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 500, 1500.0), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 600, 2100.0), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 700, 2800.0), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 800, 3600.0), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 900, 4500.0), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1000, 5500.0), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1100, 6600.0), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1200, 7800.0), expectedSchema))
    )

    val expectedData2 = Array[(Long, Row)](
      (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100, 100.0), expectedSchema)),
      (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200, 200.0), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 300, 500.0), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 400, 500.0), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 500, 1000.0), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 600, 1100.0), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 700, 1700.0), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 800, 1900.0), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 900, 2600.0), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1000, 2900.0), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1100, 3700.0), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1200, 4100.0), expectedSchema))
    )

    val result1 = volTSRdd.addSummaryColumns(Summarizers.sum("volume"))
    assert(result1.collect().deep == expectedData.map(_._2).deep)

    val result2 = volTSRdd.addSummaryColumns(Summarizers.sum("volume"), Seq("id"))
    assert(result2.collect().deep == expectedData2.map(_._2).deep)

    val result3 = insertNullRows(volTSRdd, "volume")
      .addSummaryColumns(Summarizers.sum("volume"), Seq("id")).keepRows{ row: Row => row.getAs[Any]("volume") != null }

    assertEquals(result2, result3)
  }

  it should "`addWindows` correctly" in {

    val windowLength = "50s"
    val windowColumnName = s"window_past_$windowLength"

    val innerRowSchema = Schema("id" -> IntegerType, "volume" -> LongType)
    val expectedSchema = Schema(
      "id" -> IntegerType, "volume" -> LongType, windowColumnName -> ArrayType(innerRowSchema)
    )

    val rows = volData.map(_._2)

    val expectedData = Array[(Long, Row)](
      (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100, Array(rows(0), rows(1))), expectedSchema)),
      (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200, Array(rows(0), rows(1))), expectedSchema)),
      (1050000000000L,
        new ExternalRow(Array(1050000000000L, 3, 300, Array(rows(0), rows(1), rows(2), rows(3))), expectedSchema)),
      (1050000000000L,
        new ExternalRow(Array(1050000000000L, 7, 400, Array(rows(0), rows(1), rows(2), rows(3))), expectedSchema)),
      (1100000000000L,
        new ExternalRow(Array(1100000000000L, 3, 500, Array(rows(2), rows(3), rows(4), rows(5))), expectedSchema)),
      (1100000000000L,
        new ExternalRow(Array(1100000000000L, 7, 600, Array(rows(2), rows(3), rows(4), rows(5))), expectedSchema)),
      (1150000000000L,
        new ExternalRow(Array(1150000000000L, 3, 700, Array(rows(4), rows(5), rows(6), rows(7))), expectedSchema)),
      (1150000000000L,
        new ExternalRow(Array(1150000000000L, 7, 800, Array(rows(4), rows(5), rows(6), rows(7))), expectedSchema)),
      (1200000000000L,
        new ExternalRow(Array(1200000000000L, 3, 900, Array(rows(6), rows(7), rows(8), rows(9))), expectedSchema)),
      (1200000000000L,
        new ExternalRow(Array(1200000000000L, 7, 1000, Array(rows(6), rows(7), rows(8), rows(9))), expectedSchema)),
      (1250000000000L,
        new ExternalRow(Array(1250000000000L, 3, 1100, Array(rows(8), rows(9), rows(10), rows(11))), expectedSchema)),
      (1250000000000L,
        new ExternalRow(Array(1250000000000L, 7, 1200, Array(rows(8), rows(9), rows(10), rows(11))), expectedSchema))
    )

    val result1 = volTSRdd.addWindows(Windows.pastAbsoluteTime(windowLength)).collect()
    val expectedResult = expectedData.map(_._2)

    rows.indices.foreach {
      index =>
        assert(result1(index).getAs[mutable.WrappedArray[Row]](windowColumnName).deep ==
          expectedResult(index).getAs[Array[Row]](windowColumnName).deep)
    }
    assert(result1.map(_.schema).deep == expectedResult.map(_.schema).deep)
  }

  it should "`addWindows` correctly with secondary key" in {
    val lookback = 99
    val windowLength = s"${lookback}s"
    val windowColumnName = s"window_past_${windowLength}"

    val resultWindows = forecastTSRdd.addWindows(
      Windows.pastAbsoluteTime(windowLength), key = Seq("id")
    ).collect().map(_.getAs[mutable.WrappedArray[Row]](windowColumnName))

    val expectedWindows = forecastData.map(_._2).map {
      row =>
        val rowTime = row.getAs[Long]("time")
        val rowKey = row.getAs[Int]("id")
        forecastData.map(_._2).filter {
          windowRow =>
            val windowRowTime = windowRow.getAs[Long]("time")
            val windowRowKey = windowRow.getAs[Int]("id")
            rowTime - lookback * 1000000000L <= windowRowTime &&
              windowRowTime <= rowTime && windowRowKey == rowKey
        }
    }

    assert(resultWindows.deep == expectedWindows.deep)
  }

  it should "`keepRows and filter` correctly" in {
    val expectedData = volData.filter { case (t: Long, r: Row) => r.getAs[Long]("volume") > 900 }
    val result = volTSRdd.keepRows { row: Row => row.getAs[Long]("volume") > 900 }
    val filterResult = volTSRdd.filter(volTSRdd("volume") > 900)
    assert(result.collect().deep == expectedData.map(_._2).deep)
    assert(filterResult.collect().deep == expectedData.map(_._2).deep)

    val withNulls = volTSRdd.addColumns("volume2" -> LongType -> { _ => null })
    assert(withNulls.keepRows(_.getAs[Any]("volume2") != null).count == 0)
    assert(withNulls.filter(withNulls("volume2").isNotNull).count == 0)
  }

  it should "`deleteRows` correctly" in {
    val expectedData = volData.filterNot { case (t: Long, r: Row) => r.getAs[Long]("volume") > 900 }
    val result = volTSRdd.deleteRows { row: Row => row.getAs[Long]("volume") > 900 }
    assert(result.collect().deep == expectedData.map(_._2).deep)
  }

  it should "`canonizeTime` convert Timestamp to ns with microsecond precision" in {
    val expected = Seq[Long](
      0L,
      Long.MaxValue - (Long.MaxValue % 1000), // clip to microsecond precision
      946684800000000000L, // 2001-01-01
      1262304000000000000L, // 2010-01-01
      1893456000000000000L // 2030-01-01
    )

    val inputDf = expected.map { timestampNanos =>
      Tuple1(timestampNanos)
    }.toDF("time")

    val actualDf = TimeSeriesRDD.canonizeTime(inputDf, TimeUnit.NANOSECONDS)
    val actual = actualDf.collect().map(_.getAs[Long]("time")).toSeq
    assert(actual === expected)
  }

  it should "`canonizeTime` handle a null Timestamp value" in {
    // Test with a null Timestamp
    val actualDf = TimeSeriesRDD.canonizeTime(
      spark.createDataFrame(
        sc.parallelize(Seq(Row(null))),
        StructType(Seq(StructField("time", LongType)))
      ), TimeUnit.NANOSECONDS
    )

    val actual = actualDf.collect().map(_.getAs[JLong]("time")).toSeq
    val expected = Seq[JLong](null)
    assert(actual === expected)
  }

  it should "`canonizeTime` convert ns to Timestamp with microsecond precision" in {
    // Create a new SparkSession that inherits the current SparkContext.
    // TimeSeriesRDD accesses the current SparkSession by calling dataframe.sparkSession.
    val newSpark = spark.newSession()
    // Change the default time type column to timestamp
    newSpark.conf.set(FlintConf.TIME_TYPE_CONF, "timestamp")
    import newSpark.implicits._

    val expectedNanos = Seq[Long](
      0L,
      Long.MaxValue - (Long.MaxValue % 1000), // clip to microsecond precision
      946684800000000000L, // 2001-01-01
      1262304000000000000L, // 2010-01-01
      1893456000000000000L // 2030-01-01
    )

    // This dataframe to newSpark because it is constructed by the imported
    // implicit toDF(...) method.
    val inputDf = expectedNanos.map { timestampNanos =>
      Tuple1(timestampNanos)
    }.toDF("time")

    val actualDf = TimeSeriesRDD.canonizeTime(inputDf, TimeUnit.NANOSECONDS)
    val actual = actualDf.collect().map(_.getAs[Timestamp]("time")).toSeq
    val expected = expectedNanos.map { nanos =>
      Timestamp.from(Instant.ofEpochSecond(0, nanos))
    }
    assert(actual === expected)
  }

  it should "`canonizeTime` should handle a null Long value" in {
    // Change the default time type column to timestamp
    val newSpark = spark.newSession()
    newSpark.conf.set(FlintConf.TIME_TYPE_CONF, "timestamp")

    // Test with a null Timestamp
    val actualDf = TimeSeriesRDD.canonizeTime(
      spark.createDataFrame(
        sc.parallelize(Seq(Row(null))),
        StructType(Seq(StructField("time", LongType)))
      ), TimeUnit.NANOSECONDS
    )

    val actual = actualDf.collect().map(_.getAs[Timestamp]("time")).toSeq
    val expected = Seq(null)
    assert(actual === expected)
  }

  it should "`keepColumns` correctly" in {
    val expectedSchema = Schema("id" -> IntegerType, "volume.1" -> IntegerType)
    val expectedData = Array[(Long, Row)](
      (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 100L), expectedSchema)),
      (1000000000000L, new ExternalRow(Array(1000000000000L, 7, 102L), expectedSchema)),
      (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 200L), expectedSchema)),
      (1000000000000L, new ExternalRow(Array(1000000000000L, 3, 202L), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 300L), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 3, 302L), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 400L), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 7, 402L), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 500L), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 602L), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 3, 501L), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 7, 601L), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 700L), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 802L), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 3, 701L), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 7, 801L), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 902L), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1000L), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 3, 901L), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 7, 1002L), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1100L), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1202L), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 3, 1101L), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 7, 1201L), expectedSchema))
    )

    var result = vol4TSRdd.keepColumns("id", "volume.1")
    assert(result.collect().deep == expectedData.map(_._2).deep)

    // should select the time column first
    result = vol4TSRdd.keepColumns("id", "volume.1", "time")
    assert(result.collect().deep == expectedData.map(_._2).deep)
  }

  it should "`renameColumns` correctly" in {
    intercept[IllegalArgumentException] {
      volTSRdd.renameColumns("time" -> "time2").count()
    }

    intercept[IllegalArgumentException] {
      volTSRdd.renameColumns("id" -> "newid", "id" -> "anotherid").count()
    }

    val renamedTSRdd = volTSRdd.renameColumns("id" -> "id2")
    val expectedSchema = Schema("time" -> LongType, "id2" -> IntegerType, "volume" -> LongType)

    assert(renamedTSRdd.schema == expectedSchema)

    val renamedWithDecimal = volTSRdd.renameColumns("volume" -> "volume0.01")
    val expectedSchemaWithDecimal = Schema("time" -> LongType, "id" -> IntegerType, "volume0.01" -> LongType)
    assert(renamedWithDecimal.schema == expectedSchemaWithDecimal)
    val renamedWithoutDecimal = renamedWithDecimal.renameColumns("volume0.01" -> "volume")
    assert(renamedWithoutDecimal.schema == volSchema)
  }

  it should "`deleteColumns` correctly" in {
    val expectedSchema = StructType(
      StructField("time", LongType) ::
        StructField("id", IntegerType) :: Nil
    )
    val expectedData = Array[(Long, Row)](
      (1000000000000L, new ExternalRow(Array(1000000000000L, 7), expectedSchema)),
      (1000000000000L, new ExternalRow(Array(1000000000000L, 3), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 3), expectedSchema)),
      (1050000000000L, new ExternalRow(Array(1050000000000L, 7), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 3), expectedSchema)),
      (1100000000000L, new ExternalRow(Array(1100000000000L, 7), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 3), expectedSchema)),
      (1150000000000L, new ExternalRow(Array(1150000000000L, 7), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 3), expectedSchema)),
      (1200000000000L, new ExternalRow(Array(1200000000000L, 7), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 3), expectedSchema)),
      (1250000000000L, new ExternalRow(Array(1250000000000L, 7), expectedSchema))
    )

    val result = volTSRdd.deleteColumns("volume")
    assert(result.collect().deep == expectedData.map(_._2).deep)
  }

  it should "`deleteColumns` if there are columns with a period in it's name" in {
    val extendedTsRdd = volTSRdd.addColumns("column.name.with.period" -> IntegerType -> { _ => 1 })
    val result = extendedTsRdd.deleteColumns("volume")

    assert(result.schema.fields.length == 3)
  }

  it should "`lookBackwardClock` correctly" in {
    val result = priceTSRdd.lookBackwardClock("1000s")
    val expectedData = priceTSRdd.collect().map {
      r =>
        val values = r.toSeq.toArray
        values(0) = r.getLong(0) - 1000000000000L
        new ExternalRow(values, r.schema): Row
    }
    assert(result.collect().deep == expectedData.deep)
  }

  it should "`lookForwardClock` correctly" in {
    val result = priceTSRdd.lookForwardClock("1000s")
    val expectedData = priceTSRdd.collect().map {
      r =>
        val values = r.toSeq.toArray
        values(0) = r.getLong(0) + 1000000000000L
        new ExternalRow(values, r.schema): Row
    }
    assert(result.collect().deep == expectedData.deep)
  }

  it should "shiftTime correctly with timestamp type" in {
    withTimeType("timestamp") {
      val data = TimeSeriesRDD.fromDF(priceTSRdd.toDF)(true, NANOSECONDS)
      val result = data.shift(Windows.futureAbsoluteTime("1ns")) // This should be no-op
      val expected = data
      assertIdentical(expected, result)

      val result2 = data.shift(Windows.futureAbsoluteTime("1s"))
      val expected2 = TimeSeriesRDD.fromDF(
        data.toDF.withColumn("time", (col("time").cast("long") + 1).cast("timestamp"))
      )(true, NANOSECONDS)
      assertIdentical(expected2, result2)

      val result3 = data.shift(Windows.pastAbsoluteTime("1ns")) // This is same as shift back 1 micro
      val expected3 = data.shift(Windows.pastAbsoluteTime("1micro"))

      assertIdentical(expected3, result3)
    }
  }

  it should "shiftTime correctly with long type" in {
    withTimeType("long") {
      val data = TimeSeriesRDD.fromDF(priceTSRdd.toDF)(true, NANOSECONDS)
      val result = data.shift(Windows.futureAbsoluteTime("1ns"))
      val expected = TimeSeriesRDD.fromDF(
        data.toDF.withColumn("time", col("time") + 1)
      )(true, NANOSECONDS)
      assertIdentical(expected, result)

      val result2 = data.shift(Windows.futureAbsoluteTime("1s"))
      val expected2 = TimeSeriesRDD.fromDF(
        data.toDF.withColumn("time", col("time") + 1000000000)
      )(true, NANOSECONDS)

      assertIdentical(expected2, result2)

      val result3 = data.shift(Windows.pastAbsoluteTime("1ns"))
      val expected3 = TimeSeriesRDD.fromDF(
        data.toDF.withColumn("time", col("time") - 1)
      )(true, NANOSECONDS)

      assertIdentical(expected3, result3)
    }
  }

  it should "cast columns correctly" in {

    // casting columns shouldn't change the original order
    val resultTSRdd = forecastTSRdd.cast("forecast" -> IntegerType, "id" -> ShortType)
    assertResult(
      Schema.of("time" -> LongType, "id" -> ShortType, "forecast" -> IntegerType),
      "Verify schema"
    )(resultTSRdd.schema)

    val result = resultTSRdd.collect()
    assert(result.forall(row => row.get(1).isInstanceOf[Short]))
    assert(result.forall(row => row.get(2).isInstanceOf[Int]))
    assertResult(-1, "Casting -1.5 to integer")(result(2).get(2))
  }

  it should "return the same schema if there's nothing to cast" in {
    val resultTSRdd = forecastTSRdd.cast("id" -> IntegerType)
    assert(forecastTSRdd.schema == resultTSRdd.schema)
  }

  it should "set time and return an ordered rdd" in {
    val updatedRdd = clockTSRdd.setTime {
      row: Row =>
        val time = row.getAs[Long]("time")
        if (time % 200000000000L != 0) {
          time * 2
        } else {
          time
        }
    }.collect()

    assert(updatedRdd(0).getAs[Long]("time") === 1000000000000L)
    assert(updatedRdd(1).getAs[Long]("time") === 1200000000000L)
    assert(updatedRdd(2).getAs[Long]("time") === 2200000000000L)
    assert(updatedRdd(3).getAs[Long]("time") === 2600000000000L)
  }

  it should "support adding nested data types" in {
    val columnSchema = Schema.of("subcolumn_A" -> DoubleType, "subcolumn_B" -> StringType)
    val resultTSRdd = forecastTSRdd.addColumns("nested_column" -> columnSchema -> {
      _ => new GenericRow(Array(1.0, "test"))
    })
    val filteredResult = resultTSRdd.keepRows(_.getAs[ExternalRow]("nested_column").length == 2)
    filteredResult.collect()

    assert(filteredResult.count() == 12)
  }

  it should "support converting nested data types" in {
    val df = forecastTSRdd.toDF

    val makeTuple = udf((time: Long, long: Double) => Tuple2(time.toString, long))
    val updatedDf = df.withColumn("test", makeTuple(col("time"), col("forecast")))
    val ts = TimeSeriesRDD.fromDF(updatedDf)(isSorted = true, TimeUnit.NANOSECONDS)
    val tsFiltered = ts.keepRows(_.getAs[ExternalRow]("test").length == 2)

    assert(tsFiltered.count() == 12)
  }

  // This test is temporarily tagged as "Slow" so that scalatest runner could exclude this test optionally.
  it should "read parquet files" taggedAs (Slow) in {
    withResource("/timeseries/parquet/PriceWithHeader.parquet") { source =>
      val expectedSchema = Schema("id" -> IntegerType, "price" -> DoubleType, "info" -> StringType)
      val tsrdd = TimeSeriesRDD.fromParquet(sc, "file://" + source)(true, NANOSECONDS)
      val rows = tsrdd.collect()

      assert(tsrdd.schema == expectedSchema)
      assert(rows(0).getAs[Long](TimeSeriesRDD.timeColumnName) == 1000L)
      assert(rows(0).getAs[Integer]("id") == 7)
      assert(rows(0).getAs[Double]("price") == 0.5)
      assert(rows(0).getAs[String]("info") == "test")
      assert(rows.length == 12)
    }
  }

  // This test is temporarily tagged as "Slow" so that scalatest runner could exclude this test optionally.
  it should "not modify original rows during conversions/modifications" taggedAs (Slow) in {
    withResource("/timeseries/parquet/PriceWithHeader.parquet") { source =>
      val tsrdd = TimeSeriesRDD.fromParquet(sc, "file://" + source)(true, NANOSECONDS)
      // fromParquet outputs UnsafeRows. Recording the initial state.
      val rows = tsrdd.collect()

      // conversions and modifications shouldn't affect the original RDD
      val convertedTSRDD = TimeSeriesRDD.fromDF(tsrdd.toDF)(isSorted = true, NANOSECONDS)
      convertedTSRDD.setTime(_.getAs[Long](TimeSeriesRDD.timeColumnName) + 1).count()
      convertedTSRDD.leftJoin(priceTSRdd, key = Seq("id"), leftAlias = "left", rightAlias = "right").count()

      val finalRows = convertedTSRDD.collect()
      assert(rows.deep == finalRows.deep)
    }
  }

  it should "apply DataFrame transformations faithfully using partitionPreservingDataFrameTransform" in {
    val dataframe = forecastTSRdd.toDF
    val result = forecastTSRdd.withPartitionsPreserved { df =>
      df.withColumn("forecastSquared", df.col("forecast") * df.col("forecast"))
    }
    val dfResult = dataframe.withColumn("forecastSquared", dataframe.col("forecast") * dataframe.col("forecast"))
    assert(result.collect().deep == dfResult.collect().deep)
  }

  it should "give wrong results if the partitionPreservingDataFrameTransform doesn't actually preserve partitions" in {
    val result = forecastTSRdd.withPartitionsPreserved { df =>
      df.withColumn("time", df.col("time") * -1)
    }
    val ranges = result.orderedRdd.rangeSplits.map { _.range }
    val badTimestamps = result.rdd.mapPartitionsWithIndex {
      case (index, rows) =>
        val range = ranges(index)
        rows.flatMap { row =>
          val timestamp = row.getAs[Long](timeColumnName)
          if (!range.contains(timestamp)) {
            Some(timestamp)
          } else {
            None
          }
        }
    }.collect()
    assert(badTimestamps.nonEmpty)
  }

  it should "fail to merge tables with incompatible schema" in {
    intercept[IllegalArgumentException] {
      TimeSeriesRDD.mergeSchema(priceSchema, clockSchema)
    }
    intercept[IllegalArgumentException] {
      TimeSeriesRDD.mergeSchema(volSchema, vol4Schema)
    }
  }

  it should "correctly handle nullable flags during merge" in {
    val notNullable = StructType(priceSchema.map(field => StructField(field.name, field.dataType, nullable = false)))
    val merged = TimeSeriesRDD.mergeSchema(priceSchema, notNullable)
    assert(merged.forall(_.nullable))

    val notNullableMerged = TimeSeriesRDD.mergeSchema(notNullable, notNullable)
    assert(notNullableMerged.forall(!_.nullable))
  }

  it should "correctly handle field metadata during merge" in {
    val metadata = new MetadataBuilder().putString("foo", "bar").build()
    val withMeta = StructType(priceSchema.map(field => StructField(field.name, field.dataType, metadata = metadata)))
    val merged = TimeSeriesRDD.mergeSchema(priceSchema, withMeta)
    assert(merged.forall(!_.metadata.contains("foo")))
  }

  it should "correctly merge tables" in {
    val renamedPriceTsrdd = priceTSRdd.renameColumns("price" -> "forecast")
    val merged = renamedPriceTsrdd.merge(forecastTSRdd)
    assert(merged.schema == forecastTSRdd.schema)
    assert(merged.count() == forecastData.length + priceData.length)
  }
}
