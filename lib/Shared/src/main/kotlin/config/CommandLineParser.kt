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
package config

import annotations.PollutesGlobalState
import certora.CVTVersion
import cli.SummaryResolutionPolicy
import config.Config.AvoidAnyOutput
import config.Config.MainOutputFolder
import config.Config.OverwriteMainOutputDir
import config.component.EventConfig
import sbf.SolanaConfig
import log.*
import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.MissingArgumentException
import org.apache.commons.cli.Options
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import utils.*
import utils.CheckedUrl
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.system.exitProcess

private val logger = Logger(LoggerTypes.COMMON)

open class DefaultCommandLineParser {
    val options: Options by utils.lazy {ConfigRegister.getCLIOptions().fold(Options()) { AllOptions, o -> AllOptions.addOption(o) }}
    fun withAddendum(msg: String) = "${msg}. See ${CheckedUrl.CLI_ARGS} for more help."

    private fun printErrAndExit(msg: String, withProverArgsDocLink: Boolean = false, exitcode: Int = 1): Nothing {
        check(exitcode != 0) { "Not expected to print error if exitcode is going to be 0" }
        if (withProverArgsDocLink) {
            withAddendum(msg)
        } else {
            msg
        }.let { updatedMsg ->
            printMsgAndExit(updatedMsg, withHelp = false, allHelp = false, withVersion = false, exitcode = exitcode)
        }
    }

    /**
     *
     * @return This method never returns normally.
     */
    protected open fun printMsgAndExit(msg: String?, withHelp: Boolean, allHelp: Boolean, withVersion: Boolean, exitcode: Int): Nothing {
        check (withHelp || !allHelp) { "Can't ask to not show help but also ask all advanced help" }
        if (msg != null) {
            // call early errors I ever saw were bad prover_args, and reach this flow.
            if (exitcode != 0) {
                Logger.alwaysError(msg)
            } else {
                Logger.always(msg, respectQuiet = false)
            }
            reportCommandLineParsingErrors(msg)
        }
        if (withVersion) {
            printVersion()
        }
        if (withHelp) {
            printHelp(HelpFormatter().also{it.width = 120}, options, allHelp)
        }
        exitProcess(exitcode)
    }

    private fun reportCommandLineParsingErrors(msg: String) {
        // Let's make the OutPrinter (that was supposed to be created only later), print the error, and good-bye
        OutPrinter.print(msg)
        OutPrinter.close()
        val alertReporter = CVTAlertReporter()
        if (!alertReporter.isInit()) {
            alertReporter.init()
        }
        alertReporter.reportAlert(
            CVTAlertType.GENERAL,
            CVTAlertSeverity.ERROR,
            null,
            msg,
            null,
            CheckedUrl.CLI_ARGS,
        )
        alertReporter.close()
    }

    private fun printVersion() {
        val toolName = "CertoraProver/CVT"
        @Suppress("ForbiddenMethodCall")
        print("$toolName version (${CVTVersion.getInternalVersionString()})")
    }

    private fun getCommandLineArgs(args: Array<String>): CommandLine {

        try {
            // DefaultParser is the only possible option. It parses single-letter arguments in a way that allows
            // combining several options together (like you can do `ls -ltrh`).
            val parsedArgs = DefaultParser().parse(options, args)
            // handle errors
            if (Config.ShowHelp.isConfigured(parsedArgs)) {
                // -help will just exit the process and not do anything
                printMsgAndExit(null, withHelp = true, allHelp = false, withVersion = false, exitcode = 0)
            }

            if (Config.ShowVersion.isConfigured(parsedArgs)) {
                // --version (or -v) will just exit the process and not do anything
                printMsgAndExit(null, withHelp = false, allHelp = false, withVersion = true, exitcode = 0)
            }

            if (Config.ShowAdvancedHelp.isConfigured(parsedArgs)) {
                printMsgAndExit(null, withHelp = true, allHelp = true, withVersion = false, exitcode = 0)
            }

            if (parsedArgs.args.size > 1) {
                printErrAndExit(
                    "Only one argument is expected, got ${parsedArgs.args.size}: ${
                        parsedArgs.args.map { it.toString() }.joinToString(
                            ","
                        )
                    }."
                )
            }

            return parsedArgs
        } catch (e: MissingArgumentException) {
            // unlikely that e.message is null, but being safe here
            printErrAndExit(e.message ?: "Missing argument for option: ${e.option}", withProverArgsDocLink = true)
        } catch (e: Exception) {
            printMsgAndExit(e.message, withHelp = true, allHelp = false, withVersion = false, exitcode = 1)
            // must return `Nothing` to infer that this part is unreachable
        }
    }

    private fun printHelp(helpFormatter: HelpFormatter, options: Options, all: Boolean) {
        /*
        val devUsefulOptions = listOf(
                            Config.SpecFile,
                            Config.SubContract,
                            Config.CopyLoopUnrollConstant,
                            Config.LoopUnrollConstant,
                            Config.EnableStorageVariableUnpacking,
                            Config.ShouldDeleteSMTFiles,
                            Config.EnableSplitting,
                            Config.SplittingDepth,
                            Config.CacheKeyName,
                            Config.SolverProgramChoice,
                            Config.PathThreshold,
                            Config.ShowHelp
        )
         */
        val filteredOptions = options
            .options
            .filter {
                all || it.realOpt() in Config.optionsForHelpMsg.flatMap { it.allOptions.map { it.realOpt() } }
            }
        val optionsForUsage = Options()
        filteredOptions.forEach {
            optionsForUsage.addOption(it)
        }

        helpFormatter.printHelp("certoraRun ... --prover_args '-optionName value -flag'", optionsForUsage)
    }

    private fun checkLegalConfig(cmdLineArgs: CommandLine) {
        if (ConfigType.MainFileName.getOrNull() != null && Config.getIsUseCache()) {
            printErrAndExit("Caching only available when running from scripts, and not directly on files")
        }

        if (Config.SolverMemLimit.allOptions.count { cmdLineArgs.hasOption(it.realOpt()) } > 1) {
            printErrAndExit("Must use at most one out of ${Config.SolverMemLimit.allOptions.map { it.realOpt() }}")
        }

        if (Config.CvlFunctionRevert.get() && Config.SummaryResolutionMode.get() != SummaryResolutionPolicy.SIMPLE) {
            printErrAndExit(
                "Cvl Function Revert is incompatible with Summary Resolution Mode ${Config.SummaryResolutionMode.get().name.lowercase()}. " +
                    "Please set either -${Config.CvlFunctionRevert.name} false " +
                    "or -${Config.SummaryResolutionMode.name} ${SummaryResolutionPolicy.SIMPLE.name.lowercase()}."
            )
        }
    }

    private fun basicChecksSkippedByApache(cmdLineArgs: CommandLine, originalArgs: Array<String>) {
        /*  apache.commons.cli is annoying because of how it deals with long options. Say we have
            an option `-b 2` and another option `-bNONEXISTENT`.
            if we run `java -jar emv.jar -b 2 -bNONEXISTENT` the tool won't report that `-bNONEXISTENT` does not exist.
            It actually might parse the value to the `-bNONEXISTENT` (if one was given by mistake/typo) as an argument,
            and ruin the run. We can see in the parsed arguments that the -b option appears twice (as it's a defined
            option) and with value `2` in one case and `NONEXISTENT` in the other.
            As this is a problem of the non-prefix closeness of our options, we just make sure each option appears
            only once.
         */
        cmdLineArgs.options.toList().let { optionsList ->
            val optListsWithDups = optionsList.groupBy { it.opt }.values.filter { it.size > 1 }
            if (optListsWithDups.isNotEmpty()) {
                // options cannot appear multiple times.
                // it is possible what happens is that the second instance is actually invalid.
                // if we find option|value in the original unparsed args, good chances we got an invalid option
                // undetected by apache.commons.cli
                optListsWithDups.forEach { dupOpts ->
                    dupOpts.forEach { opt ->
                        if (opt.values != null && opt.values.size > 1) {
                            printErrAndExit("Option $opt should not get multiple values: ${opt.values}", withProverArgsDocLink = true)
                        }
                        val dummyOption = "-${opt.opt}${opt.value}"
                        if (originalArgs.contains(dummyOption)) {
                            printErrAndExit(noSuchOptionErrStr(dummyOption), withProverArgsDocLink = true)
                        }
                    }
                }
            }
        }
    }

    // protected for the sake of testing
    protected fun noSuchOptionErrStr(opt: String) = "There is no such option $opt"

    // this should be called just once and early on in the process
    @PollutesGlobalState
    fun processArgsAndConfig(args: Array<String>) {
        OutPrinter.init()
        val cmdLineArgs = getCommandLineArgs(args)
        basicChecksSkippedByApache(cmdLineArgs, args)
        if (cmdLineArgs.args.size == 1) {
            ConfigType.MainFileName.set(cmdLineArgs.args.get(0))
        }

        ConfigRegister.registeredConfigs.forEach { conf ->
            when (conf) {
                is ConfigType.CmdLine -> try {
                    conf.setFromCLI(cmdLineArgs)
                } catch (e: Exception) {
                    val matchedOption = conf.getMatchedOption(cmdLineArgs)
                    check(matchedOption != null) { "Exception can't be thrown if option does not exist in cmd line arguments" }
                    // if we deal with a single-letter option, then the parser allows no space between the option and the value.
                    // this means that if that single letter is a prefix of another option O, and the user intended to type O
                    // but it contains a type, we will get an unfriendly error here.
                    val optionValue = cmdLineArgs.getOptionValue(matchedOption.realOpt())
                    if (matchedOption.realOpt().length == 1) {
                        val concatenatedOption = "-${matchedOption.realOpt()}$optionValue"
                        if (concatenatedOption in args) {
                            printErrAndExit("Invalid option $concatenatedOption", withProverArgsDocLink = true)
                        }
                    }
                    printErrAndExit("Bad argument ${matchedOption.realOpt()} = $optionValue: ${e.message}", withProverArgsDocLink = true)
                }
                else -> {}
            }
        }

        checkLegalConfig(cmdLineArgs)
    }

    @PollutesGlobalState
    fun setExecNameAndDirectory() {
        if (OverwriteMainOutputDir.get()) {
            val execName = MainOutputFolder.get()
            if (!AvoidAnyOutput.get()) {
                try {
                    logger.info { "Deleting ${execName} directory, if it already exists: ${File(execName).exists()}" }
                    File(execName).deleteRecursively()
                } catch (e: Exception) {
                    @Suppress("TooGenericExceptionThrown")
                    throw Exception("Failed to overwrite output directory ${execName}: $e")
                }
                File(execName).mkdir()
            }
            ConfigType.ExecName.set(execName)
        } else {
            val cwd = File(".")
            @Suppress("ForbiddenMethodCall")
            val regex = Regex("""${MainOutputFolder.get()}-(\d+).*""")
            val number = 1 + (cwd.list()?.fold(0) { num, filename ->
                val match = regex.find(filename)
                if (match != null) {
                    val (newNum) = match.destructured
                    max(num, newNum.toInt())
                } else {
                    num
                }
            } ?: 0)
            val runName = "certora"
            val fixedRunName = runName.replace(':', '-')
                .replace('/', '-')
                .replace('\\', '-')
                .takeLast(25)
            val now = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("dd-LLL--HH-mm")
            fun execNamePattern(t : LocalDateTime, formatter: DateTimeFormatter) =
                "${MainOutputFolder.get()}-${number}-${fixedRunName}-${t.format(formatter)}".replace(':', '-')
            val execName = execNamePattern(now, formatter)
            ConfigType.ExecName.set(execName)
            if (!AvoidAnyOutput.get()) {
                var successMkdir = File(ConfigType.ExecName.get()).mkdir()
                var attemptNo = 1
                // if we have multiple instances of the jar running at exactly the same time,
                // mkdir will fail, leading to potential directory collisions between two runs.
                // to avoid that, we can retry with nano-of-seconds in the pattern, and a refreshed pattern.
                // Retrying that more than 10 times (current MaxNumOfAttemptsToCreateMainOutputDir) seems wrong
                val retryFormatter = DateTimeFormatter.ofPattern("dd-LLL--HH-mm-n")
                while (!successMkdir && attemptNo < Config.MaxNumOfAttemptsToCreateMainOutputDir.get()) {
                    ConfigType.ExecName.set(execNamePattern(LocalDateTime.now(), retryFormatter))
                    successMkdir = File(ConfigType.ExecName.get()).mkdir()
                    ++attemptNo
                    if (successMkdir) {
                        break
                    }
                }
                if (!successMkdir) {
                    // give up!
                    throw CertoraInternalException(
                        CertoraInternalErrorType.MULTIPLE_LOCAL_CVT_INSTANCES,
                        "Could not mkdir ${execName} nor any refreshed names for $attemptNo times, giving up"
                    )
                }
            }
        }
    }
}

object CommandLineParser : DefaultCommandLineParser(){

    /** Initializing and registers all [ConfigType.CmdLine] configuration options.
     *
     * This method initializes all Config objects as the objects have fields of type [ConfigType.CmdLine].
     * [ConfigType.CmdLine] does register itself upon initialization. (see init block in which [ConfigType.CmdLine.register] is called)
     */
    fun registerConfigurations() {
        Config
        EventConfig
        SolanaConfig
    }
}
