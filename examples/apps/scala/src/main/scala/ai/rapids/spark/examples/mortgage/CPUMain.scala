/* 
 * Copyright (c) 2019, NVIDIA CORPORATION. All rights reserved. 
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
package ai.rapids.spark.examples.mortgage

import ai.rapids.spark.examples.utility.{Benchmark, Vectorize, XGBoostArgs}
import ml.dmlc.xgboost4j.scala.spark.{XGBoostClassificationModel, XGBoostClassifier}
import ml.dmlc.xgboost4j.scala.spark.rapids.GpuDataReader
import org.apache.spark.sql.SparkSession
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator

// Only 3 differences between CPU and GPU. Please refer to '=== diff ==='
object CPUMain extends Mortgage {

  def main(args: Array[String]): Unit = {
    val xgboostArgs = XGBoostArgs.parse(args)
    val processor = this.getClass.getSimpleName.stripSuffix("$").substring(0, 3)
    val appInfo = Seq(appName, processor, xgboostArgs.format)

    // build spark session
    val spark = SparkSession.builder()
      .appName(appInfo.mkString("-"))
      .getOrCreate()

    val benchmark = Benchmark(appInfo(0), appInfo(1), appInfo(2))
    // === diff ===
    // build data reader
    val dataReader = spark.read

    // load datasets, the order is (train, train-eval, eval)
    var datasets = xgboostArgs.dataPaths.map(_.map{
      path =>
        xgboostArgs.format match {
          case "csv" => dataReader.option("header", xgboostArgs.hasHeader).schema(schema).csv(path)
          case "parquet" => dataReader.parquet(path)
          case "orc" => dataReader.orc(path)
          case _ => throw new IllegalArgumentException("Unsupported data file format!")
        }
    })

    val featureNames = schema.filter(_.name != labelColName).map(_.name)

    // === diff ===
    datasets = datasets.map(_.map(ds => Vectorize(ds, featureNames, labelColName)))

    val xgbClassificationModel = if (xgboostArgs.isToTrain) {
      // build XGBoost classifier
      val xgbParamFinal = xgboostArgs.xgboostParams(commParamMap +
        // Add train-eval dataset if specified
        ("eval_sets" -> datasets(1).map(ds => Map("test" -> ds)).getOrElse(Map.empty))
      )
      val xgbClassifier = new XGBoostClassifier(xgbParamFinal)
        .setLabelCol(labelColName)
        // === diff ===
        .setFeaturesCol("features")

      // Start training
      println("\n------ Training ------")
      // Shall we not log the time if it is abnormal, which is usually caused by training failure
      val (model, _) = benchmark.time("train") {
        xgbClassifier.fit(datasets(0).get)
      }
      // Save model if modelPath exists
      xgboostArgs.modelPath.foreach(path =>
        if(xgboostArgs.isOverwrite) model.write.overwrite().save(path) else model.save(path))
      model
    } else {
      XGBoostClassificationModel.load(xgboostArgs.modelPath.get)
    }

    if (xgboostArgs.isToTransform) {
      println("\n------ Transforming ------")
      var (results, _) = benchmark.time("transform") {
        val ret = xgbClassificationModel.transform(datasets(2).get).cache()
        // Trigger the transformation
        ret.foreachPartition(_ => ())
        ret
      }
      results = if (xgboostArgs.isShowFeatures) {
        results
      } else {
        results.select(labelColName, "rawPrediction", "probability", "prediction")
      }
      results.show(xgboostArgs.numRows)

      println("\n------Accuracy of Evaluation------")
      val evaluator = new MulticlassClassificationEvaluator().setLabelCol(labelColName)
      evaluator.evaluate(results) match {
        case accuracy if !accuracy.isNaN =>
          benchmark.value(accuracy, "Accuracy", "Accuracy for")
        // Throw an exception when NaN ?
      }
    }

    spark.close()
  }
}
