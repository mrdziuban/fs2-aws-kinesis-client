package fs2.aws.utils

import cats.effect.Async
import java.util.concurrent.{CompletableFuture, CompletionException}

final class CompletableFutureEff[F[_]] {
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

object CompletableFutureEff {
  def apply[F[_]]: CompletableFutureEff[F] = new CompletableFutureEff[F]
}
