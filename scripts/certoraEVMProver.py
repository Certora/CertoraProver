#!/usr/bin/env python3
#     The Certora Prover
#     Copyright (C) 2025  Certora Ltd.
#
#     This program is free software: you can redistribute it and/or modify
#     it under the terms of the GNU General Public License as published by
#     the Free Software Foundation, version 3 of the License.
#
#     This program is distributed in the hope that it will be useful,
#     but WITHOUT ANY WARRANTY; without even the implied warranty of
#     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#     GNU General Public License for more details.
#
#     You should have received a copy of the GNU General Public License
#     along with this program.  If not, see <https://www.gnu.org/licenses/>.


import sys
from pathlib import Path

scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

import CertoraProver.certoraApp as App
from Shared.proverCommon import CertoraRunResult
from certoraRun import run_certora
from typing import List, Optional


def run_evm_prover(args: List[str]) -> Optional[CertoraRunResult]:
    return run_certora(args, App.EvmApp)

def entry_point() -> None:
    run_evm_prover(sys.argv[1:])

if __name__ == '__main__':
    entry_point()
