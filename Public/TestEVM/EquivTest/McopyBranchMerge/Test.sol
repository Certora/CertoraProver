// Regression test for a crash in the equivalence checker's buffer-update
// resolution (BufferTraceInstrumentation.getBufferUpdateFor, the MCOPY_BUFFER
// case). In V1 the external call's `bytes` argument is built on two branches
// that merge before the call. Under via-IR the branch that copies
// (bytes.concat) feeds the merged buffer to the call via an mcopy whose source
// buffer is defined once per unrolled loop iteration. An earlier iteration's
// defining copy reaches a later iteration's read but does not dominate it (the
// branch lets it be skipped), so no candidate is "dominated by all others" --
// which used to make a `.single { ... }` throw NoSuchElementException while
// instrumenting V1, before any solving.
//
// V2 is intentionally empty so the equivalence check FAILS fast (one easy
// counterexample) instead of spending minutes proving equivalence: the test
// asserts the instrumentation survives, not that the contracts match.
interface ICallee {
    function sink(bytes memory sig) external;
}

contract V1 {
    function impl(address t, bool[] memory flags, bytes memory blob) external {
        for (uint256 i; i < flags.length; i++) {
            bytes memory sig;
            if (flags[i]) {
                sig = bytes.concat(blob);
            } else {
                sig = blob;
            }
            ICallee(t).sink(sig);
        }
    }
}

contract V2 {
    function impl(address t, bool[] memory flags, bytes memory blob) external {}
}
