rm arria10_argInOuts.vcd
iverilog -o arria10Test SpatialIP.v Arria10_tb.v RetimeShiftRegister.v SRAMVerilogAWS.v
vvp arria10Test
echo "regenerated"
