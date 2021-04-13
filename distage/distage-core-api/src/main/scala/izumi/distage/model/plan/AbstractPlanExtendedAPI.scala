package izumi.distage.model.plan

import izumi.distage.model.plan.ExecutableOp.ImportDependency
import izumi.distage.model.reflection._
import izumi.reflect.Tag

private[plan] trait AbstractPlanExtendedAPI[OpType <: ExecutableOp] extends Any { this: AbstractPlan[OpType] =>
  /**
    * Get all imports (unresolved dependencies).
    *
    * Note, presence of imports does not *always* mean
    * that a plan is invalid, imports may be fulfilled by a parent
    * `Locator`, by BootstrapContext, or they may be materialized by
    * a custom [[izumi.distage.model.provisioning.strategies.ImportStrategy]]
    *
    * @see [[izumi.distage.model.plan.impl.OrderedPlanOps#assertValidOrThrow]] for a check you can use in tests
    */
  final def getImports: Seq[ImportDependency] =
    steps.collect { case i: ImportDependency => i }

  final def keys: Set[DIKey] = {
    steps.map(_.target).toSet
  }

  final def filter[T: Tag]: Seq[ExecutableOp] = {
    steps.filter(_.target == DIKey.get[T])
  }

  final def collectChildren[T: Tag]: Seq[ExecutableOp] = {
    val tpe = SafeType.get[T]
    steps.filter(op => op.instanceType <:< tpe)
  }

  final def collectChildrenKeys[T: Tag]: Set[DIKey] = {
    val tpe = SafeType.get[T]
    steps.iterator.collect {
      case op if op.instanceType <:< tpe => op.target
    }.toSet
  }

  final def collectChildrenKeysSplit[T1, T2](implicit t1: Tag[T1], t2: Tag[T2]): (Set[DIKey], Set[DIKey]) = {
    if (t1.tag == t2.tag) {
      (collectChildrenKeys[T1], Set.empty)
    } else {
      val tpe1 = SafeType.get[T1]
      val tpe2 = SafeType.get[T2]

      val res1 = Set.newBuilder[DIKey]
      val res2 = Set.newBuilder[DIKey]

      steps.foreach {
        op =>
          if (op.instanceType <:< tpe1) {
            res1 += op.target
          } else if (op.instanceType <:< tpe2) {
            res2 += op.target
          }
      }
      (res1.result(), res2.result())
    }
  }

  final def foldLeft[T](z: T, f: (T, ExecutableOp) => T): T = {
    steps.foldLeft(z)(f)
  }

}
