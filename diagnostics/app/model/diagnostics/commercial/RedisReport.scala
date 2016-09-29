package model.diagnostics.commercial

import akka.actor.{ActorSystem, Props}
import com.redis.{M => Message, S => Subscribed, E => Error, U => Unsubscribed, _}
import common.{ExecutionContexts, Logging}
import conf.Configuration
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import play.api.libs.json.Json
import services.S3

/*
ExpiredKeyEventSubscriber listens to Redis notifications. When a key expires, it gathers
all the known data for that surrogate key from Redis, and writes a single loggable report object into S3.
*/
class ExpiredKeyEventSubscriber(client: RedisClient, system: ActorSystem) extends Logging {

  val expiredKeyEventChannel = "__keyevent@0__:expired"

  val subscriber = system.actorOf(Props(new Subscriber(client)))

  subscriber ! Register(callback)
  sub(List(expiredKeyEventChannel))

  def sub(channels: List[String]) = {
    subscriber ! Subscribe(channels.toArray)
  }

  def callback(pubsub: PubSubMessage) = pubsub match {
    case Message(`expiredKeyEventChannel`, id) if !id.endsWith("-data") => {
        log.logger.info(s"expired key event received for $id")
        uploadReportToS3(id)
    }
    case Message(_, message) => log.logger.info(s"generic redis message received: $message")
    case Unsubscribed(_, _) =>
    case Subscribed(channel, _) => log.logger.info(s"subscribed to redis channel: $channel")
    case Error(_) =>
  }

  private def uploadReportToS3(id: String): Unit = {
    try {
      for {
        redisClient <- RedisReport.redisClient
        reportData <- redisClient.get(RedisReport.dataKeyFromId(id))
      } {
        log.logger.info(s"writing report to s3 bucket, view id: $id")

        if (Configuration.environment.isProd) {
          val date = DateTime.now().toString("yyyy-MM-dd")
          S3CommercialReports.putPublic(s"date=$date/$id", reportData, "text/plain")
        }
      }
    } catch {
      case e:Exception => log.logger.error(e.getMessage)
    }
  }
}

object S3CommercialReports extends S3 {
  override lazy val bucket = "ophan-raw-client-side-ad-metrics"
}

/*
RedisReport writes commercial performance beacons into Redis as key-values.
When the key expires, the ExpiredKeyEventSubscriber object will be notified.
*/
object RedisReport extends Logging with ExecutionContexts {

  val dateTimeFormat: DateTimeFormatter = DateTimeFormat.forPattern("ddMMYYYY-HH:mm:ss").withZoneUTC()

  // Make a client for each usage, otherwise there may be protocol errors.
  def redisClient: Option[RedisClient] = {
    try {
      Configuration.redis.endpoint.map(new RedisClient(_, 6379))
    }
    catch {
      case e: Exception =>
        log.logger.error(e.getMessage)
        None
    }
  }

  def dataKeyFromId(viewId: String): String = viewId + "-data"

  def reportsKeyFromDate(dateTime: DateTime): String = dateTimeFormat.print(dateTime) + "-views"

  // The number of seconds to wait before triggering the data collection process for a page view.
  private val PAGE_VIEW_DATA_COLLECTION_PERIOD = 5L
  // The time to keep the data associated with a page view.
  private val PAGE_VIEW_DATA_EXPIRY = 600L

  def report(report: UserReport): Unit = {
    redisClient.foreach { client =>
      // The surrogate key is set to expire first. This causes the expiry notification to be sent
      // on the Redis pub-sub channel, triggering the callback which will forward the data into S3.
      // Nothing bad happens if data expires too soon, or the system falls behind; we just collect less data.
      client.setex(report.viewId, PAGE_VIEW_DATA_COLLECTION_PERIOD, "surrogate-key")

      // If the data key has been written before, then the time key must have been written as well, so we skip this.
      // Otherwise, the page view would appear in several time keys.
      if (!client.exists(dataKeyFromId(report.viewId))) {
        // Register the page view at the current time. Use a time key-value which holds an array of all the view data
        // recorded for a given time period (minute periods).
        val timeKey = reportsKeyFromDate(DateTime.now())
        client.sadd(timeKey, report.viewId)
        client.expire(timeKey, PAGE_VIEW_DATA_EXPIRY.toInt)
      }

      // Write the new report data to the data key.
      client.setex(dataKeyFromId(report.viewId), PAGE_VIEW_DATA_EXPIRY, Json.toJson(report).toString)
    }
  }

  def getReports(dateTime: DateTime): List[String] = {
    val reports = for {
      client <- redisClient.toList
      maybeSet <- client.smembers[String](reportsKeyFromDate(dateTime)).toList
      maybeViewId <- maybeSet
      viewId <- maybeViewId
      report <- client.get[String](dataKeyFromId(viewId))
    } yield {
      report
    }
    reports.toList
  }
}
