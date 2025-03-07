package izumi.distage.injector

import distage._
import izumi.distage.fixtures.BasicCases.BasicCase1
import izumi.distage.fixtures.ResourceCases._
import izumi.distage.injector.ResourceEffectBindingsTest.Fn
import izumi.distage.model.definition.Lifecycle
import izumi.distage.model.effect.QuasiApplicative
import izumi.distage.model.exceptions.ProvisioningException
import izumi.distage.model.plan.Roots
import izumi.functional.bio.data.{Free, FreeError, FreePanic}
import izumi.fundamentals.platform.functional.Identity
import org.scalatest.GivenWhenThen
import org.scalatest.exceptions.TestFailedException
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable
import scala.util.Try

object ResourceEffectBindingsTest {
  final type Fn[+A] = Suspend2[Nothing, A]
  final type Ft[+A] = Suspend2[Throwable, A]
}

class ResourceEffectBindingsTest extends AnyWordSpec with MkInjector with GivenWhenThen {

  "Effect bindings" should {

    "work in a basic case in Identity monad" in {
      val definition = PlannerInput(
        new ModuleDef {
          make[Int].named("2").from(2)
          make[Int].fromEffect[Identity, Int] {
            i: Int @Id("2") => 10 + i
          }
        },
        Activation.empty,
        Roots(DIKey.get[Int]),
      )

      val injector = mkInjector()
      val plan = injector.plan(definition)
      val context = injector.produce(plan).unsafeGet()

      assert(context.get[Int] == 12)
    }

    "work in a basic case in Suspend2 monad" in {
      val definition = PlannerInput(
        new ModuleDef {
          make[Int].named("2").from(2)
          make[Int].fromEffect {
            i: Int @Id("2") => Suspend2(10 + i)
          }
        },
        Activation.empty,
        Roots(DIKey.get[Int]),
      )

      val injector = mkInjector()
      val plan = injector.plan(definition)

      val context = injector.produceCustomF[Suspend2[Throwable, ?]](plan).unsafeGet().unsafeRun()

      assert(context.get[Int] == 12)
    }

    "execute effects again in reference bindings" in {
      val execIncrement = (_: Ref[Fn, Int]).update(_ + 1)

      val definition = PlannerInput.everything(new ModuleDef {
        make[Ref[Fn, Int]].fromEffect(Ref[Fn](0))

        make[Fn[Int]].from(execIncrement)

        make[Int].named("1").refEffect[Fn, Int]
        make[Int].named("2").refEffect[Fn, Int]
      })

      val injector = mkInjector()
      val plan = injector.plan(definition)

      val context = injector.produceCustomF[Suspend2[Nothing, ?]](plan).unsafeGet().unsafeRun()

      assert(context.get[Int]("1") != context.get[Int]("2"))
      assert(Set(context.get[Int]("1"), context.get[Int]("2")) == Set(1, 2))
      assert(context.get[Ref[Fn, Int]].get.unsafeRun() == 2)
    }

    "support Identity effects in Suspend monad" in {
      val definition = PlannerInput(
        new ModuleDef {
          make[Int].named("2").from(2)
          make[Int].fromEffect[Identity, Int] {
            i: Int @Id("2") => 10 + i
          }
        },
        Activation.empty,
        Roots(DIKey.get[Int]),
      )

      val injector = mkInjector()
      val plan = injector.plan(definition)
      val context = injector.produceCustomF[Suspend2[Throwable, ?]](plan).unsafeGet().unsafeRun()

      assert(context.get[Int] == 12)
    }

    "work with set bindings" in {
      val definition = PlannerInput.everything(new ModuleDef {
        make[Ref[Fn, Set[Char]]].fromEffect(Ref[Fn](Set.empty[Char]))

        many[Char]
          .addEffect(Suspend2('a'))
          .addEffect(Suspend2('b'))

        make[Unit].fromEffect {
          (ref: Ref[Fn, Set[Char]], set: Set[Char]) =>
            ref.update(_ ++ set).void
        }
        make[Unit].named("1").fromEffect {
          ref: Ref[Fn, Set[Char]] =>
            ref.update(_ + 'z').void
        }
        make[Unit].named("2").fromEffect {
          (_: Unit, _: Unit @Id("1"), ref: Ref[Fn, Set[Char]]) =>
            ref.update(_.map(_.toUpper)).void
        }
      })

      val injector = mkInjector()
      val plan = injector.plan(definition)
      val context = injector.produceCustomF[Suspend2[Throwable, ?]](plan).unsafeGet().unsafeRun()

      assert(context.get[Set[Char]] == "ab".toSet)
      assert(context.get[Ref[Fn, Set[Char]]].get.unsafeRun() == "ABZ".toSet)
    }

  }

  "Resources" should {

    "deallocate correctly on error in .flatMap" in {
      import ResourceCase1._

      val ops1 = mutable.Queue.empty[Ops]

      Try {
        Lifecycle
          .makeSimple(ops1 += XStart)(_ => ops1 += XStop)
          .flatMap {
            _ =>
              throw new RuntimeException()
          }
          .use((_: Unit) => ())
      }

      assert(ops1 == Seq(XStart, XStop))

      val ops2 = mutable.Queue.empty[Ops]

      Try {
        Lifecycle
          .make(Suspend2(ops2 += XStart))(_ => Suspend2(ops2 += XStop).void)
          .flatMap {
            _ =>
              throw new RuntimeException()
          }
          .use((_: Unit) => Suspend2(()))
          .unsafeRun()
      }

      assert(ops2 == Seq(XStart, XStop))
    }

    "deallocate correctly on error in nested .flatMap" in {
      import ResourceCase1._

      val ops1 = mutable.Queue.empty[Ops]

      Try {
        Lifecycle
          .makeSimple(ops1 += XStart)(_ => ops1 += XStop)
          .flatMap {
            _ =>
              Lifecycle
                .makeSimple(ops1 += YStart)(_ => ops1 += YStop)
                .flatMap {
                  _ =>
                    Lifecycle.makeSimple[Unit](throw new RuntimeException())((_: Unit) => ops1 += ZStop)
                }
          }
          .use(_ => ())
      }

      assert(ops1 == Seq(XStart, YStart, YStop, XStop))

      val ops2 = mutable.Queue.empty[Ops]

      Try {
        Lifecycle
          .make(Suspend2(ops2 += XStart))(_ => Suspend2(ops2 += XStop).void)
          .flatMap {
            _ =>
              Lifecycle
                .make(Suspend2(ops2 += YStart))(_ => Suspend2(ops2 += YStop).void)
                .flatMap {
                  _ =>
                    Lifecycle.make(Suspend2[Unit](throw new RuntimeException()))((_: Unit) => Suspend2(ops2 += ZStop).void)
                }
          }
          .use(_ => Suspend2(()))
          .unsafeRun()
      }

      assert(ops2 == Seq(XStart, YStart, YStop, XStop))
    }

    "handler errors with catchAll" in {
      import ResourceCase1._

      val ops1 = mutable.Queue.empty[Ops]
      Try {
        Lifecycle
          .makeSimple[Unit](throw new Throwable())((_: Unit) => ops1 += XStop)
          .catchAll(_ => Lifecycle.makeSimple(ops1 += YStart)(_ => ops1 += YStop)).use(_ => ())
      }
      assert(ops1 == Seq(YStart, YStop))

      val ops2 = mutable.Queue.empty[Ops]
      Try {
        Lifecycle
          .make(Suspend2(ops2 += XStart))(_ => Suspend2(ops2 += XStop).void)
          .flatMap {
            _ =>
              throw new RuntimeException()
          }
          .catchAll(_ => Lifecycle.make(Suspend2[Unit](ops2 += YStart))(_ => Suspend2(ops2 += YStop).void))
          .use((_: Unit) => Suspend2(()))
          .unsafeRun()
      }
      assert(ops2 == Seq(XStart, YStart, YStop, XStop))
    }

    "recover from failures with redeem" in {
      import ResourceCase1._

      val ops1 = mutable.Queue.empty[Ops]
      val ops2 = mutable.Queue.empty[Ops]

      def redeemTest(err: Boolean) = {
        def action(q: mutable.Queue[Ops]): Unit = if (err) throw new Throwable() else q += RStart

        Try {
          Lifecycle
            .makeSimple[Unit](action(ops1))((_: Unit) => ops1 += XStop)
            .redeem(
              _ => Lifecycle.makeSimple(ops1 += YStart)(_ => ops1 += YStop),
              _ => Lifecycle.makeSimple(ops1 += ZStart)(_ => ops1 += ZStop),
            ).use(_ => ())
        }

        Try {
          Lifecycle
            .make(Suspend2(ops2 += XStart))(_ => Suspend2(ops2 += XStop).void)
            .flatMap(_ => Lifecycle.make(Suspend2[Unit](action(ops2)))(_ => Suspend2(ops2 += RStop).void))
            .redeem(
              onFailure = _ => Lifecycle.make(Suspend2[Unit](ops2 += YStart))(_ => Suspend2(ops2 += YStop).void),
              onSuccess = _ => Lifecycle.make(Suspend2[Unit](ops2 += ZStart))(_ => Suspend2(ops2 += ZStop).void),
            )
            .use((_: Unit) => Suspend2(()))
            .unsafeRun()
        }
      }

      redeemTest(true)
      assert(ops1 == Seq(YStart, YStop))
      assert(ops2 == Seq(XStart, YStart, YStop, XStop))

      ops1.clear()
      ops2.clear()

      redeemTest(false)
      assert(ops1 == Seq(RStart, ZStart, ZStop, XStop))
      assert(ops2 == Seq(XStart, RStart, ZStart, ZStop, RStop, XStop))
    }

  }

  "Resource bindings" should {

    "work in a basic case in Identity monad" in {
      import ClassResourceCase._

      val definition = PlannerInput.everything(new ModuleDef {
        make[Res].fromResource[SimpleResource]
      })

      val injector = mkInjector()
      val plan = injector.plan(definition)

      val instance = injector.produce(plan).use {
        context =>
          val instance = context.get[Res]
          assert(instance.initialized)
          instance
      }

      assert(!instance.initialized)
    }

    "work in a basic case in Suspend2 monad" in {
      import ClassResourceCase._

      val definition = PlannerInput.everything(new ModuleDef {
        make[Res].fromResource[SuspendResource]
      })

      val injector = mkInjector()
      val plan = injector.plan(definition)

      val instance = injector
        .produceCustomF[Suspend2[Throwable, ?]](plan).use {
          context =>
            val instance = context.get[Res]
            assert(instance.initialized)
            Suspend2(instance)
        }.unsafeRun()

      assert(!instance.initialized)
    }

    "work with set bindings" in {
      import izumi.distage.fixtures.ResourceCases.ClassResourceCase._

      val definition = PlannerInput.everything(new ModuleDef {
        many[Res]
          .addResource[SimpleResource]
          .addResource[SuspendResource]
      })

      val injector = mkInjector()
      val plan = injector.plan(definition)

      val resource = injector.produceCustomF[Suspend2[Throwable, ?]](plan)

      val set = resource
        .use {
          context =>
            Suspend2 {
              val set = context.get[Set[Res]]
              assert(set.size == 2)
              assert(set.forall(_.initialized == true))
              set
            }
        }.unsafeRun()

      assert(set.size == 2)
      assert(set.forall(_.initialized == false))
    }

    "incompatible effects error aborts interpreter before any work is done" in {
      import BasicCase1._

      val definition = PlannerInput.everything(new ModuleDef {
        make[NotInContext]
        make[TestClass]
        make[TestDependency3]
        make[TestDependency0].from[TestImpl0]
        make[TestDependency1]
        make[TestCaseClass]
        make[LocatorDependent]
        make[TestInstanceBinding].fromResource(new Lifecycle.Basic[Option, TestInstanceBinding] {
          override def acquire: Option[TestInstanceBinding] = None
          override def release(resource: TestInstanceBinding): Option[Unit] = None
        })
      })

      val injector = mkInjector()
      val plan = injector.plan(definition)

      val resource = injector.produceDetailedCustomF[Suspend2[Throwable, ?]](plan)

      val failure = resource
        .use {
          case Left(fail) =>
            Suspend2 {
              val nonSyntheticInstances = fail.failed.instances.filter(_._1 != DIKey.get[LocatorRef])
              assert(nonSyntheticInstances.isEmpty)
              fail
            }

          case Right(value) =>
            fail(s"Unexpected success! $value")
        }.unsafeRun()

      val exc = intercept[ProvisioningException] {
        failure.throwException().unsafeRun()
      }

      assert(exc.getMessage.contains("Incompatible effect types"))
    }

    "deallocate correctly in case of exceptions" in {
      import ResourceCase1._

      val definition = PlannerInput.everything(new ModuleDef {
        make[mutable.Queue[Ops]].fromEffect(queueEffect)
        make[X].fromResource[XResource]
        make[Y].fromResource[YResource]
        make[Z].fromResource[ZFaultyResource]
      })

      val injector = mkInjector()
      val plan = injector.plan(definition)

      val resource = injector
        .produceDetailedCustomF[Suspend2[Throwable, ?]](plan)
        .evalMap {
          case Left(failure) =>
            Suspend2 {
              val ops = failure.failed.instances(DIKey.get[mutable.Queue[Ops]]).asInstanceOf[mutable.Queue[Ops]]
              assert(ops.toSeq == Seq(XStart, YStart))
              ops
            }

          case Right(value) =>
            fail(s"Unexpected success! $value")
        }

      val ops = resource.use(ops => Suspend2(ops)).run().toOption.get

      assert(ops.toSeq == Seq(XStart, YStart, YStop, XStop))
    }

    "Display tag macro stack trace when ResourceTag is not found" in {
      val t = intercept[TestFailedException] {
        assertCompiles {
          """
          def x[F[_]]: ModuleDef = new ModuleDef {
            make[Any].fromResource[Lifecycle[F, Any]](() => ???)
          }; ""
          """
        }
      }

      assert(t.message.get contains "<trace>")
      assert(t.message.get contains "could not find implicit value for TagK[F]")
    }

    "can pass a block with inner method calls into Lifecycle.Of constructor (https://github.com/scala/bug/issues/11969)" in {
      final class XImpl
        extends Lifecycle.Of({
          def res = Lifecycle.make(Try(helper()))(_ => Try(()))

          def helper() = ()

          res
        })
      new XImpl().acquire.get
    }

    "obtain QuasiApplicative for BIO Free/FreeError/FreePanic" in {
      implicitly[QuasiApplicative[Free[Suspend2, Throwable, +?]]]
      implicitly[QuasiApplicative[FreeError[Suspend2, Throwable, +?]]]
      implicitly[QuasiApplicative[FreePanic[Suspend2, Throwable, +?]]]
    }

  }

}
