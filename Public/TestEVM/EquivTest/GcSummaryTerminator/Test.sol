// Regression test for a crash in the equivalence checker's GC-setup instrumentation
// (BufferTraceInstrumentation.instrumentLongRead). An external call forwarding a
// dynamic `bytes` argument builds its calldata via a copy loop, which is modeled as
// a LoopCopySummary -- a ConditionalBlockSummary that terminates its block with two
// successors. That site is also a garbage-collection point, and the GC-setup (a
// straight-line "Starting/End setup for N" sequence) used to be appended *after* the
// summary terminator, leaving the block ending in a LabelCmd yet keeping the summary's
// two successors. checkCodeGraphConsistency then rejected the program with
// TACStructureException "Got entries in code not consistent with graph".
//
// Gsm and GsmB are identical, so this must verify as EQUIVALENT.
contract Gsm {
    function buy(address a, bytes memory sig) external {
        a.call(sig);
    }
}

contract GsmB {
    function buy(address a, bytes memory sig) external {
        a.call(sig);
    }
}
