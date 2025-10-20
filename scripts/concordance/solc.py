#      The Certora Prover
#      Copyright (C) 2025  Certora Ltd.
#
#      This program is free software: you can redistribute it and/or modify
#      it under the terms of the GNU General Public License as published by
#      the Free Software Foundation, version 3 of the License.
#
#      This program is distributed in the hope that it will be useful,
#      but WITHOUT ANY WARRANTY; without even the implied warranty of
#      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
#      GNU General Public License for more details.
#
#      You should have received a copy of the GNU General Public License
#      along with this program.  If not, see <https://www.gnu.org/licenses/>.

import subprocess
import json

from pydantic import BaseModel, Field

class SolidityCompilerInputBase(BaseModel):
    compiler_version: str = \
        Field(description=
              "The compiler version string to use for compilation. Compiler versions are taken from the known compiler "
              "releases (e.g., 0.8.2), but with the leading '0.' dropped (e.g., 8.2). For example, to invoke Solidity version 0.8.2, "
              "this argument should be 8.2"
              )

def solidity_compiler_impl(source: str, compiler_version: str) -> str:
    compiler_input = {
        "language": "Solidity",
        "sources": {
            "harness.sol": {
                "content": source
            }
        },
        "settings": {
            "outputSelection": {
                "*": {
                    "*": []
                }
            }
        }
    }
    compile_result = subprocess.run(
        [f'solc{compiler_version}', "--standard-json"],
        input=json.dumps(compiler_input),
        text=True,
        encoding="utf-8",
        capture_output=True
    )
    res = f"Return code was: {compile_result.returncode}\nStdout:\n{compile_result.stdout}"
    return res
