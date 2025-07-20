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
import com.certora.certoraprover.cvl.formatter.util.*
import datastructures.stdcollections.*
import datastructures.stdcollections.emptyList
import datastructures.stdcollections.listOf
import spec.cvlast.SpecCallSummary
import spec.cvlast.StrongInvariantType
import spec.cvlast.WeakInvariantType
import utils.*
import utils.letIf

internal class Tokenizer(
    fileComments: List<Binding>,
    private val nodeToRange: Map<HasRange, Range.Range>,
    private val rangeToNode: Map<Range.Range, HasRange> = nodeToRange.entries.associate { (k, v) -> Pair(v, k) }
) {
    private val ranges: List<Range.Range> = rangeToNode.keys.sorted()

    private val nodeToComments: MutableMap<HasRange, List<Binding>> = fileComments
        .groupBy { binding ->
            val deepest = ranges
                .findLast { nodeRange -> binding.token.range in nodeRange }
                ?: invariantBroken("every token is inside an AST") { "got binding: $binding, ranges: $ranges" }

            rangeToNode[deepest] ?: invariantBroken("ranges is derived from the set of keys of the map")
        }
        .toMutableMap()

    /** entry point */
    fun tokenize(top: TopLevel<*>): List<Token> {
        return when (top) {
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

    private fun overrideDeclaration(ctx: OverrideDeclaration): List<Token> {
        return flatListOf(
            Token.fromSym(sym.OVERRIDE).asList(),
            when (ctx) {
                is OverrideDeclaration.CVLFunction -> cvlFunction(ctx.function)
                is OverrideDeclaration.MacroDefinition -> macro(ctx.def)
            }
        ).context(ctx)
    }

    private fun importedSpecFile(ctx: ImportedSpecFile): List<Token> {
        return listOf(
            Token.fromSym(sym.IMPORT),
            Token.string(ctx.specFileOrigPath),
            Token.Punctuation.Semicolon.toToken(),
        ).context(ctx)
    }

    private fun importedContract(ctx: ImportedContract): List<Token> {
        return listOf(
            Token.fromSym(sym.USING),
            Token.identifier(ctx.contractName),
            Token.fromSym(sym.AS),
            Token.identifier(ctx.alias),
            Token.Punctuation.Semicolon.toToken(),
        ).context(ctx)
    }

    private fun uninterpretedSortDecl(ctx: UninterpretedSortDecl): List<Token> {
        return flatListOf(
            Token.fromSym(sym.SORT).asList(),
            Token.identifier(ctx.id).asList(),
            Token.Punctuation.Semicolon.toToken().asList(),
        ).context(ctx)
    }

    private fun methodsBlock(ctx: MethodsBlock): List<Token> {
        return flatListOf(
            Token.fromSym(sym.METHODS).asList(),
            ctx.entries.flatMap(::methodEntry).surround(Token.Delimiter.CurlyBracket),
        ).context(ctx)
    }

    private fun invariantBody(mpf: MethodParamFiltersMap, proof: InvariantProof): List<Token> {
        val filters = paramFiltersMap(mpf)
        val sc = Token.Punctuation.Semicolon.toToken(Space.FT)

        return when {
            filters == null && proof.preserved.isEmpty() -> sc.asList()

            proof.preserved.isEmpty() -> flatListOf(
                Token.LineBreak.Hard.asList(),
                filters.orEmpty(),
            )

            else -> flatListOf(
                Token.LineBreak.Hard.asList(),
                filters.orEmpty(),
                block(proof.preserved, ::preserved),
            )
        }
    }

    private fun useDeclaration(ctx: UseDeclaration<*>): List<Token> {
        return when (ctx) {
            is UseDeclaration.BuiltInRule -> flatListOf(
                Token.fromSym(sym.USE).asList(),
                Token.fromSym(sym.BUILTIN).asList(),
                Token.fromSym(sym.RULE).asList(),
                Token.identifier(ctx.id).asList(),
                paramFiltersMap(ctx.methodParamFilters) ?: Token.Punctuation.Semicolon.toToken().asList(),
            ).context(ctx)

            is UseDeclaration.ImportedInvariant -> {
                flatListOf(
                    Token.fromSym(sym.USE).asList(),
                    Token.fromSym(sym.INVARIANT).asList(),
                    Token.identifier(ctx.id).asList(),
                    invariantBody(ctx.methodParamFilters, ctx.proof),
                ).context(ctx)
            }

            is UseDeclaration.ImportedRule -> flatListOf(
                Token.fromSym(sym.USE).asList(),
                Token.fromSym(sym.RULE).asList(),
                Token.identifier(ctx.id).asList(),
                paramFiltersMap(ctx.methodParamFilters) ?: Token.Punctuation.Semicolon.toToken().asList(),
            ).context(ctx)
        }
    }

    private fun macro(ctx: MacroDefinition): List<Token> {
        return flatListOf(
            Token.fromSym(sym.DEFINITION).asList(),
            Token.identifier(ctx.id).asList(),
            params(ctx.param),
            Token.fromSym(sym.RETURNS).asList(),
            typeOrLhs(ctx.returnType),
            Token.Punctuation.Equals.toToken().asList(),
            exp(ctx.body),
            Token.Punctuation.Semicolon.toToken().asList(),
        ).context(ctx)
    }

    private fun invariant(ctx: Invariant): List<Token> {
        return flatListOf(
            when (ctx.declaredInvariantType) {
                StrongInvariantType -> Token.fromSym(sym.STRONG).asList()
                WeakInvariantType -> Token.fromSym(sym.WEAK).asList()
                null -> emptyList()
            },
            Token.fromSym(sym.INVARIANT).asList(),
            Token.identifier(ctx.id).asList(),
            params(ctx.params),
            Token.Indent.asList(),
            Token.LineBreak.Hard.asList(),
            exp(ctx.exp),
            invariantBody(ctx.mpf, ctx.proof),
            Token.Unindent.asList(),
        ).context(ctx)
    }

    private fun preserved(ctx: Preserved): List<Token> {
        // has space after `with` keyword.
        fun withParams(ctx: List<CVLParam>): List<Token> = flatListOf(
            Token.fromSym(sym.WITH).asList(),
            ctx
                .tokenizeInterspersed(::param)
                .surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT)
        )

        fun middle() = when (ctx) {
            is ExplicitMethodPreserved -> methodSignature(ctx.methodSig)
            is FallbackPreserved -> listOf(
                Token.fromSym(sym.FALLBACK),
                Token.fromTerminalId(Token.Delimiter.Parenthesis.l, Space.FF),
                Token.fromTerminalId(Token.Delimiter.Parenthesis.r, Space.FT),
            )
            is GenericPreserved -> emptyList()
            is TransactionBoundaryPreserved -> Token.fromSym(sym.ON_TRANSACTION_BOUNDARY).asList()
        }

        return flatListOf(
            Token.fromSym(sym.PRESERVED).asList(),
            middle(),
            ctx.withParams.letOrEmpty(::withParams),
            cmdList(ctx.block),
            Token.LineBreak.Soft.asList(),
        ).context(ctx)
    }

    private fun hook(ctx: Hook): List<Token> {
        return flatListOf(
            Token.fromSym(sym.HOOK).asList(),
            hookPattern(ctx.pattern),
            cmdList(ctx.commands),
        ).context(ctx)
    }

    private fun hookPattern(ctx: HookPattern): List<Token> {
        /** impl: can we somehow avoid re-parsing here? ideally we want to extract this info from the [HookPattern] */

        // n.b.: the token for some of these is _not_ simply the name in all-caps.
        val hookTypeToken = when (ctx.hookType) {
            HookType.SLOAD -> Token.fromSym(sym.SLOAD)
            HookType.SSTORE -> Token.fromSym(sym.SSTORE)
            HookType.CREATE -> Token.fromSym(sym.CREATE)
            HookType.TLOAD -> Token.fromSym(sym.TLOAD)
            HookType.TSTORE -> Token.fromSym(sym.TSTORE)
            else -> Token.Terminal(ctx.hookType.name, TerminalId(sym.OPCODE))
        }

        val tail = when (ctx.hookType) {
            HookType.SLOAD, HookType.TLOAD -> {
                ensure(ctx.value != null && ctx.slot != null, "from parser production")

                flatListOf(
                    param(ctx.value),
                    slotPattern(ctx.slot),
                )
            }

            HookType.SSTORE, HookType.TSTORE -> {
                ensure(ctx.value != null && ctx.slot != null, "from parser production")

                flatListOf(
                    slotPattern(ctx.slot),
                    param(ctx.value),
                    ctx.oldValue.letOrEmpty { param(it).surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT) }
                )
            }

            HookType.CREATE -> {
                ensure(ctx.value != null, "from parser production")

                param(ctx.value).surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT)
            }

            else -> {
                // it's an opcode
                ensure(ctx.params != null, "from parser production")

                flatListOf(
                    params(ctx.params),
                    ctx.value.letOrEmpty(::param)
                )
            }
        }

        return flatListOf(hookTypeToken.asList(), tail)
    }

    private fun slotPattern(ctx: SlotPattern): List<Token> {
        // since we're iterating backwards, a list like [a,b,c,d,e,f,g]
        // might be appended to the buffer of lists as follows;
        // [] -> [[e,f,g]] -> [[e,f,g], [d]] -> [[e,f,g], [d], [b,c]] -> [[e,f,g], [d], [b,c], [a]]
        val buffer: MutableList<List<Token>> = mutableListOf()

        var ptr: SlotPattern? = ctx
        while (ptr != null) {
            val (tokens, nextPtr) = slotPatternRecurse(ptr)
            buffer.add(tokens)
            ptr = nextPtr
        }

        // ...and so, from the explanation above,
        val result = buffer.asReversed().flatten()

        return result.context(ctx)
    }

    /** recurse to the parent of [ptr]. returns the [Token]s that produce [ptr] and the parent of [ptr] */
    private fun slotPatternRecurse(ptr: SlotPattern): Pair<List<Token>, SlotPattern?> {
        val parent: SlotPattern?
        val tokensFromStep: List<Token>

        when (ptr) {
            is ArrayAccessSlotPattern -> {
                tokensFromStep = flatListOf(
                    flatListOf(
                        Token.identifier(SlotPattern.INDEX).asList(),
                        param(ptr.key),
                    ).surround(Token.Delimiter.SquareBracket)
                )
                parent = ptr.base
            }

            is MapAccessSlotPattern -> {
                tokensFromStep = flatListOf(
                    Token.identifier(SlotPattern.KEY).asList(),
                    param(ptr.key),
                ).surround(Token.Delimiter.SquareBracket)
                parent = ptr.base
            }

            is FieldAccessSlotPattern -> {
                tokensFromStep = listOf(
                    Token.Punctuation.Dot.toToken(),
                    Token.identifier(ptr.fieldName),
                )
                parent = ptr.base
            }

            is StructAccessSlotPattern -> {
                tokensFromStep = flatListOf(
                    Token.Punctuation.Dot.toToken().asList(),
                    flatListOf(
                        Token.identifier(SlotPattern.OFFSET).asList(),
                        exp(ptr.offset),
                    ).surround(Token.Delimiter.Parenthesis)
                )
                parent = ptr.base
            }

            is StaticSlotPattern -> {
                // this is the base-case of slot pattern,
                // and it's just a flat list of elements.
                //
                // impl: in the cases below, we need a space before surrounding parentheses.
                // otherwise, the pattern will bind to the token which precedes the slot pattern.
                // we can special case the preceding tokens instead, but that's not as clear.
                tokensFromStep = ptr
                    .elements
                    .map {
                        when (it) {
                            is StaticSlotPatternNamed -> Token.identifier(it.name).asList()

                            is StaticSlotPatternNumber -> flatListOf(
                                Token.identifier(it.prefix).asList(),
                                exp(it.number)
                            ).surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT)

                            is StaticSlotPatternTwoNumbers -> flatListOf(
                                Token.identifier(it.prefix1).asList(),
                                exp(it.number1),
                                Token.Punctuation.Comma.toToken().asList(),
                                Token.identifier(it.prefix2).asList(),
                                exp(it.number2),
                            ).surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT)
                        }
                    }
                    .intersperse { Token.Punctuation.Dot.toToken().asList() }
                    .flatten()
                parent = null
            }

            is SlotPatternError -> invariantBroken("should not appear in valid code that passed compilation")
        }

        return Pair(tokensFromStep, parent)
    }

    private fun cvlFunction(ctx: CVLFunction): List<Token> {
        return flatListOf(
            Token.fromSym(sym.FUNCTION).asList(),
            Token.identifier(ctx.id).asList(),
            params(ctx.params),
            ctx.returnType.letOrEmpty { Token.fromSym(sym.RETURNS) + typeOrLhs(it) },
            cmdList(ctx.block),
        ).context(ctx)
    }

    private fun methodEntry(ctx: MethodEntry): List<Token> {
        return when (ctx) {
            is CatchAllSummary -> flatListOf(
                Token.fromSym(sym.FUNCTION).asList(),
                methodReferenceExp(ctx.ref),
                ctx.preFlags.flatMap(::locatedToken),
                Token.fromSym(sym.IMPLIES).asList(),
                summary(ctx.summary),
                Token.endStatement(),
            ).context(ctx)

            is UnresolvedDynamicSummary -> {
                // TODO: CERT-9281
                ensure(
                    ctx.dispatchList != null,
                    "this is an error value and should not appear in valid code that passed compilation",
                )

                // apparently `function` used to be the keyword here, but was deprecated.
                val keyword = sym.UNRESOLVED_LOWERCASE

                flatListOf(
                    Token.fromSym(keyword).asList(),
                    ctx.preFlags.flatMap(::locatedToken),
                    Token.fromSym(sym.IN).asList(),
                    methodReferenceExp(ctx.methodReferenceExp),
                    ctx.params?.let(::params).orEmpty(),
                    Token.fromSym(sym.IMPLIES).asList(),
                    unresolvedDynamicSummary(ctx.dispatchList),
                    Token.endStatement(),
                ).context(ctx)
            }

            is ImportedFunction -> flatListOf(
                Token.fromSym(sym.FUNCTION).asList(),
                methodSignature(ctx.methodSignature),
                ctx.withParams.letOrEmpty(::tokenizeWithParams),
                ctx.summary.letOrEmpty { Token.fromSym(sym.IMPLIES) + summary(it) },
                Token.endStatement(),
            ).context(ctx)
        }
    }

    private fun summaryResolution(ctx: SpecCallSummary.SummarizationMode?): List<Token> = when (ctx) {
        SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY -> Token.fromSym(sym.UNRESOLVED).asList()
        SpecCallSummary.SummarizationMode.ALL -> Token.fromSym(sym.ALL).asList()
        SpecCallSummary.SummarizationMode.DELETE -> Token.fromSym(sym.DELETE).asList()
        null -> emptyList()
    }

    private fun unresolvedDynamicSummary(ctx: CallSummary.DispatchList): List<Token> {
        fun havocSummary(ctx: CallSummary.HavocingCallSummary): List<Token> {
            val kindToken = when (ctx) {
                is CallSummary.AssertFalse -> Token.fromSym(sym.ASSERT_FALSE)
                is CallSummary.HavocAll -> Token.fromSym(sym.HAVOC_ALL)
                is CallSummary.HavocECF -> Token.fromSym(sym.HAVOC_ECF)
                is CallSummary.Nondet -> Token.fromSym(sym.NONDET)

                is CallSummary.Auto -> invariantBroken("havoc summary incompatible with auto")
            }

            return flatListOf(
                kindToken.asList(),
                summaryResolution(ctx.summarizationMode)
            ).context(ctx)
        }

        // comma separators are optional here and this includes them
        fun dispatcherList(ctx: List<CallSummary.DispatchList.PatternSig>) = ctx
            .tokenizeInterspersed(
                ::patternSig,
                separator = listOf(Token.Punctuation.Comma.toToken(), Token.LineBreak.Hard),
                end = Token.LineBreak.Soft.asList(),
            )
            .surround(Token.Delimiter.SquareBracket, spaceOpen = Space.TF, spaceClose = Space.FT, openScope = true)

        return if (ctx.flags != null) {
            flatListOf(
                Token.fromSym(sym.DISPATCH).asList(),
                dispatcherFlags(ctx.flags),
                dispatcherList(ctx.dispatcherList),
                ctx.default.letOrEmpty { Token.fromSym(sym.DEFAULT) + havocSummary(it) },
            ).context(ctx)
        } else {
            ensure(ctx.default != null, "non-flag list syntax requires havoc summary")

            flatListOf(
                Token.fromSym(sym.DISPATCH).asList(),
                dispatcherList(ctx.dispatcherList),
                Token.fromSym(sym.DEFAULT).asList(),
                havocSummary(ctx.default),
            ).context(ctx)
        }
    }

    private fun patternSig(ctx: CallSummary.DispatchList.PatternSig): List<Token> {
        val isWildcard = when (ctx) {
            is CallSummary.DispatchList.PatternSig.Params -> false
            is CallSummary.DispatchList.PatternSig.WildcardMethod -> true
        }

        return methodSignature(ctx.sig, isWildcard).context(ctx)
    }

    private fun summary(ctx: CallSummary): List<Token> {
        val summaryTokens = when (ctx) {
            is CallSummary.Always -> flatListOf(
                Token.fromSym(sym.ALWAYS).asList(),
                exp(ctx.returnValue).surround(Token.Delimiter.Parenthesis),
            )

            is CallSummary.Dispatcher -> {
                val flags = when (ctx.flags) {
                    null -> emptyList()

                    is CallSummary.FlagSyntax.OptimismLiteral -> {
                        val sym = when (ctx.flags) {
                            CallSummary.FlagSyntax.OptimismLiteral.TRUE -> sym.TRUE
                            CallSummary.FlagSyntax.OptimismLiteral.FALSE -> sym.FALSE
                            CallSummary.FlagSyntax.OptimismLiteral.ASSERT -> sym.ASSERT
                            CallSummary.FlagSyntax.OptimismLiteral.REQUIRE -> sym.REQUIRE
                        }
                        Token.fromSym(sym).asList().surround(Token.Delimiter.Parenthesis)
                    }

                    is CallSummary.FlagSyntax.FlagList -> dispatcherFlags(ctx.flags)
                }

                flatListOf(
                    Token.fromSym(sym.DISPATCHER).asList(),
                    flags,
                    summaryResolution(ctx.summarizationMode),
                ).context(ctx)
            }

            is CallSummary.Expression -> flatListOf(
                exp(ctx.exp),
                expectClause(ctx.expectedType),
            )

            is CallSummary.Constant -> Token.fromSym(sym.CONSTANT).asList()
            is CallSummary.PerCalleeConstant -> Token.fromSym(sym.PER_CALLEE_CONSTANT).asList()
            is CallSummary.AssertFalse -> Token.fromSym(sym.ASSERT_FALSE).asList()
            is CallSummary.Auto -> Token.fromSym(sym.AUTO).asList()
            is CallSummary.HavocAll -> Token.fromSym(sym.HAVOC_ALL).asList()
            is CallSummary.HavocECF -> Token.fromSym(sym.HAVOC_ECF).asList()
            is CallSummary.Nondet -> Token.fromSym(sym.NONDET).asList()
        }

        val summarizationMode = when (ctx.summarizationMode) {
            SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY -> Token.fromSym(sym.UNRESOLVED)
            SpecCallSummary.SummarizationMode.ALL -> Token.fromSym(sym.ALL)
            SpecCallSummary.SummarizationMode.DELETE -> Token.fromSym(sym.DELETE)
            null -> null
        }

        return flatListOf(
            summaryTokens,
            summarizationMode?.asList().orEmpty(),
        ).context(ctx)
    }

    private fun expectClause(ctx: CallSummary.ExpectClause): List<Token> {
        return when (ctx) {
            is CallSummary.ExpectClause.None -> emptyList()

            is CallSummary.ExpectClause.Type -> flatListOf(
                Token.fromSym(sym.EXPECT).asList(),
                params(ctx.type),
            )

            is CallSummary.ExpectClause.Void -> flatListOf(
                Token.fromSym(sym.EXPECT).asList(),
                Token.fromSym(sym.VOID).asList(),
            )
        }.context(ctx)
    }

    private fun ghostAxiom(ctx: GhostAxiom): List<Token> = flatListOf(
        when (ctx.type) {
            GhostAxiom.Type.INITIAL -> Token.identifier("init_state").asList() // no keyword for this right now
            GhostAxiom.Type.ALWAYS -> emptyList()
        },
        Token.fromSym(sym.AXIOM).asList(),
        exp(ctx.exp),
        Token.endStatement(),
    ).context(ctx)

    private fun param(ctx: Param): List<Token> {

        return when (ctx) {
            is CVLParam -> flatListOf(
                typeOrLhs(ctx.type),
                Token.identifier(ctx.id).asList(),
            ).context(ctx)

            is VMParam -> {
                /**
                 * see caveats of [VMParam.location] which may lead to future breakage.
                 *
                 * not bothering to make an enum for this, just like I'm not bothering with the
                 * many other parser-related places where we should have used enums but didn't.
                 */
                val location = ctx.location?.let { Token.Terminal(it, TerminalId(sym.LOCATION)) }

                when (ctx) {
                    is NamedVMParam -> flatListOf(
                        typeOrLhs(ctx.type),
                        location?.asList().orEmpty(),
                        Token.identifier(ctx.id).asList(),
                    ).context(ctx)

                    is UnnamedVMParam -> flatListOf(
                        typeOrLhs(ctx.type),
                        location?.asList().orEmpty(),
                    ).context(ctx)
                }
            }
        }
    }

    private fun locatedToken(ctx: LocatedToken): List<Token> = flatListOf(
        Token.identifier(ctx.value).asList(),
    ).context(ctx)

    /** afaik only a single param is allowed here, but this is currently a list so we'll format it as a list. */
    private fun tokenizeWithParams(ctx: List<CVLParam>) = flatListOf(
        Token.fromSym(sym.WITH, Space.TF).asList(),
        params(ctx),
    )

    private fun methodSignature(ctx: MethodSig, isWildcard: Boolean = false): List<Token> {
        // we allow single return values without parenthesis, but here we add them unconditionally
        fun returns(resParams: List<VMParam>): List<Token> = if (resParams.isEmpty()) {
            emptyList()
        } else {
            Token.fromSym(sym.RETURNS) + resParams.tokenizeInterspersed(::param).surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT)
        }
        return flatListOf(
            methodReferenceExp(ctx.id),
            if (isWildcard) { emptyList() } else { params(ctx.params) },
            ctx.methodQualifiers?.preReturnsAnnotations?.flatMap(::locatedToken).orEmpty(),
            returns(ctx.resParams),
            ctx.methodQualifiers?.postReturnsAnnotations?.flatMap(::locatedToken).orEmpty(),
        ).context(ctx)
    }

    private fun methodReferenceExp(ctx: MethodReferenceExp): List<Token> {
        return flatListOf(
            ctx.contract.letOrEmpty { exp(it) + Token.Punctuation.Dot.toToken() },
            Token.identifier(ctx.method).asList(),
        ).context(ctx)
    }

    private fun rule(ctx: Rule): List<Token> {
        fun descriptionLine(description: String, id: TerminalId): List<Token> = listOf(
            Token.LineBreak.Hard,
            Token.fromTerminalId(id),
            Token.string(description),
            Token.LineBreak.Hard,
        )

        return flatListOf(
            Token.fromSym(sym.RULE).asList(),
            Token.identifier(ctx.id).asList(),
            params(ctx.params),
            paramFiltersMap(ctx.methodParamFilters).orEmpty(),
            ctx.description.letOrEmpty { descriptionLine(it, TerminalId(sym.DESCRIPTION)) },
            ctx.goodDescription.letOrEmpty { descriptionLine(it, TerminalId(sym.GOODDESCRIPTION)) },
            cmdList(ctx.block),
        ).context(ctx)
    }


    private fun <T> block(tokenizables: List<T>, tokenize: (T) -> List<Token>): List<Token> =
        tokenizables.flatMap(tokenize).surround(Token.Delimiter.CurlyBracket)

    /** we don't call [ctx] here because we manually check for comments */
    private fun cmdList(ctx: CmdList): List<Token> {
        val comments  = this.nodeToComments.remove(ctx)

        return if (ctx.cmds.isEmpty() && comments == null) {
            Token
                .SingleWhiteSpace
                .asList()
                .surround(Token.Delimiter.CurlyBracket, spaceOpen = Space.TT, spaceClose = Space.TT, openScope = false)
        } else {
            val (beforeList, afterList) = comments
                ?.tokenizeToBeforeAndAfter()
                ?: Pair(emptyList(), emptyList())
            val (afterLastElementInList, beforeStartOfList) =
                beforeList.partition { it.binding.token.range.end == ctx.range.end }
            val list = ctx
                .cmds
                .flatMap(::cmd)
                .plus(afterLastElementInList)
                .surround(Token.Delimiter.CurlyBracket)
            beforeStartOfList + list + afterList
        }
    }

    private fun paramFiltersMap(ctx: MethodParamFiltersMap): List<Token>? {
        // XXX: a decision was made here to make the map-wrapping class not-null,
        // and signal nullability here by nulling the contained map.
        // some production rules have a semicolon on missing map, others do not
        // it would be better to change this in the parser, and make the map itself nullable.
        // for now let's signal nullability with this tokenizing function
        val filters = ctx.methodParamFilters?.values?.toList() ?: return null

        fun def(ctx: MethodParamFilterDef): List<Token> = flatListOf(
            exp(ctx.methodParam), // is this right?
            Token.fromSym(sym.MAPSTO).asList(),
            exp(ctx.filterExp),
        ).context(ctx)

        return flatListOf(
            Token.LineBreak.Hard.asList(),
            Token.fromSym(sym.FILTERED).asList(),
            filters
                .tokenizeInterspersed(::def)
                .surround(Token.Delimiter.CurlyBracket, spaceOpen = Space.TT, spaceClose = Space.TT, openScope = false)
        ).context(ctx)
    }

    private fun cmd(ctx: Cmd): List<Token> {
        return when (ctx) {
            is AssertCmd -> flatListOf(
                Token.fromSym(sym.ASSERT, spaceIfNoParenthesis(ctx.exp)).asList(),
                exp(ctx.exp),
                ctx.description.letOrEmpty(::reason),
                Token.endStatement(),
            ).context(ctx)

            is DeclarationCmd -> flatListOf(
                typeOrLhs(ctx.type),
                Token.identifier(ctx.id).asList(),
                Token.endStatement(),
            ).context(ctx)

            is DefinitionCmd -> flatListOf(
                ctx.type.letOrEmpty(::typeOrLhs),
                ctx.lhs.tokenizeInterspersed(::typeOrLhs),
                Token.Punctuation.Equals.toToken().asList(),
                exp(ctx.exp),
                Token.endStatement(),
            ).context(ctx)

            is ApplyCmd -> flatListOf(
                unresolvedApplyExp(ctx.applyExp),
                Token.endStatement(),
            ).context(ctx)

            is AssumeCmd -> flatListOf(
                Token.fromSym(sym.REQUIRE, spaceIfNoParenthesis(ctx.exp)).asList(),
                exp(ctx.exp),
                ctx.description.letOrEmpty(::reason),
                Token.endStatement(),
            ).context(ctx)

            is AssumeInvariantCmd -> {
                // uses the "no parenthesis" syntax
                flatListOf(
                    Token.fromSym(sym.REQUIREINVARIANT).asList(),
                    Token.identifier(ctx.id).asList(),
                    ctx
                        .params
                        .tokenizeInterspersed(::exp)
                        .surround(Token.Delimiter.Parenthesis),
                    Token.endStatement(),
                ).context(ctx)
            }

            is BlockCmd -> block(ctx.block, ::cmd).context(ctx)

            is HavocCmd -> flatListOf(
                Token.fromSym(sym.HAVOC).asList(),
                ctx.targets.tokenizeInterspersed(::exp),
                ctx.assumingExp.letOrEmpty { Token.fromSym(sym.ASSUMING) + exp(it) },
                Token.endStatement(),
            ).context(ctx)

            is IfCmd -> flatListOf(
                Token.fromSym(sym.IF).asList(),
                exp(ctx.cond).surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT),
                cmd(ctx.thenCmd),
                ctx.elseCmd.letOrEmpty { Token.fromSym(sym.ELSE) + cmd(it) },
                Token.LineBreak.Soft.asList(),
            ).context(ctx)

            is ResetStorageCmd -> flatListOf(
                Token.fromSym(sym.RESET_STORAGE).asList(),
                exp(ctx.target),
                Token.endStatement(),
            ).context(ctx)

            is ReturnCmd -> {
                // a first approximation of a reasonable way to format this
                fun returnValues() = if (ctx.exps.isEmpty()) {
                    emptyList()
                } else if (ctx.exps.size == 1){
                    val elem = ctx.exps.single()
                    exp(elem)
                } else {
                    ctx
                        .exps
                        .tokenizeInterspersed(::exp)
                        .surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FF)
                }

                flatListOf(
                    Token.fromSym(sym.RETURN).asList(),
                    returnValues(),
                    Token.endStatement(),
                ).context(ctx)
            }

            is RevertCmd -> flatListOf(
                Token.fromSym(sym.REVERT, Space.TF).asList(),
                ctx.reason.letOrEmpty { Token.string(it).asList() }.surround(Token.Delimiter.Parenthesis),
                Token.endStatement(),
            ).context(ctx)

            is SatisfyCmd -> flatListOf(
                Token.fromSym(sym.SATISFY).asList(),
                exp(ctx.exp),
                ctx.description.letOrEmpty(::reason),
                Token.endStatement(),
            ).context(ctx)
        }
    }

    // for example: "assert(x > 3);" vs "assert false;"
    private fun spaceIfNoParenthesis(exp: Exp): Space = if (exp.hasParenthesis) { Space.TF } else { Space.TT }

    private fun reason(ctx: String): List<Token> = Token.Punctuation.Comma.toToken() + Token.string(ctx)

    private fun ghostDecl(ctx: GhostDecl): List<Token> {
        val persistent = if (ctx.persistent) {
            Token.fromSym(sym.PERSISTENT).asList()
        } else {
            emptyList()
        }

        fun axioms() = if (ctx.axioms.isEmpty()) {
            Token.endStatement()
        } else {
            block(ctx.axioms, ::ghostAxiom)
        }

        return when (ctx) {
            is GhostFunDecl -> flatListOf(
                persistent,
                Token.fromSym(sym.GHOST).asList(),
                Token.identifier(ctx.id, Space.TF).asList(),
                ctx.paramTypes.tokenizeInterspersed(::typeOrLhs).surround(Token.Delimiter.Parenthesis),
                Token.fromSym(sym.RETURNS).asList(),
                typeOrLhs(ctx.returnType),
                axioms(),
            ).context(ctx)

            is GhostMapDecl -> flatListOf(
                persistent,
                Token.fromSym(sym.GHOST).asList(),
                typeOrLhs(ctx.type),
                Token.identifier(ctx.id).asList(),
                axioms(),
            ).context(ctx)
        }
    }

    private fun typeOrLhs(ctx: TypeOrLhs): List<Token> {
        return when (ctx) {
            is ArrayLhs -> flatListOf(
                typeOrLhs(ctx.baseType),
                exp(ctx.index).surround(Token.Delimiter.SquareBracket),
            ).context(ctx)

            is DynamicArrayType -> flatListOf(
                typeOrLhs(ctx.baseType),
                emptyList<Token>().surround(Token.Delimiter.SquareBracket),
            ).context(ctx)

            is IdLhs -> Token.identifier(ctx.id).asList().context(ctx)

            is QualifiedTypeReference -> {

                flatListOf(
                    ctx.contract.letOrEmpty { Token.identifier(it) + Token.Punctuation.Dot.toToken() },
                    Token.identifier(ctx.id).asList()
                ).context(ctx)
            }

            is MappingType -> {
                flatListOf(
                    Token.fromSym(sym.MAPPING, Space.TF).asList(),
                    flatListOf(
                        typeOrLhs(ctx.keyType),
                        Token.fromSym(sym.IMPLIES).asList(),
                        typeOrLhs(ctx.valueType),
                    ).surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT),
                ).context(ctx)
            }

            is TupleType -> {
                // we already checked this has arity >= 2 (during compilation)
                ctx
                    .types
                    .tokenizeInterspersed(::typeOrLhs)
                    .surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT)
                    .context(ctx)
            }
        }
    }

    private fun exp(ctx: Exp): List<Token> {
        val tokens = when (ctx) {
            is BoolExp -> Token.boolean(ctx.asBoolean).asList()

            is RelopExp -> flatListOf(
                exp(ctx.l),
                Token.Terminal(ctx.relop.symbol, TerminalId(sym.RELOP)).asList(),
                exp(ctx.r),
            )

            is VariableExp -> {
                val identifier = Token.identifier(ctx.id)

                when (ctx.annotation) {
                    VariableExp.Annotation.OLD -> listOf(identifier, ampersand(), Token.fromSym(sym.OLD))
                    VariableExp.Annotation.NEW -> listOf(identifier, ampersand(), Token.fromSym(sym.NEW))
                    VariableExp.Annotation.NONE -> identifier.asList()
                }
            }

            is UnresolvedApplyExp -> unresolvedApplyExp(ctx)

            is NumberExp -> listOf(Token.identifier(ctx.literal))

            is UnaryExp -> {
                val opSym = when (ctx) {
                    is BwNotExp -> sym.BWNOT
                    is LNotExp -> sym.NOT
                    is UMinusExp -> sym.MINUS
                }

                // reminder that in unary expressions, the operator is a prefix, not a postfix.
                val opToken = Token.fromSym(opSym, Space.TF)

                flatListOf(
                    opToken.asList(),
                    exp(ctx.o),
                )
            }

            is BinaryExp -> {
                val op = when (ctx) {
                    is AddExp -> sym.PLUS
                    is BwAndExp -> sym.BWAND
                    is BwLeftShiftExp -> sym.BWLSHIFT
                    is BwOrExp -> sym.BWOR
                    is BwRightShiftExp -> sym.BWRSHIFT
                    is BwRightShiftWithZerosExp -> sym.BWRSHIFTWZEROS
                    is BwXOrExp -> sym.BWXOR
                    is DivExp -> sym.DIV
                    is ExponentExp -> sym.EXPONENT
                    is IffExp -> sym.IFF
                    is ImpliesExp -> sym.IMPLIES
                    is LandExp -> sym.LAND
                    is LorExp -> sym.LOR
                    is ModExp -> sym.MOD
                    is MulExp -> sym.TIMES
                    is SubExp -> sym.MINUS
                }.let(Token::fromSym)

                flatListOf(
                    exp(ctx.l),
                    op.asList(),
                    exp(ctx.r),
                )
            }

            is FieldSelectExp -> flatListOf(
                exp(ctx.b),
                Token.Punctuation.Dot.toToken().asList(),
                Token.identifier(ctx.m).asList(),
            )

            is ArrayDerefExp -> flatListOf(
                exp(ctx.ad),
                exp(ctx.indx).surround(Token.Delimiter.SquareBracket)
            )

            is ArrayLitExp -> flatListOf(ctx.a.tokenizeInterspersed(::exp).surround(Token.Delimiter.SquareBracket, spaceOpen = Space.TF, spaceClose = Space.FT)).context(ctx)

            is BifApplicationExpr -> flatListOf(
                Token.identifier(ctx.bifName).asList(),
                ctx.args.tokenizeInterspersed(::exp).surround(Token.Delimiter.Parenthesis),
            )

            is CastExpr -> {
                // uhhhhhh I think this is right?
                // why is this function enumeration thing being done in the lexer?
                flatListOf(
                    Token.identifier(ctx.castExpr.keyword).asList(),
                    exp(ctx.exp).surround(Token.Delimiter.Parenthesis),
                )
            }

            is CondExp -> flatListOf(
                exp(ctx.c),
                Token.fromSym(sym.QUESTION).asList(),
                exp(ctx.e1),
                Token.fromSym(sym.COLON).asList(),
                exp(ctx.e2),
            )


            is QuantifierExp -> {
                val keyword = if (ctx.is_forall) { sym.FORALL } else { sym.EXISTS }

                flatListOf(
                    Token.fromSym(keyword).asList(),
                    param(ctx.param),
                    Token.fromSym(sym.DOT, Space.FT).asList(),
                    exp(ctx.body)
                )
            }

            is SetMemExp -> flatListOf(
                exp(ctx.e),
                Token.fromSym(sym.IN).asList(),
                exp(ctx.set),
            )

            is SignatureLiteralExp -> flatListOf(
                Token.fromSym(sym.SIG, Space.TF).asList(),
                Token.fromSym(sym.COLON, Space.FF).asList(),
                methodSignature(ctx.methodSig),
            )

            is StringExp -> Token.string(ctx.s).asList().context(ctx)

            is SumExp -> {
                val keyword = if (ctx.isUnsigned) { sym.USUM } else { sym.SUM }

                flatListOf(
                    Token.fromSym(keyword).asList(),
                    ctx.params.tokenizeInterspersed(::param),
                    Token.fromSym(sym.DOT, Space.FT).asList(),
                    exp(ctx.body),
                )
            }

            is ErrorExpr -> invariantBroken("should not appear in valid code that passed compilation")
        }

        // this is an exp and may need parenthesis.
        return tokens
            .letIf(ctx.hasParenthesis) { it.surround(Token.Delimiter.Parenthesis, spaceOpen = Space.TF, spaceClose = Space.FT) }
            .context(ctx)
    }

    private fun unresolvedApplyExp(ctx: UnresolvedApplyExp): List<Token> {

        val annotationToken = when (ctx.annotation) {
            UnresolvedApplyExp.Annotation.OLD -> Token.fromSym(sym.OLD)
            UnresolvedApplyExp.Annotation.NEW -> Token.fromSym(sym.NEW)
            UnresolvedApplyExp.Annotation.NOREVERT -> Token.fromSym(sym.NOREVERT)
            UnresolvedApplyExp.Annotation.WITHREVERT -> Token.fromSym(sym.WITHREVERT)
            UnresolvedApplyExp.Annotation.NONE -> null
        }

        val annotation = annotationToken?.prepend { ampersand() }

        return flatListOf(
            ctx.base.letOrEmpty { exp(it) + Token.fromSym(sym.DOT, Space.FF) },
            Token.identifier(ctx.method, Space.TF).asList(),
            annotation.orEmpty(),
            ctx
                .args
                .tokenizeInterspersed(::exp)
                .surround(Token.Delimiter.Parenthesis, spaceOpen = Space.FF, spaceClose = Space.FT),
            ctx.storage.letOrEmpty { Token.fromSym(sym.AT) + exp(it) },
        ).context(ctx)
    }

    private fun ampersand() = Token.Punctuation.Ampersand.toToken()

    private fun <T> List<T>.tokenizeInterspersed(
        tokenize: (T) -> List<Token>,
        separator: List<Token> = Token.Punctuation.Comma.toToken().asList(),
        end: List<Token> = emptyList(),
    ): List<Token> {
        val newList: MutableList<Token> = mutableListOf()

        val iter = this.iterator()

        while (iter.hasNext()) {
            val tokens = tokenize(iter.next())
            newList.addAll(tokens)

            if (iter.hasNext()) {
                newList.addAll(separator)
            }
        }

        if (!this.isEmpty()) {
            newList.addAll(end)
        }

        return newList
    }

    private fun List<Token>.context(ctx: HasRange): List<Token> {
        val bindings = this@Tokenizer.nodeToComments.remove(ctx)
        return if (bindings == null) {
            this
        } else {
            val (before, after) = bindings.tokenizeToBeforeAndAfter()
            before + this + after
        }
    }

    private fun dispatcherFlags(ctx: CallSummary.FlagSyntax.FlagList): List<Token> {
        // note the order here, since tokens will be generated in list order
        val literals = listOf(
            Pair("optimistic", ctx.parsedOptimistic),
            Pair("use_fallback", ctx.parsedUseFallback),
        )

        return literals
            .mapNotNull { (name, parsedValue) ->
                if (parsedValue != null) {
                    listOf(Token.string(name), Token.Punctuation.Equals.toToken(Space.FF), Token.boolean(parsedValue))
                } else {
                    null
                }
            }
            .zipWithNextPartial { currTokens, nextTokens ->
                if (nextTokens != null) {
                    currTokens + Token.Punctuation.Comma.toToken(Space.FT)
                } else {
                    currTokens
                }
            }
            .flatten()
            .surround(Token.Delimiter.Parenthesis)
    }

    private fun params(ctx: List<Param>): List<Token> {
        val comma = Token.Punctuation.Comma.toToken()

        val enableLineBreaks = false // currently disabled

        val (separator, openScope) = if (enableLineBreaks && ctx.needsLineBreaks()) {
            Pair(listOf(comma, Token.LineBreak.Soft), true)
        } else {
            Pair(listOf(comma), false)
        }

        return ctx
            .tokenizeInterspersed(::param, separator)
            .surround(Token.Delimiter.Parenthesis, openScope = openScope)
    }

    private fun List<Binding>.tokenizeToBeforeAndAfter(): Pair<List<Token.Comments>, List<Token.Comments>> =
        this.partitionMap { binding ->
            val token = Token.Comments(binding)

            when (binding.association) {
                Association.Before -> token.toLeft()
                Association.After -> token.toRight()
            }
        }

    fun uninsertedComments(): List<Binding> = this.nodeToComments.values.flatten()
}

private fun List<Param>.needsLineBreaks(): Boolean {
    fun TypeOrLhs.lengthOfId(): Int = when (this) {
        is IdLhs -> this.id.length
        is QualifiedTypeReference -> this.id.length

        is ArrayLhs  -> this.baseType.lengthOfId()
        is DynamicArrayType -> this.baseType.lengthOfId()
        is TupleType -> this.types.sumOf { it.lengthOfId() }
        is MappingType -> this.keyType.lengthOfId() + this.valueType.lengthOfId()
    }

    fun Param.length(): Int = when (this) {
        is CVLParam -> this.type.lengthOfId() + this.id.length
        is NamedVMParam -> this.type.lengthOfId() + this.id.length
        is UnnamedVMParam -> this.type.lengthOfId()
    }

    val maxParams = 3
    val maxLength = 55

    return when {
        this.all { it is UnnamedVMParam } -> false
        this.size > maxParams -> true
        this.sumOf(Param::length) > maxLength -> true
        else -> false
    }
}