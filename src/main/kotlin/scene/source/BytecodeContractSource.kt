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

package scene.source

import bridge.ContractInstanceInSDC
import bridge.SourceLanguage
import bridge.StorageLayout
import scene.mainContractFromFilename
import java.io.FileReader

class BytecodeContractSource(val inputFile: String): IContractSourceFull {
    override fun instances(): List<ContractInstanceInSDC> {
        val contractName = mainContractFromFilename(inputFile)
        return listOf(
            ContractInstanceInSDC(
                name = contractName.name,
                file = inputFile,
                address = 0.toBigInteger(),
                is_static_address = false,
                original_file = inputFile,
                lang = SourceLanguage.Unknown,
                methods = listOf(),
                bytecode = FileReader(inputFile).readText(),
                constructorBytecode = "",
                srcmap = "",
                varmap = "",
                storageLayout = StorageLayout(),
                transientStorageLayout = StorageLayout(),
                solidityTypes = setOf(),
                srclist = mapOf(),
                immutables = listOf(),
                internalFunctionHarnesses = mapOf()
            )
        )
    }

    override fun fullInstances(): List<ContractInstanceInSDC> = instances()
}
