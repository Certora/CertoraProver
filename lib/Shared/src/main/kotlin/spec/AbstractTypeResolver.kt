/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package spec

import spec.cvlast.CVLType
import spec.cvlast.ContractAliasDefinition
import spec.cvlast.SolidityContract
import spec.cvlast.SortDeclaration
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.ok
import utils.VoidResult

abstract class AbstractTypeResolver : TypeResolver {
    /**
     * map is a map from an alias name to the canonical contract name
     */
    protected val contractAliases: MutableMap<String, String> = mutableMapOf()

    override fun registerContractAlias(alias: ContractAliasDefinition): VoidResult<CVLError> {
        return if (!validContract(alias.contractName)) {
            CVLError.General(alias.range, "Tried to register ${alias.alias} as ${alias.contractName} but " +
                "${alias.contractName} does not exist.").asError()
        } else if (alias.alias in contractAliases) {
            CVLError.General(alias.range, "Registered contract alias ${alias.alias} more than once.").asError()
        } else {
            if (alias.alias != alias.contractName && validContract(alias.alias)) {
                CVLWarningLogger.syntaxWarning( "Registered the contract alias ${alias.alias} for the contract name ${alias.contractName}, another contract with the same contract name appears in the scene. " +
                        "This may lead to inconsistent behaviour when resolving methods.", alias.range)
            }
            contractAliases[alias.alias] = alias.contractName
            ok
        }
    }


    override fun resolveContractName(contract: String): String {
        return contractAliases[contract] ?: contract
    }

    override fun resolveNameToContract(contract: String): SolidityContract {
        return SolidityContract(resolveContractName(contract))
    }

    protected abstract fun resolveCVLTypeWithContract(contract: String, id: String): CollectingResult<CVLType.PureCVLType, String>
    override fun resolveCVLType(contract: String?, id: String): CollectingResult<CVLType.PureCVLType, String> {
        return if (contract == null) {
            sorts.firstOrNull { s ->
                id == s.sort.name
            }?.sort?.lift() ?: CVLPrimitiveResolution.resolveCVLPrimitive(id)
        } else {
            resolveCVLTypeWithContract(contract, id)
        }
    }

    protected val sorts: MutableList<SortDeclaration> = mutableListOf()

    override fun registerSorts(s: List<SortDeclaration>) {
        sorts.addAll(s)
    }
}
