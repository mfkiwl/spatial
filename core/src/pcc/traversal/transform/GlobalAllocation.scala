package pcc.traversal.transform

import pcc.core._
import pcc.data._
import pcc.lang._
import pcc.node._
import pcc.node.pir._

import scala.collection.mutable.{HashMap,HashSet,ArrayBuffer}

case class GlobalAllocation(IR: State) extends MutateTransformer {
  override val name = "Global Allocation"

  val memoryPMUs = HashMap[Sym[_],VPMU]()
  def getPMU(mem: Sym[_]): VPMU = memoryPMUs.getOrElse(mem, throw new Exception(s"No PMU has been created for $mem"))

  case class CtrlData(var syms: ArrayBuffer[Sym[_]], var iters: ArrayBuffer[Seq[I32]])
  object CtrlData {
    def empty = CtrlData(ArrayBuffer.empty, ArrayBuffer.empty)
  }

  val controlData = HashMap[Sym[_],CtrlData]()

  def addOuter(s: Sym[_], ctrl: Sym[_]): Unit = {
    if (!controlData.contains(ctrl)) controlData += ctrl -> CtrlData.empty
    controlData(ctrl).syms += s
  }
  def prependOuter(data: CtrlData, ctrl: Sym[_]): Unit = {
    if (!controlData.contains(ctrl)) controlData += ctrl -> CtrlData.empty
    controlData(ctrl).syms = data.syms ++ controlData(ctrl).syms
    controlData(ctrl).iters ++= data.iters
  }

  protected def makePMU[A:Sym](lhs: Sym[A], rhs: Op[A])(implicit ctx: SrcCtx): Sym[A] = {
    val mem = super.transform(lhs,rhs)
    val pmu = VPMU(Seq(mem))
    memoryPMUs += mem -> pmu
    stage(pmu)
    mem
  }

  override def preprocess[S](block: Block[S]): Block[S] = {
    memoryPMUs.clear()
    controlData.clear()
    super.preprocess(block)
  }

  override def transform[A:Sym](lhs: Sym[A], rhs: Op[A])(implicit ctx: SrcCtx): Sym[A] = rhs match {
    case SRAMNew(_) => makePMU(lhs,rhs)
    case FIFONew(_) => makePMU(lhs,rhs)
    case LIFONew(_) => makePMU(lhs,rhs)

    case c: BlackBox => super.transform(lhs,rhs)
    case c: VPCU => super.transform(lhs,rhs)
    case c: VPMU => super.transform(lhs,rhs)

    case c: Control if isInnerControl(lhs) => innerBlock(lhs,c.blocks.head,c.iters)
    case c: Control if isOuterControl(lhs) => outerBlock(lhs,c.blocks.head,c.iters)

    case _ => super.transform(lhs,rhs)
  }

  protected def outerBlock[A:Sym](lhs: Sym[A], block: Block[_], iters: Seq[I32]): Sym[A] = {
    val usedSyms = HashMap[Sym[_],Set[Sym[_]]]()
    def addUsed(x: Sym[_], using: Set[Sym[_]]): Unit = usedSyms(x) = usedSyms.getOrElse(x, Set.empty) ++ using
    var children = HashSet[Sym[_]]()

    block.stms.reverseIterator.foreach{
      case Memory(_)  =>
      case Bus(_)     =>
      case Control(s) =>
        children += s
        s.dataInputs.foreach{in => addUsed(in, Set(s)) }

      case s =>
        if (usedSyms.contains(s)) s.dataInputs.foreach{in => addUsed(in,usedSyms(s)) }
    }

    // Stage the inner block, preserving only memory allocations and other controllers
    val blk = stageBlock {
      block.stms.foreach {
        case Memory(s)  => visit(s)
        case Control(s) => visit(s)
        case Bus(s)     => visit(s)
        case s if usedSyms.contains(s) => usedSyms(s).foreach{c => addOuter(s, c) }
        case s => dbgs(s"Dropping ${stm(s)}")
      }
      implicit val ctx: SrcCtx = block.result.ctx
      Void.c
    }
    val psu = VSwitch(blk, iters)
    stage(psu).asInstanceOf[Sym[A]]
  }

  protected def innerBlock[A:Sym](lhs: Sym[A], block: Block[_], iters: Seq[I32]): Sym[A] = {
    val parents = symParents(lhs)
    val edata = parents.map{p => controlData.getOrElse(p, CtrlData.empty) }
    val cdata = controlData.getOrElse(lhs, CtrlData.empty)
    val external = edata.flatMap(_.syms)
    val outer = cdata.syms
    val scope = block.stms
    val allIters = edata.flatMap(_.iters) ++ cdata.iters :+ iters

    val wrSyms = HashMap[Sym[_],Set[Sym[_]]]()
    val rdSyms = HashMap[Sym[_],Set[Sym[_]]]()
    val dataSyms = HashSet[Any]()
    val memAllocs = HashSet[Sym[_]]()

    val outputs = HashSet[Sym[_]]()

    def addWr(x: Sym[_], mem: Set[Sym[_]]): Unit = wrSyms(x) = wrSyms.getOrElse(x, Set.empty) ++ mem
    def addRd(x: Sym[_], mem: Set[Sym[_]]): Unit = rdSyms(x) = rdSyms.getOrElse(x, Set.empty) ++ mem
    def isWr(x: Sym[_], mem: Sym[_]): Boolean = wrSyms.contains(x) && wrSyms(x).contains(mem)
    def isRd(x: Sym[_], mem: Sym[_]): Boolean = rdSyms.contains(x) && rdSyms(x).contains(mem)
    def isDatapath(x: Sym[_]): Boolean = {
      dataSyms.contains(x) || (!wrSyms.contains(x) && !rdSyms.contains(x) && !memAllocs.contains(x))
    }
    def isRemoteMemory(mem: Sym[_]): Boolean = !mem.isReg || {
      accessesOf(mem).exists{access => parentOf(access).sym != lhs }
    }
    scope.reverseIterator.foreach{
      case s @ Reader(reads) =>
        val remotelyAccessed = reads.filter{rd => isRemoteMemory(rd.mem) }
        val (pushed, local) = remotelyAccessed.partition{rd => readersOf(rd.mem).size == 1 }
        rdSyms += s -> remotelyAccessed.map(_.mem)
        dataSyms += s
        // Only push read address to remote PMU if this the only read of this memory
        pushed.foreach{read =>
          read.addr.foreach{adr => adr.foreach{a => addRd(a, Set(read.mem)) }}
        }

      case s @ Writer(writes) =>
        val remotelyAccessed = writes.filter{wr => isRemoteMemory(wr.mem) }
        val (pushed, local) = remotelyAccessed.partition{wr => writersOf(wr.mem).size == 1}
        wrSyms += s -> remotelyAccessed.map(_.mem)
        dataSyms += s
        // Only push write address to remote PMU if this is the only write to this memory
        pushed.foreach{write =>
          write.addr.foreach{adr =>
            adr.foreach{a => addWr(a, Set(write.mem)) }
            dataSyms += write.data // Make sure not to push data calculation
          }
        }

      case Memory(s) if !s.isReg => memAllocs += s

      case s =>
        if (wrSyms.contains(s)) s.dataInputs.foreach{in => addWr(in,wrSyms(s)) }
        if (rdSyms.contains(s)) s.dataInputs.foreach{in => addRd(in,rdSyms(s)) }
        if (dataSyms.contains(s)) dataSyms ++= s.dataInputs
    }
    val wrMems: Seq[Sym[_]] = wrSyms.values.flatten.toSeq.distinct
    val rdMems: Seq[Sym[_]] = rdSyms.values.flatten.toSeq.distinct

    // Add statements to a PCU
    // For inner statements, this is always a PCU
    def update(x: Sym[_]): Unit = x match {

      case  _ => visit(x)
    }

    // Copy statements from another block
    // For inner statements, this is always a PMU
    def clone(x: Sym[_]): Unit = x match {
      case Op(CounterChainNew(ctrs)) => stage(CounterChainCopy(f(ctrs)))
      case _ => mirrorSym(x)
    }

    def blk(copy: Sym[_] => Unit)(use: Sym[_] => Boolean): Block[Void] = stageBlock {
      // Copy parent controllers' dependencies into the controller
      external.foreach{s => clone(s) }
      // Push this controller's direct dependencies into the controller
      outer.foreach{s => copy(s) }
      // Add statements to the data/address path in this block
      scope.foreach{s => if (use(s)) copy(s) }
      void
    }

    dbgs(s"${stm(lhs)}")
    dbgs(s"Iters: $allIters")
    dbgs(s"Outer: ")
    outer.foreach{s => dbgs(s"  ${stm(s)}")}
    dbgs(s"Scope: ")
    scope.foreach{s => dbgs(s"  ${stm(s)} [datapath:${isDatapath(s)}]")}

    val wrs = wrMems.map{sram => blk(clone){s => isWr(s,sram)} }
    val rds = rdMems.map{sram => blk(clone){s => isRd(s,sram)} }
    val datapath = blk(update){s => isDatapath(s) }

    val pcu = VPCU(datapath,allIters)
    memAllocs.foreach{mem => visit(mem) }
    wrMems.zip(wrs).foreach{case (mem,blk) => getPMU(f(mem)).setWr(blk,allIters) }
    rdMems.zip(rds).foreach{case (mem,blk) => getPMU(f(mem)).setRd(blk,allIters) }

    stage(pcu).asInstanceOf[Sym[A]]
  }


}