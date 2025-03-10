# Certora Prover

The Certora Prover is a tool for formally verifying smart contracts.
This document is intended for those who would like to contribute to the tool.

If you are interested to use the tool on our cloud platform without having to locally build it,
we recommend following the documentation here: https://docs.certora.com/en/latest/docs/user-guide/install.html.

The instructions here are for users on Mac OS and Linux.

## Dependencies
* JDK 10+
* SMT solvers:
  * [required] Z3 -- https://github.com/Z3Prover/z3/releases
  * [required] CVC5 -- https://github.com/cvc5/cvc5/releases
  * [optional] CVC4 -- https://cvc4.github.io/downloads.html
  * [optional] Yices -- https://github.com/SRI-CSL/yices2/releases
  * [optional] Bitwuzla -- https://github.com/bitwuzla/bitwuzla/releases
  * _NOTE_ Whichever solvers you decide to install, remember to put the executables in a directory in your system's `PATH`.

* Python 3
    - We recommend downloading from here: https://www.python.org/downloads/
    - Make sure the version of pip matches with the python version

* Solidity compiler -- https://github.com/ethereum/solidity/releases.
  Pick the version(s) that is used by the contracts you want to verify.
  Since we often use many versions, it is recommended to rename each `solc` executable
  to, e.g., solc5.12, and place all versions into a directory in your systems `PATH` like so: `export PATH="/path/to/dir/with/executables:$PATH"`

* Rust (tested on Version 1.81.0+) -- https://www.rust-lang.org/tools/install

* [`llvm-symbolizer`](https://llvm.org/docs/CommandGuide/llvm-symbolizer.html),
  which is installed as part of LLVM.

* [`rustfilt`](https://github.com/luser/rustfilt)

## Installation
* Create a directory anywhere to store build outputs.

    - Add an environment variable `CERTORA` whose value is the path to this directory.

    - Add this directory to `PATH` as well. For example if you are using a bash shell, you can edit your `~/.bashrc` file like so:
```
      export CERTORA="preferred/path/for/storing/build/outputs"
      export PATH="$CERTORA:$PATH"
```

* `cd` into a directory you want to store the CertoraProver source and clone the repo:
   ```
    git clone --recurse-submodules https://github.com/Certora/CertoraProver.git
   ```

* Compile the code by running: `./gradlew assemble`

* If you want to clean up all artifacts of the project, run: `./gradlew clean`

### Troubleshooting
- We recommend working from within a python virtual environment and installing all dependencies there:
```commandline
cd CertoraProver
python -m venv .venv
source .venv/bin/activate
pip install -r scripts/certora_cli_requirements.txt
```
- If you have `Crypto` installed, you may first need to uninstall (`pip uninstall crypto`) before installing `pycryptodome`

## Running

- You can run the tool by running `certoraRun.py -h` to see all the options.
    - There are several small examples for testing under `Public/TestEVM`. For example, you can run one of these like so:
  ```commandline
        cd Public/TestEVM/CVLCompilation/OptionalFunction
        certoraRun.py Default.conf
   ```
    - Please refer to the user guide for details on how to run the prover on real-world smart contracts: https://docs.certora.com/en/latest/docs/user-guide/index.html

- You can run unit tests directly from IDEs like IntelliJ, or from the command line with `./gradlew test --tests <name_of_test_with_wildcards>`
    - These tests are in `CertoraProver/src/test` (and also in the test directories of the various subprojects)

## Contributing
1. Fork the repo and open a pull request with your changes.
2. Contact Certora at devhelp@certora.com once your PR is ready.
3. Certora will assign a dev representative who will review and test the changes, and provide feedback directly in the PR.
4. Once the feature is approved and ready to be merged, Certora will merge it through its internal process and include the feature in a subsequent Prover release.

## LICENSE
Copyright (C) 2025 Certora Ltd. The Certora Prover is released under the GNU General Public License, Version 3, as published by the Free Software Foundation. For more information, see the file LICENSE.
