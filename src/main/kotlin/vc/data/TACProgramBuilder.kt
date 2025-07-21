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

import allocator.Allocator
import analysis.CmdPointer
import analysis.TACProgramPrinter
import com.certora.collect.*
import config.Config
import config.DestructiveOptimizationsModeEnum
import datastructures.stdcollections.*
import instrumentation.calls.ArgNum
import instrumentation.calls.CalldataEncoding
import instrumentation.createEmptyProgram
import scene.*
import tac.*
import utils.*
import utils.Color.Companion.blue
import utils.Color.Companion.green
import vc.data.TACProgramBuilder.BlockBuilder
import vc.data.TACProgramBuilder.BuiltTACProgram
import java.io.Serializable
import java.math.BigInteger

/**
 * A Class for constructing a [CoreTACProgram], especially for used in tests. Specifically it is useful for multi-block
 * programs. Best explained by an example:
 *
 * TACProgramBuilder {
 *    a assign 1
 *    jump(1) {
 *       x assign TACExpr.BinRel.Gt(a.asSym(), 0.asTACExpr())
 *       jumpCond(x)
 *       jump(2) {
 *          jump(3) {
 *             label("here")
 *             a assign 3
 *          }
 *       }
 *       jump(1)
 *    }
 * }
 *
 * The first (implicit) block is block 0, which has two commands, an assign to variable `a`, (which should be defined
 * already) of 1, followed by an unconditional jump to block 1.
 *   - Block 1 contains an assignment to variable x, and then the three lines `jumpCond(x)`, `jump(2)` and `jump(1)` at
 *     the end. Together they generate a conditional jump command: `jump to (x ? 2 : 1)`
 *   - Block 2 is created directly at the `jump(2)` command and has only an unconditional jump to block 3,
 *   - Block 3 is created similarly, and has only has one assignment command.
 *
 * The sauce is in the `jump` and `jumpCond` commands:
 *   + The block number to jump to is in the parentheses. If it is omitted, then a fresh number is assigned to the block.
 *   + If `jump` is followed by curly braces, then a new block with this number is created.
 *   + The `jump` and `jumpCond` can only appear after all other commands, and there can be up to two `jump`s in a block.
 *   + In the case of two `jump`s, a conditional jump is added to the end of the block.
 *   + If `jumpCond` is used, then the parameter is used as the condition. If there is no `jumpCond` then a fresh
 *      condition variable is generated and used.
 *
 * The 'label' command marks the command right after it in the code, and is not part of the resulting TAC program, but
 * is used to easily reference places in the generated program.
 *
 * The result of the above is an object of type [BuiltTACProgram], which holds the resulting [CoreTACProgram], but also
 * convenience functions for generating [CmdPointer]s and getting the cmds using either block numbers and positions, or
 * using the labels.
 *
 * One can also add arbitrary commands to the blocks, by calling `addCmd`, but its nicer to add to the end of the
 * [BlockBuilder] class a shortcut, such as the `assign`, `nop` and `havoc`, that are already there. As [BlockBuilder]
 * is a subclass of [TACExprFact], then all of the shortcuts there are available when writing expressions.
 *
 * It's possible now to load and store from a wordMap. So If `v` is a [TACSymbol.Var] of tag [Tag.WordMap], then:
 *    v[<loc>] assign <value>
 *    <some-var> assign v[<loc>]
 * where both <loc> and <value> should be [TACSymbol].
 *
 * To add meta to a command, use [BlockBuilder.addMetaToLastCmd] right after the command.
 */
class TACProgramBuilder private constructor() {
    companion object {

        /** blocks that were not explicitly given an id get ids starting with this number */
        private const val firstAnonymousBlock = 10000

        operator fun invoke(build: BlockBuilder.() -> Unit) =
            TACProgramBuilder().construct(build)


        /**
         * returns a string that can be printed (though [TACProgramPrinter] is much better) or used for simple
         * program comparison.
         */
        fun testProgString(code: CoreTACProgram, metas: Boolean = false, annotations: Boolean = false): String {
            val ret = StringBuilder()
            code.code.forEachEntry { (block, commands) ->
                ret.append("--- $block\n")
                commands.forEachIndexed { i, cmd ->
                    if (!annotations && cmd is TACCmd.Simple.AnnotationCmd) {
                        return@forEachIndexed
                    }
                    ret.append("   $i : ${cmd.toStringNoMeta()}\n")
                    if (metas) {
                        for (m in cmd.meta.map) {
                            ret.append("         ${m.key} = ${m.value}\n".blue)
                        }
                        if (cmd is TACCmd.Simple.AssigningCmd) {
                            for (m in cmd.lhs.meta.map) {
                                ret.append("         ${m.key} = ${m.value}\n".green)
                            }
                        }
                    }
                }
            }
            return ret.toString()
        }
    }


    private val blocks = mutableMapOf<NBId, BlockBuilder>()
    private val labels = mutableMapOf<String, CmdPointer>()

    /**
     * The end result, holds the program and convenience functions to generate pointers and cmds
     */
    class BuiltTACProgram(val code: CoreTACProgram, private val labels: Map<String, CmdPointer>) {
        fun block(blkNum: Int) =
            blkNum.nbid

        fun ptr(blkNum: Int, pos: Int) =
            CmdPointer(blkNum.nbid, pos)

        fun ptr(label: String) =
            labels[label] ?: error("No label $label in CoreTacProgram ${code.name}")

        fun lcmd(blkNum: Int, pos: Int) =
            code.analysisCache.graph.elab(ptr(blkNum, pos))

        fun lcmd(label: String) =
            code.analysisCache.graph.elab(ptr(label))

        fun cmd(blkNum: Int, pos: Int) =
            lcmd(blkNum, pos).cmd

        fun cmd(label: String) =
            lcmd(label).cmd
    }


    /**
     * Creates the root block and a [BlockBuilder] for it. [build] then with it as reciver, adding commands to it, and
     * possibly creating new block with [BlockBuilder]s for them via `jump` commands.
     *
     * Finally this returns the complete program wrapped in a [BuiltTACProgram] object.
     */
    fun construct(build: BlockBuilder.() -> Unit): BuiltTACProgram {
        BlockBuilder(0.nbid).apply { build() }
        val graph = blocks.entries.associateTo(MutableBlockGraph()) { (nbid, block) -> nbid to block.jumps.toTreapSet() + treapSetOfNotNull(block.implicit) }
        val code = blocks.map { (nbid, block) -> nbid to block.cmds() }.toMap()
        labels.forEachEntry { (label, ptr) ->
            check(ptr.pos < code[ptr.block]!!.size) {
                "label $label cannot be the last command in a block."
            }
        }
        @OptIn(Config.DestructiveOptimizationsOption::class)
        val prog = CoreTACProgram(
            code,
            graph,
            "TestProg",
            TACSymbolTable.withTags(blocks.values.flatMapToSet { it.vars }),
            UfAxioms.empty(),
            IProcedural.empty()
        ).letIf(Config.DestructiveOptimizationsMode.get() == DestructiveOptimizationsModeEnum.ENABLE) {
            it.withDestructiveOptimizations(true)
        }
        return BuiltTACProgram(prog, labels)
    }


    private var idCounter = firstAnonymousBlock

    /**
     * Holds a list of commands in the block and supplies ways of adding to it, including `jump` commands for creating
     * new blocks that are successors of this block.
     */
    inner class BlockBuilder(val nbid: NBId, val txf: TACExprFact = TACExprFactUntyped) : TACExprFact by txf {
        init {
            check(nbid !in blocks.keys) { "Can't build the same block ${nbid.num} twice" }
            blocks[nbid] = this
        }

        private val cmds = mutableListOf<TACCmd.Simple>()
        private var jumpCond: TACSymbol.Var? = null
        val jumps = mutableListOf<NBId>()
        var implicit: NBId? = null

        fun addCmd(cmd: TACCmd.Simple) {
            check(jumps.isEmpty()) { "Can't add commands after a jump command has been added (Block ${nbid.num})" }
            cmds.add(cmd)
        }

        fun label(name: String) {
            labels[name] = CmdPointer(nbid, cmds.size)
        }

        fun jump(id: Int) {
            check(implicit == null) { "Can't have an implicit next block and a jump"}
            jumps.add(id.nbid)
        }

        fun implicitJump(id: Int) {
            check(jumps.isEmpty()) { "Can't have an implicit next block and a jump"}
            implicit = id.nbid
        }

        private fun firstAnonErrMsg(id: Int) =
            "Can't use $id as a blockId. Numbers should be below $firstAnonymousBlock"

        /** Starts another block, does not add a jump from the current block into it. */
        fun jumpDest(id: Int, build: BlockBuilder.() -> Unit) {
            check(id < firstAnonymousBlock) { firstAnonErrMsg(id) }
            BlockBuilder(id.nbid).build()
        }

        /** A jump command that also supplies commands to [build] the successor block */
        fun jump(id: Int, build: BlockBuilder.() -> Unit) {
            check(id < firstAnonymousBlock) { firstAnonErrMsg(id) }
            jumps.add(id.nbid)
            BlockBuilder(id.nbid).build()
        }


        fun jump(build: BlockBuilder.() -> Unit) {
            val id = idCounter++
            jumps.add(id.nbid)
            BlockBuilder(id.nbid).build()
        }

        fun jumpCond(v: TACSymbol.Var) {
            check(jumpCond == null) { "Can't have two jump conditions (Block ${nbid.num})" }
            jumpCond = v
        }

        private fun jumpCmd(): TACCmd.Simple? =
            when (jumps.size) {
                0 -> null
                1 -> TACCmd.Simple.JumpCmd(jumps.single())
                2 -> {
                    if (jumpCond == null) {
                        jumpCond = TACSymbol.Var("JumpCond_${nbid.num}", tag = Tag.Bool)
                    }
                    TACCmd.Simple.JumpiCmd(
                        cond = jumpCond!!,
                        dst = jumps[0],
                        elseDst = jumps[1]
                    )
                }

                else -> error("Can't have more than two successors to a block (Block ${nbid.num}")
            }

        fun cmds() = cmds + listOfNotNull(jumpCmd())

        val vars get() = cmds().flatMap { cmd ->
            cmd.getFreeVarsOfRhs() + listOfNotNull(cmd.getLhs())
        }

        // shortcuts to [addCmd]

        fun TACSymbol.Var.byteStore(loc: TACSymbol, value: TACSymbol) =
            addCmd(TACCmd.Simple.AssigningCmd.ByteStore(loc, value, base=this))

        infix fun TACSymbol.Var.assign(rhs: TACExpr) =
            addCmd(TACCmd.Simple.AssigningCmd.AssignExpCmd(this, rhs))

        infix fun TACSymbol.Var.assign(rhs: Int) = this assign rhs.asTACExpr

        infix fun TACSymbol.Var.assign(rhs: BigInteger) = this assign rhs.asTACExpr

        infix fun TACSymbol.Var.assign(rhs: TACSymbol) = this assign rhs.asSym()

        fun havoc(lhs: TACSymbol.Var) =
            addCmd(TACCmd.Simple.AssigningCmd.AssignHavocCmd(lhs))

        fun assume(b: TACSymbol, msg: String = "") = addCmd(TACCmd.Simple.AssumeCmd(b, msg))

        fun assumeNot(b: TACSymbol) = addCmd(TACCmd.Simple.AssumeNotCmd(b))

        fun assumeExp(e: TACExpr) = addCmd(TACCmd.Simple.AssumeExpCmd(e))

        fun assert(b: TACSymbol, msg: String = "") = addCmd(TACCmd.Simple.AssertCmd(b, msg))
        fun assert(b: TACExpr.Sym, msg: String = "") = assert(b.s, msg)

        /** An intermediate for [TACCmd.Simple.AssigningCmd.WordLoad] and [TACCmd.Simple.WordStore] */
        inner class MapAccess(val base : TACSymbol.Var, val loc : TACSymbol) {
            init { check(base.tag == Tag.WordMap || base.tag == Tag.ByteMap) }

            val asTACExpr = TACExpr.Select(base.asSym(), loc.asSym())
        }

        operator fun TACSymbol.Var.get(loc : TACSymbol) = MapAccess(this, loc)

        operator fun TACSymbol.Var.get(loc : Int) = MapAccess(this, loc.asTACSymbol())

        infix fun TACSymbol.Var.assign(rhs: MapAccess) = addCmd(
            when(rhs.base.tag) {
                Tag.WordMap -> TACCmd.Simple.AssigningCmd.WordLoad(this, rhs.loc, rhs.base)
                Tag.ByteMap -> TACCmd.Simple.AssigningCmd.ByteLoad(this, rhs.loc, rhs.base)
                else -> `impossible!`
            }
        )

        infix fun MapAccess.assign(value: TACSymbol) = addCmd(
            when (base.tag) {
                Tag.WordMap -> TACCmd.Simple.WordStore(this.loc, value, this.base)
                Tag.ByteMap -> TACCmd.Simple.AssigningCmd.ByteStore(this.loc, value, this.base)
                else -> `impossible!`
            }
        )

        infix fun MapAccess.assign(value: Int) =
            assign(value.asTACSymbol())

        val nop get() = addCmd(TACCmd.Simple.NopCmd)

        /** adds meta to the last cmd added (ignoring jumps) */
        inline fun <reified T: Serializable> addMetaToLastCmd(k : MetaKey<T>, v : T) {
            transformLastCmd { it.plusMeta(k, v) }
        }

        /** transforms last cmd added (ignoring jumps) according to [t] */
        fun transformLastCmd(t : (TACCmd.Simple) -> TACCmd.Simple) {
            check(cmds.isNotEmpty()) { "can't call `addMetaToLastCmd` when the block is still empty (Block ${nbid.num}"}
            cmds += t(cmds.removeLast())
        }
    }
}

/** We use a simple mapping between the [Int] labels we use for writing e.g. `jump(<int>)` and the proper [NBId]s.
 * used in tac programs. This field defines it. */
private val Int.nbid: NBId get() = BlockIdentifier(this, 0, 0, 0, 0, 0)
private val NBId.num get() = this.origStartPc

/**
 * Useful as a super class for a test class with predefined variables, etc.
 * Use the [txf] member to create new expressions.
 */
open class TACBuilderAuxiliaries {
    protected var txf: TACExprFactTypeChecked = TACExprFactTypeChecked(TACSymbolTable.empty())


    /** Returns the [NBId] according to the canonical naming of this class that matches the numbers in the
     * `jump(<number>)` to [NBId]s. */
    protected val Int.nbId get() = this.nbid

    private fun updateTACSymbolTable(v: TACSymbol.Var) {
        txf = TACExprFactTypeChecked(txf.symbolTable.mergeDecls(setOf(v)))
    }

    /**
     * Introduce a new variable named [name] of type [tag]. Annotate this variables so that the variables in a
     * counterexamples are properly named.
     */
    protected fun newVar(name: String, tag: Tag): TACSymbol.Var {
        return TACSymbol.Var(name, tag).withMeta {
            it + (TACMeta.CVL_VAR to true) + (TACMeta.CVL_DISPLAY_NAME to name)
        }.also(::updateTACSymbolTable)
    }

    fun intVar(name: String): TACSymbol.Var = newVar(name, Tag.Int)
    fun boolVar(name: String): TACSymbol.Var = newVar(name, Tag.Bool)
    fun bv256Var(name: String): TACSymbol.Var = newVar(name, Tag.Bit256)
    fun mkBmVar(name: String): TACSymbol.Var = newVar(name, Tag.ByteMap)
    fun wordMapVar(name:String) : TACSymbol.Var = newVar(name, Tag.WordMap)

    fun intConst(v: Long) = TACSymbol.Const(BigInteger.valueOf(v), Tag.Int)
    @Suppress("Unused")
    fun bv256Const(v: Long) = TACSymbol.Const(BigInteger.valueOf(v), Tag.Bit256)

    val String.boolVar get() = newVar(this, Tag.Bool)
    val String.bvVar get() = newVar(this, Tag.Bit256)
    val String.intVar get() = newVar(this, Tag.Int)
    val String.bmVar get() = newVar(this, Tag.ByteMap)


    val String.boolSym get() = boolVar.asSym()
    val String.bv256Sym get() = bvVar.asSym()
    @Suppress("Unused")
    val String.bvSym get() = bv256Sym
    val String.intSym get() = intVar.asSym()
    val String.bmSym get() = bmVar.asSym()

    // for making both at once, unfortunately the destructuring is only allowed for local vals
    val String.boolVarAndSym get() = boolVar to boolSym
    val String.intVarAndSym get() = intVar to intSym
    val String.bvVarAndSym get() = bvVar to bvSym
    val String.bmVarAndSym get() = bmVar to bmSym


    val i = intVar("i")
    val j = intVar("j")
    val k = intVar("k")
    val s = intVar("s")

    val iS = i.asSym()
    val jS = j.asSym()
    val kS = k.asSym()
    val sS = s.asSym()

    val a = bv256Var("a")
    val b = bv256Var("b")
    val c = bv256Var("c")
    val d = bv256Var("d")
    val e = bv256Var("e")
    val f = bv256Var("f")
    val g = bv256Var("g")

    val aS = a.asSym()
    val bS = b.asSym()
    val cS = c.asSym()
    val dS = d.asSym()
    val eS = e.asSym()
    val fS = f.asSym()
    val gS = g.asSym()

    val x = boolVar("x")
    val y = boolVar("y")
    val z = boolVar("z")
    val u = boolVar("u")
    val v = boolVar("v")
    val w = boolVar("w")

    val xS = x.asSym()
    val yS = y.asSym()
    val zS = z.asSym()
    val uS = u.asSym()
    val vS = v.asSym()
    val wS = w.asSym()

    val bMap1 = mkBmVar("bMap1")
    val bMap2 = mkBmVar("bMap2")
    val bMap3 = mkBmVar("bMap3")
    val bMap1S = bMap1.asSym()
    val bMap2S = bMap2.asSym()
    val bMap3S = bMap3.asSym()
}


/** I have no idea what's going on here, but it creates a contract with the given method */
fun mockContract(code: CoreTACProgram): IContractClass {
    val address = 1.toBigInteger()
    return object : MapBackedContractClass(
        instanceId = address,
        instanceIdIsStaticAddress = false,
        bytecode = null,
        constructorBytecode = null,
        name = ContractUniverse.ETHEREUM.getNameOfPrecompiledByAddress(address)!!,
        storageLayout = null,
        transientStorageLayout = null
    ) {
        override val methods: Map<BigInteger?, ITACMethod>
            get() = mapOf()

        override val wholeContractMethod: ITACMethod
            get() = onlyMethod

        private val calldataEncoding = CalldataEncoding(
            byteOffsetToScalar = mapOf(),
            argNum = ArgNum.Known(4.toBigInteger()),
            valueTypesArgsOnly = false,
            sighashSize = BigInteger.ZERO
        )

        private val onlyMethod: ITACMethod = TACMethod(
            _code = code,
            _meta = MetaMap(),
            _sighash = null,
            _name = "MockContract",
            _containingContract = this,
            _soliditySignature = null,
            _calldataEncoding = calldataEncoding,
            _attrib = MethodAttribute.Unique.Whole
        )

        override fun getDeclaredMethods(): Collection<ITACMethod> = listOf(onlyMethod)

        private val emptyConstructor = TACMethod(
            name,
            createEmptyProgram(name),
            this,
            MetaMap(),
            MethodAttribute.Unique.Constructor
        )

        override val constructorMethod: ITACMethod
            get() = emptyConstructor

        override var storageInfoField: IStorageInfo
            get() = StorageInfoWithReadTracker.empty()
            set(@Suppress("UNUSED_PARAMETER") s) {}
        override var transientStorageInfoField: IStorageInfo
            get() = StorageInfo.empty()
            set(@Suppress("UNUSED_PARAMETER") s) {}

        override fun fork(): IContractClass {
            return this
        }
    }
}

/**
 * Provides an environment to build a [CoreTACProgram] where only the graph shape and the [CallId]s are of interest, by
 * and large.
 * (The blocks and jump conditions are filled with dummies by default, although it is possible to provide code as well.)
 *
 * Usage:
 *  - make procedures, blocks, and add edges (i.e. program transitions) via [proc], [blk], [edge]
 *  - then call [prog] to get the [CoreTACProgram]
 */
class TacMockGraphBuilder private constructor() {
    private val allBlocks = mutableSetOf<NBId>()
    private val allProcedures = mutableSetOf<Procedure>()
    private val addedCode = mutableMapOf<NBId, List<TACCmd.Simple>>()

    fun blk(proc: Procedure) =
        BlockIdentifier(
            origStartPc = 0,
            stkTop = 0,
            decompCopy = 0,
            calleeIdx = proc.callId,
            topOfStackValue = 0,
            freshCopy = Allocator.getFreshId(Allocator.Id.BLOCK_FRESH_COPY)
        ).also {
            allBlocks += it
        }

    fun proc(name: String): Procedure {
        check(allProcedures.none { (it.procedureId as? ProcedureId.CVLFunction)?.functionName == name }) {
            "procedure with name $name already registered, cannot register two procedures with the same name"
        }
        val proc = Procedure(
            Allocator.getFreshId(Allocator.Id.CALL_ID),
            ProcedureId.CVLFunction(ContractOfProcedure.UNKNOWN, name)
        )
        allProcedures += proc
        return proc
    }

    fun addCode(block: NBId, code: List<TACCmd.Simple>) {
        check(block !in addedCode) { "code for block $block has already been added"}
        addedCode[block] = code
    }

    val dummyCmd = TACCmd.Simple.LabelCmd("dummyTestCmd")
    val dummyCond = true.asTACSymbol()


    val graph = MutableBlockGraph()

    fun edge(src: NBId, tgt: NBId) {
        graph[src] = graph[src].orEmpty() + tgt
    }

    /** [CoreTACProgram] requires the graph to be well-formed, in particular, every node needs non-null successors. */
    private fun finalizeGraph() {
        allBlocks.toList().forEach { // need to materialize as a list to avoid concurrent modification
            if (graph[it] == null)  {
                graph[it] = treapSetOf()
            }
        }
    }

    fun prog(name: String): CoreTACProgram {
        finalizeGraph()
        return CoreTACProgram(
            code = allBlocks.associateWith { node ->
                val code = addedCode[node] ?: listOf(dummyCmd)
                val succ = graph[node]?.toList()
                    ?: error("successors missing for node $node")
                if (succ.size > 1) {
                    check(succ.size == 2)
                    code + TACCmd.Simple.JumpiCmd(succ[0], succ[1], dummyCond)
                } else {
                    code
                }
            },
            blockgraph = graph,
            name = name,
            symbolTable = TACSymbolTable(),
            instrumentationTAC = InstrumentationTAC(UfAxioms.empty()),
            procedures = allProcedures,
        )
    }

    companion object {
        operator fun invoke(b: TacMockGraphBuilder.() -> Unit) {
            TacMockGraphBuilder().b()
        }
    }
}
