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

import disassembler.DisassembledEVMBytecode
import tac.ICoreTACProgram
import tac.TACStorageLayout
import vc.data.StorageInfo
import vc.data.StorageInfoWithReadTracker
import vc.data.TACSymbol
import java.math.BigInteger

object DegeneratedContractClass : IContractClass {
    private fun degeneratedError() : Nothing = throw AssertionError("Should not call any methods of a degenerated contract class ")

    override fun getWholeContract(): ITACMethod? {
        degeneratedError()
    }

    override fun getConstructor(): ITACMethod? {
        degeneratedError()
    }

    override fun getMethodBySigHash(sig: BigInteger): ITACMethod? {
        degeneratedError()
    }

    override fun getMethodByUniqueAttribute(attr: MethodAttribute.Unique): ITACMethod? {
        degeneratedError()
    }

    override val storage: StorageInfoWithReadTracker
        get() {
            degeneratedError()
        }

    override val transientStorage : StorageInfo
        get() {
            degeneratedError()
        }

    override fun getStorageLayout(): TACStorageLayout {
        degeneratedError()
    }

    override fun getTransientStorageLayout(): TACStorageLayout {
        degeneratedError()
    }

    override fun getDeclaredMethods(): List<ITACMethod> {
        degeneratedError()
    }

    override fun mapMethodsInPlace(sort: IScene.MapSort, scene: IScene, p: (IScene, ITACMethod) -> Unit) {
        degeneratedError()
    }

    override fun mapMethods(sort: IScene.MapSort, scene: IScene, p: (IScene, ITACMethod) -> ICoreTACProgram) {
        degeneratedError()
    }

    override fun fork(): IContractClass {
        degeneratedError()
    }

    override val addressSym: TACSymbol.Var
        get() {
            degeneratedError()
        }

    override val instanceId: BigInteger
        get() {
            degeneratedError()
        }

    override val name: String
        get() {
            degeneratedError()
        }

    override val methods: Map<BigInteger?, ITACMethod>
        get() = degeneratedError()
    override val wholeContractMethod: ITACMethod
        get() = degeneratedError()
    override val constructorMethod: ITACMethod
        get() = degeneratedError()

    override val bytecode: DisassembledEVMBytecode?
        get() {
            degeneratedError()
        }

    override val constructorBytecode: DisassembledEVMBytecode?
        get() {
            degeneratedError()
        }

    private fun readResolve(): Any = DegeneratedContractClass
}
