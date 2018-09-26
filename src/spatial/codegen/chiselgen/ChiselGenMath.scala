package spatial.codegen.chiselgen

import argon._
import argon.node._
import argon.codegen.Codegen
import spatial.lang._
import spatial.node._
import spatial.metadata.control._
import spatial.metadata.memory._
import spatial.metadata.retiming._
import spatial.util.spatialConfig

trait ChiselGenMath extends ChiselGenCommon {

  // TODO: Clean this and make it nice
  private def MathDL(lhs: Sym[_], rhs: Op[_], nodelat: Double): Unit = {
    emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})")

    val backpressure = if (controllerStack.nonEmpty) getBackPressure(controllerStack.head.toCtrl) else "true.B"
    val lat = if ((lhs.fullDelay + nodelat).toInt != (lhs.fullDelay.toInt + nodelat.toInt)) s"Some($nodelat + 1.0)" else s"Some($nodelat)"
    rhs match {
      case FixMul(a,b) => emitt(src"$lhs.r := ($a.mul($b, $lat, $backpressure)).r")
      case UnbMul(a,b) => emitt(src"$lhs.r := ($a.mul($b, $lat, $backpressure, rounding = Unbiased)).r")
      case SatMul(a,b) => emitt(src"$lhs.r := ($a.mul($b, $lat, $backpressure, saturating = Saturating)).r")
      case UnbSatMul(a,b) => emitt(src"$lhs.r := ($a.mul($b, $lat, $backpressure, saturating = Saturating, rounding = Unbiased)).r")
      case FixDiv(a,b) => emitt(src"$lhs.r := ($a.div($b, $lat, $backpressure)).r")
      case UnbDiv(a,b) => emitt(src"$lhs.r := ($a.div($b, $lat, $backpressure, rounding = Unbiased)).r")
      case SatDiv(a,b) => emitt(src"$lhs.r := ($a.div($b, $lat, $backpressure, saturating = Saturating)).r")
      case UnbSatDiv(a,b) => emitt(src"$lhs.r := ($a.div($b, $lat, $backpressure, saturating = Saturating, rounding = Unbiased)).r")
      case FixMod(a,b) => emitt(src"$lhs.r := ($a.mod($b, $lat, $backpressure)).r")
      case FixRecip(a) => emitt(src"$lhs.r := (${lhs}_one.div($a, $lat, $backpressure)).r")
      case FixSqrt(x) => emitt(src"$lhs.r := Math.sqrt($x, $lat, $backpressure).r")
      case FixSin(x) => emitt(src"$lhs.r := Math.sin($x, $lat, $backpressure).r")
      case FixCos(x) => emitt(src"$lhs.r := Math.cos($x, $lat, $backpressure).r")
      case FixAtan(x) => emitt(src"$lhs.r := Math.tan($x, $lat, $backpressure).r")
      case FixSinh(x) => emitt(src"$lhs.r := Math.sin($x, $lat, $backpressure).r")
      case FixCosh(x) => emitt(src"$lhs.r := Math.cos($x, $lat, $backpressure).r")
      case FixRecipSqrt(a) => emitt(src"$lhs.r := (${lhs}_one.div(Math.sqrt($a, ${s"""latencyOption("FixSqrt", Some(bitWidth(lhs.tp)))"""}, $backpressure), $lat, $backpressure)).r")
      case FixFMA(x,y,z) => emitt(src"$lhs.r := Math.fma($x,$y,$z,$lat, $backpressure).toFixed($lhs).r")
      case FltFMA(x,y,z) => emitt(src"$lhs.r := Math.fma($x,$y,$z,$lat, $backpressure).r")
      case FltSqrt(x) => emitt(src"$lhs.r := Math.fsqrt($x, $lat, $backpressure).r")
      case FltAdd(x,y) => emitt(src"$lhs.r := Math.fadd($x, $y, $lat, $backpressure).r")
      case FltSub(x,y) => emitt(src"$lhs.r := Math.fsub($x, $y, $lat, $backpressure).r")
      case FltMul(x,y) => emitt(src"$lhs.r := Math.fmul($x, $y, $lat, $backpressure).r")
      case FltDiv(x,y) => emitt(src"$lhs.r := Math.fdiv($x, $y, $lat, $backpressure).r")
      case FixLst(x,y) => emitt(src"$lhs.r := Math.lt($x, $y, $lat, $backpressure).r")
      case FixLeq(x,y) => emitt(src"$lhs.r := Math.lte($x, $y, $lat, $backpressure).r")
      case FixNeq(x,y) => emitt(src"$lhs.r := Math.neq($x, $y, $lat, $backpressure).r")
      case FixEql(x,y) => emitt(src"$lhs.r := Math.eql($x, $y, $lat, $backpressure).r")
      case FltLst(x,y) => emitt(src"$lhs.r := Math.flt($x, $y, $lat, $backpressure).r")
      case FltLeq(x,y) => emitt(src"$lhs.r := Math.flte($x, $y, $lat, $backpressure).r")
      case FltNeq(x,y) => emitt(src"$lhs.r := Math.fneq($x, $y, $lat, $backpressure).r")
      case FltEql(x,y) => emitt(src"$lhs.r := Math.feql($x, $y, $lat, $backpressure).r")
      case FltRecip(x) => emitt(src"$lhs.r := Math.frec($x, $lat, $backpressure).r")
      case FixInv(x)   => emitt(src"$lhs.r := Math.inv($x,$lat, $backpressure).r")
      case FixNeg(x)   => emitt(src"$lhs.r := Math.neg($x,$lat, $backpressure).r")
      case FixAdd(x,y) => emitt(src"$lhs.r := Math.add($x,$y,$lat, $backpressure, Truncate, Wrapping).r")
      case FixSub(x,y) => emitt(src"$lhs.r := Math.sub($x,$y,$lat, $backpressure, Truncate, Wrapping).r")
      case FixAnd(x,y)  => emitt(src"$lhs.r := Math.and($x,$y,$lat, $backpressure).r")
      case FixOr(x,y)   => emitt(src"$lhs.r := Math.or($x,$y,$lat, $backpressure).r")
      case FixXor(x,y)  => emitt(src"$lhs.r := Math.xor($x,$y,$lat, $backpressure).r")
      case SatAdd(x,y) => emitt(src"$lhs.r := Math.add($x, $y,$lat, $backpressure, Truncate, Saturating).r")
      case SatSub(x,y) => emitt(src"$lhs.r := Math.add($x, $y,$lat, $backpressure, Truncate, Saturating).r")
      case FixToFix(a, fmt) => emitt(src"$lhs.r := Math.fix2fix(${a}, ${fmt.sign}, ${fmt.ibits}, ${fmt.fbits}, $lat, $backpressure, Truncate, Wrapping).r")
      case FixToFixSat(a, fmt) => emitt(src"$lhs.r := Math.fix2fix(${a}, ${fmt.sign}, ${fmt.ibits}, ${fmt.fbits}, $lat, $backpressure, Truncate, Saturating).r")
      case FixToFixUnb(a, fmt) => emitt(src"$lhs.r := Math.fix2fix(${a}, ${fmt.sign}, ${fmt.ibits}, ${fmt.fbits}, $lat, $backpressure, Unbiased, Wrapping).r")
      case FixToFixUnbSat(a, fmt) => emitt(src"$lhs.r := Math.fix2fix(${a}, ${fmt.sign}, ${fmt.ibits}, ${fmt.fbits}, $lat, $backpressure, Unbiased, Saturating).r")
      case FixToFlt(a, fmt) => 
        val FixPtType(s,d,f) = a.tp
        val FltPtType(m,e) = lhs.tp
        emitt(src"$lhs.r := Math.fix2flt($a,$m,$e,$lat,$backpressure).r")
      case FltToFix(a, fmt) => 
        val FixPtType(s,d,f) = lhs.tp
        val FltPtType(m,e) = a.tp
        emitt(src"$lhs.r := Math.flt2fix($a, $s,$d,$f,$lat,$backpressure, Truncate, Wrapping).r")
      case FltToFlt(a, fmt) => 
        val FltPtType(m,e) = lhs.tp
        emitt(src"$lhs.r := Math.flt2flt($a, $m, $e, $lat, $backpressure).r")

    }
  }

  override protected def gen(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case _ if lhs.isBroadcastAddr => // Do nothing
    case FixInv(x)   => MathDL(lhs, rhs, latencyOption("FixInv", Some(bitWidth(lhs.tp))))
    case FixNeg(x)   => MathDL(lhs, rhs, latencyOption("FixNeg", Some(bitWidth(lhs.tp))))
    case FixAdd(x,y) => MathDL(lhs, rhs, latencyOption("FixAdd", Some(bitWidth(lhs.tp))))
    case FixSub(x,y) => MathDL(lhs, rhs, latencyOption("FixSub", Some(bitWidth(lhs.tp))))
    case FixAnd(x,y)  => MathDL(lhs, rhs, latencyOption("FixAnd", Some(bitWidth(lhs.tp))))
    case FixOr(x,y)   => MathDL(lhs, rhs, latencyOption("FixOr", Some(bitWidth(lhs.tp))))
    case FixXor(x,y)  => MathDL(lhs, rhs, latencyOption("FixXor", Some(bitWidth(lhs.tp))))
    case FixPow(x,y)  => throw new Exception(s"FixPow($x, $y) should have transformed to either a multiply tree (constant exp) or reduce structure (variable exp)")
    case VecApply(vector, i) => emitGlobalWireMap(src"""$lhs""", src"""Wire(${lhs.tp})"""); emitt(src"$lhs := $vector.apply($i)")

    case FixLst(x,y) => MathDL(lhs, rhs, latencyOption("FixLst", Some(bitWidth(lhs.tp))))
    case FixLeq(x,y) => MathDL(lhs, rhs, latencyOption("FixLeq", Some(bitWidth(lhs.tp))))
    case FixNeq(x,y) => MathDL(lhs, rhs, latencyOption("FixNeq", Some(bitWidth(lhs.tp))))
    case FixEql(x,y) => MathDL(lhs, rhs, latencyOption("FixEql", Some(bitWidth(lhs.tp))))
    case FltLst(x,y) => MathDL(lhs, rhs, latencyOption("FltLst", Some(bitWidth(lhs.tp))))
    case FltLeq(x,y) => MathDL(lhs, rhs, latencyOption("FltLeq", Some(bitWidth(lhs.tp))))
    case FltNeq(x,y) => MathDL(lhs, rhs, latencyOption("FltNeq", Some(bitWidth(lhs.tp))))
    case FltEql(x,y) => MathDL(lhs, rhs, latencyOption("FltEql", Some(bitWidth(lhs.tp))))
    case UnbMul(x,y) => MathDL(lhs, rhs, latencyOption("UnbMul", Some(bitWidth(lhs.tp)))) 
    case UnbDiv(x,y) => MathDL(lhs, rhs, latencyOption("UnbDiv", Some(bitWidth(lhs.tp)))) 
    case SatMul(x,y) => MathDL(lhs, rhs, latencyOption("SatMul", Some(bitWidth(lhs.tp)))) 
    case SatDiv(x,y) => MathDL(lhs, rhs, latencyOption("SatDiv", Some(bitWidth(lhs.tp)))) 
    case UnbSatMul(x,y) => MathDL(lhs, rhs, latencyOption("SatMul", Some(bitWidth(lhs.tp)))) 
    case UnbSatDiv(x,y) => MathDL(lhs, rhs, latencyOption("SatDiv", Some(bitWidth(lhs.tp)))) 
    case FixMul(x,y) => MathDL(lhs, rhs, latencyOption("FixMul", Some(bitWidth(lhs.tp))))
    case FixDiv(x,y) => MathDL(lhs, rhs, latencyOption("FixDiv", Some(bitWidth(lhs.tp))))
    case FixRecipSqrt(a) => 
      emitGlobalWireMap(src"${lhs}_one", src"Wire(${lhs.tp})")
      emit(src"${lhs}_one.r := 1.FP(${lhs}_one.s, ${lhs}_one.d, ${lhs}_one.f).r")
      MathDL(lhs, rhs, latencyOption("FixDiv", Some(bitWidth(lhs.tp)))) 
    case FixRecip(y) => 
      emitGlobalWireMap(src"${lhs}_one", src"Wire(${lhs.tp})")
      emit(src"${lhs}_one.r := 1.FP(${lhs}_one.s, ${lhs}_one.d, ${lhs}_one.f).r")
      MathDL(lhs, rhs, latencyOption("FixDiv", Some(bitWidth(lhs.tp)))) 
    case FixMod(x,y) => MathDL(lhs, rhs, latencyOption("FixMod", Some(bitWidth(lhs.tp)))) 
    case FixFMA(x,y,z) => MathDL(lhs, rhs, latencyOption("FixFMA", Some(bitWidth(lhs.tp)))) 
      

    case SatAdd(x,y) => MathDL(lhs, rhs, latencyOption("FixAdd", Some(bitWidth(lhs.tp))))
    case SatSub(x,y) => MathDL(lhs, rhs, latencyOption("FixSub", Some(bitWidth(lhs.tp))))
    case FixSLA(x,y) => 
      val shift = DLTrace(y).getOrElse(throw new Exception("Cannot shift by non-constant amount in accel")).replaceAll("\\.FP.*|\\.U.*|\\.S.*|L","")
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := ($x.r << $shift).r // TODO: cast to proper type (chisel expands bits)")
    case FixSRA(x,y) => 
      val shift = DLTrace(y).getOrElse(throw new Exception("Cannot shift by non-constant amount in accel")).replaceAll("\\.FP.*|\\.U.*|\\.S.*|L","")
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := ($x >> $shift).r")
    case FixSRU(x,y) => 
      val shift = DLTrace(y).getOrElse(throw new Exception("Cannot shift by non-constant amount in accel")).replaceAll("\\.FP.*|\\.U.*|\\.S.*|L","")
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := ($x >>> $shift).r")
    case BitRandom(None) if lhs.parent.s.isDefined => emitt(src"val $lhs = Math.fixrand(${scala.math.random*scala.math.pow(2, bitWidth(lhs.tp))}.toInt, ${bitWidth(lhs.tp)}, ${swap(lhs.parent.s.get, DatapathEn)}) === 1.U")
    case FixRandom(None) if lhs.parent.s.isDefined => emitGlobalWire(src"val $lhs = Wire(${lhs.tp})");emitt(src"$lhs.r := Math.fixrand(${scala.math.random*scala.math.pow(2, bitWidth(lhs.tp))}.toInt, ${bitWidth(lhs.tp)}, ${swap(lhs.parent.s.get, DatapathEn)}).r")
    case FixRandom(x) =>
      val FixPtType(s,d,f) = lhs.tp
      emitGlobalWire(src"val $lhs = Wire(${lhs.tp})")
      val seed = (scala.math.random*1000).toInt
      val size = x match{
        case Some(Const(xx)) => s"$xx"
        case Some(_) => s"$x"
        case None => "4096"
      }
      emitt(s"val ${quote(lhs)}_bitsize = fringe.utils.log2Up($size) max 1")
      emitGlobalModule(src"val ${lhs}_rng = Module(new PRNG($seed))")
      val en = if (lhs.parent.s.isDefined) src"${swap(lhs.parent.s.get, DatapathEn)}" else "true.B"
      emitGlobalModule(src"${lhs}_rng.io.en := $en")
      emitt(src"${lhs}.r := ${lhs}_rng.io.output(${lhs}_bitsize,0)")
    case FltRandom(None) if lhs.parent.s.isDefined => 
      val FltPtType(m,e) = lhs.tp
      emitGlobalWire(src"val $lhs = Wire(${lhs.tp})")
      emitt(src"$lhs.r := Math.frand(${scala.math.random*scala.math.pow(2, bitWidth(lhs.tp))}.toInt, $m, $e, ${swap(lhs.parent.s.get, DatapathEn)}).r")
    case FltRandom(x) => throw new Exception(s"Can only generate random float with no bounds right now!")
      // emitGlobalWire(src"val $lhs = Wire(${lhs.tp})")
      // val seed = (scala.math.random*1000).toInt
      // val size = x match{
      //   case Some(Const(xx)) => s"$xx"
      //   case Some(_) => s"$x"
      //   case None => "4096"
      // }
      // emitt(s"val ${quote(lhs)}_bitsize = fringe.utils.log2Up($size) max 1")
      // emitGlobalModule(src"val ${lhs}_rng = Module(new PRNG($seed))")
      // val en = if (lhs.parent.s.isDefined) src"${swap(lhs.parent.s.get, DatapathEn)}" else "true.B"
      // emitGlobalModule(src"${lhs}_rng.io.en := $en")
      // emitt(src"${lhs}.r := ${lhs}_rng.io.output(${lhs}_bitsize,0)")

    case FixAbs(x) =>
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})")
      emitt(src"$lhs.r := Mux($x < 0.U, -$x, $x).r")

    case FixSqrt(x) => MathDL(lhs, rhs, latencyOption("FixSqrt", Some(bitWidth(lhs.tp))))
    case FixSin(x) => MathDL(lhs, rhs, latencyOption("FixSin", Some(bitWidth(lhs.tp))))
    case FixCos(x) => MathDL(lhs, rhs, latencyOption("FixCos", Some(bitWidth(lhs.tp))))
    case FixAtan(x) => MathDL(lhs, rhs, latencyOption("FixAtan", Some(bitWidth(lhs.tp))))
    case FixSinh(x) => MathDL(lhs, rhs, latencyOption("FixSinh", Some(bitWidth(lhs.tp))))
    case FixCosh(x) => MathDL(lhs, rhs, latencyOption("FixCosh", Some(bitWidth(lhs.tp))))
    case FltFMA(x,y,z) => MathDL(lhs, rhs, latencyOption("FltFMA", Some(bitWidth(lhs.tp)))) 

    case FltNeg(x) =>
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})")
      emitt(src"$lhs.r := (-$x).r")

    case FltAdd(x,y) => MathDL(lhs, rhs, latencyOption("FltAdd", Some(bitWidth(lhs.tp))))
    case FltSub(x,y) => MathDL(lhs, rhs, latencyOption("FltSub", Some(bitWidth(lhs.tp))))
    case FltMul(x,y) => MathDL(lhs, rhs, latencyOption("FltMul", Some(bitWidth(lhs.tp))))
    case FltDiv(x,y) => MathDL(lhs, rhs, latencyOption("FltDiv", Some(bitWidth(lhs.tp))))
    case FltMax(x,y) => 
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})")
      emitt(src"$lhs.r := Mux($x > $y, ${x}.r, ${y}.r)")

    case FltMin(x,y) => 
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})")
      emitt(src"$lhs.r := Mux($x < $y, ${x}.r, ${y}.r)")

    case FltAbs(x) => 
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})")
      emitt(src"$lhs.r := chisel3.util.Cat(false.B, ${x}(${x}.getWidth-1,0))")

    case FltPow(x,exp) => throw new Exception(s"FltPow($x, $exp) should have transformed to either a multiply tree (constant exp) or reduce structure (variable exp)")

    case FltSqrt(x) => MathDL(lhs, rhs, latencyOption("FltSqrt", Some(bitWidth(lhs.tp))))

    // case FltPow(x,y) => if (emitEn) throw new Exception("Pow not implemented in hardware yet!")
    // case FixFloor(x) => emitt(src"val $lhs = floor($x)")
    // case FixCeil(x) => emitt(src"val $lhs = ceil($x)")

    // case FltSin(x)  => throw new spatial.TrigInAccelException(lhs)
    // case FltCos(x)  => throw new spatial.TrigInAccelException(lhs)
    // case FltTan(x)  => throw new spatial.TrigInAccelException(lhs)
    // case FltSinh(x) => throw new spatial.TrigInAccelException(lhs)
    // case FltCosh(x) => throw new spatial.TrigInAccelException(lhs)
    // case FltTanh(x) => emitt(src"val $lhs = tanh($x)")
    // case FltSigmoid(x) => emitt(src"val $lhs = sigmoid($x)")
    // case FltAsin(x) => throw new spatial.TrigInAccelException(lhs)
    // case FltAcos(x) => throw new spatial.TrigInAccelException(lhs)
    // case FltAtan(x) => throw new spatial.TrigInAccelException(lhs)

    case OneHotMux(sels, opts) => 
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})")
      emitt(src"$lhs.r := Mux1H(List($sels), List(${opts.map{x => src"$x.r"}}))")

    case Mux(sel, a, b) => 
      emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})")
      emitt(src"$lhs.r := Mux(($sel), $a.r, $b.r)")

    case FixMin(a, b) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := Mux(($a < $b), $a, $b).r")
    case FixMax(a, b) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := Mux(($a > $b), $a, $b).r")
    case FixToFix(a, fmt) => MathDL(lhs, rhs, latencyOption("FixToFix", Some(bitWidth(lhs.tp))))
    case FixToFixSat(a, fmt) => MathDL(lhs, rhs, latencyOption("FixToFixSat", Some(bitWidth(lhs.tp))))
    case FixToFixUnb(a, fmt) => MathDL(lhs, rhs, latencyOption("FixToFixUnb", Some(bitWidth(lhs.tp))))
    case FixToFixUnbSat(a, fmt) => MathDL(lhs, rhs, latencyOption("FixToFixUnbSat", Some(bitWidth(lhs.tp))))
    case FltToFlt(a, fmt) => MathDL(lhs, rhs, latencyOption("FltToFlt", Some(bitWidth(lhs.tp))))
    case FixToFlt(a, fmt) => MathDL(lhs, rhs, latencyOption("FixToFlt", Some(bitWidth(lhs.tp))))
    case FltToFix(a, fmt) => MathDL(lhs, rhs, latencyOption("FltToFix", Some(bitWidth(lhs.tp))))
    case FltRecip(x) => MathDL(lhs, rhs, latencyOption("FltRecip", Some(bitWidth(lhs.tp)))) 
    
    case And(a, b) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs := $a & $b")
    case Not(a) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs := ~$a")
    case Or(a, b) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs := $a | $b")
    case Xor(a, b) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs := $a ^ $b")
    case Xnor(a, b) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs := ~($a ^ $b)")

    case FixFloor(a) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := Cat($a.raw_dec, 0.U(${fracBits(a)}.W))")
    case FixCeil(a) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := Mux($a.raw_frac === 0.U, $a.r, Cat($a.raw_dec + 1.U, 0.U(${fracBits(a)}.W)))")
    // case FltFloor(a) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := Cat($a.raw_dec, 0.U(${fracBits(a)}.W))")
    // case FltCeil(a) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := Mux($a.raw_frac === 0.U, $a.r, Cat($a.raw_dec + 1.U, 0.U(${fracBits(a)}.W)))")
    case DataAsBits(data) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.zipWithIndex.foreach{case (dab, i) => dab := $data(i)}")
    case BitsAsData(data, fmt) => emitGlobalWireMap(src"$lhs", src"Wire(${lhs.tp})");emitt(src"$lhs.r := chisel3.util.Cat($data.reverse)")
    // case FltInvSqrt(x) => x.tp match {
    //   case DoubleType() => throw new Exception("DoubleType not supported for FltInvSqrt") 
    //   case HalfType() =>  emitt(src"val $lhs = frsqrt($x)")
    //   case FloatType()  => emitt(src"val $lhs = frsqrt($x)")
    // }

	  case _ => super.gen(lhs, rhs)
  }


}