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

contract C {
    uint n;
    D public d;

    function foo() public {
        n += 1;
    }

    function bar() public {
        n += 2;
    }

    function baz() public {
        n += 3;
    }

    function getD() external returns (address) {
        return d.getD();
    }
}

contract D {
    function getD() external returns (address) {
        return address(this);
    }
}
