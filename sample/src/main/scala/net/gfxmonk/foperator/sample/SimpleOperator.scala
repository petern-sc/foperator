package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator.sample.Models._
import net.gfxmonk.foperator.skuberengine.Skuber
import net.gfxmonk.foperator.skuberengine.implicits._
import skuber.apiextensions.CustomResourceDefinition

object SimpleOperator extends TaskApp {
  override def run(args: List[String]): Task[ExitCode] = {
    new SimpleOperator(Skuber()).run.as(ExitCode.Success)
  }

  // These would commonly be defined on `class SimpleOperator` instead,
  // but we want to expose them for reuse by AdvancedOperator
  def expectedStatus(greeting: Greeting): GreetingStatus =
    GreetingStatus(s"Hello, ${greeting.spec.name.getOrElse("UNKNOWN")}", people = Nil)

  val reconciler = Skuber.Reconciler[Greeting].status { greeting =>
    // Always return the expected status, Reconciler.customResourceUpdater
    // will make this a no-op without any API calls if it is unchanged.
    Task.pure(expectedStatus(greeting))
  }
}

class SimpleOperator[C](client: Skuber) {
  import SimpleOperator._

  def install = {
    client.ops[CustomResourceDefinition].forceWrite(greetingCrd).void
  }

  def run: Task[ExitCode] = {
    install >> client.ops[Greeting].runReconciler(reconciler).as(ExitCode.Success)
  }
}
