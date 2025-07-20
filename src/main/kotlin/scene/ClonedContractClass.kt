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

import tac.IStorageInfo
import tac.TACStorageLayout
import java.math.BigInteger

class ClonedContractClass(
    instanceId: BigInteger,
    name: String,
    storageLayout: TACStorageLayout?,
    transientStorageLayout: TACStorageLayout?,
    methods: Map<BigInteger?, IFreeTACMethod>,
    constructorMethod: IFreeTACMethod,
    wholeContractMethod: IFreeTACMethod?,
    private val storageInfo: IStorageInfo,
    private val transientStorageInfo: IStorageInfo,
    override val sourceContractId: BigInteger,
    override var createdContractInstance: Int = 0
) : MapBackedContractClass(
    instanceId,
    instanceIdIsStaticAddress = false,
    bytecode = null,
    constructorBytecode = null,
    name,
    storageLayout,
    transientStorageLayout
), IClonedContract {
    override val methods: Map<BigInteger?, ITACMethod> = methods.mapValues { it.value.cloneWithContract(this) }
    override val wholeContractMethod: ITACMethod? = wholeContractMethod?.cloneWithContract(this)
    override val constructorMethod: ITACMethod = constructorMethod.cloneWithContract(this)
    override var storageInfoField = storageInfo
    override var transientStorageInfoField = transientStorageInfo

    override fun fork(): IContractClass {
        return ClonedContractClass(
            instanceId, name, storageLayout = this.getStorageLayout(), transientStorageLayout = this.getTransientStorageLayout(),
            methods.mapValues {
                it.value.fork()
            },
            constructorMethod.fork(),
            wholeContractMethod?.fork(),
            storageInfo = storageInfo,
            transientStorageInfo = transientStorageInfo,
            sourceContractId = this.sourceContractId
        )
    }
}
