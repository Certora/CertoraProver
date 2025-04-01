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

@file:kotlinx.serialization.UseSerializers(BigIntegerSerializer::class)
package vc.data

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import allocator.SuppressRemapWarning
import bridge.ContractIdentifier
import bridge.NamedContractIdentifier
import com.certora.collect.*
import scene.IContractClass
import scene.ITACMethod
import spec.cvlast.SolidityContract
import spec.cvlast.SpecCallSummary
import tac.CallId
import utils.*
import vc.data.ProcedureId.*
import java.io.Serializable
import java.math.BigInteger
import utils.KSerializable

/**
In the context of a [TACProgram], the nodes with call ID [callId] are connected to a TAC "procedure" identifiable by [procedureId].
This class is used in the context of Call trace generation as part of counterexamples.
 */
@GenerateRemapper
@KSerializable
@Treapable
data class Procedure(@GeneratedBy(Allocator.Id.CALL_ID) val callId: CallId, val procedureId: ProcedureId) :
    Serializable, RemapperEntity<Procedure> {
    constructor(_callId: CallId, _method: ITACMethod) : this(
        _callId,
        ProcedureId.Standard(
            _method
        )
    )

    constructor(_callId: CallId, _cvlFunc: spec.cvlast.CVLFunction) : this(
        _callId,
        ProcedureId.CVLFunction(
            _cvlFunc
        )
    )

    constructor(_callId: CallId, _method: ITACMethod, _name: String) : this(
        _callId, _method.getContainingContract(), _name
    )

    constructor(_callId: CallId, _contract: IContractClass, _name: String) : this(
        _callId,
        ProcedureId.Standard(_contract, _name)
    )

    constructor(_callId: CallId, _summary: SpecCallSummary) : this(
        _callId,
        ProcedureId.Summary(_summary)
    )
}

/**
a "Procedure" may be associated with some real/synthetic contract, that has an address in the universe of code objects (for us, the "blockchain".)
 */
@KSerializable
@Treapable
sealed class ContractOfProcedure : AmbiSerializable {
    abstract fun asBigInteger(): BigInteger?

    @KSerializable
    object UNKNOWN : ContractOfProcedure() {
        override fun hashCode() = utils.hashObject(this)
        override fun toString(): String = "Unknown contract"
        override fun asBigInteger() = null

        fun readResolve(): Any {
            return ContractOfProcedure.UNKNOWN
        }
    }

    @KSerializable
    data class withContractId(val contractInstanceId: BigInteger) : ContractOfProcedure() {
        override fun toString(): String = contractInstanceId.toString(16)
        override fun asBigInteger() = contractInstanceId
    }

    @KSerializable
    object Summary : ContractOfProcedure() {
        override fun asBigInteger(): BigInteger? = null

        fun readResolve(): Any = Summary

        override fun hashCode() = utils.hashObject(this)
        override fun toString(): String = "Synthetic summary"
    }
}

/**
A [ProcedureId] represents a cluster of TAC blocks that are related to a contract (i.e address in the "universe");
potentially a method inside that contract ([Standard]), or a [WholeContract].
It may be also some synthetic code for havocing parts of the universe we manage: [Havocer].
 */
@KSerializable
@Treapable
sealed class ProcedureId : AmbiSerializable {

    abstract val address: ContractOfProcedure

    @KSerializable
    data class CVLFunction(
        override val address: ContractOfProcedure,
        val functionName: String
    ) : ProcedureId() {
        constructor(f: spec.cvlast.CVLFunction) : this(
            ContractOfProcedure.UNKNOWN, f.declarationId
        )

        override fun toString() = functionName
    }

    @KSerializable
    data class Standard(
        override val address: ContractOfProcedure,
        val contract: NamedContractIdentifier,
        val procedureName: String
    ) : ProcedureId() {

        constructor(m: ITACMethod) : this(
            ContractOfProcedure.withContractId(m.getContainingContract().instanceId),
            SolidityContract(m.getContainingContract().name),
            m.name
        )

        constructor(contract: IContractClass, name: String) : this(
            ContractOfProcedure.withContractId(contract.instanceId),
            SolidityContract(contract.name),
            name
        )

        override fun toString(): String =
            "${contract}.${procedureName}"

    }

    @KSerializable
    data class WholeContract(
        override val address: ContractOfProcedure,
        val contract: ContractIdentifier
    ) : ProcedureId() {
        constructor(m: ITACMethod) : this(
            ContractOfProcedure.withContractId(m.getContainingContract().instanceId),
            SolidityContract(m.getContainingContract().name)
        )

        override fun toString(): String =
            "Whole ${contract}"
    }

    @KSerializable
    data class Constructor(
            override val address: ContractOfProcedure,
            val contract: ContractIdentifier
    ) : ProcedureId() {
        constructor(m: ITACMethod) : this(
                ContractOfProcedure.withContractId(m.getContainingContract().instanceId),
                SolidityContract(m.getContainingContract().name)
        )

        override fun toString(): String =
                "Constructor ${contract}"
    }

    @KSerializable
    data class Havocer(
        override val address: ContractOfProcedure,
        private val havocdContractNames: List<String>,
        private val src: String
    ) : ProcedureId() {

        private val str = "havocd ${havocdContractNames.joinToString(",")} in ${src.condense()}"
        override fun toString(): String =
            str
    }

    @KSerializable
    data class Summary(
        val summaryType: SpecCallSummary
    ) : ProcedureId() {
        override val address: ContractOfProcedure
            get() = ContractOfProcedure.Summary

        override fun toString(): String = summaryType.toUIString()

    }

    /**
     * Used in CodeMap context -- when adding internal functions to the TAC program.
     */
    @KSerializable
    @SuppressRemapWarning
    data class Internal(val name: String, val externalProc: Procedure) : ProcedureId() {
        init {
            check(externalProc.procedureId !is Internal) {
                "externalProc arg must not point to an internal procedure, got: name: $name, externalProc: $externalProc "
            }
        }
        override val address: ContractOfProcedure
            get() = externalProc.procedureId.address
        override fun toString() = when (externalProc.procedureId) {
            is Standard -> "${externalProc.procedureId.contract}.$name"
            is Constructor, is WholeContract -> "${externalProc.procedureId}.$name"
            else -> "${externalProc.procedureId.address}.$name"
        }.sanitizeProcName() + "(internal)"

        /** E.g. spaces in the procedure name make problems in [CodeMap] / tac dump land (wrong html, very annoying to
         * debug..), so we sanitize the names here. */
        private fun String.sanitizeProcName(): String =
            this.replace(" ", "_")
                .replace(":", "_")
    }

    /** Solana procedure. Corresponds to a method call. */
    @KSerializable
    @SuppressRemapWarning
    data class Solana(val name: String) : ProcedureId() {

        @KSerializable
        object SolanaEntryPointContractOfProcedure : ContractOfProcedure() {
            override fun hashCode() = hashObject(this)
            override fun toString(): String = "main"
            override fun asBigInteger() = null
            fun readResolve(): Any = SolanaEntryPointContractOfProcedure
        }

        override val address: ContractOfProcedure get() =
            // Since in Solana there is no concept of contracts of a procedure, we just use a dummy one.
            SolanaEntryPointContractOfProcedure

        override fun toString() =
            // We sanitize the name: if there are spaces, they are substituted by the underscore. Names in Rust should
            // not have spaces though.
            name.replace(" ", "_")
    }
}
