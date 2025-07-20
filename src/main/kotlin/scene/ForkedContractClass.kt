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

import bridge.ContractInstanceInSDC
import disassembler.DisassembledEVMBytecode
import tac.IStorageInfo
import tac.TACStorageLayout
import vc.data.CoreTACProgram
import java.math.BigInteger

class ForkedContractClass(
        override val src: ContractInstanceInSDC,
        wholeCode: IFreeTACMethod?,
        constructorCode: IFreeTACMethod,
        methods: Collection<IFreeTACMethod>,
        storageLayout: TACStorageLayout?,
        transientStorageLayout: TACStorageLayout?,
        bytecode: DisassembledEVMBytecode?,
        constructorBytecode: DisassembledEVMBytecode?,
        override var storageInfoField: IStorageInfo,
        override var transientStorageInfoField: IStorageInfo
) : MapBackedContractClass(
        instanceId = src.address,
        instanceIdIsStaticAddress = src.is_static_address,
        name = src.name,
        storageLayout = storageLayout,
        transientStorageLayout = transientStorageLayout,
        bytecode = bytecode,
        constructorBytecode = constructorBytecode,
),
        IContractWithSource {
    override fun fork(): ForkedContractClass {
        return ForkedContractClass(
            src = this.src,
            wholeCode = this.wholeContractMethod?.fork(),
            constructorCode = this.constructorMethod.fork(),
            methods = this.methods.values.map { it.fork() },
            storageLayout = this.getStorageLayout(),
            transientStorageLayout = this.getTransientStorageLayout(),
            storageInfoField = storageInfoField,
            transientStorageInfoField = transientStorageInfoField,
            bytecode = this.bytecode,
            constructorBytecode = this.constructorBytecode
        )
    }

    override val methods: Map<BigInteger?, ITACMethod> = run {
        val toAdd = mutableMapOf<BigInteger?, ITACMethod>()
        for(im in methods) {
            toAdd[im.sigHash?.n] = im.cloneWithContract(this)
        }
        toAdd
    }

    override val wholeContractMethod: ITACMethod? = wholeCode?.cloneWithContract(this)
    override val constructorMethod: ITACMethod = constructorCode.cloneWithContract(this)
}


fun IFreeTACMethod.cloneWithContract(c: IContractClass) : ITACMethod {
    return TACMethod(
        _code = this.code as CoreTACProgram,
        attribute = this.attribute,
        meta = this.meta,
        name = this.name,
        soliditySignature = this.soliditySignature,
        sigHash = this.sigHash,
        evmExternalMethodInfo = this.evmExternalMethodInfo,
        _containingContract = c,
        calldataEncoding = calldataEncoding
    )
}
