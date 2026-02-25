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

package sbf.domains

/** Interface required by GenericScalarAnalysis **/
interface IScalarDomainFactory<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, ScalarDomain> {

    fun mkTop(fac: ISbfTypeFactory<TNum, TOffset>, globalState: GlobalState): ScalarDomain

    fun mkBottom(fac: ISbfTypeFactory<TNum, TOffset>, globalState: GlobalState): ScalarDomain

    /**
     *  Return the initial abstract state.
     *
     *  If [addPreconditions] is true then it adds some facts that are true when the SBF program
     *  is loaded (e.g., `r10` must point to the top of the stack)
     */
    fun init(
        fac: ISbfTypeFactory<TNum, TOffset>,
        globalState: GlobalState,
        addPreconditions: Boolean
    ): ScalarDomain
}
