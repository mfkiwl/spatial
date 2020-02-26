package fringe.targets.de1

import chisel3.Module
import chisel3.core._
import fringe.targets.zynq.ZynqLike
import fringe.{AbstractAccelUnit, BigIP, SpatialIPInterface}

// TODO: At some point we need to unify these Fringe code...
//  So many duplicates!
class DE1Like extends ZynqLike {
  override def makeBigIP: BigIP = new fringe.targets.de1.BigIPDE1

  override def addFringeAndCreateIP(
      reset: Reset,
      accel: AbstractAccelUnit): SpatialIPInterface = {
    val io = IO(new DE1Interface)

    // Avalon Fringe
    val blockingDRAMIssue = false // Allow only one in-flight request, block until response comes back
    val fringe = Module(
      new FringeDE1(blockingDRAMIssue, io.avalonLiteParams, io.avalonParams))

    // Fringe <-> Host connections
    fringe.io.S_AVALON <> io.S_AVALON

    // Fringe <-> DRAM connections
    io.M_AVALON <> fringe.io.M_AVALON

    // TODO: Probe
    io.TOP_M_AVALON <> fringe.io.TOP_M_AVALON
    io.rdata := DontCare

    accel.io.argIns := fringe.io.argIns
    fringe.io.argOuts.zip(accel.io.argOuts) foreach {
      case (fringeArgOut, accelArgOut) =>
        fringeArgOut.bits := accelArgOut.port.bits
        fringeArgOut.valid := accelArgOut.port.valid
    }

    fringe.io.argEchos.zip(accel.io.argOuts) foreach {
      case (fringeArgOut, accelArgOut) =>
        accelArgOut.echo := fringeArgOut
    }

    fringe.io.externalEnable := false.B
    fringe.io.memStreams <> accel.io.memStreams
    fringe.io.heap <> accel.io.heap
    accel.io.enable := fringe.io.enable
    fringe.io.done := accel.io.done
    fringe.reset := reset.toBool
    accel.reset := fringe.io.reset

    io
  }
}

class DE1 extends DE1Like {
  override def makeBigIP: BigIP = new fringe.targets.de1.BigIPDE1

  override def regFileAddrWidth(n: Int): Int = 32

  override val magPipelineDepth: Int = 0
  override val addrWidth: Int = 18
  override val dataWidth: Int = 32
  override val wordsPerStream: Int = 16
  override val num_channels = 1
}