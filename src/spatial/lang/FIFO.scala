package spatial.lang

import argon._
import forge.tags._
import spatial.node._

import scala.collection.mutable.Queue

@ref class FIFO[A:Bits] extends Top[FIFO[A]]
         with LocalMem1[A,FIFO]
         with Ref[Queue[Any],FIFO[A]] {
  val A: Bits[A] = Bits[A]
  val evMem: FIFO[A] <:< LocalMem[A,FIFO] = implicitly[FIFO[A] <:< LocalMem[A,FIFO]]
}
object FIFO {
  @api def apply[A:Bits](depth: I32): FIFO[A] = stage(FIFONew(depth))
}