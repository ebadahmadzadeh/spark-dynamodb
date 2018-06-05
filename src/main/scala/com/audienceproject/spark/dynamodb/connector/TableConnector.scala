/**
  * Licensed to the Apache Software Foundation (ASF) under one
  * or more contributor license agreements.  See the NOTICE file
  * distributed with this work for additional information
  * regarding copyright ownership.  The ASF licenses this file
  * to you under the Apache License, Version 2.0 (the
  * "License"); you may not use this file except in compliance
  * with the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the License is distributed on an
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  * KIND, either express or implied.  See the License for the
  * specific language governing permissions and limitations
  * under the License.
  *
  * Copyright © 2018 AudienceProject. All rights reserved.
  */
package com.audienceproject.spark.dynamodb.connector

import com.amazonaws.services.dynamodbv2.document.spec.{BatchWriteItemSpec, ScanSpec}
import com.amazonaws.services.dynamodbv2.document.{Item, ItemCollection, ScanOutcome, TableWriteItems}
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity
import com.amazonaws.services.dynamodbv2.xspec.ExpressionSpecBuilder
import org.apache.spark.sql.Row
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.spark_project.guava.util.concurrent.RateLimiter

import scala.collection.JavaConverters._

private[dynamodb] class TableConnector(tableName: String, totalSegments: Int, parameters: Map[String, String])
    extends DynamoConnector with Serializable {

    private val consistentRead = parameters.getOrElse("stronglyConsistentReads", "false").toBoolean
    private val filterPushdown = parameters.getOrElse("filterPushdown", "true").toBoolean

    override val (keySchema, rateLimit, itemLimit, totalSizeInBytes) = {
        val table = getClient.getTable(tableName)
        val desc = table.describe()

        // Key schema.
        val keySchema = KeySchema.fromDescription(desc.getKeySchema.asScala)

        // Parameters.
        val bytesPerRCU = parameters.getOrElse("bytesPerRCU", "4000").toInt
        val targetCapacity = parameters.getOrElse("targetCapacity", "1").toDouble
        val readFactor = if (consistentRead) 1 else 2

        // Rate limit calculation.
        val tableSize = desc.getTableSizeBytes
        val avgItemSize = tableSize.toDouble / desc.getItemCount
        val readCapacity = desc.getProvisionedThroughput.getReadCapacityUnits * targetCapacity

        val rateLimit = (readCapacity / totalSegments).toInt
        val itemLimit = (bytesPerRCU / avgItemSize * rateLimit).toInt * readFactor

        (keySchema, rateLimit, itemLimit, tableSize.toLong)
    }

    override def scan(segmentNum: Int, columns: Seq[String], filters: Seq[Filter]): ItemCollection[ScanOutcome] = {
        val scanSpec = new ScanSpec()
            .withSegment(segmentNum)
            .withTotalSegments(totalSegments)
            .withMaxPageSize(itemLimit)
            .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
            .withConsistentRead(consistentRead)

        if (columns.nonEmpty) {
            val xspec = new ExpressionSpecBuilder().addProjections(columns: _*)

            if (filters.nonEmpty && filterPushdown) {
                xspec.withCondition(FilterPushdown(filters))
            }

            scanSpec.withExpressionSpec(xspec.buildForScan())
        }

        getClient.getTable(tableName).scan(scanSpec)
    }

    def putItems(schema: StructType, batchSize: Int)(items: Iterator[Row]): Unit = {
        val columnNames = schema.map(_.name)
        val hashKeyIndex = columnNames.indexOf(keySchema.hashKeyName)
        val rangeKeyIndex = keySchema.rangeKeyName.map(columnNames.indexOf)
        val columnIndices = columnNames.zipWithIndex.filterNot({
            case (name, _) => keySchema match {
                case KeySchema(hashKey, None) => name == hashKey
                case KeySchema(hashKey, Some(rangeKey)) => name == hashKey || name == rangeKey
            }
        })

        val rateLimiter = RateLimiter.create(rateLimit max 1)
        val client = getClient

        // For each batch.
        items.grouped(batchSize).foreach(itemBatch => {
            val batchWriteItemSpec = new BatchWriteItemSpec().withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
            batchWriteItemSpec.withTableWriteItems(new TableWriteItems(tableName).withItemsToPut(
                // Map the items.
                itemBatch.map(row => {
                    val item = new Item()

                    // Map primary key.
                    keySchema match {
                        case KeySchema(hashKey, None) => item.withPrimaryKey(hashKey, row(hashKeyIndex))
                        case KeySchema(hashKey, Some(rangeKey)) =>
                            item.withPrimaryKey(hashKey, row(hashKeyIndex), rangeKey, row(rangeKeyIndex.get))
                    }

                    // Map remaining columns.
                    columnIndices.foreach({ case (name, index) => item.`with`(name, row(index)) })

                    item
                }): _*
            ))

            val response = client.batchWriteItem(batchWriteItemSpec)

            Option(response.getBatchWriteItemResult.getConsumedCapacity).map(capacityList => {
                math.ceil(capacityList.asScala.map(_.getCapacityUnits.toDouble).sum).toInt
            }).foreach(rateLimiter.acquire)
        })
    }

}