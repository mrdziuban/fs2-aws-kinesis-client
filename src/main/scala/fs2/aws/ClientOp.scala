package fs2.aws

import cats.effect.Async
import java.util.concurrent.{CompletableFuture, CompletionException}

object clientOp {
  final class ClientOpPartialAp[F[_]](private val dummy: Boolean = false) extends AnyVal {
    def apply[A](fut: => CompletableFuture[A])(implicit F: Async[F]): F[A] =
      F.async_ { cb =>
        fut.handle[Unit] { case (a, x) =>
          if (a == null)
            x match {
              case t: CompletionException => cb(Left(t.getCause))
              case t => cb(Left(t))
            }
          else
            cb(Right(a))
        }
        ()
      }
  }

  def apply[F[_]]: ClientOpPartialAp[F] = new ClientOpPartialAp[F]
}
