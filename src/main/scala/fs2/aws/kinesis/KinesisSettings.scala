package fs2.aws.kinesis

import fs2.aws.internal.Exceptions._
import java.net.URI
import java.util.Date
import scala.concurrent.duration._
import software.amazon.awssdk.regions.Region
import software.amazon.kinesis.common.InitialPositionInStream

/** Settings for configuring the Kinesis consumer
  *
  *  @param streamName name of the Kinesis stream to read from
  *  @param appName name of the application which the KCL daemon should assume
  *  @param region AWS region in which the Kinesis stream resides. Defaults to US-EAST-1
  *  @param maxConcurrency max size of the KinesisAsyncClient HTTP thread pool. Defaults to Int.MaxValue.
  *  @param bufferSize size of the internal buffer used when reading messages from Kinesis
  *  @param endpoint endpoint for the clients that are created. Default to None (i.e. AWS) but can be overridden (e.g. to localhost)
  *  @param retrievalMode FanOut (push) or Polling (pull). Defaults to FanOut (the new default in KCL 2.x).
  */
class KinesisConsumerSettings private (
  val streamName: String,
  val appName: String,
  val region: Region,
  val maxConcurrency: Int,
  val bufferSize: Int,
  val initialPositionInStream: Either[InitialPositionInStream, Date],
  val endpoint: Option[URI],
  val retrievalMode: RetrievalMode
)

object KinesisConsumerSettings {
  def apply(
    streamName: String,
    appName: String,
    region: Region = Region.US_EAST_1,
    maxConcurrency: Int = 100,
    bufferSize: Int = 10,
    initialPositionInStream: Either[InitialPositionInStream, Date] = Left(InitialPositionInStream.LATEST),
    endpoint: Option[String] = None,
    retrievalMode: RetrievalMode = FanOut
  ): KinesisConsumerSettings =
    new KinesisConsumerSettings(
      streamName,
      appName,
      region,
      maxConcurrency,
      bufferSize,
      initialPositionInStream,
      endpoint.map(URI.create),
      retrievalMode
    )
}

sealed trait RetrievalMode
case object FanOut  extends RetrievalMode
case object Polling extends RetrievalMode

/** Settings for configuring the Kinesis checkpointer pipe
  *
  *  @param maxBatchSize the maximum number of records to aggregate before checkpointing the cluster of records. Passing 1 means checkpoint on every record
  *  @param maxBatchWait the maximum amount of time to wait before checkpointing the cluster of records
  */
class KinesisCheckpointSettings private (
  val maxBatchSize: Int,
  val maxBatchWait: FiniteDuration
)

object KinesisCheckpointSettings {
  val defaultInstance = new KinesisCheckpointSettings(1000, 10.seconds)

  def apply(
    maxBatchSize: Int,
    maxBatchWait: FiniteDuration
  ): Either[Throwable, KinesisCheckpointSettings] =
    (maxBatchSize, maxBatchWait) match {
      case (s, _) if s <= 0 =>
        Left(MaxBatchSizeException("Must be greater than 0"))
      case (_, w) if w <= 0.milliseconds =>
        Left(
          MaxBatchWaitException(
            "Must be greater than 0 milliseconds. To checkpoint immediately, pass 1 to the max batch size."
          )
        )
      case (s, w) =>
        Right(new KinesisCheckpointSettings(s, w))
    }
}
