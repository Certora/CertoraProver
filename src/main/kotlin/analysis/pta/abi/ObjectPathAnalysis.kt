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
package analysis.pta.abi

import analysis.LTACCmd
import analysis.pta.ArrayStateAnalysis
import analysis.pta.PointerSemantics
import analysis.pta.PointsToDomain
import analysis.pta.StructStateAnalysis
import analysis.pta.abi.ObjectPathGen.*
import analysis.ptaInvariant
import com.certora.collect.*
import datastructures.stdcollections.*
import utils.*
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACKeyword
import vc.data.TACSymbol

typealias ObjectPath = ObjectPathGen<ObjectPathAnalysis.ObjectRoot>

typealias PathState = TreapMap<TACSymbol.Var, TreapSet<ObjectPath>>

fun PathState.join(other: PathState) = this.pointwiseBinopOrNull(other) { a, b ->
    val joined = a.flatMap {
        b.mapNotNull {o ->
            if(o.root != it.root) {
                null
            } else {
                o.joinOrNull(it)
            }
        }
    }.toTreapSet()
    // return `joined`, but try to preserve the memory allocated for `a`
    (a intersect joined) union joined
}

class ObjectPathAnalysis {

    @KSerializable
    @Treapable
    sealed class ObjectRoot : AmbiSerializable {
        companion object {
            operator fun invoke(v: TACSymbol.Var) : ObjectRoot = V(v)
            operator fun invoke(calldata: DecoderAnalysis.BufferAccessPath, fieldDepth: Int) = CalldataRoot(calldata, fieldDepth)
        }

        @KSerializable
        data class V(val v: TACSymbol.Var) : ObjectRoot()

        /**
            While buffer access paths are sufficient to uniquely identify a value within the buffer, they do not provide
            sufficient information about that values' identity. For example, consider the type

            struct A {
                B b;
                uint x;
            }
            struct B {
                uint b1;
                uint b2;
            }

            Which begins at some BAP p. Then Offset(32, p) uniquely identifies b2's location within a buffer, but if we have
            a value that was read from that location, does that value represent:
            1) The b2 field of the b field of a struct A which starts at p? or
            2) The b2 field of a struct B (which also happens to start at p)?

            We don't know! This problem is not simply academic, suppose we have:

            function example(A calldata a) {
                externalCall(a);
            }

            vs

            function example(A calldata a) {
                externalCall(a.b);
            }

            In both cases, the serialization code will begin by reading the first two words from the location corresponding to
            the beginning of a (in this example, just 4). Despite reading the same values, the "identity" of those values is different.
            In the first example, the two values are the fields of a struct that is the first field of a "larger" value being serialized,
            i.e., the containing struct A.
            In the latter example, the values are being read as the *entirety* of the object being serialized, i.e., the struct B.

            Intuitively, we want some way to say ObjectPath.Field(0, START-OF-B) and ObjectPath.Field(0, ObjectPath.Field(0, START-OF-A)).
            What do we use for START-OF-B and START-OF-A? A naive choice would be to embed *just* the buffer access paths into
            ObjectPath domains. But we once again run into the inherent ambiguity of BAP: from a buffer access path perspective,
            the start of A and the start of B are actually the same! You can convince yourself that this ambiguity
            only happens due to a static struct occurring at offset 0 within some larger struct.

            The solution here are what we call extended access paths that include a "fieldDepth" parameter. We first define an ambiguous
            path. A buffer access path q is ambiguous if, there are multiple types such that the first scalar value within that type
            (first being defined via the depth-first traversal used by the ABI specification) occur at that access path.
            For example, the buffer access path q for A is ambiguous, because there are multiple types at that location whose first
            value occurs at p, the type of A itself, the type B, and the uint256. When disambiguating, the dismabiguation is always
            done with respect to the largest type, in this example A. Then the [fieldDepth] is added to indicate the number of
            "logical" offset 0 fields that need to be traversed from that largest type to reach the type in question.
            Thus, the fieldDepth added to path to A is 0, the [fieldDepth] for B's path is 1, and the field depth for the uint256
            at that same location 2.

            TL;DR - The [CalldataRoot] extends buffer access path to provide different "identities" to the same location within a buffer;
            this overloading of locations results from the flattening of static types mandated by the ABI specification.
         */
        @KSerializable
        data class CalldataRoot(val bp: DecoderAnalysis.BufferAccessPath, val fieldDepth: Int) : ObjectRoot()
    }

    fun step(c: LTACCmd, s: PointsToDomain) : PathState {
        val p = project(s)
        if(c.cmd !is TACCmd.Simple.AssigningCmd) {
            return p
        }
        val remove = p.updateValues { k, objectPath ->
            when {
                k == c.cmd.lhs -> null
                objectPath.none { (it.root as? ObjectRoot.V)?.v == c.cmd.lhs } -> return@updateValues objectPath
                else -> objectPath.retainAll {
                    (it.root as? ObjectRoot.V)?.v != c.cmd.lhs
                }
            }?.takeIf { it.isNotEmpty() }
        }.builder()
        when(c.cmd) {
            is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                if(c.cmd.base == TACKeyword.MEMORY.toVar() && c.cmd.loc is TACSymbol.Var) {
                    val struct = s.structState[c.cmd.loc]
                    val arr = s.arrayState[c.cmd.loc]
                    if(struct != null) {
                        ptaInvariant(struct.sort != StructStateAnalysis.ValueSort.MaybeConstArray) {
                            "Reading from an apparently unsafe base ${c.cmd.loc} @ $c"
                        }
                        val basePath = remove[struct.base].orEmpty() + Root(ObjectRoot(struct.base))
                        if(struct.sort == StructStateAnalysis.ValueSort.ConstArray || struct.sort is StructStateAnalysis.ValueSort.StridingPointer) {
                            remove[c.cmd.lhs] = basePath.updateElements {
                                StaticArrayField(it)
                            }
                        } else {
                            check(struct.sort is StructStateAnalysis.ValueSort.FieldPointer)
                            remove[c.cmd.lhs] = basePath.updateElements { Field(offset = struct.sort.offs, parent = it) }
                        }
                    } else if(arr != null && arr is ArrayStateAnalysis.Value.ElementPointer) {
                        remove[c.cmd.lhs] = (arr.arrayPtr.flatMap {
                            remove[it].orEmpty()
                        } + arr.arrayPtr.map {
                            Root<ObjectRoot>(ObjectRoot(it))
                        }).mapToTreapSet {
                            ArrayElem(it)
                        }
                    } else if(arr != null && (arr is ArrayStateAnalysis.Value.ArrayBasePointer || (arr is ArrayStateAnalysis.Value.MaybeEmptyArray && arr.tyVar.isResolvedArray()))) {
                        val base = treapSetOf(Root(ObjectRoot(c.cmd.loc))) + remove[c.cmd.loc].orEmpty()
                        remove[c.cmd.lhs] = base.mapToTreapSet { ArrayLength(it) }
                    }
                }
            }
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                if(c.cmd.rhs is TACExpr.Sym.Var && c.cmd.rhs.s in remove) {
                    remove[c.cmd.lhs] = remove[c.cmd.rhs.s]!!
                }
            }
            else -> {}
        }
        return remove.build()
    }

    fun collectReferenced(st: PathState, referencedFromLive: MutableSet<TACSymbol.Var>, lva: Set<TACSymbol.Var>) {
        st.forEachEntry { (k, u) ->
            if(k in lva) {
                u.mapNotNullTo(referencedFromLive) {
                    (it.root as? ObjectRoot.V)?.v
                }
            }
        }
    }

    fun startBlock(st: PathState, lva: Set<TACSymbol.Var>, referencedFromLive: Set<TACSymbol.Var>) : PathState {
        return st.retainAllKeys { k ->
            k in lva || k in referencedFromLive
        }
    }

    private fun project(s: PointsToDomain): PathState {
        return s.objectPath
    }

    fun endBlock(objectPath: PathState, last: LTACCmd, live: Set<TACSymbol.Var>): PathState {
        unused(last)
        unused(live)
        return objectPath
    }

    fun getPathOf(v: TACSymbol.Var, whole: PointsToDomain) : Set<ObjectPath> = whole.objectPath[v].orEmpty() + Root(ObjectRoot(v))
    fun kill(o_: PathState, killedBySideEffects: PointerSemantics.WriteSideEffect): PathState {
        val killRelation = killedBySideEffects.killArrayLengthFacts + killedBySideEffects.killArrayLengthFacts
        return o_.updateValues { p, objectPathGens ->
            if(p in killedBySideEffects.killValue) {
                return@updateValues null
            }
            objectPathGens.retainAll { path ->
                path.rootVar?.let { it in killRelation } != true
            }
        }
    }
}
