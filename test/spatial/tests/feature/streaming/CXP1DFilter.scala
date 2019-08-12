package spatial.tests.feature.streaming

import spatial.dsl._
import spatial.lang.CXPPixelBus
import spatial.tests.apps._

@spatial class CXP1DFilter extends SpatialTest {
  override def backends = DISABLED


  val pxlBits = 16


  type T = FixPt[TRUE, _16, _16]
  val colTileSize = 512
  val rowTileSize = 64
  val deriv_window = 40
  def main(args: Array[String]): Unit = {

    // Get hard/soft derivative kernels
    val sharp_kernel = Helpers.build_derivkernel(deriv_window/8, deriv_window)
    println(r"""Kernel: ${sharp_kernel.mkString("\t")}""")

    // /** DRAM TESTING */
    // // Get input data
    // val input_data = loadCSV2D[I16](s"$DATA/slac/xppc00117_r136_refsub_ipm4_del3.csv"," ","\n")
    // // Set up dram
    // val COLS = ArgIn[Int]
    // val ROWS = ArgIn[Int]
    // val LINES_TODO = ArgIn[Int]
    // setArg(COLS, input_data.cols)
    // setArg(ROWS, input_data.rows)
    // setArg(LINES_TODO, args(0).to[Int])
    // val input_dram = DRAM[I16](ROWS, COLS)
    // setMem(input_dram, input_data)
    // val output_composite_dram = DRAM[composite](LINES_TODO)

    /** BOARD TESTING */
    val in = StreamIn[U256](CXPPixelBus)
    val LINES_TODO = ArgIn[Int]
    setArg(LINES_TODO, 64)
    val COLS = ArgIn[Int]
    setArg(COLS, 1024)
    val out = StreamOut[U256](CXPPixelBus)

    // Create HW accelerator
    Accel {
      Stream.Foreach(*){r => 
        val input_fifo = FIFO[I16](colTileSize)
        val issue = FIFO[Int](2*rowTileSize)
        val result = FIFO[composite](2*rowTileSize)

        // Stage 1: consume cxp-like stream
        Pipe {
          val raw: U256 = in.value
          input_fifo.enqVec(raw.asVec[I16])
        }

        // Stage 2: Process (Force II = 1 to squeeze sr write and sr read into one cycle)
        Pipe {
          SpatialHelper.ComputeUnit[T](COLS, 1, sharp_kernel, input_fifo, issue, result, r, rowTileSize, LINES_TODO, true)
        }

        // Stage 3: Store
        Pipe{
          val numel = issue.deq()
          if (numel > -1) { // Store every result since we don't care about bursting here
            // // DEBUG
            // deriv store deriv_fifo
            // Store results
            out := result.deqVec(256/16).asPacked[U256]
          }
        }
      }
    }

  }
}
