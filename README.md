# Certora Prover

The Certora Prover is a tool for formally verifying smart contracts.
This document is intended for those who would like to contribute to the tool.

If you are interested to use the tool on our cloud platform without having to locally build it,
we recommend following the documentation here: https://docs.certora.com/en/latest/docs/user-guide/install.html.

## Dependencies
* JDK 10+
* SMT solvers:
  * Z3 -- https://github.com/Z3Prover/z3/releases
  * CVC4 -- https://cvc4.github.io/downloads.html
  * CVC5 -- https://github.com/cvc5/cvc5/releases
  * Yices -- https://github.com/SRI-CSL/yices2/releases
  * Bitwuzla -- https://github.com/bitwuzla/bitwuzla/releases
  * _NOTE_ Whichever solvers you decide to install, remember to put the executables in a directory in your system's `PATH`.

* Python 3
    - You may require some packages which you can install like so: `pip3 install requests tabulate sly pycryptodome tqdm click dominate junit_xml rich json5`
    - If you have `Crypto` installed, you may first need to uninstall (`pip uninstall crypto`) before installing `pycryptodome`

* Solidity compiler -- https://github.com/ethereum/solidity/releases.
  Pick the version(s) that is used by the contracts you want to verify.
  Since we often use many versions, it is recommended to rename each `solc` executable
  to, e.g., solc5.12, and place all versions into a directory in your systems PATH.

* Rust (tested on Version 1.81.0+) -- https://www.rust-lang.org/tools/install

* [`llvm-symbolizer`](https://llvm.org/docs/CommandGuide/llvm-symbolizer.html),
  which is installed as part of LLVM.

* [`rustfilt`](https://github.com/luser/rustfilt)

## Installation
* Create a directory anywhere to store build outputs.

    - Add an environment variable `CERTORA` whose value is the path to this directory.

    - Add this directory to `PATH` as well.

* `cd` into a directory you want to store the CertoraProver source and clone the repo:
   ```
    git clone --recurse-submodules https://github.com/Certora/CertoraProver.git
   ```

* Compile the code by running:
    * On Mac/Linux/Bash: `./gradlew assemble`
    * On Windows: `./gradlew.bat assemble`

* If you want to clean up all artifacts of the project, run:
  `./gradlew clean`

## Running

- You can run the tool by running `certoraRun.py -h` to see all the options.
    - Please refer to the user guide for details on how to run it on actual smart contracts: https://docs.certora.com/en/latest/docs/user-guide/index.html

- You can run unit tests directly from IDEs like IntelliJ, or from the command line with `./gradlew test --tests <name_of_test_with_wildcards>`
    - These tests are in `CertoraProver/src/test` (and also in the test directories of the various subprojects)

## LICENSE

Copyright (C) 2025 Certora Ltd. The Certora Prover is released under the GNU General Public License, Version 3, as published by the Free Software Foundation. For more information, see the file LICENSE.
