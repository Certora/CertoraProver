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

package scene

import bridge.EVMExternalMethodInfo
import config.ReportTypes
import evm.SighashInt
import spec.CVLReservedVariables
import tac.ICoreTACProgram
import tac.MetaMap
import utils.*
import java.io.Serializable


/**
 * A view of the [ITACMethod] Identifiers: name, address, etc..
 * Does not include: TAC-program, Forking, update-code or back-pointer to containing contract
 * if [IMethodIdentifiers] == [MethodAttribute.Unique.Whole] then  [IMethodIdentifiers.name] == [ITACMethod.getContainingContract]'s name
 **/
interface IMethodIdentifiers: Serializable {
    val name: String
    val soliditySignature: String?
    val attribute: MethodAttribute
    val evmExternalMethodInfo: EVMExternalMethodInfo?
    val sigHash: SighashInt?

    val isFallback get() = (name == CVLReservedVariables.certorafallback_0.name)
}

/**
 * A minimal copy of [IMethodIdentifiers] that is guaranteed to be immutable, and lightweight to Serialize
 **/
data class MethodIdentifiers(
    override val name: String,
    override val soliditySignature: String?,
    override val attribute: MethodAttribute,
    override val evmExternalMethodInfo: EVMExternalMethodInfo?,
    override val sigHash: SighashInt?,
) : IMethodIdentifiers

/*
A tac method that is not associated with any particular contract. All ITACMethods are free tac methods,
this type just "hides" the ownership field.
 */
interface IFreeTACMethod :  IMethodIdentifiers {
    val code: ICoreTACProgram
    val meta: MetaMap

    val calldataEncoding : ICalldataEncoding

    fun toIdentifiers() = MethodIdentifiers(name, soliditySignature, attribute, evmExternalMethodInfo, sigHash)
}

interface PatchableProgram {
    fun toCode(base: ICoreTACProgram): ICoreTACProgram
}

interface ITACMethod : IFreeTACMethod, NamedCode<ReportTypes> {
    override var code: ICoreTACProgram
    fun getContainingContract(): IContractClass

    fun updateCode(p :PatchableProgram)

    @Deprecated("All transformations should use a mutable or patching TAC program. This is temporary")
    fun updateCode(p: ICoreTACProgram) {
        code = p
    }

    fun fork(): ITACMethod // Deep-copies the code only
    fun shallowForkWithCodeAndCalldataEncoding(newCode: ICoreTACProgram, cdEncoding: ICalldataEncoding): ITACMethod //Shallow-copies with the given code
    fun shallowForkWithCalldataEncoding(cdEncoding: ICalldataEncoding): ITACMethod
    override fun toString(): String // force implementation
    override fun myName(): String = code.name
}
