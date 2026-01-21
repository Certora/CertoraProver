# Project for Solana Tests

This is a Rust project testing line numbers and variable information for the VScode debugger. Ensure to maintain the line numbers
and variable names when working on this project, as these are directly tested on by the tests in
`src/test/kotlin/solver/DebugAdapterCallTraceTest.kt`

When the source code is modified, this needs to be re-compiled with `just build-sbf`.
The build script assumes that the project has been pre-compiled and that the executable is placed in the root directory.
This is to avoid re-compiling the code when running the tests.
