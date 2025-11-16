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

from abc import ABC, abstractmethod
from typing import Dict, Any, Optional


class CompilerParameters(ABC):
    @abstractmethod
    def as_dict(self) -> Dict[str, Any]:
        return {}


class SolcParameters(CompilerParameters):
    def __init__(self, optimizer_on: bool, optimizer_runs: Optional[int], via_ir: bool):
        self.optimizer_on = optimizer_on
        self.optimizer_runs = optimizer_runs
        self.via_ir = via_ir
        CompilerParameters.__init__(self)

    def as_dict(self) -> Dict[str, Any]:
        as_dict = CompilerParameters.as_dict(self)
        as_dict.update({"optimizerOn": self.optimizer_on, "optimizerRuns": self.optimizer_runs, "viaIR": self.via_ir,
                        "type": "SolcParameters"})
        return as_dict

class VyperParameters(CompilerParameters):

    def __init__(self, is_venom: bool):
        self.is_venom = is_venom
        CompilerParameters.__init__(self)

    def as_dict(self) -> Dict[str, Any]:
        as_dict = CompilerParameters.as_dict(self)
        as_dict.update({"is_venom": self.is_venom, "type": "VyperParameters"})
        return as_dict
