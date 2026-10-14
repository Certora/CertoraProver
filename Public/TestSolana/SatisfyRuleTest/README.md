# Satisfy rule test

A satisfy rule of the Solana flow may contain assertions of the user, unlike the ones of the Wasm and the Move flows:
the CVLR vacuity check derives a satisfy rule from a rule that asserts. This test pins down what such a rule checks -
the assertions become assumptions, and the satisfy is what is verified - and covers the vacuity check itself, over a
rule that passes it and a rule that does not.

The executable `./satisfy_rules.so` is pre-built and the build script `./certora_build.sh` only reports where it is.
After changing the code under `src`, rebuild it with `just build-sbf` and commit the new executable.
