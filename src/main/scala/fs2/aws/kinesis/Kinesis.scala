package fs2.aws
package kinesis

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Concurrent, Sync}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.concurrent.SignallingRef
import fs2.{Chunk, Pipe, Stream}
import java.util.UUID
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ConfigsBuilder, InitialPositionInStreamExtended}
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.metrics.MetricsUtil
import software.amazon.kinesis.processor.ShardRecordProcessorFactory
import software.amazon.kinesis.retrieval.KinesisClientRecord
import software.amazon.kinesis.retrieval.polling.PollingConfig

trait Kinesis[F[_]] {

  /** Initialize a worker and start streaming records from a Kinesis stream
    * On stream finish (due to error or other), worker will be shutdown
    *
    * @param appName    name of the Kinesis application. Used by KCL when resharding
    * @param streamName name of the Kinesis stream to consume from
    * @return an infinite fs2 Stream that emits Kinesis Records
    */
  def readFromKinesisStream(appName: String, streamName: String): Stream[F, CommittableRecord] =
    readFromKinesisStream(KinesisConsumerSettings(streamName, appName))

  /** Initialize a worker and start streaming records from a Kinesis stream
    * On stream finish (due to error or other), worker will be shutdown
    *
    * @param consumerConfig configuration parameters for the KCL
    * @return an infinite fs2 Stream that emits Kinesis Records
    */
  def readFromKinesisStream(consumerConfig: KinesisConsumerSettings): Stream[F, CommittableRecord] =
    readChunkedFromKinesisStream(consumerConfig).flatMap(Stream.chunk)

  /** Initialize a worker and start streaming records from a Kinesis stream
    * On stream finish (due to error or other), worker will be shutdown
    *
    * @param consumerConfig configuration parameters for the KCL
    * @return an infinite fs2 Stream that emits Kinesis Records Chunks
    */
  def readChunkedFromKinesisStream(
    consumerConfig: KinesisConsumerSettings
  ): Stream[F, Chunk[CommittableRecord]]

  /** Pipe to checkpoint records in Kinesis, marking them as processed
    * Groups records by shard id, so that each shard is subject to its own clustering of records
    * After accumulating maxBatchSize or reaching maxBatchWait for a respective shard, the latest record is checkpointed
    * By design, all records prior to the checkpointed record are also checkpointed in Kinesis
    *
    * @param checkpointSettings configure maxBatchSize and maxBatchWait time before triggering a checkpoint
    * @return a stream of Record types representing checkpointed messages
    */
  def checkpointRecords(
    checkpointSettings: KinesisCheckpointSettings
  ): Pipe[F, CommittableRecord, KinesisClientRecord]

}

object Kinesis {
  abstract class GenericKinesis[F[_]: Async] extends Kinesis[F] {
    private[kinesis] def readChunksFromKinesisStream(
      streamConfig: KinesisConsumerSettings,
      schedulerFactory: ShardRecordProcessorFactory => F[Scheduler]
    ): Stream[F, Chunk[CommittableRecord]] = {
      // Initialize a KCL scheduler which appends to the internal stream queue on message receipt
      def instantiateScheduler(
        dispatcher: Dispatcher[F],
        queue: Queue[F, Chunk[CommittableRecord]],
        signal: SignallingRef[F, Boolean]
      ): Stream[F, Scheduler] =
        Stream.bracket {
          schedulerFactory(() =>
            new ChunkedRecordProcessor(records => dispatcher.unsafeRunSync(queue.offer(records)))
          ).flatTap(s =>
            Concurrent[F].start(Async[F].blocking(s.run()).flatTap(_ => signal.set(true)))
          )
        }(s => Async[F].blocking(s.shutdown()))

      // Instantiate a new bounded queue and concurrently run the queue populator
      // Expose the elements by dequeuing the internal buffer
      for {
        dispatcher      <- Stream.resource(Dispatcher[F])
        buffer          <- Stream.eval(Queue.bounded[F, Chunk[CommittableRecord]](streamConfig.bufferSize))
        interruptSignal <- Stream.eval(SignallingRef[F, Boolean](false))
        _               <- instantiateScheduler(dispatcher, buffer, interruptSignal)
        stream          <- Stream.fromQueueUnterminated(buffer).interruptWhen(interruptSignal)
      } yield stream
    }
    def checkpointRecords(
      checkpointSettings: KinesisCheckpointSettings
    ): Pipe[F, CommittableRecord, KinesisClientRecord] = {
      def checkpoint(
        checkpointSettings: KinesisCheckpointSettings
      ): Pipe[F, CommittableRecord, KinesisClientRecord] =
        _.groupWithin(checkpointSettings.maxBatchSize, checkpointSettings.maxBatchWait)
          .collect { case chunk if chunk.size > 0 => chunk.toList.max }
          .flatMap(cr => fs2.Stream.eval(cr.checkpoint.as(cr.record)).drain)

      def bypass: Pipe[F, CommittableRecord, KinesisClientRecord] = _.map(_.record)

      _.through(internal.groupBy(r => Sync[F].pure(r.shardId))).map {
        case (_, st) =>
          st.broadcastThrough(checkpoint(checkpointSettings), bypass)
      }.parJoinUnbounded
    }
  }

  def create[F[_]: Async](
    schedulerFactory: ShardRecordProcessorFactory => F[Scheduler]
  ): Kinesis[F] = new GenericKinesis[F] {
    override def readChunkedFromKinesisStream(
      consumerConfig: KinesisConsumerSettings
    ): Stream[F, Chunk[CommittableRecord]] =
      readChunksFromKinesisStream(consumerConfig, schedulerFactory)
  }

  def create[F[_]: Async](
    kinesisAsyncClient: KinesisAsyncClient,
    dynamoDbAsyncClient: DynamoDbAsyncClient,
    cloudWatchAsyncClient: CloudWatchAsyncClient
  ): Kinesis[F] = {

    def defaultScheduler(
      settings: KinesisConsumerSettings,
      kinesisClient: KinesisAsyncClient,
      dynamoClient: DynamoDbAsyncClient,
      cloudWatchClient: CloudWatchAsyncClient,
      schedulerId: UUID
    ): ShardRecordProcessorFactory => F[Scheduler] = { recordProcessorFactory =>
      val configsBuilder: ConfigsBuilder = new ConfigsBuilder(
        settings.streamName,
        settings.appName,
        kinesisClient,
        dynamoClient,
        cloudWatchClient,
        schedulerId.toString,
        recordProcessorFactory
      )

      val metricsConfig = configsBuilder.metricsConfig.metricsEnabledDimensions(
        Set(MetricsUtil.OPERATION_DIMENSION_NAME, MetricsUtil.SHARD_ID_DIMENSION_NAME).asJava
      )

      val retrievalConfig = configsBuilder.retrievalConfig()

      settings.retrievalMode match {
        case Polling =>
          retrievalConfig.retrievalSpecificConfig(
            new PollingConfig(settings.streamName, kinesisClient)
          )
        case _ => ()
      }

      retrievalConfig.initialPositionInStreamExtended(
        settings.initialPositionInStream match {
          case Left(position) =>
            InitialPositionInStreamExtended.newInitialPosition(position)

          case Right(date) =>
            InitialPositionInStreamExtended.newInitialPositionAtTimestamp(date)
        }
      )

      new Scheduler(
        configsBuilder.checkpointConfig(),
        configsBuilder.coordinatorConfig(),
        configsBuilder.leaseManagementConfig(),
        configsBuilder.lifecycleConfig(),
        metricsConfig,
        configsBuilder.processorConfig(),
        retrievalConfig
      ).pure[F]
    }

    new GenericKinesis[F] {
      override def readChunkedFromKinesisStream(
        consumerConfig: KinesisConsumerSettings
      ): Stream[F, Chunk[CommittableRecord]] =
        Stream
          .eval(Sync[F].delay(UUID.randomUUID()))
          .flatMap(uuid =>
            readChunksFromKinesisStream(
              consumerConfig,
              defaultScheduler(
                consumerConfig,
                kinesisAsyncClient,
                dynamoDbAsyncClient,
                cloudWatchAsyncClient,
                uuid
              )
            )
          )
    }

  }
}
