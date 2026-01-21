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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)
package vc.data

import analysis.CmdPointer
import analysis.LTACCmd
import com.certora.collect.*
import evm.SighashInt
import scene.*
import utils.KSerializable
import java.io.Serializable
import java.math.BigInteger

@KSerializable
@Treapable
sealed interface CallableRef : Serializable

@KSerializable
@Treapable
data class CVLFunctionRef(
    val declarationId: String
) : CallableRef

@KSerializable
@Treapable
data class MethodRef(
        val contractId : BigInteger,
        val sigHash: SighashInt?,
        val attr: MethodAttribute
) : CallableRef, Comparable<MethodRef> {

    fun getContractAndMethod(scene: ISceneIdentifiers): Pair<IContractClassIdentifiers, IMethodIdentifiers>? =
        scene.getContractOrNull(contractId)?.let { c ->
            if (attr is MethodAttribute.Common) {
                c.getMethodBySigHash(sigHash?.n ?: error("No sighash for $this"))
            } else {
                check (attr is MethodAttribute.Unique) { "expected the attribute $attr to be [MethodAttribute.Unique]" }
                c.getMethodByUniqueAttribute(attr)
            }?.let { m -> c to m }
        }

    fun toString(scene: IScene): String? =
        getContractAndMethod(scene)?.let { (c, m) ->
            "${c.name}.${m.soliditySignature}"
        }

    companion object {
        val comparator : Comparator<MethodRef> = Comparator.comparing<MethodRef, BigInteger> {
            it.contractId
        }.thenComparing { a: MethodRef ->
            a.sigHash?.n ?: -1.toBigInteger()
        }.thenComparing { it: MethodRef ->
            when(it.attr) {
                MethodAttribute.Common -> 0
                MethodAttribute.Unique.Constructor -> 1
                MethodAttribute.Unique.Fallback -> 2
                MethodAttribute.Unique.Whole -> 3
            }
        }
    }

    override fun compareTo(other: MethodRef): Int {
        return comparator.compare(this, other)
    }
}

fun MethodRef.resolveIn(scene: IScene) : ITACMethod? =
        scene.getContractOrNull(this.contractId)?.let {
        this.resolveIn(it)
    }

fun MethodRef.resolveIn(contract: IContractClass): ITACMethod? =
    if(this.attr is MethodAttribute.Unique) {
        contract.getMethodByUniqueAttribute(attr)
    } else {
        contract.getMethodBySigHash(this.sigHash?.n ?: error("No sighash for $this"))
    }

fun MethodRef.resolveIn(scene: ISceneIdentifiers) : IMethodIdentifiers? =
        scene.getContractOrNull(this.contractId)?.let {
            this.resolveIn(it)
        }

fun MethodRef.resolveIn(contract: IContractClassIdentifiers): IMethodIdentifiers? =
    if(this.attr is MethodAttribute.Unique) {
        contract.getMethodByUniqueAttribute(attr)
    } else {
        contract.getMethodBySigHash(this.sigHash?.n ?: error("No sighash for $this"))
    }

fun IBoundTACMethod.toRef() = MethodRef(
        contractId = this.getContainingContract().instanceId,
        sigHash = this.sigHash,
        attr = this.attribute
)

fun LTACCmd.lift(meth: ITACMethod) : ContractCmd = this.lift(meth.toRef())

fun LTACCmd.lift(methodRef: MethodRef): ContractCmd = ContractCmd(method = methodRef, ptr = this.ptr, cmd = this.cmd)

fun ContractCmd.toLTAC() = LTACCmd(ptr = this.ptr, cmd = this.cmd)

data class ContractCmd(val method: MethodRef, val ptr: CmdPointer, val cmd: TACCmd.Simple)
