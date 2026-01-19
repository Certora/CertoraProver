Gimli-DWARF-JSONDump
----
This rust program extract DWARF debug information into a JSON dump for further processing on the Kotlin side of CertoraProver 
(see class DebugSymbolLoader). It is based on https://github.com/gimli-rs/gimli a library for reading DWARF debug information 
from LLVM compiled binaries.

The JSON dump will contain all required information to enrich the TAC dump with debug information, this includes start and end 
addresses of function names, start and end ranges of function that have been inlined by the LLVM compiler, formal parameters 
and variable names and their types. We also extract line number information from DWARF to be able to map all addresses
back to the source code.
 

Implementation Details
----

A DWARF dump can be obtained via:

* `cargo run --bin dwarfdump -- <PATH_TO_SO_FILE>` (using gimli) or via
* `llvm-dwarfdump <PATH_TO_SO_FILE>` (using LLVM)


The information below provide a rough guidance, more information can also be found at https://dwarfstd.org/dwarf5std.html

Here is an example of debug information as found in the .debug_info section - the dwarf dump of a Subprogram with two formal parameters, the information contains line number information
(decl_line / decl_file attributes). The sources are (source see src/test/resources/solana/debugger_tests/src/certora/spec/rule_add_with_function.rs)


< 4><0x00008b74>          DW_TAG_namespace
                            DW_AT_name                  rule_add_with_function
< 5><0x00008b79>            DW_TAG_subprogram
                              DW_AT_linkage_name          _ZN14debugger_tests7certora4spec22rule_add_with_function10faulty_add17haa870ed6dd92d490E
                              DW_AT_name                  faulty_add
                              DW_AT_decl_file             0x00000028 src/certora/spec/rule_add_with_function.rs
                              DW_AT_decl_line             0x0000000b
                              DW_AT_type                  0x000040f4<.debug_info+0x000040f4>
                              DW_AT_inline                DW_INL_inlined
< 6><0x00008b89>              DW_TAG_lexical_block
< 7><0x00008b8a>                DW_TAG_formal_parameter
                                  DW_AT_name                  faulty_add_param1
                                  DW_AT_decl_file             0x00000028 src/certora/spec/rule_add_with_function.rs
                                  DW_AT_decl_line             0x0000000b
                                  DW_AT_type                  0x000040f4<.debug_info+0x000040f4>
< 7><0x00008b95>                DW_TAG_formal_parameter
                                  DW_AT_name                  faulty_add_param2
                                  DW_AT_decl_file             0x00000028 src/certora/spec/rule_add_with_function.rs
                                  DW_AT_decl_line             0x0000000b

The .debug_info section is a tree structure, additionally one can follow references to other offsets, see for instance the DW_AT_type attribute that
states where the type information for that variable / program is located. Looking up this offset yields this information.


Line Number Information
---
A DW_TAG_subprogram can also contain a node with DW_TAG_inlined_subroutine, this means the compiler inlined the method / function.
A DW_TAG_inlined_subroutine entry also contains a DW_AT_call_file / DW_AT_call_line which matches the call site of the inlined method, this is one
source of the line number.

A second source of line number information is the line number program, which is maintained in the .debug_line section.

Variable and Type Information
---
In the example above, the variable faulty_add_param1 states it's type is defined in 0x000040f4<.debug_info+0x000040f4>, looking up this offset one finds.

< 1><0x000040f4>    DW_TAG_base_type
                      DW_AT_name                  u64
                      DW_AT_encoding              DW_ATE_unsigned
                      DW_AT_byte_size             0x00000008

As type can be recursive, extracting the information from DWARF and serializing it requires a worklist algorithm.

(Variable) Locations  List
---
Location lists in DWARF encode where the variables is located (i.e. in which range and in which registers). Here is an example of a formal parameter
variable that has a location list:


< 6><0x000058ea>              DW_TAG_formal_parameter
                                DW_AT_location              <loclist at .debug_loc+0x000026b3>
			[ 0]<base-address 0xd70>
			[ 1]<offset-pair 0x0, 0x8> [0xd70, 0xd78]DW_OP_reg2
			[ 2]<offset-pair 0x8, 0x50> [0xd78, 0xdc0]DW_OP_reg6
                                DW_AT_name                  input_a

This encode for the instructions in address range [0xd70, 0xd78] the variable input_a is stored in r2 in the bytecode
and in the range [0xd78, 0xdc0] the variable input_a is stored in r6.

For composite types, for instance, larger structs the values will spread across several registers and DWARF have longer location lists.

This expression `DW_OP_lit1 DW_OP_stack_value DW_OP_piece 4 DW_OP_lit10 DW_OP_stack_value DW_OP_piece 4`

First piece (4 bytes): The value 1

DW_OP_lit1 pushes the literal value 1 onto the stack
DW_OP_stack_value indicates this stack value is the actual value (not an address)
DW_OP_piece 4 takes 4 bytes of this value


Second piece (4 bytes): The value 10

DW_OP_lit10 pushes the literal value 10 onto the stack
DW_OP_stack_value indicates this stack value is the actual value
DW_OP_piece 4 takes 4 bytes of this value

I.e. this could represent a struct with two 32-bit integer fields containing 1 and 10 or a two element array [1, 10] of u32.
While this location list is fully constant, the list can also contain `DW_OP_reg` / `DW_OP_fbreg` entries (and offsets from these registers).
