package izumi.functional.bio.laws.env

import cats.Eq
import cats.effect.testkit.TestInstances

import scala.concurrent.ExecutionException

trait EqThrowable { this: TestInstances =>
  implicit override lazy val eqThrowable: Eq[Throwable] = new Eq[Throwable] {
    override def eqv(x: Throwable, y: Throwable): Boolean = {
      val ex1 = extractEx(x)
      val ex2 = extractEx(y)
      val result = ex1.getClass == ex2.getClass && ex1.getMessage == ex2.getMessage
      result
    }

    // Unwraps exceptions that got caught by Future's implementation
    // and that got wrapped in ExecutionException (`Future(throw ex)`)
    private def extractEx(ex: Throwable): Throwable =
      ex match {
        case ref: ExecutionException =>
          Option(ref.getCause).getOrElse(ref)
        case _ =>
          ex
      }
  }
}
