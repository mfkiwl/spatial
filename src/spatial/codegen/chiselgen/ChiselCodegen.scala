package spatial.codegen.chiselgen

import argon._
import argon.codegen.FileDependencies
import emul.Bool
import spatial.codegen.naming._
import spatial.lang._
import spatial.metadata.access._
import spatial.metadata.control._
import spatial.metadata.blackbox._
import spatial.metadata.memory._
import spatial.node._
import spatial.traversal.AccelTraversal
import spatial.util.spatialConfig


trait ChiselCodegen extends NamedCodegen with FileDependencies with AccelTraversal {
  override val lang: String = "chisel"
  override val ext: String = "scala"
  backend = "accel"
  final val CODE_WINDOW: Int = spatialConfig.codeWindow

  protected var globalBlockID: Int = 0
  protected var ensigs = new scala.collection.mutable.ListBuffer[String]
  protected var boreMe = new scala.collection.mutable.ListBuffer[(String, String)]
  var blackBoxStreamInWidth = -1 // Experimental, for tracking width of BlackBoxStream
  var blackBoxStreamOutWidth = -1 // Experimental, for tracking width of BlackBoxStream
  val controllerStack = scala.collection.mutable.Stack[Sym[_]]()

  // Buffer mappings from LCA to list of memories controlled by it
  case class BufMapping(val mem: Sym[_], val lane: Int)
  var bufMapping = scala.collection.mutable.HashMap[Sym[_], List[BufMapping]]()
  var regchainsMapping =  scala.collection.mutable.HashMap[Sym[_], List[BufMapping]]()

  /** Map between cchain and list of controllers it is copied for, due to stream controller logic */
  val cchainCopies = scala.collection.mutable.HashMap[Sym[_], List[Sym[_]]]()

  override def named(s: Sym[_], id: Int): String = {
    val name = s.op match {
      case Some(rhs) => rhs match {
        case _: AccelScope       => s"RootController"
        case DelayLine(_, data) => data match {
          case Const(_) => src"$data"
          case _ => super.named(s, id)
        }
        case _ => super.named(s, id)
      }
      case _ => super.named(s, id)
    }

    if (scoped.contains(s)) scoped(s).assemble()
    else name
  }

  protected def bitWidth(tp: Type[_]): Int = tp match {
    case Bits(bT) => bT.nbits
    case _ => -1
  }
  protected def fracBits(tp: Type[_]): Int = tp match {
    case FixPtType(_, _,f) => f
    case _ => 0
  }

  override def emitHeader(): Unit = {
    emit("""package accel""")
    emit("import fringe._")
    emit("import fringe.templates.memory._")
    emit("import fringe.templates._")
    emit("import fringe.Ledger._")
    emit("import fringe.utils._")
    emit("import fringe.utils.implicits._")
    emit("import fringe.templates.math._")
    emit("import fringe.templates.counters._")
    emit("import fringe.templates.vector._")
    emit("import fringe.templates.axi4._")
    emit("import fringe.SpatialBlocks._")
    emit("import fringe.templates.memory._")
    emit("import fringe.templates.memory.implicits._")
    emit("import fringe.templates.retiming._")
    emit("import emul.ResidualGenerator._")
    emit("import fringe.templates.euresys._")
    emit("import api._")
    emit("import chisel3._")
    emit("import chisel3.util._")
    emit("import Args._")
    emit("import scala.collection.immutable._")
    emit("")
    super.emitHeader()
  }

  protected def forEachChild(lhs: Sym[_])(body: (Sym[_],Int) => Unit): Unit = {
    lhs.children.filter(_.s.get != lhs).zipWithIndex.foreach { case (cc, idx) =>
      val c = cc.s.get
      controllerStack.push(c)
      body(c,idx)
      controllerStack.pop()
    }
  }

  protected def markCChainObjects(b: Block[_]): Unit = {
    b.stms.collect{case x if (x.isCounterChain && x.getOwner.isDefined && x.owner.isOuterStreamLoop) => 
      forEachChild(x.owner){case (c, _) => cchainCopies += (x -> {cchainCopies.getOrElse(x, List()) ++ List(c)})}
    }
  }

  override protected def gen(b: Block[_], withReturn: Boolean = false): Unit = {
    /** Returns list of stms that are not in a broadcast path, and the "weight" of the stm */
    markCChainObjects(b)
    def printableStms(stms: Seq[Sym[_]]): Seq[StmWithWeight[Sym[_]]] = stms.collect{case x: Sym[_] if (x.parent == Ctrl.Host) => StmWithWeight[Sym[_]](x, 0, Seq[String]()); case x: Sym[_] if !x.isBroadcastAddr => StmWithWeight[Sym[_]](x, x.parOrElse1,cchainCopies.getOrElse(x, Seq[String]()).map{c => src"_copy$c"})}
    def isLive(s: Sym[_], remaining: Seq[Sym[_]]): Boolean = (b.result == s || remaining.exists{x => x.nestedInputs.contains(s) || bufMapping.getOrElse(x, List[BufMapping]()).map{_.mem}.contains(s)})
    // TODO: is branchSfx still required?  Its crashing when s is an inner switch with return type defined
    def branchSfx(s: Sym[_], n: Option[String] = None): String = {if (s.isBranch && !(s.isSwitch && s.isInnerControl)) src""""${n.getOrElse(quote(s))}" -> $s.data""" else src""""${n.getOrElse(quote(s))}" -> $s"""}
    def initChunkState(): Unit = {ensigs = new scala.collection.mutable.ListBuffer[String]}
    val hierarchyDepth = (scala.math.log(1 max printableStms(b.stms).map(_.weight).sum) / scala.math.log(CODE_WINDOW)).toInt
    globalBlockID = javaStyleChunk[Sym[_]](
      printableStms(b.stms), 
      CODE_WINDOW, 
      hierarchyDepth, 
      globalBlockID, 
      isLive, 
      branchSfx, 
      arg, 
      () => initChunkState
    )(visit _ )
    
    if (withReturn) emit(src"${b.result}")
  }

  def emitPreMain(): Unit = {
    inGen(out, s"AccelWrapper.$ext") {
      emit ("package accel")
      emit ("import chisel3._")
      emit ("import chisel3.util._")
      emit ("import fringe._")
      emit ("import utils.implicits._")
      emit ("import fringe.templates.math._")
      emit ("import fringe.templates.counters._")
      emit("import fringe.templates.vector._")
      emit ("import fringe.templates.memory._")
      emit ("import fringe.templates.axi4._")
      emit ("import fringe.templates.retiming._")
      
      open("trait AccelWrapper extends Module {")
     emit (s"""val io_w = if ("${spatialConfig.target.name}" == "VCS" || "${spatialConfig.target.name}" == "ASIC") 8 else 32 // TODO: How to generate these properly?""")
     emit (s"""val io_v = if ("${spatialConfig.target.name}" == "VCS" || "${spatialConfig.target.name}" == "ASIC") 64 else 16 // TODO: How to generate these properly?""")
    }

    inGen(out, "Instantiator.scala") {
      emit("package spatialIP")
      emit("")
      emit("import accel._")
      emit("import fringe._")
      emit("import chisel3.core.Module")
      emit("import chisel3._")
      emit("import chisel3.util._")
      emit("import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}")
      emit("import fringe.templates.axi4._")
      emit("")
      emit("import scala.collection.mutable.ListBuffer")

      emit("/**")
      emit(" * SpatialIP test harness")
      emit(" */")
      open("class SpatialIPUnitTester(c: SpatialIP)(implicit args: Array[String]) extends ArgsTester(c) {")
      close("}")
      emit("")
      open("object Instantiator extends CommonMain {")
        emit("type DUTType = SpatialIP")
        emit("")
        open("def dut = () => {")

    }


    inGen(out, "ArgInterface.scala"){
      emit("""package accel""")
      emit("import fringe.templates._")
      emit("import fringe.utils._")
      emit("import fringe.utils.implicits._")
      emit("import fringe.templates.math._")
      emit("import fringe.templates.counters._")
      emit("import fringe.templates.vector._")
      emit("import fringe.templates.memory._")
      emit("import fringe.templates.retiming._")
      emit("import api._")
      emit("import chisel3._")
      emit("import chisel3.util._")
      emit("import Args._")
      emit("import scala.collection.immutable._")
      open("object Args {")
    }

  }
  def emitPostMain(): Unit = {

    inGen(out, s"AccelWrapper.$ext") {
      emit ("// Combine values")
      emit ("val io_numArgIns = scala.math.max(1, io_numArgIns_reg + io_numArgIns_mem + io_numArgIOs_reg)")
      emit ("val io_numArgOuts = scala.math.max(1, io_numArgOuts_reg + io_numArgIOs_reg + io_numArgOuts_instr + io_numArgOuts_breakpts)")
      emit ("val io_numArgIOs = io_numArgIOs_reg")
      emit ("val io_numArgInstrs = io_numArgOuts_instr")
      emit ("val io_numArgBreakpts = io_numArgOuts_breakpts")
      emit ("globals.numArgIns = io_numArgIns")
      emit ("globals.numArgOuts = io_numArgOuts")
      emit ("globals.numArgIOs = io_numArgIOs")
      emit ("globals.numArgInstrs = io_numArgInstrs")
      emit ("globals.loadStreamInfo = io_loadStreamInfo")
      emit ("globals.storeStreamInfo = io_storeStreamInfo")
      emit ("globals.gatherStreamInfo = io_gatherStreamInfo")
      emit ("globals.scatterStreamInfo = io_scatterStreamInfo")
      emit ("globals.axiStreamInsInfo = io_axiStreamInsInfo")
      emit ("globals.axiStreamOutsInfo = io_axiStreamOutsInfo")
      emit ("globals.numAllocators = io_numAllocators")

      emit("val io = chisel3.core.dontTouch(")
      emit("  IO(new CustomAccelInterface(io_w, io_v, globals.LOAD_STREAMS, globals.STORE_STREAMS, globals.GATHER_STREAMS, globals.SCATTER_STREAMS, globals.AXI_STREAMS_IN, globals.AXI_STREAMS_OUT, globals.numAllocators, io_numArgIns, io_numArgOuts))")
      emit(")")
      emit ("var outStreamMuxMap: scala.collection.mutable.Map[String, Int] = scala.collection.mutable.Map[String,Int]()")
      open("def getStreamOutLane(id: String): Int = {")
        emit ("val lane = outStreamMuxMap.getOrElse(id, 0)")
        emit ("outStreamMuxMap += (id -> {lane + 1})")
        emit ("lane")
      close("}")
      emit ("var outBuffMuxMap: scala.collection.mutable.Map[String, Int] = scala.collection.mutable.Map[String,Int]()")
      open("def getBuffOutLane(id: String): Int = {")
        emit ("val lane = outBuffMuxMap.getOrElse(id, 0)")
        emit ("outBuffMuxMap += (id -> {lane + 1})")
        emit ("lane")
      close("}")
      emit ("var inStreamMuxMap: scala.collection.mutable.Map[String, Int] = scala.collection.mutable.Map[String,Int]()")
      open("def getStreamInLane(id: String): Int = {")
        emit ("val lane = inStreamMuxMap.getOrElse(id, 0)")
        emit ("inStreamMuxMap += (id -> {lane + 1})")
        emit ("lane")
      close("}")
      close("}")
    }
    inGen(out, "Instantiator.scala") {
      emit (s"""val w = if (this.target == "zcu") 32 else if (this.target == "VCS" || this.target == "ASIC") 8 else 32""")
      emit ("val numArgIns = numArgIns_mem  + numArgIns_reg + numArgIOs_reg")
      emit ("val numArgOuts = numArgOuts_reg + numArgIOs_reg + numArgOuts_instr + numArgOuts_breakpts")
      emit ("val numArgIOs = numArgIOs_reg")
      emit ("val numArgInstrs = numArgOuts_instr")
      emit ("val numArgBreakpts = numArgOuts_breakpts")
      emit (s"""new SpatialIP(this.target, () => Module(new AccelUnit(w, numArgIns, numArgOuts, numArgIOs, numArgOuts_instr + numArgBreakpts, numAllocators, loadStreamInfo, storeStreamInfo, gatherStreamInfo, scatterStreamInfo, axiStreamInsInfo, axiStreamOutsInfo)))""")
      // emit ("new SpatialIP(w, numArgIns, numArgOuts, numArgIOs, numArgOuts_instr + numArgBreakpts, loadStreamInfo, storeStreamInfo, streamInsInfo, streamOutsInfo, globals.target)")
      close("}")
      emit ("def tester = { c: DUTType => new SpatialIPUnitTester(c) }")
      close("}")
    }
    inGen(out, "AccelUnit.scala") {
      emit(s"""package accel""")
      emit("import chisel3._")
      emit("import chisel3.util._")
      emit("import fringe._")
      emit("import utils.implicits._")
      emit("import fringe.templates.math._")
      emit("import fringe.templates.counters._")
      emit("import fringe.templates.vector._")
      emit("import fringe.templates.axi4._")
      emit("import fringe.templates.memory._")
      emit("import fringe.templates.retiming._")
      open("class AccelUnit(")
        emit("val top_w: Int,")
        emit("val numArgIns: Int,")
        emit("val numArgOuts: Int,")
        emit("val numArgIOs: Int,")
        emit("val numArgInstrs: Int,")
        emit("val numAllocators: Int,")
        emit("val loadStreamInfo: List[StreamParInfo],")
        emit("val storeStreamInfo: List[StreamParInfo],")
        emit("val gatherStreamInfo: List[StreamParInfo],")
        emit("val scatterStreamInfo: List[StreamParInfo],")
        emit("val streamInsInfo: List[AXI4StreamParameters],")
        emit("val streamOutsInfo: List[AXI4StreamParameters]")
      closeopen(s") extends AbstractAccelUnit with AccelWrapper { ")
        emit("val retime_released_reg = RegInit(false.B)")
        emit("val accelReset = reset.toBool | io.reset")
        emit("Main.main(this)")
      close("}")
    }

    inGen(out, "ArgInterface.scala"){
      close("}")
    }

    inGen(out, "Controllers.scala"){
      emitFooter()
    }

  }

  override protected def emitEntry(block: Block[_]): Unit = {
    open(src"object Main {")
      open(src"def main(accelUnit: AccelUnit): Unit = {")
        emit("accelUnit.io <> DontCare")
        emitPreMain()
        outsideAccel{gen(block)}
        emitPostMain()
      close(src"}")
    close(src"}")
  }


  protected def forceEmit(x: String): Unit = {
    val on = config.enGen
    config.enGen = true
    emit(x)
    config.enGen = on
  }


  var maxretime = 0

  override protected def quoteConst(tp: Type[_], c: Any): String = (tp,c) match {
    case (FixPtType(s,d,f), _) => c.toString + {if (d+f >= 32 && f == 0) "L" else ""} + s".FP($s, $d, $f)"
    case (FltPtType(g,e), _) => c.toString + s".FlP($g, $e)"
    case (_:Bit, c:Bool) => s"${c.value}.B"
    case (_:Text, c: String) => s"$c"
    case _ => super.quoteConst(tp,c)
  }

  override protected def remap(tp: Type[_]): String = tp match {
    case FixPtType(s,d,f) => s"new FixedPoint($s, $d, $f)"
    // case FixPtType(s,d,f) => s"new FixedPoint($s, $d, $f)"
    case FltPtType(m,e) => s"new FloatingPoint($m, $e)"
    case BitType() => "Bool()"
    case tp: Vec[_] => src"Vec(${tp.width}, ${tp.typeArgs.head})"
    // case tp: StructType[_] => src"UInt(${bitWidth(tp)}.W)"
    case _ => super.remap(tp)
  }

  /** Define how to pass a node through a Scala function call.  We use Scala function calls to handle modularization of
    * kernels.
     */
  protected def arg(tp: Type[_], node: Option[Sym[_]] = None): String = tp match {
    case FixPtType(_, _, _) if !node.exists(_.isDeqInterface) => s"FixedPoint"
    case _: Var[_] => "String"
    case FltPtType(_, _) => s"FloatingPoint"
    case BitType() => "Bool"
    case tp: Vec[_] => src"Vec[${arg(tp.typeArgs.head)}]"
    case _: Struct[_] => s"UInt"
    case _: StreamStruct[_] => s"StreamStructInterface"
    // case tp: StructType[_] => src"UInt(${bitWidth(tp)}.W)"
      case _ => node match {
      case Some(Op(_: FIFODeqInterface[_])) => "FIFODeqInterface"
      case Some(x) if x.isNBuffered => "NBufInterface"
      case Some(Op(_: ArgInNew[_])) => "UInt"
      case Some(Op(_: ArgOutNew[_])) => "MultiArgOut"
      case Some(Op(_: HostIONew[_])) => "MultiArgOut"
      case Some(Op(_: CounterNew[_])) => "CtrObject"
      case Some(Op(_: ForeverNew)) => "CtrObject"
      case Some(Op(_: CounterChainNew)) => "CounterChainInterface"
      case Some(Op(_: RegNew[_])) if (node.get.optimizedRegType.isDefined && node.get.optimizedRegType.get == AccumFMA) => "FixFMAAccumBundle"
      case Some(Op(_: RegNew[_])) if (node.get.optimizedRegType.isDefined) => "FixOpAccumBundle"
      case Some(Op(_: RegNew[_])) => "StandardInterface"
      case Some(Op(_: RegFileNew[_,_])) => "ShiftRegFileInterface"
      case Some(Op(_: LUTNew[_,_])) => "StandardInterface"
      case Some(Op(_: SRAMNew[_,_])) => "StandardInterface"
      case Some(Op(_: FIFONew[_])) => "FIFOInterface"
      case Some(Op(_: FIFORegNew[_])) => "FIFOInterface"
      case Some(Op(_: MergeBufferNew[_])) => "MergeBufferFullIO"
      case Some(Op(_: LIFONew[_])) => "FIFOInterface"
      case Some(Op(_: DRAMHostNew[_,_])) => "FixedPoint"
      case Some(Op(_: DRAMAccelNew[_,_])) => "DRAMAllocatorIO"
      case Some(Op(_@StreamInNew(bus))) =>
        bus match {
          case _: BurstDataBus[_] => "DecoupledIO[AppLoadData]"
          case BurstAckBus => "DecoupledIO[Bool]"
          case _: GatherDataBus[_] => "DecoupledIO[Vec[UInt]]"
          case ScatterAckBus => "DecoupledIO[Bool]"
          case AxiStream64Bus(_, _) => "AXI4Stream"
          case AxiStream256Bus(_, _) => "AXI4Stream"
          case AxiStream512Bus(_, _) => "AXI4Stream"
          case _ => s"DecoupledIO[UInt]"
        }
      case Some(Op(_@StreamOutNew(bus))) =>
        bus match {
          case BurstCmdBus => "DecoupledIO[AppCommandDense]"
          case _: BurstFullDataBus[_] => "DecoupledIO[AppStoreData]"
          case GatherAddrBus => "DecoupledIO[AppCommandSparse]"
          case _: ScatterCmdBus[_] => "DecoupledIO[ScatterCmdStream]"
          case AxiStream64Bus(_, _) => "AXI4Stream"
          case AxiStream256Bus(_, _) => "AXI4Stream"
          case AxiStream512Bus(_, _) => "AXI4Stream"
          case _ => s"DecoupledIO[UInt]"
        }

      case _ => super.remap(tp)
    }
  }

  /** Define how set up the IO port for a node on a Chisel Module. */
  protected def port(tp: Type[_], node: Option[Sym[_]] = None): String = tp match {
    case FixPtType(_, _, _) => s"Input(${remap(tp)})"
    case _: Var[_] => "String"
    case FltPtType(_, _) => s"Input(${remap(tp)})"
    case BitType() => "Input(Bool())"
    case tp: Vec[_] => src"Vec(${tp.width},${port(tp.typeArgs.head)})"
    case _: Struct[_] => s"Input(UInt(${bitWidth(tp)}.W))"
    case x: StreamStruct[_] =>
        val widths = x.fields.map{case (f,t) => s"""("$f" -> ${bitWidth(t)})"""}.mkString("Map(",",",")")
        s"Flipped(new StreamStructInterface($widths))"
    // case tp: StructType[_] => src"UInt(${bitWidth(tp)}.W)"
    case _ => node match {
      case Some(x) if x.isNBuffered => src"""Flipped(new NBufInterface(ModuleParams.getParams("${x}_p").asInstanceOf[NBufParams] ))"""
      case Some(Op(_: ArgInNew[_])) => "Input(UInt(64.W))"
      case Some(x@Op(_: ArgOutNew[_])) => s"new MultiArgOut(${scala.math.max(1,x.writers.filter(_.parent != Ctrl.Host).size)})"
      case Some(x@Op(_: HostIONew[_])) => s"new MultiArgOut(${scala.math.max(1,x.writers.filter(_.parent != Ctrl.Host).size)})"
      case Some(Op(_: CounterNew[_])) => "CtrObject"
      case Some(x@Op(_: CounterChainNew)) => src"""Flipped(new CounterChainInterface(ModuleParams.getParams("${x}_p").asInstanceOf[(List[Int],List[Int])] ))"""
      case Some(x@Op(_: RegNew[_])) if (node.get.optimizedRegType.isDefined && node.get.optimizedRegType.get == AccumFMA) => 
        val FixPtType(_,d,f) = x.tp.typeArgs.head
        src"Flipped(new FixFMAAccumBundle(${x.writers.size}, $d, $f))"
      case Some(x@Op(_: RegNew[_])) if (node.get.optimizedRegType.isDefined) => 
        val FixPtType(_,d,f) = x.tp.typeArgs.head
        src"Flipped(new FixOpAccumBundle(${x.writers.size}, $d, $f))"
      case Some(x@Op(_: RegNew[_])) => src"""Flipped(new StandardInterface(ModuleParams.getParams("${x}_p").asInstanceOf[MemParams] ))"""
      case Some(x@Op(_: RegFileNew[_,_])) => src"""Flipped(new ShiftRegFileInterface(ModuleParams.getParams("${x}_p").asInstanceOf[MemParams] ))"""
      case Some(x@Op(_: LUTNew[_,_])) => src"""Flipped(new StandardInterface(ModuleParams.getParams("${x}_p").asInstanceOf[MemParams] ))"""
      case Some(x@Op(_: SRAMNew[_,_])) => src"""Flipped(new StandardInterface(ModuleParams.getParams("${x}_p").asInstanceOf[MemParams] ))"""
      case Some(x@Op(_: FIFONew[_])) => src"""Flipped(new FIFOInterface(ModuleParams.getParams("${x}_p").asInstanceOf[MemParams] ))"""
      case Some(x@Op(_: FIFORegNew[_])) => src"""Flipped(new FIFOInterface(ModuleParams.getParams("${x}_p").asInstanceOf[MemParams] ))"""
      case Some(x@Op(_: MergeBufferNew[_])) => src"""Flipped(new MergeBufferFullIO(ModuleParams.getParams("${x}_p").asInstanceOf[(Int,Int,Int,Int)] ))"""
      case Some(x@Op(_: LIFONew[_])) => src"""Flipped(new FIFOInterface(ModuleParams.getParams("${x}_p").asInstanceOf[MemParams] ))"""
      case Some(Op(_: DRAMHostNew[_,_])) => "Input(new FixedPoint(true, 64, 0))"
      case Some(x@Op(_: DRAMAccelNew[_,_])) => src"""Flipped(new DRAMAllocatorIO(ModuleParams.getParams("${x}_p").asInstanceOf[(Int, Int)] ))"""
      case Some(x@Op(_@StreamInNew(bus))) => 
        bus match {
          case _: BurstDataBus[_] => src"""Flipped(Decoupled(new AppLoadData(ModuleParams.getParams("${x}_p").asInstanceOf[(Int, Int)] )))"""
          case BurstAckBus => "Flipped(Decoupled(Bool()))"
          case _: GatherDataBus[_] => 
            val (par,width) = x.readers.head match { case Op(e@StreamInBankedRead(_, ens)) => (ens.length, bitWidth(e.A.tp)) }
            s"Flipped(Decoupled(Vec($par,UInt($width.W))))"
          case ScatterAckBus => "Flipped(Decoupled(Bool()))"
          case AxiStream64Bus(_, _) => "new AXI4Stream(AXI4StreamParameters(64, 8, 32))"
          case AxiStream256Bus(_, _) => "new AXI4Stream(AXI4StreamParameters(256, 8, 32))"
          case AxiStream512Bus(_, _) => "new AXI4Stream(AXI4StreamParameters(512, 8, 32))"
          case _ => s"Flipped(Decoupled(UInt(${bus.nbits}.W)))"
        }
      case Some(x@Op(_@StreamOutNew(bus))) => 
        bus match {
          case BurstCmdBus => src"""Decoupled(new AppCommandDense(ModuleParams.getParams("${x}_p").asInstanceOf[(Int,Int)] ))"""
          case _: BurstFullDataBus[_] => src"""Decoupled(new AppStoreData(ModuleParams.getParams("${x}_p").asInstanceOf[(Int,Int)] ))"""
          case GatherAddrBus => src"""Decoupled(new AppCommandSparse(ModuleParams.getParams("${x}_p").asInstanceOf[(Int,Int)] ))"""
          case _: ScatterCmdBus[_] => src"""Decoupled(new ScatterCmdStream(ModuleParams.getParams("${x}_p").asInstanceOf[StreamParInfo] ))"""
          case AxiStream64Bus(_, _) => "Flipped(new AXI4Stream(AXI4StreamParameters(64, 8, 32)))"
          case AxiStream256Bus(_, _) => "Flipped(new AXI4Stream(AXI4StreamParameters(256, 8, 32)))"
          case AxiStream512Bus(_, _) => "Flipped(new AXI4Stream(AXI4StreamParameters(512, 8, 32)))"
          case _ => s"Decoupled(UInt(${bus.nbits}.W))"
        }

      case _ => super.remap(tp)
    }
  }

  protected def param(node: Sym[_]): Option[String] = node match {
    case x if x.isNBuffered => Some(src"m.io.np")
    case Op(_: MergeBufferNew[_]) => Some(src"(m.io.ways, m.io.par, m.io.bitWidth, m.io.readers)")
    case x if x.isMemPrimitive => Some(src"m.io.p")
    case Op(_: DRAMAccelNew[_,_]) => Some(src"($node.rank, $node.appReqCount)")
    case x if x.isCounterChain => 
      val sfx = if (cchainCopies.contains(x)) src"_copy${cchainCopies(x).head}" else ""
      Some(src"($x$sfx.par, $x$sfx.widths)")
    case x@Op(_@StreamInNew(bus)) => 
      bus match {
        case _: BurstDataBus[_] => Some(src"($x.bits.v, $x.bits.w)")
        case _ => None
      }
    case x@Op(_@StreamOutNew(bus)) => 
      bus match {
        case BurstCmdBus => Some(src"($x.bits.addrWidth, $x.bits.sizeWidth)")
        case _: BurstFullDataBus[_] => Some(src"($x.bits.v, $x.bits.w)")
        case GatherAddrBus => Some(src"($x.bits.v, $x.bits.addrWidth)")
        case _: ScatterCmdBus[_] => Some(src"$x.bits.p")
        case _ => None
      }

    case _ => None
  }
  // Check if this node has an io that will be partially connected in each controller
  protected def ledgerized(node: Sym[_]): Boolean = node match {
    case _ if node.isMem & !node.isArgIn & !node.isDRAM & !node.isStreamIn & !node.isStreamOut => true
    case _ if node.isDRAMAccel => true
    case _ if node.isCtrlBlackbox => true
    case _ if node.isBound && node.parent.s.exists(_.isBlackboxImpl) => true
    case _ if node.isBlackboxUse => true
    case _ => false
  }


}
