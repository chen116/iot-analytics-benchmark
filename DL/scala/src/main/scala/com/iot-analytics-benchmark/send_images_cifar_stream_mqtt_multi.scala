/*
send_images_cifar_stream.scala: sends CIFAR10 images encoded as a string to a Spark Streaming inference program - Scala version

Usage:
  $ scala -J-Xmx128g -cp <path>/scopt_2.11-3.7.1.jar:scala/target/scala-2.11/infer_stream_cifar_2.11-0.0.1.jar com.intel.analytics.bigdl.models.resnet.send_images_cifar \
    <arguments> | nc -lk <port>
OR
  $ java -Xmx128g -cp <path>/scala-library.jar:<path>/scopt_2.11-3.7.1.jar:scala/target/scala-2.11/infer_stream_cifar_2.11-0.0.1.jar \
    com.intel.analytics.bigdl.models.resnet.send_images_cifar <arguments> | nc -lk <port>

Arguments:
  -f, --folder <value>        the location of Cifar10 dataset  Default: cifar-10-batches-bin
  -i, --imagesPerSec <value>  images per second                Default: 10
  -t, --totalImages <value>   total images                     Default: 100

Uses Intel's BigDL library (https://github.com/intel-analytics/BigDL) and CIFAR10 dataset from https://www.cs.toronto.edu/~kriz/cifar.html

This product is licensed to you under the Apache 2.0 license (the "License").  You may not use this product except in compliance with the Apache 2.0 License.

This product may include a number of subcomponents with separate copyright notices and license terms. Your use of these subcomponents is subject to the terms and conditions of the subcomponent's license, as noted in the LICENSE file.
*/

package com.intel.analytics.bigdl.models.resnet

import scala.collection.mutable.ArrayBuffer
import scala.math._
import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.Random

import scopt.OptionParser
import java.util.Properties

import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}

import scala.concurrent.Promise


import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence


object send_images_cifar_stream_mqtt_multi {

  // From https://github.com/intel-analytics/BigDL/blob/master/spark/dl/src/main/scala/com/intel/analytics/bigdl/models/resnet/Utils.scala, with HDFS removed
  case class ByteRecord(data: Array[Byte], label: Float)
  def load(featureFile: String, result : ArrayBuffer[ByteRecord]): Unit = {
    val rowNum = 32
    val colNum = 32
    val imageOffset = rowNum * colNum * 3 + 1
    val channelOffset = rowNum * colNum
    val bufferOffset = 8

    val featureBuffer =
      ByteBuffer.wrap(Files.readAllBytes(Paths.get(featureFile)))


    val featureArray = featureBuffer.array()
    val featureCount = featureArray.length / (rowNum * colNum * 3 + 1)

    var i = 0
    while (i < featureCount) {
      val img = new Array[Byte]((rowNum * colNum * 3 + bufferOffset))
      val byteBuffer = ByteBuffer.wrap(img)
      byteBuffer.putInt(rowNum)
      byteBuffer.putInt(colNum)

      val label = featureArray(i * imageOffset).toFloat
      var y = 0
      val start = i * imageOffset + 1
      while (y < rowNum) {
        var x = 0
        while (x < colNum) {
          img((x + y * colNum) * 3 + 2 + bufferOffset) =
            featureArray(start + x + y * colNum)
          img((x + y * colNum) * 3 + 1 + bufferOffset) =
            featureArray(start + x + y * colNum + channelOffset)
          img((x + y * colNum) * 3 + bufferOffset) =
            featureArray(start + x + y * colNum + 2 * channelOffset)
          x += 1
        }
        y += 1
      }
      result.append(ByteRecord(img, label + 1.0f))
      i += 1
    }
  }

  // From https://github.com/intel-analytics/BigDL/blob/master/spark/dl/src/main/scala/com/intel/analytics/bigdl/models/resnet/Utils.scala
  def loadTest(dataFile: String): Array[ByteRecord] = {
    val result = new ArrayBuffer[ByteRecord]()
    val testFile = dataFile + "/test_batch.bin"
    load(testFile, result)
    result.toArray
  }

  def accurateWait(wait_in_seconds: Double): Unit = {
    val  waitUntil = System.nanoTime() + round(1000000000 * wait_in_seconds)
    while (waitUntil > System.nanoTime()) {}
  }


  class MyThread extends Thread
  {
    override def run()
    {
      // Displaying the thread that is running
      println("Thread " + Thread.currentThread().getName() +
        " is running.")
    }
  }


  def main(args: Array[String]) {



    case class Params(
                       folder: String = "cifar-10-batches-bin",
                       imagesPerSec: Int  = 10,
                       totalImages: Int = 100,
                       dir: String = "$tmp",
                       mqttdest: String = "kafkaSource",
                       mqttserver: String = "hpc0982"
                     )

    val parser = new OptionParser[Params]("send_images_cifar") {
      opt[String]('f', "folder")
        .text("the location of Cifar10 dataset - default: cifar-10-batches-bin")
        .action((x, c) => c.copy(folder = x))
      opt[Int]('i', "imagesPerSec - default: 10")
        .text("images per second")
        .action((x, c) => c.copy(imagesPerSec = x))
      opt[Int]('t', "totalImages - default: 100")
        .text("total images")
        .action((x, c) => c.copy(totalImages = x))
      opt[String]('d', "mqtt persistence dir - default: 100")
        .text("mqtt persistence dir")
        .action((x, c) => c.copy(dir = x))
      opt[String]('m', "mqtt dest - default: kafkaSource")
        .text("mqtt dest")
        .action((x, c) => c.copy(mqttdest = x))
      opt[String]('s', "mqtt server - default: hpc0982")
        .text("mqtt server")
        .action((x, c) => c.copy(mqttserver = x))
    }

    parser.parse(args, Params()).foreach { param =>

      System.err.println("Will send to kafka" + param.imagesPerSec + " images per second for a total of " + param.totalImages + " images")

      //  First, write labeled test images to an array
      val image_string_array = new Array[String](10000)
      val images = loadTest(param.folder)
      for (i <- 0 to images.length-1) {
        val d = images(i).data.mkString(",")
        val l = images(i).label.toString
        val dl = l + "," + d
        image_string_array(i) = dl
      }

      // mqtt out
      val brokerUrl = "tcp://"+param.mqttserver+":1883"
      val topic = param.mqttdest
      var client: MqttClient = null
      // Creating new persistence for mqtt client
      val persistence = new MqttDefaultFilePersistence(param.dir)
      client = new MqttClient(brokerUrl, MqttClient.generateClientId, persistence)
      client.connect()
      val msgTopic = client.getTopic(topic)

      System.err.println("Pausing 1 seconds - start image_stream_cifar")
      Thread.sleep(1000)

      // Use lognormal distribution to generate a positive random wait time with mean determined from imagesPerSecond and long tail
      val rand = new Random
      val mean_wait = 1.0/param.imagesPerSec
      // Set standard deviation to half the mean_wait
      val std_dev = mean_wait/2.0

      val t1 = System.nanoTime

      //  Send images from array, wrapping if necessary
      System.err.println(Instant.now() + ": Sending images")
      for (i <- 0 to param.totalImages-1) {
        println(image_string_array(i % image_string_array.length).charAt(0))
        msgTopic.publish(new MqttMessage(  image_string_array(i % image_string_array.length).getBytes("utf-8")))
        System.out.flush()
        if ((i+1) % param.imagesPerSec == 0 ) System.err.println(Instant.now() + ": " + (i+1) + " images sent")
        // Wait a random time from lognormal distribution with mean mean_wait
        accurateWait(mean_wait * exp(rand.nextGaussian()*std_dev + mean_wait)/exp(mean_wait + pow(std_dev,2)/2))
      }

      val t2 = System.nanoTime
      client.disconnect()
      System.err.println(Instant.now() + ": Sent " + param.totalImages + " images in " + "%.1f".format((t2-t1)/1000000000.0) + " seconds")
    }
  }
}
