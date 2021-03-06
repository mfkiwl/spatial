package spatial.lang

import argon._
import forge.tags._
import utils.implicits.collections._
import spatial.util._
import utils.io.files._

import spatial.node._
import spatial.lang.types._

abstract class LUT[A:Bits,C[T]](implicit val evMem: C[A] <:< LUT[A,C]) extends LocalMem[A,C] with TensorMem[A]{
  val A: Bits[A] = Bits[A]
  protected def M1: Type[LUT1[A]] = implicitly[Type[LUT1[A]]]
  protected def M2: Type[LUT2[A]] = implicitly[Type[LUT2[A]]]
  protected def M3: Type[LUT3[A]] = implicitly[Type[LUT3[A]]]
  protected def M4: Type[LUT4[A]] = implicitly[Type[LUT4[A]]]
  protected def M5: Type[LUT5[A]] = implicitly[Type[LUT5[A]]]
  def rank: Int

  /** Returns the dimensions of this LUT as a Sequence. */
  @api def dims: Seq[I32] = Seq.tabulate(rank){d => stage(MemDim(this,d)) }

  /** Returns the value at `addr`.
    * The number of indices should match the LUT's rank.
    * NOTE: Use the apply method if the LUT's rank is statically known.
    */
  @api def read(addr: Seq[Idx], ens: Set[Bit] = Set.empty): A = {
    checkDims(addr.length)
    stage(LUTRead[A,C](me,addr,Set.empty))
  }

  @rig private def checkDims(given: Int): Unit = {
    if (given != rank) {
      error(ctx, s"Expected a $rank-dimensional address for $this (${this.name}), got a $given-dimensional address.")
      error(ctx)
    }
  }

  // --- Typeclass Methods
  @rig def __read(addr: Seq[Idx], ens: Set[Bit]): A = read(addr, ens)
  @rig def __write(data: A, addr: Seq[Idx], ens: Set[Bit]): Void = {
    error(ctx, "Cannot write to LUT")
    error(ctx)
    err[Void]("Cannot write to LUT")
  }
  @rig def __reset(ens: Set[Bit]): Void = void
}
object LUT {
  /** Allocates a 1-dimensional [[LUT1]] with capacity of `length` elements of type A. */
  @api def apply[A:Bits](length: Int)(elems: Bits[A]*): LUT1[A] = {
    stage(LUTNew[A,LUT1](Seq(length),elems))
  }

  /** Allocates a 2-dimensional [[LUT2]] with `rows` x `cols` elements of type A. */
  @api def apply[A:Bits](rows: Int, cols: Int)(elems: Bits[A]*): LUT2[A] = {
    stage(LUTNew[A,LUT2](Seq(rows,cols), elems))
  }

  /** Allocates a 3-dimensional [[LUT3]] with the given dimensions and elements of type A. */
  @api def apply[A:Bits](d0: Int, d1: Int, d2: Int)(elems: Bits[A]*): LUT3[A] = {
    stage(LUTNew[A,LUT3](Seq(d0,d1,d2), elems))
  }

  /** Allocates a 4-dimensional [[LUT4]] with the given dimensions and elements of type A. */
  @api def apply[A:Bits](d0: Int, d1: Int, d2: Int, d3: Int)(elems: Bits[A]*): LUT4[A] = {
    stage(LUTNew[A,LUT4](Seq(d0,d1,d2,d3), elems))
  }

  /** Allocates a 5-dimensional [[LUT5]] with the given dimensions and elements of type A. */
  @api def apply[A:Bits](d0: Int, d1: Int, d2: Int, d3: Int, d4: Int)(elems: Bits[A]*): LUT5[A] = {
    stage(LUTNew[A,LUT5](Seq(d0,d1,d2,d3,d4), elems))
  }

  /** Allocates a 2-dimensional [[LUT2]] with `rows` x `cols` elements of type A. */
  @api def fromSeq[A:Bits](rows: Int, cols: Int)(elems: Seq[Bits[A]]): LUT2[A] = {
    stage(LUTNew[A,LUT2](Seq(rows,cols), elems))
  }

  /** Allocates a 3-dimensional [[LUT3]] with the given dimensions and elements of type A. */
  @api def fromSeq[A:Bits](d0: Int, d1: Int, d2: Int)(elems: Seq[Bits[A]]): LUT3[A] = {
    stage(LUTNew[A,LUT3](Seq(d0,d1,d2), elems))
  }

  /** Allocates a 4-dimensional [[LUT4]] with the given dimensions and elements of type A. */
  @api def fromSeq[A:Bits](d0: Int, d1: Int, d2: Int, d3: Int)(elems: Seq[Bits[A]]): LUT4[A] = {
    stage(LUTNew[A,LUT4](Seq(d0,d1,d2,d3), elems))
  }

  /** Allocates a 5-dimensional [[LUT5]] with the given dimensions and elements of type A. */
  @api def fromSeq[A:Bits](d0: Int, d1: Int, d2: Int, d3: Int, d4: Int)(elems: Seq[Bits[A]]): LUT5[A] = {
    stage(LUTNew[A,LUT5](Seq(d0,d1,d2,d3,d4), elems))
  }


  /** Allocates a 1-dimensional [[LUT1]] with capacity of `length` elements of type A. */
  @api def fromSeq[A:Bits](elems: Seq[Bits[A]]): LUT1[A] = {
    stage(LUTNew[A,LUT1](Seq(elems.size),elems))
  }

  /** Allocates a 2-dimensional [[LUT2]] with `rows` x `cols` elements of type A. */
  @api def fromSeq[A:Bits](elems: Seq[Seq[Bits[A]]]): LUT2[A] = {
    val row = elems.size
    val col = elems.head.size
    stage(LUTNew[A,LUT2](Seq(elems.size,col), elems.flatten))
  }

  /** Allocates a 3-dimensional [[LUT3]] with the given dimensions and elements of type A. */
  @api def fromSeq[A:Bits](elems: Seq[Seq[Seq[Bits[A]]]]): LUT3[A] = {
    val d0 = elems.size
    val d1 = elems.head.size
    val d2 = elems.head.head.size
    stage(LUTNew[A,LUT3](Seq(d0,d1,d2), elems.map { _.flatten}.flatten))
  }

  /** Allocates a 1-dimensional [[LUT1]] with capacity of `length` and elements of type A.  
    * Elements are read from csv file at compile time with delimiter ",". 
    */
  @api def fromFile[A:Bits](length: Int)(filename: String): LUT1[A] = {
    val elems = loadCSVNow[A](filename, ",")(parseValue[A]).map(_.asInstanceOf[Bits[A]])
    stage(LUTNew[A,LUT1](Seq(length),elems))
  }

 /** Allocates a 2-dimensional [[LUT2]] with `rows` x `cols` and elements of type A.  
   * Elements are read from csv file at compile time with delimiter ",". 
   */
 @api def fromFile[A:Bits](rows: Int, cols: Int)(filename: String): LUT2[A] = {
  val elems = loadCSVNow[A](filename, ",")(parseValue[A]).map(_.asInstanceOf[Bits[A]])
   stage(LUTNew[A,LUT2](Seq(rows,cols), elems))
 }

 /** Allocates a 3-dimensional [[LUT3]] with the given dimensions and elements of type A.  
   * Elements are read from csv file at compile time with delimiter ",". 
   */
 @api def fromFile[A:Bits](d0: Int, d1: Int, d2: Int)(filename: String): LUT3[A] = {
  val elems = loadCSVNow[A](filename, ",")(parseValue[A]).map(_.asInstanceOf[Bits[A]])
   stage(LUTNew[A,LUT3](Seq(d0,d1,d2), elems))
 }

 /** Allocates a 4-dimensional [[LUT4]] with the given dimensions and elements of type A.  
   * Elements are read from csv file at compile time with delimiter ",". 
   */
 @api def fromFile[A:Bits](d0: Int, d1: Int, d2: Int, d3: Int)(filename: String): LUT4[A] = {
  val elems = loadCSVNow[A](filename, ",")(parseValue[A]).map(_.asInstanceOf[Bits[A]])
   stage(LUTNew[A,LUT4](Seq(d0,d1,d2,d3), elems))
 }

 /** Allocates a 5-dimensional [[LUT5]] with the given dimensions and elements of type A.  
   * Elements are read from csv file at compile time with delimiter ",". 
   */
 @api def fromFile[A:Bits](d0: Int, d1: Int, d2: Int, d3: Int, d4: Int)(filename: String): LUT5[A] = {
  val elems = loadCSVNow[A](filename, ",")(parseValue[A]).map(_.asInstanceOf[Bits[A]])
   stage(LUTNew[A,LUT5](Seq(d0,d1,d2,d3,d4), elems))
 }

}

object FileLUT {
  /** Allocates a 1-dimensional [[LUT1]] with capacity of `length` elements of type A. */
  @api def apply[A:Bits](length: Int)(filename: String): LUT1[A] = {
    stage(FileLUTNew[A,LUT1](Seq(length),filename))
  }

  /** Allocates a 2-dimensional [[LUT2]] with `rows` x `cols` elements of type A. */
  @api def apply[A:Bits](rows: Int, cols: Int)(filename: String): LUT2[A] = {
    stage(FileLUTNew[A,LUT2](Seq(rows,cols),filename))
  }

  /** Allocates a 3-dimensional [[LUT3]] with the given dimensions and elements of type A. */
  @api def apply[A:Bits](d0: Int, d1: Int, d2: Int)(filename: String): LUT3[A] = {
    stage(FileLUTNew[A,LUT3](Seq(d0,d1,d2),filename))
  }

  /** Allocates a 4-dimensional [[LUT4]] with the given dimensions and elements of type A. */
  @api def apply[A:Bits](d0: Int, d1: Int, d2: Int, d3: Int)(filename: String): LUT4[A] = {
    stage(FileLUTNew[A,LUT4](Seq(d0,d1,d2,d3),filename))
  }

  /** Allocates a 5-dimensional [[LUT5]] with the given dimensions and elements of type A. */
  @api def apply[A:Bits](d0: Int, d1: Int, d2: Int, d3: Int, d4: Int)(filename: String): LUT5[A] = {
    stage(FileLUTNew[A,LUT5](Seq(d0,d1,d2,d3,d4),filename))
  }
}

/** A 1-dimensional LUT with elements of type A. */
@ref class LUT1[A:Bits]
  extends LUT[A,LUT1]
    with LocalMem1[A,LUT1]
    with ReadMem1[A]
    with Mem1[A,LUT1]
    with Ref[Array[Any],LUT1[A]] {

  def rank: Int = 1
  @api def length: I32 = dims.head
  @api override def size: I32 = dims.head

  /** Returns the value at `pos`. */
  @api def apply(pos: I32): A = stage(LUTRead(this,Seq(pos),Set.empty))
}

/** A 2-dimensional LUT with elements of type A. */
@ref class LUT2[A:Bits]
  extends LUT[A,LUT2]
    with LocalMem2[A,LUT2]
    with ReadMem2[A]
    with Mem2[A,LUT1,LUT2]
    with Ref[Array[Any],LUT2[A]] {
  def rank: Int = 2
  @api def rows: I32 = dims.head
  @api def cols: I32 = dim1

  /** Returns the value at (`row`, `col`). */
  @api def apply(row: I32, col: I32): A = stage(LUTRead(this,Seq(row,col),Set.empty))
}

/** A 3-dimensional LUT with elements of type A. */
@ref class LUT3[A:Bits]
  extends LUT[A,LUT3]
    with ReadMem3[A]
    with LocalMem3[A,LUT3]
    with Mem3[A,LUT1,LUT2,LUT3]
    with Ref[Array[Any],LUT3[A]] {

  def rank: Int = 3

  /** Returns the value at (`d0`,`d1`,`d2`). */
  @api def apply(d0: I32, d1: I32, d2: I32): A = stage(LUTRead(this,Seq(d0,d1,d2),Set.empty))
}

/** A 4-dimensional LUT with elements of type A. */
@ref class LUT4[A:Bits]
  extends LUT[A,LUT4]
    with LocalMem4[A,LUT4]
    with ReadMem4[A]
    with Mem4[A,LUT1,LUT2,LUT3,LUT4]
    with Ref[Array[Any],LUT4[A]] {

  def rank: Int = 4

  /** Returns the value at (`d0`,`d1`,`d2`,`d3`). */
  @api def apply(d0: I32, d1: I32, d2: I32, d3: I32): A = {
    stage(LUTRead(this,Seq(d0,d1,d2,d3),Set.empty))
  }
}

/** A 5-dimensional LUT with elements of type A. */
@ref class LUT5[A:Bits]
  extends LUT[A,LUT5]
    with LocalMem5[A,LUT5]
    with ReadMem5[A]
    with Mem5[A,LUT1,LUT2,LUT3,LUT4,LUT5]
    with Ref[Array[Any],LUT5[A]] {

  def rank: Int = 5

  /** Returns the value at (`d0`,`d1`,`d2`,`d3`,`d4`). */
  @api def apply(d0: I32, d1: I32, d2: I32, d3: I32, d4: I32): A = {
    stage(LUTRead(this,Seq(d0,d1,d2,d3,d4),Set.empty))
  }
}




