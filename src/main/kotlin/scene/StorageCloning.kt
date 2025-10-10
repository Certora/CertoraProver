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

import analysis.EthereumVariables
import analysis.split.SplitRewriter
import vc.data.ScalarizationSort
import vc.data.TACMeta
import vc.data.TACSymbol
import java.math.BigInteger

/**
 * Shared utility object for creating clones of storage variables from one contract to another contract
 */
object StorageCloning {
    /**
     * Creates a copy of the storage variable [storageVar] that represents some piece of storage for [sourceId], relocated
     * to appear as an equivalent storage variable for [targetContract]. For example, when cloning a contract A
     * for dynamic contract creation, all of the storage variables that represent A's storage need to be copied to now
     * represent the storage for `A$Clone`. For obvious reasons, these variables need to have different names.
     *
     * In principle, this function could return any arbitrary name, but for convenience sake, the variable returned
     * from this function will (if possible) mirror the naming of the original [storageVar].
     *
     * In addition, changing the relevant [tac.MetaKey] entries of [storageVar] to point to [targetContract] is
     * handled by this function.
     */
    fun cloneStorageVarTo(sourceId: BigInteger, storageVar: TACSymbol.Var, targetContract: BigInteger) : TACSymbol.Var {
        val meta = storageVar.meta
        val scalarizationSort = storageVar.meta.get(TACMeta.SCALARIZATION_SORT)
        check(scalarizationSort != null) {
            "Scalarization sort for $storageVar was null?"
        }
        /**
         * Unsplit storage reference as input, so create an unsplit reference for output
         */
        val stableStorageFamily = meta.get(TACMeta.STABLE_STORAGE_FAMILY) ?: return EthereumVariables.getStorageInternal(targetContract).withMeta {
            storageVar.meta + (TACMeta.STORAGE_KEY to targetContract)
        }
        val stablePath = meta.get(TACMeta.STABLE_STORAGE_PATH)
        check(stablePath != null) {
            "Have stable storage family but have lost the stable storage path for $storageVar"
        }
        val splitStart = when(scalarizationSort) {
            is ScalarizationSort.Packed -> scalarizationSort.start
            is ScalarizationSort.Split,
            ScalarizationSort.UnscalarizedBuffer -> null
        }
        val newName = if(TACMeta.SCALARIZED_ARRAY in storageVar.meta) {
            SplitRewriter.storageArrayPathName(
                targetContract,
                repPath = stablePath,
                width = storageVar.meta[TACMeta.BIT_WIDTH] ?: error("Bit width not defined for unpacked array: $storageVar")
            )
        } else {
            SplitRewriter.storageVarName(
                targetContract,
                equivClassSize = stableStorageFamily.storagePaths.size,
                repPath = stablePath,
                splitStart = splitStart
            )
        }
        /**
         * Reconstruct the variable name to refer to the [targetContract] version (which *should* just be a find replace
         * of the original name with the address hex string of the original contract with the hex address of [targetContract])
         */
        return storageVar.copy(namePrefix = newName).withMeta {
            it + (TACMeta.STORAGE_KEY to targetContract)
        }.also {
            @Suppress("ForbiddenMethodCall") // for sanity check only
            check(it.namePrefix == storageVar.namePrefix.replace(sourceId.toString(16), targetContract.toString(16))) {
                "Sanity check failed, reconstructing the variable name didn't work as expected $it vs $storageVar (${storageVar.meta})"
            }
        }

    }
}
