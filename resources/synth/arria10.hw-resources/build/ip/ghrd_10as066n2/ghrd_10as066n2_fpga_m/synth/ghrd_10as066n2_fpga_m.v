// ghrd_10as066n2_fpga_m.v

// Generated using ACDS version 17.1 240

`timescale 1 ps / 1 ps
module ghrd_10as066n2_fpga_m (
		input  wire        clk_clk,              //          clk.clk
		input  wire        clk_reset_reset,      //    clk_reset.reset
		output wire [31:0] master_address,       //       master.address
		input  wire [31:0] master_readdata,      //             .readdata
		output wire        master_read,          //             .read
		output wire        master_write,         //             .write
		output wire [31:0] master_writedata,     //             .writedata
		input  wire        master_waitrequest,   //             .waitrequest
		input  wire        master_readdatavalid, //             .readdatavalid
		output wire [3:0]  master_byteenable,    //             .byteenable
		output wire        master_reset_reset    // master_reset.reset
	);

	ghrd_10as066n2_fpga_m_altera_jtag_avalon_master_171_wqhllki #(
		.USE_PLI     (0),
		.PLI_PORT    (50000),
		.FIFO_DEPTHS (2)
	) fpga_m (
		.clk_clk              (clk_clk),              //   input,   width = 1,          clk.clk
		.clk_reset_reset      (clk_reset_reset),      //   input,   width = 1,    clk_reset.reset
		.master_address       (master_address),       //  output,  width = 32,       master.address
		.master_readdata      (master_readdata),      //   input,  width = 32,             .readdata
		.master_read          (master_read),          //  output,   width = 1,             .read
		.master_write         (master_write),         //  output,   width = 1,             .write
		.master_writedata     (master_writedata),     //  output,  width = 32,             .writedata
		.master_waitrequest   (master_waitrequest),   //   input,   width = 1,             .waitrequest
		.master_readdatavalid (master_readdatavalid), //   input,   width = 1,             .readdatavalid
		.master_byteenable    (master_byteenable),    //  output,   width = 4,             .byteenable
		.master_reset_reset   (master_reset_reset)    //  output,   width = 1, master_reset.reset
	);

endmodule
