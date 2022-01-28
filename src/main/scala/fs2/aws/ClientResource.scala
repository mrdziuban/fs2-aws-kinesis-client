package fs2.aws

import cats.effect.{Resource, Sync}
import software.amazon.awssdk.core.client.builder.SdkClientBuilder

object clientResource {
  final class ClientResourcePartialAp[F[_]](private val dummy: Boolean = false) extends AnyVal {
    def apply[B <: SdkClientBuilder[B, C], C <: AutoCloseable](
      builder: SdkClientBuilder[B, C]
    )(implicit F: Sync[F]): Resource[F, C] =
      Resource.fromAutoCloseable(F.delay(builder.build))
  }

  def apply[F[_]]: ClientResourcePartialAp[F] = new ClientResourcePartialAp[F]
}
