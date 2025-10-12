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

package smtlibutils

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import log.*
import parallel.coroutines.onThisThread
import smtlibutils.cmdprocessor.*
import smtlibutils.data.*
import smtlibutils.parser.SMTParser
import smtlibutils.statistics.CollectDifficultyStats
import utils.`impossible!`
import java.io.File
import java.io.FileReader

/** Using Cli Kt as command line parser, for documentation check [https://ajalt.github.io/clikt/]. */
class SMTLIBUtils : CliktCommand() {

    /** parses the first command line argument as a file */
    private val smtFiles by argument().file(mustExist = true).multiple()

    /* flags to trigger various execution modes  */

    private val prettyPrint by option(
        "--prettyPrint",
        help = "parse the given file and prettyprint to the given file (e.g. `--prettyPrint=myFile.smt2`)"
    )

    private val collectDifficultyStats by option(
        "--collectDifficultyStats",
        help = "parse the given file and return some statistics about it; if `--dumpFile` option is given, this will" +
                "create a file in `.csv` format with the results"
    ).flag()

    private val dumpFileOpt: String? by option("--dumpFile")

    override fun run() {
        check(smtFiles.isNotEmpty()) { "please give at least one smt file in the arguments" }
        if (collectDifficultyStats) {
            CollectDifficultyStats.collectStats(dumpFileOpt?.let { File(it) }, smtFiles)
        } else {
            runOnSingleFile(smtFiles.singleOrNull() ?:
                error("expecting only one smt file in the arguments when we're not in \"--collectDifficultyStats\" mode"))
        }
    }


    private fun runOnSingleFile(smtFile: File) {
        val parser = FileReader(smtFile).use { f ->
            val parser = SMTParser(f.readText())
            parser.parse()
            parser
        }

        if (collectDifficultyStats) {
            `impossible!`
        } else {
            /* parse [smtFile] as a regular smtLib script */
            val smt = parser.getSmt()
            val logic = FindLogic.findLogic(smt)
            logger.info { "parsed file as smt script" }
            val smtScript = SmtScript()
            val sortedAndNormalized = SortAndNormalize(smtScript, logic).smt(smt)

            if (prettyPrint != null) {
                val prettyPrinter = SmtPrettyPrinter.DiffFriendly()
                val printingCmdProcessor = PrintingCmdProcessor(prettyPrint as String, prettyPrinter = prettyPrinter)
                onThisThread {
                    sortedAndNormalized.cmds.forEach { printingCmdProcessor.process(it) }
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    val path = System.getProperty("user.dir")
    logger.info { "Working in $path" }
    val libpath = System.getProperty("java.library.path")
    logger.debug { "Lib path: $libpath" }

    logger.info { "Running with arguments: ${args.joinToString(",")}" }

    SMTLIBUtils().main(args)
}

private val logger = Logger(SMTLIBUtilsLoggerTypes.SMTLIBUTILS)
