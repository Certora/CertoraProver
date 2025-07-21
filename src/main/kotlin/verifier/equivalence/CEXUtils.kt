/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package verifier.equivalence

import solver.CounterexampleModel
import utils.*
import java.math.BigInteger

object CEXUtils {
    fun <T> Pair<Boolean, BigInteger?>.asEither(msg: T) = this.second?.takeIf {
        this.first
    }?.toLeft() ?: msg.toRight()

    fun <T> Pair<Boolean, BigInteger?>.asEither(msg: () -> T) = this.asEither(msg())

    fun Either<BigInteger, CounterexampleModel.ResolvingFailure>.withFailureString(msg: String) = this.mapRight {
        "$msg: $it"
    }

    /**
     * (Monadically) turns a partial function that represents a buffer into a list of ubytes representing
     * a buffer of length [len]. If any bytes in the range 0 .. [len] are not mapped by the partial function, this function
     * returns an Either.Right.
     */
    fun Either<PartialFn<BigInteger, UByte>, String>.toList(len: BigInteger) : Either<List<UByte>, String> {
        return this.bindLeft pp@{ fn ->
            val outL = mutableListOf<UByte>()
            for(i in 0 until len.intValueExact()) {
                outL.add(
                    fn[i.toBigInteger()] ?: return@pp "Missing byte value at $i".toRight()
                )
            }
            outL.toLeft()
        }
    }

}
