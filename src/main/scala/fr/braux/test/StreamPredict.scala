package fr.braux.test

import java.util.Properties

import org.apache.flink.streaming.api.scala._
import org.apache.flink.util.Collector
import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer, FlinkKafkaProducer}
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.scala.function.ProcessAllWindowFunction
import org.apache.flink.streaming.api.windowing.assigners.EventTimeSessionWindows
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks
import org.apache.flink.streaming.api.watermark.Watermark
import ml.dmlc.xgboost4j.scala.DMatrix
import ml.dmlc.xgboost4j.java.{DMatrix => JDMatrix}
import ml.dmlc.xgboost4j.scala.{Booster, XGBoost}
import ml.dmlc.xgboost4j.LabeledPoint


import scala.util.Random

object StreamPredict extends App {

  if (args.length == 0) {
    System.err.println("USAGE: StreamPredict --brokers <kafka brokers> --in <input topic> --out <output topic> --model <XGBoost model> --variables <# variables>")
    System.exit(1)
  }

  val params: ParameterTool = ParameterTool.fromArgs(args)
  val env = StreamExecutionEnvironment.getExecutionEnvironment
  env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
  env.setParallelism(2)

  val properties = new Properties()
  properties.setProperty("bootstrap.servers", params.getRequired("brokers"))
  properties.setProperty("group.id", Random.alphanumeric.filter(_.isLetter).take(20).mkString)
  properties.setProperty("auto.offset.reset", "earliest")
  // Partition discovery
  properties.setProperty("flink.partition-discovery.interval-millis", "1000")

  val model = params.getRequired("model")
  val booster = XGBoost.loadModel("/data/" + model)
  val variables = params.getInt("variables", 100)

  val consumer = new FlinkKafkaConsumer[String](params.getRequired("in"), new SimpleStringSchema(), properties)
  class TimeLagWatermarkGenerator extends AssignerWithPeriodicWatermarks[String] {
    val maxTimeLag = 5000L // 5 seconds
    override def extractTimestamp(element: String, previousElementTimestamp: Long): Long = previousElementTimestamp
    override def getCurrentWatermark: Watermark = new Watermark(System.currentTimeMillis() - maxTimeLag)
  }
  consumer.assignTimestampsAndWatermarks(new TimeLagWatermarkGenerator)

  val producer = new FlinkKafkaProducer[String](params.getRequired("brokers"), params.getRequired("out"), new SimpleStringSchema())
  // producer.setWriteTimestampToKafka(true)

  val streamin = env.addSource(consumer)

  // TEMPORARY SOLUTION: per message processing (not optimal but working)
  class MapPredict(boost: Booster, numvar: Int) extends RichMapFunction[String, String] {
    override def map(svmrow: String): String = {
      // decode a svmlib row and build a CSR sparse matrix with a single row
      val items = svmrow.split(' ').tail
      val (indices, values) = items.map { item => (item.split(':')(0).toInt, item.split(':')(1).toFloat) }.unzip
      val dmat = new DMatrix(List[Long](0, indices.length).toArray, indices, values, JDMatrix.SparseType.CSR, numvar + 1)
      val predict = boost.predict(dmat)
      // the prediction contains a single row and a single column (value between 0 and 1) which is rounded and prepended to the message
      (predict(0).map(p => if (p > 0.5) "1" else "0").head +: items).mkString(" ")
    }
  }

  // val streamout = streamin.map(new MapPredict(booster, variables))

  // FINAl SOLUTION: windows processing
  class WindowPredictFunction(boost: Booster) extends ProcessAllWindowFunction[String, String, TimeWindow] {
    override def process(context: Context, input: Iterable[String], out: Collector[String]) = {
      val mapper = (svmrow: String) => {
        val items = svmrow.split(' ').tail
        val (indices, values) = items.map { item => (item.split(':')(0).toInt, item.split(':')(1).toFloat) }.unzip
        LabeledPoint(0.0f, indices, values)
      }
      val dmat = new DMatrix(for (x <- input.iterator) yield mapper(x), null)
      val predict = boost.predict(dmat)
      out.collect(predict.map(_.head).map(p => if (p > 0.5) "1" else "0").head)
    }
  }

  // windows processing test
  class TestWindowFunction extends ProcessAllWindowFunction[String, String, TimeWindow] {
    override def process(context: Context, input: Iterable[String], out: Collector[String]) = {
      var count = 0
      for (in <- input) {
        count = count + 1
      }
      out.collect(count.toString)
    }
  }

  val streamout = streamin.windowAll(EventTimeSessionWindows.withGap(Time.seconds(1))).process(new TestWindowFunction)
  streamout.addSink(producer)
  env.execute("StreamPredict(" + model + ")")
}
