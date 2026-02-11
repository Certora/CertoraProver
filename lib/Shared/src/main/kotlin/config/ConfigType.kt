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

@file:OptIn(PollutesGlobalState::class)
package config

import annotations.PollutesGlobalState
import cli.*
import datastructures.stdcollections.*
import log.*
import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.Option
import smt.*
import solver.SolverConfig
import utils.*
import java.io.File
import java.io.Serializable
import java.math.BigInteger

/**
 * A template for a configurable value.
 * @param default which can be nullable (a member)
 * @param name for presentation (a member)
 * @param postponeRegister - defines if we're going to register in [ConfigRegister] in the base class constructor,
 *                           or if the responsibility to register is moved to the derived or instantiating object.
 */
sealed class ConfigType<T : Serializable>(
    val default: T?,
    val name: String,
    postponeRegister: Boolean = false
) {
    /**
     * Holds the actual value set to this configurable. If [value] is null, it is assumed to be [default] by [get].
     * It can be modified with [set] and set back to null with [reset].
     */
    private var value: T? = null

    /**
     * Check if the new value is a valid value (beyond just adhering to a type T)
     */
    abstract fun check(newValue: T): Boolean

    /**
     * Message that would be printed when the configured value is invalid
     */
    abstract fun illegalArgMessage(newValue:T): String

    /**
     * A callback that registers the configuration `this` and allows it to be processed transparently, see [ArgsParser].
     * Must be called after the main fields of the object are initialized.
     */
    fun register() {
        ConfigRegister.register(this)
    }

    /**
     * @param newValue
     * sets a new value, only if it's legal.
     * Null types are rejected here, but a default may be null in certain cases.
     */
    @PollutesGlobalState
    fun set(newValue: T) {
        if (check(newValue)) {
            value = newValue
        } else {
            throw IllegalArgumentException("${illegalArgMessage(newValue)}: $newValue")
        }
    }

    /**
     * @param newValue
     * sets a new value (via [set]) if the config was not set yet (checked via [isDefault])
     */
    fun setIfUnset(newValue: T) {
        if (isDefault()) {
            set(newValue)
        }
    }

    /**
     * Resets the value back to null so that [isDefault] and [get] return true
     * and [default], respectively. Mostly useful to undo changes for testing.
     */
    @PollutesGlobalState
    fun reset() {
        value = null
    }

    /**
     * Checks if a non-null value exists (either [value] or [default]).
     */
    fun exists(): Boolean = getOrNull() != null

    /**
     * Checks is the default value is used or a user input ([value])
     */
    fun isDefault() = value == null

    /**
     * Gets the current [value].
     * Rejects nulls. If the default is nullable, must use [getOrNull]
     */
    open fun get(): T = getOrNull() ?: error("This configuration must be non-null: $name")

    /**
     * Gets the current value, if exists, and the default otherwise.
     * invariant: getOrNull() != null <=> exists()
     */
    open fun getOrNull(): T? = value ?: default

    init {
        if (!postponeRegister) {
            register()
        }
    }

    object MainFileName : ConfigType<String>(null, "MainFileName") {
        override fun check(newValue: String): Boolean = true
        override fun illegalArgMessage(newValue: String): String = ""
    }


    object WithArtifacts : ConfigType<ArtifactManagerFactory.WithArtifactMode>(
        ArtifactManagerFactory.WithArtifactMode.WithoutArtifacts,
        "WithArtifacts"
    ) {
        override fun check(newValue: ArtifactManagerFactory.WithArtifactMode): Boolean = true
        override fun illegalArgMessage(newValue: ArtifactManagerFactory.WithArtifactMode): String = ""
    }

    // this one is set after reading other configs, so no need to register it
    object ExecName : ConfigType<String>(null, "ExecName", postponeRegister = true) {
        override fun check(newValue: String): Boolean = newValue.isNotBlank() && !newValue.contains(File.separator)
        override fun illegalArgMessage(newValue: String): String = "Illegal execution name"
    }

    // a non-command line config for making sure assertions are not appended after full rule compilation.
    // (this helps ensure that if we ever enable MultiAssertCheck on the rule, it will not fail.)
    // this is only set on the usual certoraRun flow
    object CheckNoAddedAssertions : ConfigType<Boolean>(false, "CheckNoAddedAssertions", postponeRegister = true) {
        override fun check(newValue: Boolean) = true
        override fun illegalArgMessage(newValue: Boolean): String {
            `impossible!`
        }
    }

    /**
     * @param default - passed to base class constructor
     * @param converter for converting from string to the type T
     * @param option the main command line option
     * @param aliases list of short options we support due to backward compatibility (generates more options for the command line)
     */
    open class CmdLine<T : Serializable>(
        default: T?,
        private val converter: Converter<T>,
        val option: Option,
        val aliases: List<Option> = listOf(), // if we want to have backward-compatibility
        val pythonName: String? = null,
    ) : ConfigType<T>(default, option.realOpt(), postponeRegister = true) {
        val allOptions = listOf(option) + (aliases.map { Option(it.realOpt(), it.hasArg(), it.description) })

        override fun check(newValue: T): Boolean {
            return true
        }

        override fun illegalArgMessage(newValue: T): String = ""

        fun getMatchedOption(cmdLine: CommandLine): Option? {
            val matched = allOptions.filter { o ->
                // at least one of the opts must be not null
                cmdLine.hasOption(o.realOpt())
            }
            return when (matched.size) {
                0 -> null
                1 -> matched.single()
                else -> {
                    Logger.alwaysWarn(
                        "Option ${option.realOpt()} was set using multiple aliases (${matched.map { "-${it.opt}" }.joinToString(", ")}), will use -${matched.first().opt}. Please use only a single one."
                    )
                    matched.first()
                }
            }
        }

        fun isConfigured(cmdLine: CommandLine): Boolean =
            getMatchedOption(cmdLine) != null

        fun setFromCLI(cmdLine: CommandLine) {
            val matchedOption = getMatchedOption(cmdLine)
            if (matchedOption != null) {
                if (!matchedOption.hasArg()) {
                    // only for booleans
                    check(default is Boolean) {
                        "Option ${matchedOption.realOpt()} without an argument can only be a boolean flag"
                    }
                    set(true.uncheckedAs<T>())
                    // (if a flag option does not appear, we leave the default)
                } else {
                    val rawValue = cmdLine.getOptionValue(matchedOption.realOpt())
                    val convertedValue = converter.convert(rawValue)
                    set(
                        convertedValue
                    )
                }
            }
        }

        fun userFacingName() = if (pythonName != null) { "`${pythonName}`" } else { "`--prover_args '-$name ...`" }

        // SG: I don't like it but it should be last
        // Invariant: if postponeRegister = true, must register
        init {
            register()
        }
    }

    open class BooleanCmdLine(
        default: Boolean?,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<Boolean>(
        default,
        BooleanConverter, option, aliases, pythonName
    )

    open class IntCmdLine(
        default: Int?,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<Int>(
        default,
        IntConverter, option, aliases, pythonName
    )

    open class BigIntCmdLine(
        default: BigInteger?,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<BigInteger>(
        default,
        BigIntConverter, option, aliases
    )

    open class StringCmdLine(
        default: String?,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<String>(
        default,
        StringConverter, option, aliases, pythonName
    )

    open class StringListCmdLine(
            default: Array<String>,
            option: Option,
            aliases: List<Option> = listOf()
    ) : CmdLine<Array<String>>(
            default,
            StringListConverter, option, aliases
    )

    open class StringSetCmdLine(
        default: HashSet<String>?,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<HashSet<String>>(
        default,
        StringSetConverter, option, aliases, pythonName
    )

    open class AssertFilterCmdLine(
        default: HashSet<String>?,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<HashSet<String>>(
        default,
        AssertFilterConverter, option, aliases, pythonName
    )

    open class SolverProgramCmdLine(
        default: Array<SolverConfig>,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<Array<SolverConfig>>(
        default,
        SolverProgramListConverter, option, aliases
    )

    open class CEGARConfigCmdLine(
        default: CEGARConfig,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<CEGARConfig>(
        default,
        CEGARConfigConverter, option, aliases
    )

    open class HashingSchemeCmdLine(
        default: HashingScheme?,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<HashingScheme>(
        default,
        HashingSchemeConverter, option, aliases
    )

    open class DebugAdapterCmdLine(
        default: DebugAdapterProtocolMode?,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<DebugAdapterProtocolMode>(
        default,
        DebugAdapterConverter, option, aliases
    )

    open class HardFailCmdLine(
        default: HardFailMode,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<HardFailMode>(default, HardFailConverter, option, aliases, pythonName)

    open class ConstraintChooserCmdLine(
            default: ConstraintChooserEnum,
            option: Option,
            aliases: List<Option> = listOf()
    ) : CmdLine<ConstraintChooserEnum>(
            default,
            ConstraintChooserConverter, option, aliases
    )

    open class PrettifyCEXCmdLine(
        default: PrettifyCEXEnum,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<PrettifyCEXEnum>(
            default,
            PrettifyCEXConverter, option, aliases
    )

    open class CoverageInfoCmdLine(
        default: CoverageInfoEnum,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<CoverageInfoEnum>(
        default,
        CoverageInfoConverter, option, aliases, pythonName
    )

    open class MultipleCEXStrategyCmdLine(
        default: MultipleCEXStrategyEnum,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null,
    ) : CmdLine<MultipleCEXStrategyEnum>(
        default,
        MultipleCEXStrategyConverter, option, aliases, pythonName
    )

    open class RelaxedFinders(
        default: Boolean,
        option: Option
    ) : CmdLine<Boolean>(default, Converter { raw ->
        when(raw.lowercase()) {
            "default", "extended" -> false
            "relaxed" -> true
            else -> throw ConversionException(raw, RelaxedFinders::class.java)
        }
    }, option)

    open class SanityMode(
        default: SanityValues,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<SanityValues>(
        default,
        SanityModeConverter, option, aliases, pythonName
    )

    open class ActiveEcosystem(
        default: Ecosystem,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<Ecosystem>(
        default,
        EcosystemConverter, option, aliases, pythonName
    )

    open class RustVecLayout(
        default: cli.RustVecLayout,
        option: Option,
        aliases: List<Option> = listOf(),
        pythonName: String? = null
    ) : CmdLine<cli.RustVecLayout>(
        default,
        RustVecLayoutConverter, option, aliases, pythonName
    )

    open class SummaryResolutionMode(
        default: SummaryResolutionPolicy,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<SummaryResolutionPolicy>(
        default,
        Converter { raw ->
            when(raw.lowercase()) {
                "simple" -> SummaryResolutionPolicy.SIMPLE
                "tiered" -> SummaryResolutionPolicy.TIERED
                "aggressive" -> SummaryResolutionPolicy.AGGRESSIVE
                else -> throw ConversionException(raw, SummaryResolutionPolicy::class.java)
            }
        }, option, aliases
    )

    enum class StackAnnotation { CODE, TRANSFORM }

    open class StackAnnotationConfig(
        default: Array<StackAnnotation>,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<Array<StackAnnotation>>(
        default,
        Converter { raw ->
            @Suppress("ForbiddenMethodCall") // split
            raw.split(",").flatMapToSet {
                when (it.lowercase()) {
                    "none" -> listOf()
                    "code" -> listOf(StackAnnotation.CODE)
                    "transform" -> listOf(StackAnnotation.TRANSFORM)
                    "all" -> StackAnnotation.entries
                    else -> throw ConversionException(it, StackAnnotation::class.java)
                }
            }.toTypedArray()
        }, option, aliases
    )

    @Suppress("ForbiddenMethodCall")
    open class RelaxedSemantics(
        default: Array<Pair<String, String>>,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<Array<Pair<String, String>>>(
        default = default,
        aliases = aliases,
        option = option,
        converter = Converter { raw ->
            raw.toNameValuePairs<RelaxedSemantics>().toTypedArray()
        }
    )

    open class PerTransformConcurrencyLimitConfig(
        default: Array<Pair<ReportTypes, Int>>,
        option: Option,
        aliases: List<Option> = listOf()
    ) : CmdLine<Array<Pair<ReportTypes, Int>>>(
        default = default,
        aliases = aliases,
        option = option,
        converter = Converter { raw ->
            raw.toNameValuePairs<PerTransformConcurrencyLimitConfig>().map { (k, v) ->
                val type = ReportTypes.byLowerCaseName[k.lowercase()] ?: throw ConversionException(
                    src = PerTransformConcurrencyLimitConfig::class.java,
                    raw = k
                )
                val limit = v.toIntOrNull() ?: throw ConversionException(
                    src = PerTransformConcurrencyLimitConfig::class.java,
                    raw = v
                )
                type to limit
            }.toTypedArray()
        }
    ) {
        override fun check(newValue: Array<Pair<ReportTypes, Int>>) = newValue.all { (_, limit) -> limit > 0 }
    }

    open class TacEntryPointCmdLine(
        default: ReportTypes?,
        option: Option,
        aliases: List<Option> = emptyList(),
    ): CmdLine<ReportTypes> (
        default = default,
        aliases = aliases,
        option = option,
        converter = Converter { string ->
            ReportTypes.byLowerCaseName[string.lowercase()]
                ?: ReportTypes.byConfigName[string.lowercase()]
                ?: throw ConversionException(
                    src = PerTransformConcurrencyLimitConfig::class.java,
                    raw = string
                )
        }
    )

    companion object {
        @Suppress("ForbiddenMethodCall") // split
        private inline fun <reified S : ConfigType<*>> String.toNameValuePairs(): List<Pair<String, String>> =
            split(',', ' ').map {
                val tok = it.split(':', limit = 2)
                if(tok.size != 2) {
                    throw ConversionException(
                        src = S::class.java,
                        raw = this
                    )
                }
                tok[0] to tok[1]
            }
    }
}

fun Option.realOpt() = (this.opt ?: this.longOpt)
    ?: throw IllegalArgumentException(
        "For an option ${this.description} either short or long options must be non-null"
    )

