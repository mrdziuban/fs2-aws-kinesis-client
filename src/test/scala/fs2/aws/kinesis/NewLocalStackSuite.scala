package fs2.aws
package kinesis

import cats.effect.unsafe.IORuntime
import cats.effect.{ IO, Resource }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Minutes, Second, Span }
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.{
  CloudWatchAsyncClient,
  CloudWatchAsyncClientBuilder
}
import software.amazon.awssdk.services.dynamodb.{ DynamoDbAsyncClient, DynamoDbAsyncClientBuilder }
import software.amazon.awssdk.services.kinesis.model.{
  CreateStreamRequest,
  DeleteStreamRequest,
  DescribeStreamRequest,
  UpdateShardCountRequest
}
import software.amazon.awssdk.services.kinesis.{ KinesisAsyncClient, KinesisAsyncClientBuilder }
import software.amazon.kinesis.common.InitialPositionInStream

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class NewLocalStackSuite extends AnyFlatSpec with Matchers with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val runtime: IORuntime   = IORuntime.global

  // this is required to make the KCL work with LocalStack
  System.setProperty("aws.cborEnabled", "false")
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(2, Minutes)), interval = scaled(Span(1, Second)))

  val streamName = "test"
  val partitionKey = "test"

  val consumerConfig = KinesisConsumerSettings(
    streamName,
    "test-app",
    initialPositionInStream = Left(InitialPositionInStream.TRIM_HORIZON),
    retrievalMode = Polling
  )

  val cp = StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy"))

  val kac = KinesisAsyncClient
    .builder()
    .credentialsProvider(cp)
    .region(Region.US_EAST_1)
    .endpointOverride(URI.create("http://localhost:4566"))
  val dac = DynamoDbAsyncClient
    .builder()
    .credentialsProvider(cp)
    .region(Region.US_EAST_1)
    .endpointOverride(URI.create("http://localhost:4566"))
  val cac =
    CloudWatchAsyncClient
      .builder()
      .credentialsProvider(cp)
      .region(Region.US_EAST_1)
      .endpointOverride(URI.create("http://localhost:4566"))

  "The Kinesis consumer" should "be able to consume from LocalStack" in {

    val data = List("foo", "bar", "baz")

    val test = kAlgebraResource(kac, dac, cac).use { case (_, kAlgebra) =>
      kAlgebra
        .readFromKinesisStream(consumerConfig)
        .take(data.length.toLong)
        .compile
        .toList
    }
    val records = test.unsafeToFuture().futureValue

    val actualPartitionKeys = records.map(_.record.partitionKey())
    val actualData          = records.map(deserialiseData)
    actualPartitionKeys shouldBe (1 to data.length).map(_ => partitionKey)
    actualData          shouldBe data
  }

  "The Kinesis fs2 Stream" should "seamlessly consume from re-sharded stream " ignore {

    val data = List("foo", "bar", "baz")
    val sn   = "resharding_test"
    val consumerConfig = KinesisConsumerSettings(
      sn,
      "test-app",
      initialPositionInStream = Left(InitialPositionInStream.TRIM_HORIZON),
      retrievalMode = Polling
    )
    val resource = for {
      r @ (kAsyncInterpreter, _) <- kAlgebraResource(kac, dac, cac)
      _ <- Resource.make(
            clientOp[IO](kAsyncInterpreter.createStream(
              CreateStreamRequest.builder().streamName(sn).shardCount(1).build()
            ))
          )(_ =>
            clientOp[IO](kAsyncInterpreter
              .deleteStream(DeleteStreamRequest.builder().streamName(sn).build())
            ).void
          )
    } yield r

    val test = resource.use {
      case (kAsyncInterpreter, kStreamInterpreter) =>
        for {
          _ <- clientOp[IO](kAsyncInterpreter.updateShardCount(
                UpdateShardCountRequest.builder().streamName(sn).targetShardCount(2).build()
              ))
          _ <- fs2.Stream
                .retry(
                  clientOp[IO](kAsyncInterpreter
                    .describeStream(
                      DescribeStreamRequest.builder().streamName(sn).build()
                    )
                  ).flatMap { r =>
                    if (r.streamDescription().shards().size() != 2)
                      IO.raiseError(new RuntimeException("Expected 2 shards"))
                    else IO.unit
                  },
                  2 seconds,
                  _.*(2),
                  5,
                  _.getMessage == "Expected 2 shards"
                )
                .compile
                .drain
          record <- kStreamInterpreter
                     .readFromKinesisStream(consumerConfig)
                     .take((data.length * 2).toLong)
                     .compile
                     .toList

        } yield record

    }
    val records = test.unsafeToFuture().futureValue

    val actualPartitionKeys = records.map(_.record.partitionKey())
    val actualData          = records.map(deserialiseData)
    actualPartitionKeys shouldBe (1 to data.length).map(_ => partitionKey)
    actualData          shouldBe data
  }

  private def deserialiseData(committable: CommittableRecord) =
    StandardCharsets.UTF_8.decode(committable.record.data()).toString

  private def kAlgebraResource(
    kac: KinesisAsyncClientBuilder,
    dac: DynamoDbAsyncClientBuilder,
    cac: CloudWatchAsyncClientBuilder
  ): Resource[IO, (KinesisAsyncClient, Kinesis[IO])] =
    for {
      k <- clientResource[IO](kac)
      d <- clientResource[IO](dac)
      c <- clientResource[IO](cac)
    } yield (k, Kinesis.create[IO](k, d, c))
}
