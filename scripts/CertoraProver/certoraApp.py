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

import CertoraProver.certoraContextAttributes as Attrs
from Shared import certoraAttrUtil as AttrUtil


from abc import ABC
from typing import Type


class CertoraApp(ABC):
    attr_class: Type[AttrUtil.Attributes] = Attrs.EvmProverAttributes

class EvmAppClass(CertoraApp):
    pass

class EvmApp(EvmAppClass):
    pass

class RustAppClass(CertoraApp):
    pass

class MoveAppClass(CertoraApp):
    pass

class SolanaApp(RustAppClass):
    attr_class = Attrs.SolanaProverAttributes

class SorobanApp(RustAppClass):
    attr_class = Attrs.SorobanProverAttributes

class RangerApp(EvmAppClass):
    attr_class = Attrs.RangerAttributes

class ConcordApp(EvmAppClass):
    attr_class = Attrs.ConcordAttributes

class SuiApp(MoveAppClass):
    attr_class = Attrs.SuiProverAttributes
