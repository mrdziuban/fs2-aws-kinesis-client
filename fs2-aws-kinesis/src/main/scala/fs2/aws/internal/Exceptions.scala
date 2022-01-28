package fs2.aws.internal

object Exceptions {
  sealed trait KinesisSettingsException extends Exception
  case class MaxBatchWaitException(override val getMessage: String) extends KinesisSettingsException
  case class MaxBatchSizeException(override val getMessage: String) extends KinesisSettingsException
}
