# Project for Solana Tests

This is a Rust example project that uses the intrinsics functions exposed by the Certora Prover.
We use this project to run tests against. This is used in the call trace and tree view reporter tests for Solana
(see src/test/kotlin/solver/SolanaCallTraceTest.kt or src/test/kotlin/report/SolanaFlowTreeViewReporterTest.kt).

When the source code is modified, this needs to be re-compiled with `just build-sbf`.
The build script assumes that the project has been pre-compiled and that the executable is placed in the root directory.
This is to avoid re-compiling the code when running the tests.
