package spatial.util

import argon._
import spatial.data._
import spatial.lang._
import spatial.node._
import spatial.internal.spatialConfig
import spatial.issues.AmbiguousMetaPipes
import forge.tags._
import utils.implicits.collections._
import utils.Tree

import scala.util.Try

trait UtilsControl {

  /** True if the given symbol is allowed to be defined on the Host and used in Accel
    * This is true for the following types:
    *   - Global (shared) memories (e.g. DRAM, ArgIn, ArgOut, HostIO, StreamIn, StreamOut)
    *   - Bit-based types (if ArgIn inference is allowed)
    *   - Text (staged strings) (TODO: only allowed in debug mode?)
    */
  def allowSharingBetweenHostAndAccel(s: Sym[_], allowArgInference: Boolean): Boolean = {
    s.isRemoteMem ||
    (s.isBits && allowArgInference) ||
    s.isInstanceOf[Text]
  }


  implicit class ControlOpOps(op: Op[_]) {
    /** True if this Op is a loop which has a body which is run multiple times at runtime. */
    def isLoop: Boolean = op match {
      case loop: Loop[_] => !loop.cchains.forall(_._1.willFullyUnroll)
      case _ => false
    }
    def isFullyUnrolledLoop: Boolean = op match {
      case loop: Loop[_] => loop.cchains.forall(_._1.willFullyUnroll)
      case _ => false
    }
  }

  abstract class CtrlHierarchyOps(s: Option[Sym[_]]) {
    private def op: Option[Op[_]] = s.flatMap{sym => sym.op : Option[Op[_]] }

    /** True if this is a controller or the Host control. */
    def isControllerOrHost: Boolean

    /** Returns the nearest controller at or above the current symbol or controller.
      * Identity for Ctrl instances.
      */
    def toCtrl: Ctrl

    /** Returns the level of this controller (Outer or Inner). */
    def level: CtrlLevel = toCtrl match {
      case ctrl @ Ctrl.Node(sym,_) if sym.isRawOuter && ctrl.mayBeOuterBlock => Outer
      case Ctrl.Host => Outer
      case _         => Inner
    }

    /** Returns whether this control node is a Looped control or Single iteration control.
      * Nodes which will be fully unrolled are considered Single control.
      */
    def looping: CtrlLooping = if (op.exists(_.isLoop)) Looped else Single

    def schedule: CtrlSchedule = {
      val ctrlLoop  = this.looping
      val ctrlLevel = this.level
      s.map(_.rawSchedule) match {
        case None => Sequenced
        case Some(actualSchedule) => (ctrlLoop, ctrlLevel) match {
          case (Looped, Inner) => actualSchedule
          case (Looped, Outer) => actualSchedule
          case (Single, Inner) => actualSchedule match {
            case Sequenced => Sequenced
            case Pipelined => Sequenced
            case Streaming => Streaming
            case ForkJoin  =>  ForkJoin
            case Fork => Fork
          }
          case (Single, Outer) => actualSchedule match {
            case Sequenced => Sequenced
            case Pipelined => Sequenced
            case Streaming => Streaming
            case ForkJoin  => ForkJoin
            case Fork => Fork
          }
        }
      }
    }

    def isCtrl(
      loop:  CtrlLooping = null,
      level: CtrlLevel = null,
      sched: CtrlSchedule = null
    ): Boolean = {
      val isCtrl  = this.isControllerOrHost
      if (!isCtrl) false
      else {
        val _loop  = Option(loop)
        val _level = Option(level)
        val _sched = Option(sched)
        val ctrlLoop  = this.looping
        val ctrlLevel = this.level
        val ctrlSched = this.schedule

        val hasLoop = _loop.isEmpty || _loop.contains(ctrlLoop)
        val hasLevel = _level.isEmpty || _level.contains(ctrlLevel)
        val hasSched = _sched.isEmpty || _sched.contains(ctrlSched)

        isCtrl && hasLoop && hasLevel && hasSched
      }
    }

    /** True if this node will become a fully unrolled loop at unrolling time. */
    def isFullyUnrolledLoop: Boolean = op.exists(_.isFullyUnrolledLoop)

    /** True if this is a control block which is not run iteratively. */
    def isSingleControl: Boolean = isCtrl(loop = Single)

    /** True if this is a loop whose body will be executed for multiple iterations at runtime.
      * False for fully unrolled loops and UnitPipe.
      */
    def isLoopControl: Boolean = isCtrl(loop = Looped)

    /** True if this is an inner scope Ctrl stage or symbol. */
    def isInnerControl: Boolean = isCtrl(level = Inner)
    /** True if this is an outer scope Ctrl stage or symbol. */
    def isOuterControl: Boolean = isCtrl(level = Outer)

    /** True if this is a sequential controller. */
    def isSeqControl: Boolean = isCtrl(sched = Sequenced)
    /** True if this is a pipelined controller. */
    def isPipeControl: Boolean = isCtrl(sched = Pipelined)
    /** True if this is a streaming scheduled controller. */
    def isStreamControl: Boolean = isCtrl(sched = Streaming)

    /** True if this is an inner, sequential controller. */
    def isInnerSeqControl: Boolean = isCtrl(level = Inner, sched = Sequenced)

    /** True if this is an outer streaming controller.
      * (Note that all streaming controllers should be outer.)
      */
    def isOuterStreamControl: Boolean = isCtrl(level = Outer, sched = Streaming)

    /** True if this is an outer pipelined controller.
      * (Note that all outer pipelined controllers are loops.)
      */
    def isOuterPipeControl: Boolean = isCtrl(level = Outer, sched = Pipelined)

    /** True if this is an inner controller for a loop. */
    def isInnerLoop: Boolean = isCtrl(loop = Looped, level = Inner)
    /** True if this is an outer controller for a loop. */
    def isOuterLoop: Boolean = isCtrl(loop = Looped, level = Outer)

    /** True if this is a sequential controller for a loop. */
    def isSeqLoop: Boolean = isCtrl(loop = Looped, sched = Sequenced)

    /** True if this is a pipelined controller for a loop. */
    def isPipeLoop: Boolean = isCtrl(loop = Looped, sched = Pipelined)

    /** True if this is an inner pipelined controller for a loop. */
    def isInnerPipeLoop: Boolean = isCtrl(loop = Looped, level = Inner, sched = Pipelined)

    /** True if this is an outer pipelined controller for a loop. */
    def isOuterPipeLoop: Boolean = isCtrl(loop = Looped, level = Outer, sched = Pipelined)

    /** True if this is an outer streaming controller for a loop. */
    def isOuterStreamLoop: Boolean = isCtrl(loop = Looped, level = Outer, sched = Streaming)


    // --- Control Looping / Conditional Execution Functions --- //

    /** True if this controller, counterchain, or counter is statically known to run for an
      * infinite number of iterations. */
    def isForever: Boolean = op match {
      case Some(op: Control[_]) => op.cchains.exists(_._1.isForever)
      case Some(op: CounterChainNew) => op.counters.exists(_.isForever)
      case Some(_: ForeverNew) => true
      case _ => false
    }

    /** True if this controller, counterchain, or counter is statically known to run forever.
      * Also true if any of this controller's descendants will run forever.
      */
    @stateful def willRunForever: Boolean = isForever || children.exists(_.willRunForever)

    /** True if this control or symbol is a loop or occurs within a loop. */
    def willRunMultiple: Boolean = s.exists(_.isLoopControl) || hasLoopAncestor

    /** True if this symbol or Ctrl block takes enables as inputs. */
    def takesEnables: Boolean = op.exists{
      case _:EnPrimitive[_] | _:EnControl[_] => true
      case _ => false
    }

    // --- Control hierarchy --- //

    /** Returns the controller or Host which is the direct controller parent of this controller. */
    def parent: Ctrl

    /** Returns a sequence of all controllers and subcontrollers which are direct children in the
      * control hierarchy of this symbol or controller.
      */
    @stateful def children: Seq[Ctrl.Node]

    /** Returns all ancestors of the controller or symbol.
      * Ancestors are ordered outermost to innermost
      */
    def ancestors: Seq[Ctrl] = Tree.ancestors(this.toCtrl){_.parent}

    /** Returns all ancestors of the controller or symbol, stopping when stop is true (exclusive).
      * Ancestors are ordered outermost to innermost
      */
    def ancestors(stop: Ctrl => Boolean): Seq[Ctrl] = Tree.ancestors(toCtrl, stop){_.parent}

    /** Returns all ancestors of the controller or symbol, stopping at `stop` (exclusive).
      * Ancestors are ordered outermost to innermost
      */
    def ancestors(stop: Ctrl): Seq[Ctrl] = Tree.ancestors[Ctrl](toCtrl, {c => c == stop}){_.parent}

    /** True if this control or symbol has the given ancestor controller. */
    def hasAncestor(ctrl: Ctrl): Boolean = ancestors.contains(ctrl)


    /** True if this control or symbol occurs within a loop. */
    def hasLoopAncestor: Boolean = ancestors.exists(_.isLoopControl)


    // --- Streaming Controllers --- //

    /** True if this controller or symbol has a streaming controller ancestor. */
    def hasStreamAncestor: Boolean = ancestors.exists(_.isStreamControl)
    /** True if this controller or symbol has a streaming controller parent. */
    def hasStreamParent: Boolean = toCtrl.parent.isStreamControl

    /** True if this controller or symbol has an ancestor which runs forever. */
    def hasForeverAncestor: Boolean = ancestors.exists(_.isForever)

    /** True if this is an inner controller which directly contains
      * stream enablers/holder accesses.
      */
    def hasStreamAccess: Boolean = isInnerControl && (op match {
      case Some(o) => o.blocks.flatMap(_.nestedStms).exists{ sym =>
        sym.isStreamStageEnabler || sym.isStreamStageHolder
      }
      case None => false
    })


    /** Returns a list of all counterhains in this controller. */
    def cchains: Seq[CounterChain] = op match {
      case Some(op: Control[_]) => op.cchains.map(_._1).distinct
      case _ => Nil
    }
  }


  abstract class ScopeHierarchyOps(s: Option[Sym[_]]) extends CtrlHierarchyOps(s) {
    // --- Scope hierarchy --- //
    def toScope: Scope

    /** Returns the control scope in which this symbol or controller is defined. */
    def scope: Scope

    /** Returns all scope ancestors of the controller or symbol.
      * Ancestors are ordered outermost to innermost
      */
    def scopes: Seq[Scope] = Tree.ancestors[Scope](this.toScope){_.parent.scope}

    /** Returns all scope ancestors of the controller or symbol, stopping when stop is true (exclusive).
      * Ancestors are ordered outermost to innermost
      */
    def scopes(stop: Scope => Boolean): Seq[Scope] = Tree.ancestors[Scope](toScope, stop){_.parent.scope}

    /** Returns all scope ancestors of the controller or symbol, stopping at `stop` (exclusive).
      * Scopes are ordered outermost to innermost.
      */
    def scopes(stop: Scope): Seq[Scope] = Tree.ancestors[Scope](toScope, {c => c == stop})(_.parent.scope)
  }


  implicit class SymControl(s: Sym[_]) extends ScopeHierarchyOps(Some(s)) {
    def toCtrl: Ctrl = if (s.isControl) Ctrl.Node(s,-1) else s.parent
    def toScope: Scope = if (s.isControl) Scope.Node(s,-1,-1) else s.scope
    def isControllerOrHost: Boolean = s.isControl

    @stateful def children: Seq[Ctrl.Node] = {
      if (s.isControl) toCtrl.children
      else throw new Exception(s"Cannot get children of non-controller.")
    }

    def parent: Ctrl = s.rawParent
    def scope: Scope = s.rawScope
  }

  implicit class ScopeOperations(scp: Scope) {
    def toCtrl: Ctrl = scp match {
      case Scope.Node(sym,id,_) => Ctrl.Node(sym,id)
      case Scope.Host           => Ctrl.Host
    }
    def toScope: Scope = scp
    def isControllerOrHost: Boolean = true

    @stateful def children: Seq[Ctrl.Node] = toCtrl.children
    def parent: Ctrl = toCtrl.parent
    def scope: Scope = scp
  }


  implicit class CtrlControl(ctrl: Ctrl) extends CtrlHierarchyOps(ctrl.s) {
    def toCtrl: Ctrl = ctrl
    def isControllerOrHost: Boolean = true

    @stateful def children: Seq[Ctrl.Node] = ctrl match {
      // "Master" controller case
      case Ctrl.Node(sym, -1) => sym match {
        case Op(ctrl: Control[_]) => ctrl.bodies.zipWithIndex.flatMap{case (body,id) =>
          val stage = Ctrl.Node(sym,id)
          // If this is a pseudostage, transparently return all controllers under this pseudostage
          if (body.isPseudoStage) sym.rawChildren.filter(_.scope.toCtrl == stage)
          // Otherwise return the subcontroller for this "future" stage
          else Seq(Ctrl.Node(sym, id))
        }
        case _ => throw new Exception(s"Cannot get children of non-controller.")
      }
      // Subcontroller case - return all children which have this subcontroller as an owner
      case Ctrl.Node(sym,_) => sym.rawChildren.filter(_.scope.toCtrl == ctrl)

      // The children of the host controller is all Accel scopes in the program
      case Ctrl.Host => hwScopes.all
    }

    def parent: Ctrl = ctrl match {
      case Ctrl.Node(sym,-1) => sym.parent
      case Ctrl.Node(sym, _) => Ctrl.Node(sym, -1)
      case Ctrl.Host => Ctrl.Host
    }

    def scope: Scope = ctrl match {
      case Ctrl.Node(sym,-1) => sym.scope
      case Ctrl.Node(sym, _) => Scope.Node(sym, -1, -1)
      case Ctrl.Host         => Scope.Host
    }
  }


  implicit class CounterChainHelperOps(x: CounterChain) {
    def node: CounterChainNew = x match {
      case Op(c: CounterChainNew) => c
      case _ => throw new Exception(s"Could not find counterchain definition for $x")
    }

    def counters: Seq[Counter[_]] = x.node.counters
    def pars: Seq[I32] = counters.map(_.ctrPar)
    def willFullyUnroll: Boolean = counters.forall(_.willFullyUnroll)
    def isUnit: Boolean = counters.forall(_.isUnit)
    def isStatic: Boolean = counters.forall(_.isStatic)
  }


  implicit class CounterHelperOps[F](x: Counter[F]) {
    def node: CounterNew[F] = x match {
      case Op(c: CounterNew[_]) => c.asInstanceOf[CounterNew[F]]
      case _ => throw new Exception(s"Could not find counter definition for $x")
    }

    def start: Sym[F] = x.node.start
    def step: Sym[F] = x.node.step
    def end: Sym[F] = x.node.end
    def ctrPar: I32 = x.node.par
    def isStatic: Boolean = (start,step,end) match {
      case (Final(_), Final(_), Final(_)) => true
      case _ => false
    }
    def nIters: Option[Bound] = (start,step,end) match {
      case (Final(min), Final(stride), Final(max)) =>
        Some(Final(Math.ceil((max - min).toDouble / stride).toInt))

      case (Expect(min), Expect(stride), Expect(max)) =>
        Some(Expect(Math.ceil((max - min).toDouble / stride).toInt))

      case _ => None
    }
    def willFullyUnroll: Boolean = (nIters,ctrPar) match {
      case (Some(Expect(nIter)), Expect(par)) => par >= nIter
      case _ => false
    }
    def isUnit: Boolean = nIters match {
      case (Some(Final(1))) => true
      case _ => false
    }
  }

  implicit class IndexHelperOps[W](i: Ind[W]) {
    def ctrStart: Ind[W] = i.counter.start.unbox
    def ctrStep: Ind[W] = i.counter.step.unbox
    def ctrEnd: Ind[W] = i.counter.end.unbox
    def ctrPar: I32 = i.counter.ctrPar
    def ctrParOr1: Int = i.getCounter.map(_.ctrPar.toInt).getOrElse(1)
  }


  def getCChains(block: Block[_]): Seq[CounterChain] = getCChains(block.stms)
  def getCChains(stms: Seq[Sym[_]]): Seq[CounterChain] = stms.collect{case s: CounterChain => s}


  def scopeIters(scope: Scope): Seq[I32] = Try(scope match {
    case Scope.Host => Nil
    case Scope.Node(Op(loop: Loop[_]), -1, -1)         => loop.iters
    case Scope.Node(Op(loop: Loop[_]), stage, block)   => loop.bodies(stage).blocks.apply(block)._1
    case Scope.Node(Op(loop: UnrolledLoop[_]), -1, -1) => loop.iters
    case Scope.Node(Op(loop: UnrolledLoop[_]), i ,_)   => loop.bodiess.apply(i)._1.flatten
    case _ => Nil
  }).getOrElse(throw new Exception(s"$scope had no iterators defined"))

  def scopeValids(scope: Scope): Seq[Bit] = Try(scope match {
    case Scope.Host => Nil
    case Scope.Node(Op(loop: UnrolledLoop[_]), _, _) => loop.valids
    case _ => Nil
  }).getOrElse(throw new Exception(s"$scope had valids defined"))



  /** Returns the least common ancestor (LCA) of the two controllers.
    * If the controllers have no ancestors in common, returns None.
    */
  def LCA(a: Sym[_], b: Sym[_]): Ctrl = LCA(a.toCtrl,b.toCtrl)
  def LCA(a: Ctrl, b: Ctrl): Ctrl= Tree.LCA(a, b){_.parent}

  /** Returns the least common ancestor (LCA) of a list of controllers.
    * Also returns the pipeline distance between the first and last accesses,
    * and the pipeline distance between the first access and the first stage.
    * If the controllers have no ancestors in common, returns None.
    */
  @stateful def LCA(n: => Seq[Sym[_]]): Ctrl = { LCA(n.map(_.toCtrl)) }
  @stateful def LCA(n: Seq[Ctrl]): Ctrl = {
    val anchor = n.distinct.head
    val candidates = n.distinct.drop(1).map{x =>
      if (x.ancestors.map(_.master).contains(anchor.master)) anchor
      else if (anchor.ancestors.map(_.master).contains(x)) x else LCA(anchor, x)
    }
    if (candidates.distinct.length == 1) candidates.head 
    else if (n.distinct.length == 1) n.distinct.head
    else LCA(candidates)
  }
  @stateful def LCAPortMatchup(n: List[Sym[_]], lca: Ctrl): (Int,Int) = {
    val lcaChildren = lca.children.toList.map(_.master)
    val portMatchup = n.map{a => lcaChildren.indexOf(lcaChildren.filter{ s => a.ancestors.map(_.master).contains(s) }.head)}
    val basePort = portMatchup.min
    val numPorts = portMatchup.max - portMatchup.min
    (basePort, numPorts) 
  }

  def LCAWithPaths(a: Ctrl, b: Ctrl): (Ctrl, Seq[Ctrl], Seq[Ctrl]) = {
    Tree.LCAWithPaths(a,b){_.parent}
  }

  /** Returns the LCA between two symbols a and b along with their pipeline distance.
    *
    * Pipeline distance between a and b:
    *   If a and b have a least common control ancestor which is neither a nor b,
    *   this is defined as the dataflow distance between the LCA's children which contain a and b
    *   If a and b are equal, the distance is defined as zero.
    *
    *   The distance is undefined when the LCA is a xor b, or if a and b occur in parallel
    *   The distance is positive if a comes before b, negative otherwise
    */
  @stateful def LCAWithDistance(a: Sym[_], b: Sym[_]): (Ctrl,Int) = LCAWithDistance(a.toCtrl,b.toCtrl)


  @stateful def LCAWithDistance(a: Ctrl, b: Ctrl): (Ctrl,Int) = {
    if (a == b) (a,0)
    else {
      val (lca, pathA, pathB) = LCAWithPaths(a, b)
      if (lca.isOuterControl && lca != a && lca != b) {
        logs(s"PathA: " + pathA.mkString(", "))
        logs(s"PathB: " + pathB.mkString(", "))
        logs(s"LCA: $lca")

        val topA = pathA.find{c => c != lca }.get
        val topB = pathB.find{c => c != lca }.get
        // TODO[2]: Update with arbitrary children graph once defined
        val idxA = lca.children.indexOf(topA)
        val idxB = lca.children.indexOf(topB)
        if (idxA < 0 || idxB < 0) throw new Exception(s"Undefined distance between $a and $b (idxA=$idxA,idxB=$idxB)")
        val dist = idxB - idxA
        (lca,dist)
      }
      else {
        (lca, 0)
      }
    }
  }

  /** Returns the LCA and coarse-grained pipeline distance between accesses a and b.
    *
    * Coarse-grained pipeline distance:
    *   If the LCA controller of a and b is a metapipeline, the pipeline distance
    *   is the distance between the respective controllers for a and b. Otherwise zero.
    */
  @stateful def LCAWithCoarseDistance(a: Sym[_], b: Sym[_]): (Ctrl,Int) = {
    LCAWithCoarseDistance(a.toCtrl, b.toCtrl)
  }
  @stateful def LCAWithCoarseDistance(a: Ctrl, b: Ctrl): (Ctrl,Int) = {
    val (lca,dist) = LCAWithDistance(a,b)
    val coarseDist = if (lca.isOuterPipeLoop || lca.isOuterStreamLoop) dist else 0
    (lca, coarseDist)
  }

  // FIXME: Should have metadata for control owner for inputs to counters, shouldn't need this method.  Bug #
  @stateful def lookahead(a: Sym[_]): Sym[_] = {
    if (a.consumers.nonEmpty) {
      val p = a.consumers.filterNot{x => a.ancestors.collect{case y if (y.s.isDefined) => y.s.get}.contains(x)} // Remove consumers who are in the ancestry of this node
                         .map{ case y @ Op(x: CounterNew[_]) => y.getOwner.get; case y => y } // Convert counters to their owner ctrl
      if (p.toList.length >= 1) p.head else a
    } 
    else a
  }

  /** Returns all metapipeline parents between all pairs of (w in writers, a in readers U writers). */
  @stateful def findAllMetaPipes(readers: Set[Sym[_]], writers: Set[Sym[_]]): Map[Ctrl,Set[(Sym[_],Sym[_])]] = {
    if (writers.isEmpty && readers.isEmpty) Map.empty
    else {
      val ctrlGrps: Set[(Ctrl,(Sym[_],Sym[_]))] = writers.flatMap{w =>
        (readers ++ writers).flatMap{a =>
          val (lca, dist) = LCAWithCoarseDistance(lookahead(w),lookahead(a))
          //dbgs(s"lcawithdist for $w (${lookahead(w)} <-> $a (${lookahead(a)} = $lca $dist")
          if (dist != 0) Some(lca.master, (w,a)) else None
        }
      }
      ctrlGrps.groupBy(_._1).mapValues(_.map(_._2))
    }
  }

  @stateful def ctrlTree(accesses: Set[Sym[_]], top: Option[Ctrl] = None, tab: Int = 0): Iterator[String] = {
    if (accesses.nonEmpty) {
      val lca_init = LCA(accesses.toList)
      val lca = top match {
        case Some(ctrl) if lca_init.ancestors.contains(ctrl) => ctrl
        case None => lca_init
      }

      val head: Iterator[String] = Seq(lca match {
        case Ctrl.Node(s,id) => "  "*tab + s"${shortStm(s)} ($id) [Level: ${lca.level}, Loop: ${lca.looping}, Schedule: ${lca.schedule}]"
        case Ctrl.Host       => "  "*tab + "Host"
      }).iterator
      if (lca.isInnerControl) {
        val inner: Iterator[String] = head ++ accesses.iterator.map{a => "  "*tab + s"  ${shortStm(a)}" }
        inner
      }
      else {
        val children = lca.children.iterator
        val childStrs: Iterator[String] = children.flatMap{ child =>
          val accs = accesses.filter(_.ancestors.contains(child))
          ctrlTree(accs, Some(child), tab + 1)
        }
        head ++ childStrs
      }
    }
    else Nil.iterator
  }

  @stateful def findMetaPipe(mem: Sym[_], readers: Set[Sym[_]], writers: Set[Sym[_]]): (Option[Ctrl], Map[Sym[_],Option[Int]], Option[Issue]) = {
    val accesses = readers ++ writers
    val metapipeLCAs = findAllMetaPipes(readers, writers)
    val hierarchicalBuffer = metapipeLCAs.keys.size > 1
    val issue = if (hierarchicalBuffer) Some(AmbiguousMetaPipes(mem, metapipeLCAs)) else None

    metapipeLCAs.keys.headOption match {
      case Some(metapipe) =>
        val group  = metapipeLCAs(metapipe)
        val anchor = group.head._1
        val dists = accesses.map{a =>
          val (lca,dist) = 
            if (a.parent.s.isDefined && (a.parent.s.get match { case Op(_: OpReduce[_]) => true; case _ => false})) { // Hack for bug # 
              val Op(OpReduce(_,_,_,map,load,_,_,_,_,_)) = a.parent.s.get
              if (map.result == a) {
                (a.parent, a.parent.children.filter(_.s.get != a.parent.s.get).length)
              }
              else LCAWithCoarseDistance(lookahead(anchor), lookahead(a))
            }
            else if (a.parent.s.isDefined && (a.parent.s.get match { case Op(_: OpMemReduce[_,_]) => true; case _ => false})) { // Hack for bug # 
              val Op(OpMemReduce(_,_,_,_,map,_,_,_,_,_,_,_,_)) = a.parent.s.get
              if (map.result == a) {
                (a.parent, a.parent.children.filter(_.s.get != a.parent.s.get).length)
              }
              else LCAWithCoarseDistance(lookahead(anchor), lookahead(a))
            }
            else LCAWithCoarseDistance(lookahead(anchor),lookahead(a))

          // val (lca,dist) = LCAWithCoarseDistance(lookahead(anchor),lookahead(a))

          dbgs(s"$a <-> $anchor # LCA: $lca, Dist: $dist")

          if (lca == metapipe || anchor == a) a -> Some(dist) else a -> None
        }
        val buffers = dists.filter{_._2.isDefined}.map(_._2.get)
        val minDist = buffers.minOrElse(0)
        val ports = dists.map{case (a,dist) => a -> dist.map{d => d - minDist} }.toMap
        (Some(metapipe), ports, issue)

      case None =>
        (None, accesses.map{a => a -> Some(0)}.toMap, issue)
    }
  }

  @stateful def getReadStreams(ctrl: Ctrl): Set[Sym[_]] = {
    // ctrl.children.flatMap(getReadStreams).toSet ++
    localMems.all.filter{mem => mem.readers.exists{_.parent.s == ctrl.s }}
      .filter{mem => mem.isStreamIn || mem.isFIFO }
    // .filter{case Op(StreamInNew(bus)) => !bus.isInstanceOf[DRAMBus[_]]; case _ => true}
  }

  @stateful def getWriteStreams(ctrl: Ctrl): Set[Sym[_]] = {
    // ctrl.children.flatMap(getWriteStreams).toSet ++
    localMems.all.filter{mem => mem.writers.exists{c => c.parent.s == ctrl.s }}
      .filter{mem => mem.isStreamOut || mem.isFIFO }
    // .filter{case Op(StreamInNew(bus)) => !bus.isInstanceOf[DRAMBus[_]]; case _ => true}
  }

}
