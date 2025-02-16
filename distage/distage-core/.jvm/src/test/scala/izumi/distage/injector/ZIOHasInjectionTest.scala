package izumi.distage.injector

import distage.Id
import izumi.distage.compat.ZIOTest
import izumi.distage.constructors.ZEnvConstructor
import izumi.distage.fixtures.TraitCases.*
import izumi.distage.fixtures.TypesCases.*
import izumi.distage.model.PlannerInput
import izumi.distage.model.definition.ModuleDef
import izumi.distage.model.reflection.TypedRef
import izumi.functional.lifecycle.Lifecycle
import izumi.fundamentals.platform.assertions.ScalatestGuards
import org.scalatest.wordspec.AnyWordSpec
import zio.*

import scala.annotation.nowarn
import scala.util.Try

@nowarn("msg=reflectiveSelectable")
class ZIOHasInjectionTest extends AnyWordSpec with MkInjector with ZIOTest with ScalatestGuards {

  import izumi.distage.fixtures.TraitCases.TraitCase2.*

  type HasInt = Int
  type HasX[B] = B
  type HasIntBool = HasInt & HasX[Boolean]

  def trait1(d1: Dependency1): Trait1 = new Trait1 { override def dep1: Dependency1 = d1 }

  def getDep1: URIO[Dependency1, Dependency1] = ZIO.service[Dependency1]
  def getDep2: URIO[Dependency2, Dependency2] = ZIO.service[Dependency2]

  final class ResourceHasImpl()
    extends Lifecycle.LiftF(for {
      d1 <- getDep1
      d2 <- getDep2
    } yield new Trait2 { val dep1 = d1; val dep2 = d2 })

  final class ResourceEmptyHasImpl(
    d1: Dependency1
  ) extends Lifecycle.LiftF[UIO, Trait1](
      ZIO.succeed(trait1(d1))
    )

  "ZEnvConstructor" should {

    "construct Has with tricky type aliases" in {
      val hasCtor = ZEnvConstructor[HasIntBool & Any].get

      val value = hasCtor.unsafeApply(Seq(TypedRef(5), TypedRef(false))).asInstanceOf[ZEnvironment[HasIntBool]]

      assert(value.get[Int] == 5)
      assert(value.get[Boolean] == false)
    }

    "handle empty Has (Any)" in {
      import TypesCase1.*

      val definition = new ModuleDef {
        make[Dep].from[DepA]
        make[TestClass2[Dep]].fromZIOEnv {
          (value: Dep) => ZIO.attempt(TestClass2(value))
        }
        make[TestClass2[Dep]].named("noargs").fromZIOEnv(ZIO.attempt(TestClass2(new DepA: Dep)))
      }

      val injector = mkNoCyclesInjector()
      val plan = injector.planUnsafe(PlannerInput.everything(definition))

      val context = unsafeRun(injector.produceCustomF[Task](plan).unsafeGet())

      val instantiated1 = context.get[TestClass2[Dep]]
      assert(instantiated1.isInstanceOf[TestClass2[Dep]])
      assert(instantiated1.inner != null)
      assert(instantiated1.inner eq context.get[Dep])

      val instantiated2 = context.get[TestClass2[Dep]]("noargs")
      assert(instantiated2.isInstanceOf[TestClass2[Dep]])
      assert(instantiated2.inner != null)
      assert(instantiated2.inner ne context.get[Dep])
    }

    "handle one-arg fromZEnv" in {
      import TypesCase1.*

      val definition = new ModuleDef {
        make[Dep].from[DepA]
        make[TestClass2[Dep]].fromZIOEnv(ZIO.environmentWithZIO {
          (value: ZEnvironment[Dep]) =>
            ZIO.attempt(TestClass2(value.get))
        })
      }

      val injector = mkNoCyclesInjector()
      val plan = injector.planUnsafe(PlannerInput.everything(definition))

      val context = unsafeRun(injector.produceCustomF[Task](plan).unsafeGet())
      val instantiated = context.get[TestClass2[Dep]]
      assert(instantiated.isInstanceOf[TestClass2[Dep]])
      assert(instantiated.inner != null)
      assert(instantiated.inner eq context.get[Dep])
    }

    "progression test: since ZIO 2 can't support named bindings in zio.ZEnvironment type parameters" in {
      import TypesCase1.*

      val ctorA: ZIO[Dep @Id("A"), Nothing, TestClass2[Dep]] = ZIO.serviceWith[Dep @Id("A")] {
        (value: Dep @Id("A")) => TestClass2(value)
      }
      val ctorB = ZIO.serviceWith[Dep @Id("B")] {
        (value: Dep @Id("B")) => TestClass2(value)
      }

      val definition = PlannerInput.everything(new ModuleDef {
        make[Dep].named("A").from[DepA]
        make[Dep].named("B").from[DepB]
        make[TestClass2[Dep]].named("A").fromZIOEnv[Dep @Id("A"), Nothing, TestClass2[Dep]](ctorA)
        make[TestClass2[Dep]].named("B").fromZIOEnv(ctorB)
      })

      val injector = mkInjector()
      val plan = injector.planUnsafe(definition)

      val t = Try {
        val context = unsafeRun(injector.produceCustomF[Task](plan).unsafeGet())

        val instantiated = context.get[TestClass2[Dep]]("A")
        assert(instantiated.inner.isInstanceOf[DepA])
        assert(!instantiated.inner.isInstanceOf[DepB])

        val instantiated1 = context.get[TestClass2[Dep]]("B")
        assert(instantiated1.inner.isInstanceOf[DepB])
        assert(!instantiated1.inner.isInstanceOf[DepA])
      }
      assert(t.failed.get.getMessage.contains("Found other bindings for the same type (did you forget to add or remove `@Id` annotation?)"))
    }

    "progression test: since ZIO 2 can't support multiple named bindings in zio.ZEnvironment with & without a type signature" in {
      import TypesCase1.*

      // even a type signature won't help right now
      val ctorAB: ZIO[(DepA @Id("A")) & (DepB @Id("B")), Nothing, TestClass3[Dep]] = for {
        a <- ZIO.service[DepA @Id("A")]
        b <- ZIO.service[DepB @Id("B")]
      } yield TestClass3[Dep](a, b)

      val definition = PlannerInput.everything(new ModuleDef {
        make[DepA].named("A").from[DepA]
        make[DepB].named("B").from[DepB]
        make[TestClass3[Dep]].fromZIOEnv(ctorAB)
      })

      val injector = mkInjector()
      val plan = injector.planUnsafe(definition)

      // On Scala 2, extracting R from `Scope with R` is destroying annotations.
      // On Scala 3 it's even worse, even without Scope with R, I think we're not even getting the annotated type into
      //   the ZEnvConstructor macro on Scala 3 - it's widened it's passed to macro...
      val t = Try {
        val context = unsafeRun(injector.produceCustomF[Task](plan).unsafeGet())

        val instantiated = context.get[TestClass3[Dep]]
        assert(instantiated.a.isInstanceOf[DepA])
        assert(!instantiated.a.isInstanceOf[DepB])
        assert(instantiated.b.isInstanceOf[DepB])
        assert(!instantiated.b.isInstanceOf[DepA])
      }
      assert(t.failed.get.getMessage.contains("Found other bindings for the same type (did you forget to add or remove `@Id` annotation?)"))
    }

    "handle multi-parameter Has with mixed args & env injection and a refinement return" in {
      import TraitCase2.*
      import scala.language.reflectiveCalls

      def getDep1: URIO[Dependency1, Dependency1] = ZIO.environmentWith[Dependency1](_.get)
      def getDep2: URIO[Dependency2, Dependency2] = ZIO.environmentWith[Dependency2](_.get)

      val definition = PlannerInput.everything(new ModuleDef {
        make[Dependency1]
        make[Dependency2]
        make[Dependency3]
        make[Trait3 { def acquired: Boolean }].fromZIOEnv(
          (d3: Dependency3) =>
            for {
              d1 <- getDep1
              d2 <- getDep2
              res: (Trait3 { def acquired: Boolean; def acquired_=(b: Boolean): Unit }) @unchecked = new Trait3 {
                override val dep1 = d1
                override val dep2 = d2
                override val dep3 = d3
                var acquired = false
              }
              _ <- ZIO.acquireRelease(
                ZIO.succeed(res.acquired = true)
              )(_ => ZIO.succeed(res.acquired = false))
            } yield res
        )

        make[Trait2].fromZIOEnv(for {
          d1 <- ZIO.service[Dependency1]
          d2 <- ZIO.service[Dependency2]
        } yield new Trait2 { val dep1 = d1; val dep2 = d2 })

        make[Trait1].fromZLayerEnv {
          (d1: Dependency1) =>
            ZLayer.succeed(new Trait1 { val dep1 = d1 })
        }

        make[Trait2].named("classbased").fromZEnvResource[ResourceHasImpl]
        make[Trait1].named("classbased").fromZEnvResource[ResourceEmptyHasImpl]

        many[Trait2].addZEnvResource[ResourceHasImpl]
        many[Trait1].addZEnvResource[ResourceEmptyHasImpl]
      })

      val injector = mkNoCyclesInjector()
      val plan = injector.planUnsafe(definition)

      val instantiated = unsafeRun(injector.produceCustomF[Task](plan).use {
        context =>
          ZIO.succeed {

            assert(context.find[Trait3].isEmpty)

            val instantiated = context.get[Trait3 { def acquired: Boolean }]

            assert(instantiated.dep1 eq context.get[Dependency1])
            assert(instantiated.dep2 eq context.get[Dependency2])
            assert(instantiated.dep3 eq context.get[Dependency3])
            assert(instantiated.acquired)

            val instantiated10 = context.get[Trait2]
            assert(instantiated10.dep2 eq context.get[Dependency2])

            val instantiated2 = context.get[Trait1]
            assert(instantiated2 ne null)

            val instantiated3 = context.get[Trait2]("classbased")
            assert(instantiated3.dep2 eq context.get[Dependency2])

            val instantiated4 = context.get[Trait1]("classbased")
            assert(instantiated4 ne null)

            val instantiated5 = context.get[Set[Trait2]].head
            assert(instantiated5.dep2 eq context.get[Dependency2])

            val instantiated6 = context.get[Set[Trait1]].head
            assert(instantiated6 ne null)

            instantiated
          }
      })
      assert(!instantiated.acquired)
    }

    "can handle AnyVals" in {
      import TraitCase6.*

      val definition = PlannerInput.everything(new ModuleDef {
        make[Dep]
        make[AnyValDep]
        make[TestTrait].fromZIOEnv(
          ZIO.environmentWith[AnyValDep](
            h =>
              new TestTrait {
                override val anyValDep: AnyValDep = h.get
              }
          )
        )
      })

      val injector = mkInjector()
      val plan = injector.planUnsafe(definition)
      val context = unsafeRun(injector.produceCustomF[Task](plan).unsafeGet())

      assert(context.get[TestTrait].anyValDep ne null)
      // AnyVal reboxing happened
      assert(context.get[TestTrait].anyValDep ne context.get[AnyValDep].asInstanceOf[AnyRef])
      assert(context.get[TestTrait].anyValDep.d eq context.get[Dep])
    }

    "Scala 3 regression test: support more than 2 dependencies in ZEnvConstructor" in {
      trait OpenTracingService

      trait SttpBackend[F[_], +P]

      trait MyEndpoints[F[_, _]]

      trait ZioStreams

      trait MyPublisher
      trait MyClient

      object MyPlugin extends ModuleDef {
        make[MyClient].fromZIOEnv {
          ZIO.succeed(???): ZIO[OpenTracingService & MyPublisher & SttpBackend[Task, ZioStreams] & MyEndpoints[IO], Nothing, MyClient]
        }
      }
      val _ = MyPlugin
    }

  }

}
