// Internal-function summaries are matched against markers that certora-cli instruments into the
// sources and the Prover recovers from the compiled bytecode. The Yul full inliner destroys that
// recovery, so certoraBuild sends optimizer steps with the inliner removed; this test fails if a
// compiler version ever gets steps that still run it.
methods {
    function outerOnce(uint256) external returns (uint256) envfree;
    function outerTwice(uint256) external returns (uint256) envfree;

    function calledOnce(uint256 a) internal returns (uint256) => cvlOnce(a);
    function calledTwice(uint256 a) internal returns (uint256) => cvlTwice(a);
}

function cvlOnce(uint256 a) returns uint256 {
    return 42;
}

function cvlTwice(uint256 a) returns uint256 {
    return 7;
}

rule summaryAppliesToSingleCaller(uint256 x) {
    assert outerOnce(x) == 42, "summary of a once-called internal function did not apply";
}

rule summaryAppliesToRepeatedCaller(uint256 x) {
    assert outerTwice(x) == 14, "summary of a twice-called internal function did not apply";
}
