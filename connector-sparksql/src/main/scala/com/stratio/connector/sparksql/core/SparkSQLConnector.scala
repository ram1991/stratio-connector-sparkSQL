/*
 * Licensed to STRATIO (C) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.  The STRATIO (C) licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.stratio.connector.sparksql.core

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Kill}
import com.stratio.connector.commons.{Loggable, Metrics, timer}
import com.stratio.connector.sparksql.core.connection.ConnectionHandler
import com.stratio.connector.sparksql.core.engine.SparkSQLMetadataListener
import com.stratio.connector.sparksql.core.engine.query.{QueryEngine, QueryManager}
import com.stratio.crossdata.common.connector._
import com.stratio.crossdata.common.data.ClusterName
import com.stratio.crossdata.common.exceptions.UnsupportedException
import com.stratio.crossdata.common.security.ICredentials
import com.stratio.crossdata.connectors.ConnectorApp
import com.typesafe.config.Config
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}


class SparkSQLConnector(
                         system: ActorRefFactory,
                         connectorApp: Option[ConnectorApp] = None) extends IConnector
with Loggable
with Metrics {

  import SparkSQLConnector._
  import timer._

  val connectionHandler = new ConnectionHandler

  lazy val sparkContext: SparkContext =
    timeFor("SparkContext created.") {
      initContext(connectorConfig.get)
    }

  lazy val sqlContext: SparkSQLContext =
    timeFor(s"$sqlContextType created from SparkContext.") {
      sqlContextBuilder(sqlContextType, sparkContext)
    }


  lazy val queryManager: ActorRef =
    timeFor("QueryManager created.") {
      system.actorOf(
        QueryManager(
          queryExecutors,
          sqlContext,
          connectionHandler,
          (workflow, connectionHandler, sqlContext) =>
            QueryEngine.executeQuery(
              workflow,
              sqlContext,
              connectionHandler)))
    }


  //  Engines

  lazy val queryEngine: QueryEngine =
    timeFor("Query engine instance is up.") {
      new QueryEngine(sqlContext, queryManager, connectionHandler)
    }

  //  Config parameters

  val queryExecutors: Int =
    connectorConfig.get.getInt(QueryExecutorsAmount)

  val sqlContextType: String =
    connectorConfig.get.getString(SQLContextType)

  //  IConnector implemented methods

  override def getConnectorManifestPath(): String =
    getClass().getClassLoader.getResource(ConnectorConfigFile).getPath()

  override def getDatastoreManifestPath(): Array[String] =
    providers.all.map(x=> {
      getClass().getClassLoader.getResource(x.manifest).getPath()
    })

  override def restart(): Unit = {
    timeFor(s"SparkSQL connector initialized.") {
      timeFor("All providers are initialized") {
        providers.all.foreach(_.initialize(sparkContext))
      }
      timeFor("Subscribed to metadata updates.") {
        connectorApp.foreach(_.subscribeToMetadataUpdate(
          SparkSQLMetadataListener(
            sqlContext,
            connectionHandler,
            queryManager)))
      }
    }
  }

  def init(configuration: IConfiguration): Unit =
    timeFor(s"SparkSQL connector initialized.") {
      timeFor("All providers are initialized") {
        providers.all.foreach(_.initialize(sparkContext))
      }
      timeFor("Subscribed to metadata updates.") {
        connectorApp.foreach(_.subscribeToMetadataUpdate(
          SparkSQLMetadataListener(
            sqlContext,
            connectionHandler,
            queryManager)))
      }
    }

  def connect(
                        credentials: ICredentials,
                        config: ConnectorClusterConfig): Unit =
    timeFor("Connected to SparkSQL connector") {
      connectionHandler.createConnection(config, sqlContext, Option(credentials))
    }

  def getQueryEngine: IQueryEngine =
    queryEngine

  def isConnected(name: ClusterName): Boolean =
    connectionHandler.isConnected(name.getName)

  def close(name: ClusterName): Unit = {
    logger.info(s"Connection to $name cluster was closed")
    timeFor(s"Connection  cluster closed") {
      connectionHandler.closeConnection(name.getName)
    }
  }

  def shutdown(): Unit =
    timeFor("Connector has been shut down...") {
      logger.debug("Disposing QueryManager")
      queryManager ! Kill
      logger.debug("Disposing SparkContext")
      sparkContext.stop()
    }

  //  Helpers

  /**
   * Build a brand new Spark context given some config parameters.
   * @param config Configuration object.
   * @return A new Spark context
   */
  def initContext(config: Config): SparkContext = {
    import scala.collection.JavaConversions._
    new SparkContext(new SparkConf()
      .setAppName(SparkSQLConnector.SparkSQLConnectorJobConstant)
      .setSparkHome(config.getString(SparkHome))
      .setMaster(config.getString(SparkMaster))
      .setJars(config.getConfig(Spark).getStringList(SparkJars))
      .setAll(List(
      SparkDriverMemory,
      SparkExecutorMemory,
      SparkCoresMax).filter(config.hasPath).map(k => k -> config.getString(k))))
  }

  /**
   * Build a new SQLContext depending on given type.
   *
   * @param contextType 'HiveContext' and 'SQLContext' (by default) are,
   *                    by now, the only supported types.
   * @param sc SparkContext used to build new SQLContext.
   * @return A brand new SQLContext.
   */
  def sqlContextBuilder(
                         contextType: String,
                         sc: SparkContext): SparkSQLContext =
    contextType match {
      case HIVEContext => new HiveContext(sc) with Catalog
      case _ => new SQLContext(sc) with Catalog
    }

  //  Unsupported methods

  def getStorageEngine: IStorageEngine =
    throw new UnsupportedException(SparkSQLConnector.MethodNotSupported)

  def getMetadataEngine: IMetadataEngine =
    throw new UnsupportedException(SparkSQLConnector.MethodNotSupported)

}

object SparkSQLConnector extends App
with Constants
with Configuration
with Loggable
with Metrics{

  import timer._

  val system = {
    logger.info(s"'$ActorSystemName' actor system has been initialized.")
    timeFor(" actor system initialized.") {
      ActorSystem(ActorSystemName)
    }
  }

  val connectorApp =
    timeFor("Connector App has been created.") {
      new ConnectorApp
    }

  val sparkSQLConnector =
    timeFor(s"SparkSQLConnector built.") {
      new SparkSQLConnector(system,Some(connectorApp))
    }

  timeFor("Connector has been started...") {
    connectorApp.startup(sparkSQLConnector)
  }

  system.registerOnTermination {
    timeFor("Termination detected. Shutting down actor system...") {
      sparkSQLConnector.shutdown()
    }
  }

  def providersFromConfig: Array[String] =
    connectorConfig.get.getStringList(ProvidersInUse).toArray.map(_.toString)

}