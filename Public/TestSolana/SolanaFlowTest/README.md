# Solana flow test

This test checks that the Solana flow is correctly supported by the Prover.
This test calls the build script `./certora_build.py` which mocks building the code.
The executable file `./sanity_check.so` is a symbolic link to `../SanityTest/sanity_check.so`, which is pre-built.
To change the code, modify and rebuild the executable in the sanity test.
