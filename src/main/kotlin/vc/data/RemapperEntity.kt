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

package vc.data

import com.certora.collect.*
import utils.*
import java.lang.reflect.InvocationTargetException

interface RemapperEntity<T> : UniqueIdEntity<T>, ExtensionGetter where T: java.io.Serializable, T : UniqueIdEntity<T> {
    override fun mapId(f: (Any, Int, () -> Int) -> Int): T {
        return try {
            this.getExtensionMethod("Remapper").invoke(null, this, f).uncheckedAs<T>()
        } catch (e: InvocationTargetException) {
            /*
             * The remapper is invoked reflectively, so any exception `f` throws comes back wrapped in an
             * InvocationTargetException. Callers that intentionally throw from `f` (e.g. BMC's dangling-id
             * validator) catch a specific exception type, so unwrap here to let the original propagate.
             * Nested RemapperEntity.mapId calls each unwrap one level, so the original surfaces cleanly.
             */
            throw e.targetException ?: e
        }
    }
}
