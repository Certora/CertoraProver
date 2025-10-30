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

// This invariant should pass on the sequence `foo->foo` only if the parameter to the preserved is
// the same parameter passed to it's corresponding function call.
invariant preservedParamsLinkedToFunctionCall() currentContract.counter == 0 {
    preserved foo(uint a) {
        require a == 0;
    }
}

// This invariant should fail on `bar->bar` only if the parameters to the two invocations are independent.
invariant preservedParamsIndependent() currentContract.counter < 2 {
    preserved bar(uint a) {
        require a == currentContract.counter;
    }
}

invariant preservedWithDeclaration() currentContract.counter < 10 {
    preserved {
        uint c = currentContract.counter;
        require c < 9;
    }
}
