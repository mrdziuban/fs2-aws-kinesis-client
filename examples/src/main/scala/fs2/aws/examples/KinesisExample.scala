package fs2.aws
package examples

import cats.effect.{Async, ExitCode, IO, IOApp, Resource, Sync}
import fs2.Stream
import fs2.aws.kinesis.{Kinesis, KinesisConsumerSettings}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder
import software.amazon.awssdk.services.kinesis.{KinesisAsyncClient, KinesisAsyncClientBuilder}
import software.amazon.awssdk.services.kinesis.model.{CreateStreamRequest, DeleteStreamRequest, PutRecordRequest}
import fs2.aws.examples.syntax._
import java.nio.charset.StandardCharsets
import cats.implicits._
import scala.concurrent.duration.DurationInt

object KinesisExample extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val appConfig = KinesisAppConfig.localstackConfig
    kAlgebraResource[IO](
      appConfig.kinesisSdkBuilder,
      appConfig.dynamoSdkBuilder,
      appConfig.cloudwatchSdkBuilder,
      appConfig.streamName
    ).use { case (kinesisClient, kinesis) =>
      (
        read[IO](kinesis, appConfig.consumerConfig),
        write[IO](kinesisClient, appConfig.consumerConfig)
      ).parTupled.as(ExitCode.Success)
    }
  }
  private def kAlgebraResource[F[_]: Async](
    kac: KinesisAsyncClientBuilder,
    dac: DynamoDbAsyncClientBuilder,
    cac: CloudWatchAsyncClientBuilder,
    streamName: String
  ) =
    for {
      k <- clientResource[F](kac)
      d <- clientResource[F](dac)
      c <- clientResource[F](cac)
      _ <- disposableStream(k, streamName)
    } yield (k, Kinesis.create[F](k, d, c))

  def write[F[_]: Async](
    kinesis: KinesisAsyncClient,
    consumerSettings: KinesisConsumerSettings
  ): F[Unit] =
    Stream
      .awakeEvery[F](5.seconds)
      .map(_.toString())
      .evalTap(d => Sync[F].delay(println(s"Producing $d")))
      .evalMap(d => clientOp[F](kinesis.putRecord(PutRecordRequest
        .builder
        .partitionKey(d)
        .streamName(consumerSettings.streamName)
        .data(SdkBytes.fromUtf8String(d))
        .build)))
      .compile
      .drain

  def read[F[_]: Sync](
    kinesis: Kinesis[F],
    consumerSettings: KinesisConsumerSettings
  ): F[Unit] =
    kinesis
      .readFromKinesisStream(consumerSettings)
      .map(cr => StandardCharsets.UTF_8.decode(cr.record.data()).toString)
      .evalTap(cr => Sync[F].delay(println(s"Consuming $cr")))
      .compile
      .drain

  private def disposableStream[F[_]: Async](
    client: KinesisAsyncClient,
    streamName: String
  ) =
    Resource.make(clientOp[F](
      client.createStream(CreateStreamRequest.builder.streamName(streamName).shardCount(1).build)
    ))(_ => clientOp[F](client.deleteStream(DeleteStreamRequest.builder.streamName(streamName).build)).void)
}
