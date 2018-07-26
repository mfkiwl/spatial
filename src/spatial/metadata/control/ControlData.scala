package spatial.metadata.control

import argon._
import spatial.lang._

/** Control node schedule */
sealed abstract class CtrlSchedule
case object Sequenced extends CtrlSchedule
case object Pipelined extends CtrlSchedule
case object Streaming extends CtrlSchedule
case object ForkJoin extends CtrlSchedule
case object Fork extends CtrlSchedule

/** Control node level. */
sealed abstract class CtrlLevel
case object Inner extends CtrlLevel
case object Outer extends CtrlLevel

/** Control node looping. */
sealed abstract class CtrlLooping
case object Single extends CtrlLooping
case object Looped extends CtrlLooping

/** A controller's level in the control hierarchy. Flag marks whether this is an outer controller.
  *
  * Getter:  sym.rawLevel
  * Setter:  sym.rawLevel = (CtrlLevel)
  * Default: undefined
  */
case class ControlLevel(level: CtrlLevel) extends Data[ControlLevel](SetBy.Flow.Self)

/** A controller, counter, or counterchain's owning controller.
  *
  * Getter:  sym.owner
  * Setter:  sym.owner = (Sym[_])
  * Default: undefined
  */
case class CounterOwner(owner: Sym[_]) extends Data[CounterOwner](SetBy.Flow.Consumer)


/** The control schedule determined by the compiler.
  *
  * Option:  sym.getRawSchedule
  * Getter:  sym.rawSchedule
  * Setter:  sym.rawSchedule = (Sched)
  * Default: undefined
  */
case class ControlSchedule(sched: CtrlSchedule) extends Data[ControlSchedule](SetBy.Flow.Self)


/** The control schedule annotated by the user, if any.
  *
  * Option:  sym.getUserSchedule
  * Getter:  sym.userSchedule
  * Setter:  sym.userSchedule = (Sched)
  * Default: undefined
  */
case class UserScheduleDirective(sched: CtrlSchedule) extends Data[UserScheduleDirective](SetBy.User)


/** Metadata holding a list of children within a controller.
  *
  * Note that the type of a child is Ctrl.Node, not Ctrl, since a child cannot be the Host.
  *
  * Getter:  sym.rawChildren
  * Setter:  sym.rawChildren = (Seq[Ctrl.Node])
  * Default: Nil
  */
case class Children(children: Seq[Ctrl.Node]) extends Data[Children](SetBy.Flow.Self)

/** The controller (Ctrl) parent of a symbol within the controller hierarchy.
  *
  * Operations defined outside Accel always have the Host as their parent.
  * If the parent is not the Host, the id corresponds to the direct parent controller.
  * MemReduce, for example, has several isolated blocks which represent the logical accumulation
  * into the accumulator. The id in Ctrl will give the index into the logical stage for every
  * true future stage (e.g. the accumulation stage) and will be -1 for pseudo-stages.
  *
  * Getter:  sym.parent
  * Setter:  sym.parent = (Ctrl)
  * Default: Host
  */
case class ParentCtrl(parent: Ctrl) extends Data[ParentCtrl](SetBy.Flow.Consumer)

/** The scope (Ctrl) parent of a symbol within the controller hierarchy.
  *
  * The controller id corresponds to the logical stage index of the parent controller.
  * MemReduce, for example, has several isolated blocks which represent the logical accumulation
  * into the accumulator. The id in Controller will always give the index into the logical stage.
  *
  * Getter:  sym.scope
  * Setter:  sym.scope = (Ctrl)
  * Default: Host
  */
case class ScopeCtrl(scope: Scope) extends Data[ScopeCtrl](SetBy.Flow.Consumer)



/** The block ID (Blk) a symbol is defined within the IR.
  *
  * If the parent is not the Host, the id is the raw index into the parent controller's
  * list of .blocks where this symbol is defined.
  * MemReduce, for example, has several isolated blocks which represent the logical accumulation
  * into the accumulator. Blk will give the index into the specific block.
  *
  * Getter:  sym.blk
  * Setter:  sym.blk = (Blk)
  * Default: Host
  */
case class DefiningBlk(blk: Blk) extends Data[DefiningBlk](SetBy.Flow.Consumer)


/** The counter associated with a loop iterator. Only defined for loop iterators.
  *
  * Option:  sym.getCounter
  * Getter:  sym.counter
  * Setter:  sym.counter = (Counter)
  * Default: undefined
  */
case class IndexCounter(ctr: Counter[_]) extends Data[IndexCounter](SetBy.Flow.Consumer)


/** Latency of a given inner pipe body - used for control signal generation.
  *
  * Getter:  sym.bodyLatency
  * Setter:  sym.bodyLatency = (Seq[Double])
  * Default: Nil
  */
case class BodyLatency(latency: Seq[Double]) extends Data[BodyLatency](SetBy.Analysis.Self)


/** Initiation interval of a given controller - used for control signal generation.
  *
  * Getter:  sym.II
  * Setter:  sym.II = (Double)
  * Default: 1.0
  */
case class InitiationInterval(interval: Double) extends Data[InitiationInterval](SetBy.Analysis.Self)


/** User-defined initiation interval of a given controller.
  *
  * Option:  sym.userII
  * Setter:  sym.userII = (Option[Double])
  * Default: None
  */
case class UserII(interval: Double) extends Data[UserII](SetBy.User)


/** Memories which are written in a given controller.
  *
  * Getter: sym.writtenMems
  * Setter: sym.writtenMems = (Set[ Sym[_] ])
  * Default: empty set
  */
case class WrittenMems(mems: Set[Sym[_]]) extends Data[WrittenMems](SetBy.Flow.Self)

/** Memories which are read in a given controller.
  *
  * Getter: sym.readMems
  * Setter: sym.readMems = (Set[ Sym[_] ])
  * Default: empty set
  */
case class ReadMems(mems: Set[Sym[_]]) extends Data[ReadMems](SetBy.Flow.Self)
