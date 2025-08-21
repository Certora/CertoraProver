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

package report.calltrace.sarif

import com.certora.collect.*
import report.calltrace.CallInstance
import report.calltrace.StorageDisplayPathGenerator
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.CallTraceValue
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.cvlast.EVMBuiltinTypes
import spec.cvlast.GhostSort
import spec.cvlast.StorageBasis
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.*
import vc.data.SnippetCmd
import vc.data.TACMeta
import vc.data.find
import vc.data.state.TACValue

fun <@Treapable T> sarifFromInvokingInstance(instance: CallInstance.InvokingInstance<T>): Sarif {
    val builder = SarifBuilder()

    val argSeparator = ", "

    val params = instance
        .params
        .withIndex()
        .map { (idx, param) -> param to instance.paramValues[idx] }
    val returnValues = instance
        .returnTypes
        .withIndex()
        .map { (idx, type) -> type to instance.returnValues[idx] }

    builder.append(instance.callName)
    builder.append("(")

    for ((idx, paramAndValue) in params.withIndex()) {
        val param = paramAndValue.first
        val value = paramAndValue.second ?: CallTraceValue.Empty

        if (param.type == EVMBuiltinTypes.env) {
            // we omit `env` variables from the function's call trace
            // representation, since they mostly just add noise.
            continue
        }

        val arg =
            value.toSarif(instance.formatter, ordinalTooltip("parameter", idx, params.size))

        val name = param.name ?: value.paramName
        if (name != null) {
            builder.append("${name}=")
        }
        builder.append(arg)
        builder.append(argSeparator)
    }

    builder.removeSuffix(argSeparator) // needed because we don't know how many args were pushed
    builder.append(")")

    var needsSeparatorForReturnValue = true

    for ((idx, typeAndValue) in returnValues.withIndex()) {
        val value = typeAndValue.second ?: continue

        if (needsSeparatorForReturnValue) {
            needsSeparatorForReturnValue = false
            builder.append(Sarif.EVALUATES_TO)
        }

        value.paramName?.let { builder.append(it) } // prefixes typically a parameter name, e.g. `x`, right now (could still be nicer, all this, not sure if this ever happens for returns)

        val arg =
            value.toSarif(instance.formatter, ordinalTooltip("return value", idx, returnValues.size))

        builder.append(arg)
        builder.append(argSeparator)
    }

    builder.removeSuffix(argSeparator) // needed because we don't know how many args were pushed

    return builder.build()
}

private fun ordinalTooltip(enumeratedElement: String, zeroBasedIdx: Int, listSize: Int): String {
    return when {
        zeroBasedIdx < 0 || zeroBasedIdx >= listSize -> throw IllegalArgumentException(
            "got zeroBasedIdx = $zeroBasedIdx, listSize = $listSize"
        )

        listSize == 1 -> enumeratedElement

        else -> {
            val ordinal = (zeroBasedIdx + 1).toOrdinalString()
            "$ordinal $enumeratedElement"
        }
    }
}

fun sarifForStorageLocation(
    snippetCmd: SnippetCmd.CVLSnippetCmd.StorageComparison,
    model: CounterexampleModel,
    formatter: CallTraceValueFormatter,
    scene: ISceneIdentifiers,
): Either<Sarif, String> {
    val skeyResolver = StorageDisplayPathGenerator(scene)
    val sarifFormatter = SarifFormatter(formatter)

    return when (snippetCmd) {
        is SnippetCmd.CVLSnippetCmd.VMStorageComparison -> {
            when (snippetCmd) {
                is SnippetCmd.CVLSnippetCmd.ScalarStorageComparison -> {
                    val id = snippetCmd.p1.second.meta.find(TACMeta.STORAGE_KEY)
                        ?: return "Could not find instance ID for storage ${snippetCmd.p1.second} in $snippetCmd".toRight()
                    val displayPath =
                        skeyResolver.displayPathFor(id, snippetCmd.p1.second)?.toFormattedString(formatter)
                            ?: return "Could not infer storage path name in $snippetCmd".toRight()

                    sarifFormatter.fmt("in contract storage at {}", FmtArg(displayPath)).toLeft()
                }

                is SnippetCmd.CVLSnippetCmd.StorageWitnessComparison -> {
                    val witnessSkey = (model.valueAsTACValue(snippetCmd.witnessSymbol) as? TACValue.SKey)
                        ?: return "Value of witness symbol ${snippetCmd.witnessSymbol} in $snippetCmd was not an SKey".toRight()

                    when (snippetCmd.basis) {
                        StorageBasis.Balances -> {
                            val addrTv = (witnessSkey as? TACValue.SKey.Basic)?.offset
                                ?: return "Address $witnessSkey for balance was not a Basic skey for $snippetCmd".toRight()
                            val addr = FmtArg.CtfValue.buildOrUnknown(
                                tv = addrTv,
                                type = EVMTypeDescriptor.address,
                                tooltip = "address of contract",
                            )

                            sarifFormatter.fmt("in balances for contract with address {}", addr).toLeft()
                        }

                        StorageBasis.ExternalCodesizes -> {
                            val addrTv = (witnessSkey as? TACValue.SKey.Basic)?.offset
                                ?: return "Address $witnessSkey for extcodesize was not a Basic skey for $snippetCmd".toRight()

                            val addr = FmtArg.CtfValue.buildOrUnknown(
                                tv = addrTv,
                                type = EVMTypeDescriptor.address,
                                tooltip = "address of contract"
                            )

                            sarifFormatter.fmt("in extcodesize for contract with address {}", addr).toLeft()
                        }

                        is StorageBasis.ContractClass -> {
                            val id = scene.getContract(snippetCmd.basis.canonicalId).instanceId
                            val displayPath = skeyResolver.displayPathFor(id, witnessSkey, snippetCmd.baseMap)
                                ?.toFormattedString(formatter)
                                ?: return "Could not find instance ID for storage ${snippetCmd.p1.second} in $snippetCmd".toRight()

                            sarifFormatter.fmt("in contract storage at {}", FmtArg(displayPath)).toLeft()
                        }
                    }
                }
            }
        }

        is SnippetCmd.CVLSnippetCmd.GhostWitnessComparison -> {
            val builder = SarifBuilder()

            val isMapping = when (snippetCmd.basis.sort) {
                GhostSort.Function -> `impossible!`
                GhostSort.Mapping -> true
                GhostSort.Variable -> false
            }

            val (separator, prefix, postfix) = if (isMapping) {
                Triple("][", "[", "]")
            } else {
                Triple(", ", "(", ")")
            }

            if (isMapping) {
                builder.append("in ghost mapping ")
            } else {
                builder.append("for ghost function ")
            }
            builder.append(snippetCmd.basis.name)

            builder.append(prefix)

            var written = 0

            for ((elem, ty) in snippetCmd.typedParameters) {
                val tv = model.valueAsTACValue(elem)
                    ?: return "Could not pretty print all arguments for ghost witness $snippetCmd".toRight()

                val arg = CallTraceValue.cvlCtfValueOrUnknown(tv, ty).toSarif(formatter, "ghost argument")

                builder.append(arg)
                written += 1

                if (written < snippetCmd.typedParameters.size) {
                    builder.append(separator)
                }
            }

            builder.append(postfix)

            builder.build().toLeft()
        }

        is SnippetCmd.CVLSnippetCmd.ScalarGhostComparison -> {
            val sarif = if (snippetCmd.sort == GhostSort.Variable) {
                sarifFormatter.fmt("for ghost variable {}", FmtArg(snippetCmd.basis.name))
            } else {
                sarifFormatter.fmt("for ghost function {}()", FmtArg(snippetCmd.basis.name))
            }

            sarif.toLeft()
        }
    }
}
