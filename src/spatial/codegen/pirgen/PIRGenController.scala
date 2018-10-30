package spatial.codegen.pirgen

import argon._
import argon.node._
import spatial.metadata.control._
import spatial.metadata.memory._
import spatial.metadata.types._
import spatial.lang._
import spatial.node._

trait PIRGenController extends PIRCodegen {

  override def emitAccelHeader = {
    super.emitAccelHeader
    emit("""
    val ctrlMap = scala.collection.mutable.Map[ControlTree, Controller]()
    def create[T<:Controller](schedule:String)(newCtrler: => T):T = {
      val tree = ControlTree(schedule)
      beginState(tree)
      val ctrler = newCtrler
      tree.ctrler(ctrler)
      ctrlMap.get(tree.parent.get.as[ControlTree]).fold { 
        //ctrler.parentEn(hostWrite)
      } { pctrler =>
        ctrler.parentEn(pctrler.valid)
      }
      ctrlMap += tree -> ctrler
      ctrler
    }
""")
  }

  override protected def genHost(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case AccelScope(func) => 
      emit("runAccel()")
      inAccel { 
        genInAccel(lhs, rhs)
      }

    case _ => super.genHost(lhs, rhs)
  }

  def emitIterValids(lhs:Sym[_], iters:Seq[Seq[Sym[_]]], valids:Seq[Seq[Sym[_]]]) = {
    iters.zipWithIndex.foreach { case (iters, i) =>
      iters.zipWithIndex.foreach { case (iter, j) =>
        state(iter)(src"CounterIter($i).counter($lhs.cchain.T($i)).resetParent($lhs)")
      }
    }
    valids.zipWithIndex.foreach { case (valids, i) =>
      valids.zipWithIndex.foreach { case (valid, j) =>
        state(valid)(src"CounterValid($j).counter($lhs.cchain.T($i)).resetParent($lhs)")
      }
    }
  }

  def emitController(
    lhs:Lhs, 
    ctrler:Option[String]=None,
    schedule:Option[Any]=None,
    cchain:Option[Sym[_]]=None, 
    iters:Seq[Seq[Sym[_]]]=Nil, 
    valids: Seq[Seq[Sym[_]]]=Nil, 
    ens:Set[Bit]=Set.empty
  )(blk: => Unit) = {
    val newCtrler = ctrler.getOrElse("UnitController()")
    val tp = newCtrler.trim.split("\\(")(0).split(" ").last
    state(lhs, tp=Some(tp))(
      src"""create(schedule="${schedule.getOrElse(lhs.sym.schedule)}")(${newCtrler})""" + 
      cchain.ms(chain => src".cchain($chain)") +
      (if (ens.isEmpty) "" else src".en($ens)")
    )
    emitIterValids(lhs.sym, iters, valids)
    blk
    emit(src"endState[Ctrl]")
  }

  override protected def genAccel(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case AccelScope(func) =>
      emitController(lhs) { ret(func) }

    case UnitPipe(ens, func) =>
      emitController(lhs, ens=ens) { ret(func) }

    case ParallelPipe(ens, func) =>
      emitController(lhs, ens=ens) { ret(func) }

    case UnrolledForeach(ens,cchain,func,iters,valids) =>
      emitController(lhs, ctrler=Some("LoopController()"), cchain=Some(cchain), iters=iters, valids=valids, ens=ens) { ret(func) }

    case UnrolledReduce(ens,cchain,func,iters,valids) =>
      emitController(lhs, ctrler=Some("LoopController()"), cchain=Some(cchain), iters=iters, valids=valids, ens=ens) { ret(func) }

    case op@Switch(selects, body) =>
      emit(s"//TODO: ${qdef(lhs)}")

    case SwitchCase(body) => // Controlled by Switch
      emit(s"//TODO: ${qdef(lhs)}")

    case StateMachine(ens, start, notDone, action, nextState) =>
      emit(s"//TODO: ${qdef(lhs)}")

    case IfThenElse(cond, thenp, elsep) =>
      emit(s"//TODO: ${qdef(lhs)}")

    case _ => super.genAccel(lhs, rhs)
  }

}
