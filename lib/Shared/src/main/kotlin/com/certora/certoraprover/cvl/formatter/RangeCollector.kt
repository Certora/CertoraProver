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

package com.certora.certoraprover.cvl.formatter

import com.certora.certoraprover.cvl.*
import com.certora.certoraprover.cvl.formatter.util.ensure
import com.certora.certoraprover.cvl.formatter.util.invariantBroken
import datastructures.stdcollections.*
import utils.HasRange
import utils.Range

/**
 * visits all nodes that are [HasRange], starting from a [TopLevel],
 * and populates [nodeToRange] and [rangeToNode].
 *
 * this class should eventually be rewritten as an impl of a "visitor" interface,
 * also implemented by [Tokenizer], to avoid all this code duplication
 * between this and [Tokenizer]
 */
internal class RangeCollector(topLevels: List<TopLevel<*>>) {
    val nodeToRange: MutableMap<HasRange, Range.Range> = mutableMapOf()
    val rangeToNode: MutableMap<Range.Range, HasRange> = mutableMapOf()

    init {
        for (top in topLevels) {
            this.traverse(top)
        }
    }

    private fun visit(ctx: HasRange) {
        val range = ctx.range.nonEmpty()
        if (range != null) {
            val prev = nodeToRange.put(ctx, range)
            ensure(prev == null, "we do not visit the same node twice") {
                "got k = $ctx, v = $range, prev = $prev aka ${rangeToNode[prev]}"
            }
            rangeToNode[range] = ctx
        }
    }

    /** entry point */
    private fun traverse(top: TopLevel<*>) {
        when (top) {
            is GhostDecl -> this.ghostDecl(top)

            is Rule -> this.rule(top)

            is MethodsBlock -> this.methodsBlock(top)

            is CVLFunction -> this.cvlFunction(top)

            is Hook -> this.hook(top)

            is Invariant -> this.invariant(top)

            is MacroDefinition -> this.macro(top)

            is UninterpretedSortDecl -> this.uninterpretedSortDecl(top)

            is UseDeclaration -> this.useDeclaration(top)

            is ImportedContract -> this.importedContract(top)

            is ImportedSpecFile -> this.importedSpecFile(top)

            is OverrideDeclaration -> this.overrideDeclaration(top)
        }
    }


    private fun overrideDeclaration(ctx: OverrideDeclaration) {
        visit(ctx)
        when (ctx) {
            is OverrideDeclaration.CVLFunction -> cvlFunction(ctx.function)
            is OverrideDeclaration.MacroDefinition -> macro(ctx.def)
        }
    }

    private fun importedSpecFile(ctx: ImportedSpecFile) {
        visit(ctx)
    }

    private fun importedContract(ctx: ImportedContract) {
        visit(ctx)
    }

    private fun uninterpretedSortDecl(ctx: UninterpretedSortDecl) {
        visit(ctx)
    }

    private fun methodsBlock(ctx: MethodsBlock) {
        visit(ctx)
        ctx.entries.forEach(::methodEntry)
    }

    private fun useDeclaration(ctx: UseDeclaration<*>) {
        visit(ctx)
        paramFiltersMap(ctx.methodParamFilters)
    }

    private fun macro(ctx: MacroDefinition) {
        visit(ctx)
        ctx.param.forEach(::param)
        typeOrLhs(ctx.returnType)
        exp(ctx.body)
    }

    private fun invariant(ctx: Invariant) {
        visit(ctx)
        ctx.proof.preserved.forEach(::preserved)
        paramFiltersMap(ctx.mpf)
        ctx.params.forEach(::param)
        exp(ctx.exp)
    }

    private fun preserved(ctx: Preserved) {
        visit(ctx)

        when (ctx) {
            is ExplicitMethodPreserved -> methodSignature(ctx.methodSig)
            is FallbackPreserved -> Unit
            is GenericPreserved -> Unit
            is TransactionBoundaryPreserved -> Unit
            is ConstructorPreserved -> ctx.params.forEach(::param)
        }

        ctx.withParams?.forEach(::param)
        cmdList(ctx.block)
    }

    private fun hook(ctx: Hook) {
        visit(ctx)
        hookPattern(ctx.pattern)
        cmdList(ctx.commands)
    }

    private fun hookPattern(ctx: HookPattern) {
        visit(ctx)
        ctx.value?.run(::param)
        ctx.slot?.run(::slotPattern)
        ctx.oldValue?.run(::param)
    }

    private fun slotPattern(ctx: SlotPattern) {
        when (ctx) {
            is StaticSlotPattern -> {
                ctx.elements.forEach(::staticSlotPatternElement)
            }

            is ArrayAccessSlotPattern -> {
                visit(ctx.key)
                slotPattern(ctx.base)
            }
            is FieldAccessSlotPattern -> {
                slotPattern(ctx.base)
            }
            is MapAccessSlotPattern -> {
                visit(ctx.key)
                slotPattern(ctx.base)
            }
            is StructAccessSlotPattern -> {
                visit(ctx.offset)
                slotPattern(ctx.base)
            }

            is SlotPatternError -> invariantBroken("should not appear in valid code that passed compilation")
        }

    }

    private fun staticSlotPatternElement(ctx: StaticSlotPatternElement) {
        when (ctx) {
            is StaticSlotPatternNamed -> Unit
            is StaticSlotPatternNumber -> visit(ctx.number)
            is StaticSlotPatternTwoNumbers -> {
                visit(ctx.number1)
                visit(ctx.number2)
            }
        }
    }

    private fun cvlFunction(ctx: CVLFunction) {
        visit(ctx)
        ctx.params.forEach(::param)
        ctx.returnType?.run(::typeOrLhs)
        cmdList(ctx.block)
    }

    private fun methodEntry(ctx: MethodEntry) {
        visit(ctx)
        when (ctx) {
            is CatchAllSummary -> {
                methodReferenceExp(ctx.ref)
                ctx.preFlags.forEach(::locatedToken)
                summary(ctx.summary)
            }

            is UnresolvedDynamicSummary -> {
                // TODO: CERT-9281
                ensure(
                    ctx.summary != null,
                    "this is an error value and should not appear in valid code that passed compilation",
                )

                ctx.preFlags.forEach(::locatedToken)
                methodReferenceExp(ctx.methodReferenceExp)
                summary(ctx.summary)
            }

            is ImportedFunction -> {
                methodSignature(ctx.methodSignature)
                ctx.withParams?.forEach(::param)
                ctx.summary?.run(::summary)
            }
        }
    }

    private fun dispatchList(ctx: CallSummary.DispatchList) {
        fun havocSummary(ctx: CallSummary.HavocingCallSummary) {
            visit(ctx)
        }

        ctx.dispatcherList.forEach(::patternSig)
        ctx.default?.run(::havocSummary)
    }

    private fun patternSig(ctx: CallSummary.DispatchList.PatternSig) {
        visit(ctx)
        return when (ctx) {
            is CallSummary.DispatchList.PatternSig.WildcardMethod -> methodSignature(ctx.sig)
            is CallSummary.DispatchList.PatternSig.Params -> methodSignature(ctx.sig)
        }
    }

    private fun summary(ctx: CallSummary) {
        visit(ctx)
        when (ctx) {
            is CallSummary.Always -> exp(ctx.returnValue)

            is CallSummary.Expression -> {
                exp(ctx.exp)
                expectClause(ctx.expectedType)
            }

            is CallSummary.DispatchList -> dispatchList(ctx)

            is CallSummary.Constant,
            is CallSummary.Dispatcher,
            is CallSummary.AssertFalse,
            is CallSummary.Auto,
            is CallSummary.HavocAll,
            is CallSummary.HavocECF,
            is CallSummary.Nondet,
            is CallSummary.PerCalleeConstant -> Unit
        }
    }

    private fun expectClause(ctx: CallSummary.ExpectClause) {
        visit(ctx)
        when (ctx) {
            is CallSummary.ExpectClause.None -> Unit

            is CallSummary.ExpectClause.Type -> ctx.type.forEach(::param)

            is CallSummary.ExpectClause.Void -> Unit
        }
    }

    private fun ghostAxiom(ctx: GhostAxiom) {
        visit(ctx)
        exp(ctx.exp)
    }

    private fun param(ctx: Param) {
        visit(ctx)
        typeOrLhs(ctx.type)
    }

    private fun locatedToken(ctx: LocatedToken) {
        visit(ctx)
    }

    private fun methodSignature(ctx: MethodSig) {
        visit(ctx)
        methodReferenceExp(ctx.id)
        ctx.params.forEach(::param)
        ctx.methodQualifiers?.preReturnsAnnotations?.forEach(::locatedToken)
        ctx.resParams.forEach(::param)
        ctx.methodQualifiers?.postReturnsAnnotations?.forEach(::locatedToken)
    }

    private fun methodReferenceExp(ctx: MethodReferenceExp) {
        visit(ctx)
        ctx.contract?.run(::exp)
    }

    private fun rule(ctx: Rule) {
        visit(ctx)
        ctx.params.forEach(::param)
        paramFiltersMap(ctx.methodParamFilters)
        cmdList(ctx.block)
    }

    private fun cmdList(ctx: CmdList) {
        visit(ctx)
        ctx.cmds.forEach(::cmd)
    }

    private fun paramFiltersMap(ctx: MethodParamFiltersMap) {
        visit(ctx)

        fun def(ctx: MethodParamFilterDef) {
            exp(ctx.methodParam)
            exp(ctx.filterExp)
        }

        ctx.methodParamFilters?.values?.forEach(::def)
    }

    private fun cmd(ctx: Cmd) {
        visit(ctx)
        when (ctx) {
            is AssertCmd -> exp(ctx.exp)

            is DeclarationCmd -> typeOrLhs(ctx.type)

            is DefinitionCmd -> {
                ctx.type?.run(::typeOrLhs)
                ctx.lhs.forEach(::typeOrLhs)
                exp(ctx.exp)
            }

            is ApplyCmd -> exp(ctx.applyExp)

            is AssumeCmd -> exp(ctx.exp)

            is AssumeInvariantCmd -> ctx.params.forEach(::exp)

            is BlockCmd -> ctx.block.forEach(::cmd)

            is HavocCmd -> {
                ctx.targets.forEach(::exp)
                ctx.assumingExp?.run(::exp)
            }

            is IfCmd -> {
                exp(ctx.cond)
                cmd(ctx.thenCmd)
                ctx.elseCmd?.run(::cmd)
            }

            is ResetStorageCmd -> exp(ctx.target)

            is ReturnCmd -> ctx.exps.forEach(::exp)

            is RevertCmd -> Unit

            is SatisfyCmd -> exp(ctx.exp)
        }
    }

    private fun ghostDecl(ctx: GhostDecl) {
        visit(ctx)
        when (ctx) {
            is GhostFunDecl -> {
                ctx.paramTypes.forEach(::typeOrLhs)
                typeOrLhs(ctx.returnType)
                ctx.axioms.forEach(::ghostAxiom)
            }

            is GhostMapDecl -> {
                typeOrLhs(ctx.type)
                ctx.axioms.forEach(::ghostAxiom)
            }
        }
    }

    private fun typeOrLhs(ctx: TypeOrLhs) {
        visit(ctx)
        when (ctx) {
            is ArrayLhs -> {
                typeOrLhs(ctx.baseType)
                exp(ctx.index)
            }

            is DynamicArrayType -> typeOrLhs(ctx.baseType)

            is IdLhs -> Unit

            is QualifiedTypeReference -> Unit

            is MappingType -> {
                typeOrLhs(ctx.keyType)
                typeOrLhs(ctx.valueType)
            }

            is TupleType -> ctx.types.forEach(::typeOrLhs)
        }
    }

    private fun exp(ctx: Exp) {
        visit(ctx)
        when (ctx) {
            is BoolExp -> Unit

            is RelopExp -> {
                exp(ctx.l)
                exp(ctx.r)
            }

            is VariableExp -> Unit

            is UnresolvedApplyExp -> {
                ctx.base?.run(::exp)
                ctx.args.forEach(::exp)
            }

            is NumberExp -> Unit

            is UnaryExp -> exp(ctx.o)

            is BinaryExp -> {
                exp(ctx.l)
                exp(ctx.r)
            }

            is FieldSelectExp -> exp(ctx.b)

            is ArrayDerefExp -> {
                exp(ctx.ad)
                exp(ctx.indx)
            }

            is ArrayLitExp -> ctx.a.forEach(::exp)

            is BifApplicationExpr -> ctx.args.forEach(::exp)

            is CastExpr -> exp(ctx.exp)

            is CondExp -> {
                exp(ctx.c)
                exp(ctx.e1)
                exp(ctx.e2)
            }


            is QuantifierExp -> {
                param(ctx.param)
                exp(ctx.body)
            }

            is SetMemExp -> {
                exp(ctx.e)
                exp(ctx.set)
            }

            is SignatureLiteralExp -> methodSignature(ctx.methodSig)

            is StringExp -> Unit

            is SumExp -> {
                ctx.params.forEach(::param)
                exp(ctx.body)
            }

            is ErrorExpr -> invariantBroken("should not appear in valid code that passed compilation")
        }

    }

}
