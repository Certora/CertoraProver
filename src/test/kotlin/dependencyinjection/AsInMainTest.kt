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

package dependencyinjection

import log.*
import org.junit.jupiter.api.*
import utils.InjectedDependencies

class AsInMainTest {

    @BeforeEach
    fun setUp() {
        setupDependencyInjection()
    }

    @Test
    fun artifactsTest() {
        InjectedDependencies().getFilePathForSmtQuery("foo")
    }

    @Test
    fun configTest() {
        InjectedDependencies().smt_dumpAll()
    }

    @Test
    fun loggerTest() {
        assertNotNull(Logger(LoggerTypes.COMMON))
    }
}
