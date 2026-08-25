// Exercises the EIP-7843 instruction SLOTNUM (0x4b), which pushes the current
// block's consensus-layer slot number as a uint64. No released compiler emits
// it, so it reaches the bytecode through Yul's `verbatim`.
object "Eip7843" {
    code {
        datacopy(0, dataoffset("Runtime"), datasize("Runtime"))
        return(0, datasize("Runtime"))
    }
    object "Runtime" {
        code {
            if lt(calldatasize(), 4) { revert(0, 0) }
            switch shr(224, calldataload(0))
            // keccak256("slotnum()")
            case 0x0bb7676a {
                ret(slotnum())
            }
            // keccak256("slotnumDelta()"). Two SLOTNUM executions in one
            // transaction must agree, so this is 0. Default.conf leaves the
            // optimizer off, which keeps solc from folding them into one.
            case 0xc985f364 {
                ret(sub(slotnum(), slotnum()))
            }
            default { revert(0, 0) }

            function slotnum() -> v { v := verbatim_0i_1o(hex"4b") }
            function ret(v) { mstore(0, v) return(0, 32) }
        }
    }
}
